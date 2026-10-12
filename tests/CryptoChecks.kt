package com.mingyue.app

import java.io.*
import java.security.MessageDigest
import java.util.zip.CRC32
import javax.imageio.ImageIO

private fun pattern(position: Long): Byte = ((position * 31 + 7) and 255).toByte()
private class Synthetic(private val size: Long) : InputStream() {
    var position = 0L
    override fun read(): Int = if (position == size) -1 else pattern(position++).toInt() and 255
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (position == size) return -1
        val count = minOf(length.toLong(), size - position).toInt()
        for (i in 0 until count) bytes[offset + i] = pattern(position++)
        return count
    }
}
private fun write(file: File, size: Long, secret: CharArray) {
    file.outputStream().use { output -> val png = VaultPng.Sink(output)
        Synthetic(size).use { VaultCrypto.encrypt(it, png, secret, VaultInfo("测试中文名字🙂.bin", "application/octet-stream", size)) }
        png.finish()
    }
}
private fun fail(action: () -> Unit) { check(runCatching(action).isFailure) { "Unauthenticated input accepted" } }
private fun sha(input: InputStream): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(8192)
    input.use { while (true) { val n = it.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
    return digest.digest()
}

fun main(args: Array<String>) {
    val directory = File(args[0]).apply { mkdirs() }
    val secret = "合成测试🔒Ａ１２".toCharArray()
    val salt = ByteArray(16) { it.toByte() }
    check(VaultCrypto.derive(secret, salt, 100_000).contentEquals(VaultCrypto.derive(secret, salt, 100_000, true)))
    var checks = 1
    val image = java.awt.image.BufferedImage(31, 19, java.awt.image.BufferedImage.TYPE_INT_RGB)
    for (y in 0 until image.height) for (x in 0 until image.width) image.setRGB(x, y, (x * 7 shl 16) or (y * 11 shl 8) or 85)
    val customCover = ByteArrayOutputStream().apply { ImageIO.write(image, "png", this) }.toByteArray()
    for (format in listOf("png", "jpg", "gif")) {
        val original = ByteArrayOutputStream().apply { check(ImageIO.write(image, format, this)) }.toByteArray()
        val encrypted = File(directory, "photo-$format.png")
        encrypted.outputStream().use { output -> val png = VaultPng.Sink(output, customCover)
            VaultCrypto.encrypt(ByteArrayInputStream(original), png, secret, VaultInfo("原图.$format", "image/$format", original.size.toLong())); png.finish()
        }
        val visible = ImageIO.read(encrypted)
        check(visible.width == 31 && visible.height == 19 && visible.getRGB(12, 8) == image.getRGB(12, 8))
        VaultCrypto.Reader(encrypted, secret).use { reader ->
            reader.verify(); check(reader.info.name == "原图.$format"); check(reader.inputStream().readBytes().contentEquals(original))
        }
        fail { VaultCrypto.Reader(encrypted, "错误密码".toCharArray()).use { it.verify() } }
        checks += 3
    }
    fail { VaultPng.Sink(ByteArrayOutputStream(), byteArrayOf(1, 2, 3)) }
    val damagedCover = customCover.copyOf().also { it[29] = (it[29].toInt() xor 1).toByte() }
    fail { VaultPng.Sink(ByteArrayOutputStream(), damagedCover) }
    checks += 2
    val sizes = listOf(0L, 1L, VaultCrypto.BLOCK - 1L, VaultCrypto.BLOCK.toLong(), VaultCrypto.BLOCK + 17L, 5L * VaultCrypto.BLOCK + 117, 80L * VaultCrypto.BLOCK + 9)
    for (size in sizes) {
        val file = File(directory, "$size.png"); write(file, size, secret)
        check(ImageIO.read(file).let { cover -> cover.width == 96 && cover.height == 96 && (0 until 96).map { cover.getRGB(it, 0) }.toSet().size > 64 })
        VaultCrypto.Reader(file, secret).use { reader ->
            check(reader.info.name == "测试中文名字🙂.bin" && reader.info.size == size)
            reader.verify()
            check(sha(reader.inputStream()).contentEquals(sha(Synthetic(size))))
            val buffer = ByteArray(4099)
            for (pos in listOf(0L, 23L, VaultCrypto.BLOCK - 50L, size - 20)) {
                if (pos < 0) continue
                val n = reader.readAt(pos, buffer, 0, buffer.size)
                if (pos >= size) check(n == -1) else for (i in 0 until n) check(buffer[i] == pattern(pos + i))
            }
            check(reader.readAt(size, buffer, 0, 0) == 0)
            fail { reader.readAt(-1, buffer, 0, 1) }
        }
        fail { VaultCrypto.Reader(file, "错误密码".toCharArray()).use { it.verify() } }
        checks += 5
        if (size == 5L * VaultCrypto.BLOCK + 117) {
            val tampered = File(directory, "tampered.png"); file.copyTo(tampered, overwrite = true)
            RandomAccessFile(tampered, "rw").use { raw ->
                raw.seek(8)
                while (true) {
                    val length = raw.readInt(); val type = ByteArray(4).also(raw::readFully); val start = raw.filePointer
                    if (String(type) == "myVe") {
                        val bytes = ByteArray(length).also(raw::readFully)
                        bytes[100_000] = (bytes[100_000].toInt() xor 1).toByte()
                        raw.seek(start); raw.write(bytes)
                        raw.writeInt(CRC32().apply { update(type); update(bytes) }.value.toInt()); break
                    }
                    raw.seek(start + length + 4)
                }
            }
            // Forging a valid PNG CRC must still fail authenticated block decryption.
            fail { VaultCrypto.Reader(tampered, secret).use { it.verify() } }
            val truncated = File(directory, "truncated.png"); file.copyTo(truncated, overwrite = true)
            RandomAccessFile(truncated, "rw").use { it.setLength(it.length() - 40) }
            fail { VaultCrypto.Reader(truncated, secret).use { it.verify() } }
            val trailing = File(directory, "trailing.png"); file.copyTo(trailing, overwrite = true)
            trailing.appendBytes(byteArrayOf(1, 2, 3)); fail { VaultCrypto.Reader(trailing, secret).use { it.verify() } }
            val changed = File(directory, "changed.png")
            val newPassword = "新的测试密码".toCharArray()
            VaultCrypto.Reader(file, secret).use { reader ->
                reader.verify()
                changed.outputStream().use { output -> val png = VaultPng.Sink(output)
                    VaultCrypto.encrypt(reader.inputStream(), png, newPassword, reader.info); png.finish()
                }
            }
            VaultCrypto.Reader(changed, newPassword).use { it.verify(); check(sha(it.inputStream()).contentEquals(sha(Synthetic(size)))) }
            fail { VaultCrypto.Reader(changed, secret).use { it.verify() } }
            checks += 5
        }
        check(file.delete())
    }
    println("PASS: $checks crypto, PNG, boundary, authentication and password-change checks; 80 MiB streamed with 64 MiB heap.")
}
