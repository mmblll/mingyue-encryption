package com.mingyue.app

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import android.system.Os
import java.io.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal data class VaultEntry(val file: File, val info: VaultInfo?)

internal class VaultStore(private val context: Context) {
    private val directory = File(context.cacheDir, "mingyue-processing").apply { check(exists() || mkdirs()) }
    fun legacyFiles(): List<File> = File(context.filesDir, "vault").listFiles()?.filter { it.isFile && it.name.endsWith(".mingyue.png") } ?: emptyList()
    fun clearTemporary() {
        directory.listFiles()?.forEach { file ->
            if (file.canonicalFile.parentFile == directory.canonicalFile && file.isFile &&
                (file.name.endsWith(".mingyue.png") || file.name.endsWith(".part"))) file.delete()
        }
    }
    fun files(): List<File> = directory.listFiles()?.filter { it.isFile && it.name.endsWith(".mingyue.png") }
        ?.sortedByDescending { it.lastModified() } ?: emptyList()
    fun used(): Long = files().sumOf { it.length() }
    fun available(): Long = StatFs(directory.absolutePath).availableBytes
    private fun checkSpace() { check(available() > 8L * 1024 * 1024) { "手机空间不足，请先导出或清理文件" } }

    fun catalog(password: CharArray): List<VaultEntry> = files().map { file ->
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
        // A different password or a damaged file stays in the vault, never deleted.
        VaultEntry(file, try { VaultCrypto.Reader(file, password).use { it.info } } catch (_: Exception) { null })
    }

    fun describe(uri: Uri): VaultInfo {
        var name = "未命名文件"
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) {
                if (!it.isNull(0)) name = it.getString(0)
                if (!it.isNull(1)) size = it.getLong(1)
            }
        }
        val detected = context.contentResolver.getType(uri)
        val inferred = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
        return VaultInfo(name.take(240), if (detected == null || detected == "application/octet-stream") inferred ?: "application/octet-stream" else detected, size)
    }

    private fun commit(writer: (File) -> Unit): File {
        checkSpace()
        val id = UUID.randomUUID().toString()
        val temporary = File(directory, "$id.part")
        val destination = File(directory, "$id.mingyue.png")
        try {
            writer(temporary)
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
            // Same filesystem rename: an interrupted import cannot become a complete item.
            Os.rename(temporary.absolutePath, destination.absolutePath)
            return destination
        } finally { if (temporary.exists()) temporary.delete() }
    }

    fun importPlain(uri: Uri, password: CharArray, progress: (Long) -> Unit, cover: ByteArray? = null): File {
        val info = describe(uri)
        check(info.size < 0 || info.size + 1024 * 1024 < available()) { "手机空间不足" }
        return context.contentResolver.openInputStream(uri)!!.use { input ->
            encrypt(input, password, info, progress, cover)
        }
    }

    fun importBundle(uris: List<Uri>, password: CharArray, progress: (Long) -> Unit, cover: ByteArray? = null): File {
        require(uris.isNotEmpty() && uris.size <= 1000) { "一次最多合成 1000 个文件" }
        val failure = AtomicReference<Throwable?>(null)
        val input = PipedInputStream(64 * 1024)
        val pipe = PipedOutputStream(input)
        val producer = Thread({
            try {
                ZipOutputStream(pipe).use { zip ->
                    zip.setLevel(0)
                    uris.forEachIndexed { index, uri ->
                        if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
                        val name = describe(uri).name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "文件" }
                        zip.putNextEntry(ZipEntry("${index + 1}-$name"))
                        context.contentResolver.openInputStream(uri)!!.use { source ->
                            val buffer = ByteArray(VaultCrypto.BLOCK)
                            try { while (true) {
                                if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
                                val n = source.read(buffer); if (n < 0) break
                                zip.write(buffer, 0, n)
                            } } finally { buffer.fill(0) }
                        }
                        zip.closeEntry()
                    }
                }
            } catch (error: Throwable) { failure.set(error); runCatching { pipe.close() } }
        }, "mingyue-bundle")
        try {
            producer.start()
            return commit { file ->
                FileOutputStream(file).use { output ->
                    val png = VaultPng.Sink(output, cover)
                    VaultCrypto.encrypt(input, png, password, VaultInfo("混合合集.zip", "application/zip", -1)) {
                        checkSpace(); progress(it)
                    }
                    producer.join()
                    failure.get()?.let { throw IOException("合集读取失败", it) }
                    png.finish(); output.fd.sync()
                }
                VaultCrypto.Reader(file, password).use { it.verify() }
            }
        } finally {
            runCatching { input.close() }; runCatching { pipe.close() }; producer.interrupt()
        }
    }

    fun encrypt(input: InputStream, password: CharArray, info: VaultInfo, progress: (Long) -> Unit, cover: ByteArray? = null): File = commit { file ->
        FileOutputStream(file).use { output ->
            val png = VaultPng.Sink(output, cover)
            VaultCrypto.encrypt(input, png, password, info) { bytes -> checkSpace(); progress(bytes) }
            png.finish()
            output.fd.sync()
        }
        VaultCrypto.Reader(file, password).use { it.verify() }
    }

    fun importCipher(uri: Uri, password: CharArray, progress: (Long) -> Unit): File = commit { file ->
        val buffer = ByteArray(VaultCrypto.BLOCK)
        try {
            context.contentResolver.openInputStream(uri)!!.use { input -> FileOutputStream(file).use { output ->
                var total = 0L
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
                    val n = input.read(buffer); if (n < 0) break
                    checkSpace(); output.write(buffer, 0, n); total += n; progress(total)
                }
                output.fd.sync()
            } }
            try { VaultCrypto.Reader(file, password).use { it.verify() } }
            catch (error: Exception) {
                if (Thread.currentThread().isInterrupted) throw error
                LegacyImport.decrypt(file, password).use { clear ->
                    FileOutputStream(file).use { output ->
                        val png = VaultPng.Sink(output)
                        ByteArrayInputStream(clear.bytes, clear.offset, clear.bytes.size - clear.offset).use { source ->
                            VaultCrypto.encrypt(source, png, password, clear.info, progress)
                        }
                        png.finish(); output.fd.sync()
                    }
                }
                VaultCrypto.Reader(file, password).use { it.verify() }
            }
        } finally { buffer.fill(0) }
    }

    fun changePassword(file: File, old: CharArray, new: CharArray, progress: (Long) -> Unit) {
        requireOwned(file)
        check(file.length() + 1024 * 1024 < available()) { "改密码需要一份文件的临时空间" }
        val temporary = File(directory, "${UUID.randomUUID()}.part")
        try {
            VaultCrypto.Reader(file, old).use { reader ->
                reader.verify()
                FileOutputStream(temporary).use { output ->
                    val png = VaultPng.Sink(output)
                    VaultCrypto.encrypt(reader.inputStream(), png, new, reader.info, progress)
                    png.finish()
                    output.fd.sync()
                }
            }
            VaultCrypto.Reader(temporary, new).use { it.verify() }
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
            // The original remains intact until the replacement has fully authenticated.
            Os.rename(temporary.absolutePath, file.absolutePath)
        } finally { if (temporary.exists()) temporary.delete() }
    }

    fun delete(file: File) { requireOwned(file); check(file.delete()) { "删除失败" } }
    private fun requireOwned(file: File) { require(file.canonicalFile.parentFile == directory.canonicalFile && file.name.endsWith(".mingyue.png")) }
}

internal fun humanSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
