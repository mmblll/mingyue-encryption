package com.mingyue.app

import java.io.*
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal data class VaultInfo(val name: String, val mime: String, val size: Long)

// MYVAULT1: authenticated metadata, independently authenticated 1 MiB blocks,
// and an authenticated end record. Neither filenames nor content are cleartext.
internal object VaultCrypto {
    const val BLOCK = 1024 * 1024
    const val ROUNDS = 600_000
    private val MAGIC = "MYVAULT1".toByteArray(Charsets.US_ASCII)
    private const val HEADER = 40

    fun derive(password: CharArray, salt: ByteArray, rounds: Int = ROUNDS, fallback: Boolean = false): ByteArray {
        require(password.isNotEmpty() && password.size <= 1024 && rounds in 100_000..2_000_000)
        val normalized = Normalizer.normalize(String(password), Normalizer.Form.NFKC)
        if (!fallback) {
            val spec = PBEKeySpec(normalized.toCharArray(), salt, rounds, 256)
            try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
            catch (_: java.security.NoSuchAlgorithmException) { /* Android 6/7 fallback */ }
            finally { spec.clearPassword() }
        }
        val bytes = normalized.toByteArray(Charsets.UTF_8)
        try {
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(bytes, "HmacSHA256")) }
            var u = mac.doFinal(salt + byteArrayOf(0, 0, 0, 1))
            val result = u.copyOf()
            repeat(rounds - 1) {
                if (it % 4096 == 0 && Thread.currentThread().isInterrupted) throw InterruptedIOException()
                val next = mac.doFinal(u); u.fill(0); u = next
                for (i in result.indices) result[i] = (result[i].toInt() xor u[i].toInt()).toByte()
            }
            u.fill(0)
            return result
        } finally { bytes.fill(0) }
    }

    private fun cipher(key: ByteArray, header: ByteArray, type: Int, index: Int, mode: Int): Cipher {
        val nonce = header.copyOfRange(24, 32) + byteArrayOf(
            (index ushr 24).toByte(), (index ushr 16).toByte(), (index ushr 8).toByte(), index.toByte())
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(header)
            updateAAD(byteArrayOf(type.toByte(), (index ushr 24).toByte(), (index ushr 16).toByte(),
                (index ushr 8).toByte(), index.toByte()))
        }
    }

    private fun metadata(info: VaultInfo): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeUTF(info.name.take(240)); out.writeUTF(info.mime.take(240)); out.writeLong(info.size)
        }
        bytes.toByteArray()
    }

    fun encrypt(input: InputStream, output: OutputStream, password: CharArray, info: VaultInfo,
                progress: (Long) -> Unit = {}) {
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val prefix = ByteArray(8).also(random::nextBytes)
        val header = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).apply { write(MAGIC); write(salt); write(prefix); writeInt(BLOCK); writeInt(ROUNDS) }
            bytes.toByteArray()
        }
        val key = derive(password, salt)
        val block = ByteArray(BLOCK)
        try {
            val out = DataOutputStream(output)
            out.write(header)
            var index = 0
            fun record(type: Int, clear: ByteArray, count: Int = clear.size) {
                check(index < Int.MAX_VALUE)
                val encrypted = cipher(key, header, type, index++, Cipher.ENCRYPT_MODE).doFinal(clear, 0, count)
                out.writeByte(type); out.writeInt(encrypted.size); out.write(encrypted)
            }
            val meta = metadata(info)
            try { record(0, meta) } finally { meta.fill(0) }
            var total = 0L
            var blocks = 0
            while (true) {
                var used = 0
                while (used < BLOCK) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
                    val n = input.read(block, used, BLOCK - used)
                    if (n < 0) break
                    if (n == 0) continue
                    used += n
                }
                if (used == 0) break
                record(1, block, used); total += used; blocks++
                block.fill(0); progress(total)
            }
            require(info.size < 0 || total == info.size) { "Source file changed" }
            val ending = ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).apply { writeLong(total); writeInt(blocks) }
                bytes.toByteArray()
            }
            record(2, ending); out.flush()
        } finally { key.fill(0); block.fill(0) }
    }

    class Reader(file: File, password: CharArray) : Closeable {
        private val source = VaultPng.Source(file)
        private val header = ByteArray(HEADER)
        private var key = byteArrayOf()
        private data class Part(val offset: Long, val encrypted: Int, val index: Int)
        private val parts = ArrayList<Part>()
        val info: VaultInfo
        private var cachedIndex = -1
        private var cached = byteArrayOf()
        private var closed = false

        init {
            try {
                source.readFully(header)
                require(header.copyOfRange(0, 8).contentEquals(MAGIC)) { "Unsupported encrypted file" }
                val params = DataInputStream(ByteArrayInputStream(header, 32, 8))
                require(params.readInt() == BLOCK && params.readInt() == ROUNDS) { "Invalid format parameters" }
                key = derive(password, header.copyOfRange(8, 24))
                require(source.readUnsignedByte() == 0)
                val metaLength = source.readInt()
                require(metaLength in 16..4096)
                val encryptedMeta = ByteArray(metaLength).also(source::readFully)
                val clear = cipher(key, header, 0, 0, Cipher.DECRYPT_MODE).doFinal(encryptedMeta)
                val original = try {
                    val input = DataInputStream(ByteArrayInputStream(clear))
                    val value = VaultInfo(input.readUTF(), input.readUTF(), input.readLong())
                    require(input.read() == -1 && value.size >= -1)
                    value
                } finally { clear.fill(0) }
                var total = 0L
                var shortBlock = false
                var index = 1
                while (true) {
                    require(index < Int.MAX_VALUE)
                    val type = source.readUnsignedByte()
                    val length = source.readInt()
                    if (type == 2) {
                        require(length == 28)
                        val encrypted = ByteArray(length).also(source::readFully)
                        val ending = cipher(key, header, 2, index, Cipher.DECRYPT_MODE).doFinal(encrypted)
                        try {
                            val input = DataInputStream(ByteArrayInputStream(ending))
                            require(input.readLong() == total && input.readInt() == parts.size)
                            require(source.filePointer == source.length()) { "Trailing data" }
                            require(original.size < 0 || original.size == total)
                        } finally { ending.fill(0) }
                        info = original.copy(size = total)
                        break
                    }
                    require(type == 1 && length in 17..BLOCK + 16 && !shortBlock)
                    require(source.filePointer + length <= source.length())
                    parts.add(Part(source.filePointer, length, index++))
                    source.seek(source.filePointer + length)
                    total += length - 16
                    shortBlock = length < BLOCK + 16
                }
            } catch (error: Throwable) {
                key.fill(0); source.close(); throw error
            }
        }

        @Synchronized private fun block(index: Int): ByteArray {
            check(!closed)
            if (cachedIndex != index) {
                cached.fill(0)
                cached = byteArrayOf(); cachedIndex = -1
                val part = parts[index]
                source.seek(part.offset)
                val encrypted = ByteArray(part.encrypted).also(source::readFully)
                cached = cipher(key, header, 1, part.index, Cipher.DECRYPT_MODE).doFinal(encrypted)
                cachedIndex = index
            }
            return cached
        }

        @Synchronized fun verify(progress: (Long) -> Unit = {}) {
            var total = 0L
            parts.indices.forEach { index ->
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
                total += block(index).size; progress(total)
            }
            cached.fill(0); cached = byteArrayOf(); cachedIndex = -1
        }

        @Synchronized fun readAt(position: Long, target: ByteArray, offset: Int, length: Int): Int {
            check(!closed)
            require(position >= 0 && offset >= 0 && length >= 0 && offset + length <= target.size)
            if (length == 0) return 0
            if (position >= info.size) return -1
            var pos = position
            var used = 0
            while (used < length && pos < info.size) {
                val index = (pos / BLOCK).toInt()
                val clear = block(index)
                val inside = (pos % BLOCK).toInt()
                val amount = minOf(length - used, clear.size - inside)
                clear.copyInto(target, offset + used, inside, inside + amount)
                used += amount; pos += amount
            }
            return used
        }

        fun inputStream(): InputStream = object : InputStream() {
            var position = 0L
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one) < 0) -1 else one[0].toInt() and 255
            }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                readAt(position, bytes, offset, length).also { if (it > 0) position += it }
        }

        fun copyTo(output: OutputStream) {
            val buffer = ByteArray(BLOCK)
            try { inputStream().use { input -> while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
                val n = input.read(buffer); if (n < 0) break; output.write(buffer, 0, n)
            } } }
            finally { buffer.fill(0) }
        }

        @Synchronized override fun close() {
            if (!closed) { closed = true; cached.fill(0); key.fill(0); source.close() }
        }
    }
}
