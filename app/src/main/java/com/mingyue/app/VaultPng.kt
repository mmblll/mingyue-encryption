package com.mingyue.app

import java.io.*
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

// Small PNG cover + private ciphertext chunks: large media is never a bitmap.
internal object VaultPng {
    val signature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
    private fun chunk(out: DataOutputStream, name: String, data: ByteArray, size: Int = data.size) {
        val tag = name.toByteArray(Charsets.US_ASCII)
        out.writeInt(size); out.write(tag); out.write(data, 0, size)
        out.writeInt(CRC32().apply { update(tag); update(data, 0, size) }.value.toInt())
    }
    private fun coverChunks(bytes: ByteArray, output: DataOutputStream) {
        require(bytes.size <= 12 * 1024 * 1024) { "封面过大" }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(ByteArray(8).also(input::readFully).contentEquals(signature))
        var header = false; var image = false; var end = false
        while (!end) {
            val size = input.readInt()
            require(size >= 0 && size.toLong() + 8 <= input.available())
            val tag = ByteArray(4).also(input::readFully)
            val name = String(tag, Charsets.US_ASCII)
            if (!header) require(name == "IHDR" && size == 13)
            val data = ByteArray(size).also(input::readFully)
            require(input.readInt() == CRC32().apply { update(tag); update(data) }.value.toInt())
            when (name) {
                "IHDR" -> { require(!header); header = true; chunk(output, name, data) }
                "PLTE" -> chunk(output, name, data)
                "IDAT" -> { image = true; chunk(output, name, data) }
                "IEND" -> { require(size == 0 && image && input.available() == 0); end = true }
                // Preserve transparency only. Strip comments, location and old ciphertext.
                "tRNS" -> chunk(output, name, data)
                else -> require(tag[0].toInt() and 32 != 0)
            }
        }
    }
    class Sink(output: OutputStream, coverPng: ByteArray? = null) : OutputStream() {
        private val out = DataOutputStream(output)
        private val buffer = ByteArray(VaultCrypto.BLOCK)
        private var used = 0
        private var ended = false
        init {
            out.write(signature)
            if (coverPng != null) coverChunks(coverPng, out) else {
            val header = ByteArrayOutputStream().apply {
                DataOutputStream(this).apply { writeInt(96); writeInt(96); write(byteArrayOf(8, 2, 0, 0, 0)) }
            }.toByteArray()
            chunk(out, "IHDR", header)
            val cover = ByteArrayOutputStream().apply {
                DeflaterOutputStream(this).use { deflate ->
                    val row = ByteArray(289)
                    val random = java.security.SecureRandom()
                    for (y in 0 until 96) {
                        random.nextBytes(row); row[0] = 0
                        deflate.write(row)
                    }
                }
            }.toByteArray()
            chunk(out, "IDAT", cover)
            }
        }
        override fun write(value: Int) { write(byteArrayOf(value.toByte())) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            check(!ended)
            var pos = offset; var left = length
            while (left > 0) {
                val n = minOf(buffer.size - used, left)
                bytes.copyInto(buffer, used, pos, pos + n); used += n; pos += n; left -= n
                if (used == buffer.size) emit()
            }
        }
        private fun emit() { if (used > 0) { chunk(out, "myVe", buffer, used); buffer.fill(0); used = 0 } }
        override fun flush() { emit(); out.flush() }
        fun finish() { check(!ended); emit(); chunk(out, "IEND", byteArrayOf()); out.flush(); ended = true }
    }
    class Source(file: File) : Closeable {
        private val source = RandomAccessFile(file, "r")
        private data class Segment(val logical: Long, val physical: Long, val size: Int)
        private val segments = ArrayList<Segment>()
        private var total = 0L
        var filePointer = 0L
            private set
        init {
            try {
                val prefix = ByteArray(8).also(source::readFully)
                if (!prefix.contentEquals(signature)) {
                    require(String(prefix, Charsets.US_ASCII) == "MYVAULT1") { "不是明月加密图片" }
                    var offset = 0L
                    while (offset < source.length()) {
                        val n = minOf(VaultCrypto.BLOCK.toLong(), source.length() - offset).toInt()
                        segments.add(Segment(offset, offset, n)); offset += n
                    }
                    total = source.length()
                } else {
                    val buffer = ByteArray(VaultCrypto.BLOCK)
                    var hasHeader = false; var hasImage = false; var ended = false
                    try {
                        while (!ended) {
                            val size = source.readInt()
                            require(size >= 0 && source.filePointer + 8L + size <= source.length())
                            val type = ByteArray(4).also(source::readFully)
                            val name = String(type, Charsets.US_ASCII)
                            if (!hasHeader) require(name == "IHDR" && size == 13)
                            val physical = source.filePointer
                            val crc = CRC32().apply { update(type) }
                            var remaining = size
                            while (remaining > 0) {
                                if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
                                val n = minOf(remaining, buffer.size)
                                source.readFully(buffer, 0, n); crc.update(buffer, 0, n); remaining -= n
                            }
                            require(source.readInt() == crc.value.toInt()) { "加密图片损坏" }
                            when (name) {
                                "IHDR" -> { require(!hasHeader); hasHeader = true }
                                "IDAT" -> hasImage = true
                                "myVe" -> {
                                    require(hasImage && size in 1..VaultCrypto.BLOCK)
                                    segments.add(Segment(total, physical, size)); total += size
                                }
                                "IEND" -> { require(size == 0 && hasImage && segments.isNotEmpty()); ended = true }
                                else -> require(type[0].toInt() and 32 != 0) { "不支持的图片容器" }
                            }
                        }
                        require(source.filePointer == source.length()) { "图片后存在额外数据" }
                    } finally { buffer.fill(0) }
                }
            } catch (error: Throwable) { source.close(); throw error }
        }
        fun length(): Long = total
        fun seek(position: Long) { require(position in 0..total); filePointer = position }
        fun readFully(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
            require(offset >= 0 && length >= 0 && offset.toLong() + length <= bytes.size)
            if (filePointer + length > total) throw EOFException()
            var used = 0
            while (used < length) {
                var low = 0; var high = segments.lastIndex
                while (low < high) {
                    val mid = (low + high + 1) ushr 1
                    if (segments[mid].logical <= filePointer) low = mid else high = mid - 1
                }
                val segment = segments[low]
                val inside = (filePointer - segment.logical).toInt()
                val n = minOf(length - used, segment.size - inside)
                require(n > 0)
                source.seek(segment.physical + inside); source.readFully(bytes, offset + used, n)
                used += n; filePointer += n
            }
        }
        fun readUnsignedByte(): Int = ByteArray(1).also { readFully(it) }[0].toInt() and 255
        fun readInt(): Int = DataInputStream(ByteArrayInputStream(ByteArray(4).also { readFully(it) })).readInt()
        override fun close() { source.close() }
    }
}
