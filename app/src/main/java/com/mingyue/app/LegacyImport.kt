package com.mingyue.app

import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.*
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// Read original IMGLOCK1 encrypted pictures, authenticate fully in memory,
// then convert into the bounded-memory format. No plaintext temporary files.
internal object LegacyImport {
    data class Clear(val info: VaultInfo, val bytes: ByteArray, val offset: Int) : Closeable {
        override fun close() { bytes.fill(0) }
    }
    private val wrapMagic = "MINGYUEIMG1".toByteArray(Charsets.US_ASCII)
    private const val LIMIT = 36 * 1024 * 1024
    private fun int(bytes: ByteArray, offset: Int) = DataInputStream(ByteArrayInputStream(bytes, offset, 4)).readInt()
    private fun memory(needed: Long) {
        val runtime = Runtime.getRuntime()
        require(runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory() > needed + 32L * 1024 * 1024) {
            "这张旧版加密图片过大，请先在电脑旧版解密，再用新版加密。"
        }
    }
    private fun unwrap(bytes: ByteArray): ByteArray {
        require(bytes.size >= 15 && bytes.copyOfRange(0, 11).contentEquals(wrapMagic))
        val size = int(bytes, 11)
        require(size in 62..LIMIT && size <= bytes.size - 15)
        return bytes.copyOfRange(15, 15 + size)
    }
    private fun payload(file: File): ByteArray {
        RandomAccessFile(file, "r").use { source ->
            val start = ByteArray(8).also(source::readFully)
            if (String(start, Charsets.US_ASCII) == "IMGLOCK1") {
                require(source.length() <= LIMIT); memory(source.length() * 4)
                source.seek(0); return ByteArray(source.length().toInt()).also(source::readFully)
            }
            require(start.contentEquals(VaultPng.signature))
            while (source.filePointer < source.length()) {
                val size = source.readInt()
                val type = ByteArray(4).also(source::readFully)
                require(size >= 0 && source.filePointer + size + 4 <= source.length())
                val name = String(type, Charsets.US_ASCII)
                if (name == "mYUE") {
                    require(size in 77..LIMIT + 15); memory(size.toLong() * 4)
                    val data = ByteArray(size).also(source::readFully)
                    try {
                        require(source.readInt() == CRC32().apply { update(type); update(data) }.value.toInt())
                        return unwrap(data)
                    } finally { data.fill(0) }
                }
                source.seek(source.filePointer + size + 4)
                if (name == "IEND") break
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val pixels = bounds.outWidth.toLong() * bounds.outHeight
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && pixels <= 12L * 1024 * 1024)
        memory(pixels * 10)
        val bitmap = requireNotNull(BitmapFactory.decodeFile(file.absolutePath))
        try {
            val row = IntArray(bitmap.width)
            val prefix = ByteArray(15)
            bitmap.getPixels(row, 0, bitmap.width, 0, 0, bitmap.width, 1)
            require(bitmap.width >= 5)
            for (i in prefix.indices) prefix[i] = (row[i / 3] ushr (16 - 8 * (i % 3))).toByte()
            require(prefix.copyOfRange(0, 11).contentEquals(wrapMagic))
            val size = int(prefix, 11)
            require(size in 62..LIMIT && size.toLong() + 15 <= pixels * 3)
            val result = ByteArray(size)
            var position = -15
            for (y in 0 until bitmap.height) {
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
                bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                for (pixel in row) for (shift in intArrayOf(16, 8, 0)) {
                    if (position in result.indices) result[position] = (pixel ushr shift).toByte()
                    position++
                }
                if (position >= size) break
            }
            row.fill(0); return result
        } finally { bitmap.recycle() }
    }
    fun decrypt(file: File, password: CharArray): Clear {
        val encrypted = payload(file)
        try {
            require(String(encrypted, 0, 8, Charsets.US_ASCII) == "IMGLOCK1" && encrypted[8].toInt() == 1 && encrypted[9].toInt() == 1)
            val rounds = int(encrypted, 10)
            require(int(encrypted, 42) == encrypted.size - 46)
            val key = VaultCrypto.derive(password, encrypted.copyOfRange(14, 30), rounds)
            val clear = try {
                Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, encrypted.copyOfRange(30, 42)))
                    updateAAD(encrypted, 0, 42)
                }.doFinal(encrypted, 46, encrypted.size - 46)
            } finally { key.fill(0) }
            try {
                val size = int(clear, 0)
                require(size in 1..1024 * 1024 && 4 + size <= clear.size)
                val meta = JSONObject(String(clear, 4, size, Charsets.UTF_8))
                val count = clear.size - 4 - size
                require(!meta.has("size") || meta.getLong("size") == count.toLong())
                return Clear(VaultInfo(meta.optString("name", "旧版图片"), meta.optString("type", "image/png"), count.toLong()), clear, 4 + size)
            } catch (error: Throwable) { clear.fill(0); throw error }
        } finally { encrypted.fill(0) }
    }
}
