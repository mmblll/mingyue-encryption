package com.mingyue.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import java.io.Closeable
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.launch

internal class VaultPreview(val reader: VaultCrypto.Reader, val bitmap: Bitmap?, val text: String?, val bundle: List<String>, private val ownsReader: Boolean = true) : Closeable {
    private var player: MediaPlayer? = null
    fun attach(value: MediaPlayer) { player = value }
    override fun close() { player?.runCatching { release() }; player = null; bitmap?.recycle(); if (ownsReader) reader.close() }
    companion object {
        private fun bitmap(reader: VaultCrypto.Reader): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            reader.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片格式无法预览" }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
            return reader.inputStream().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        }
        fun open(file: File, password: CharArray): VaultPreview {
            val reader = VaultCrypto.Reader(file, password)
            return openReader(reader, ownsReader = true)
        }
        fun openReader(reader: VaultCrypto.Reader, ownsReader: Boolean): VaultPreview {
            try {
                reader.verify()
                val mime = reader.info.mime
                val image = if (mime.startsWith("image/")) bitmap(reader) else null
                val text = if (mime.startsWith("text/") && reader.info.size <= 1024 * 1024) {
                    val bytes = reader.inputStream().readBytes()
                    try { String(bytes, Charsets.UTF_8) } finally { bytes.fill(0) }
                } else null
                val bundle = if (mime == "application/zip") ZipInputStream(reader.inputStream()).use { zip ->
                    val names = ArrayList<String>()
                    var expanded = 0L
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        require(names.size < 1000) { "合集条目过多" }
                        names.add(entry.name)
                        // Only enumerate app-produced stored-deflate archives, with a
                        // global expansion ceiling against malicious imported ZIP files.
                        var count = 0L; val buffer = ByteArray(8192)
                        try { while (true) { val n = zip.read(buffer); if (n < 0) break
                            count += n; expanded += n
                            require(expanded <= reader.info.size * 2 + 1024 * 1024) { "合集压缩比例异常" }
                            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                        } } finally { buffer.fill(0) }
                    }
                    names
                } else emptyList()
                return VaultPreview(reader, image, text, bundle, ownsReader)
            } catch (error: Throwable) { if (ownsReader) reader.close(); throw error }
        }
    }
    fun bundleImage(name: String): Bitmap? = ZipInputStream(reader.inputStream()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: return@use null
            if (entry.name == name) {
                val bytes = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                try { while (true) { val n = zip.read(buffer); if (n < 0) break
                    require(bytes.size() + n <= 12 * 1024 * 1024) { "合集图片超过 12 MB，可解密导出后查看" }; bytes.write(buffer, 0, n)
                } } finally { buffer.fill(0) }
                val data = bytes.toByteArray()
                return@use try { bundleBitmap(data) } finally { data.fill(0); bytes.reset() }
            }
            zip.closeEntry()
        }
        @Suppress("UNREACHABLE_CODE") null
    }
}

private fun bundleBitmap(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

@Composable internal fun VaultPreviewDialog(preview: VaultPreview, close: () -> Unit) {
    Dialog(close, properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOff)) {
        Surface(Modifier.fillMaxWidth().widthIn(max = 680.dp).safeDrawingPadding().padding(12.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(preview.reader.info.name, style = MaterialTheme.typography.titleLarge)
                if (preview.bitmap != null && !preview.bitmap.isRecycled) Image(preview.bitmap.asImageBitmap(), "解密图片",
                    Modifier.fillMaxWidth().heightIn(max = 420.dp), contentScale = ContentScale.Fit)
                else if (preview.text != null) Text(preview.text)
                else if (preview.reader.info.mime.startsWith("video/") || preview.reader.info.mime.startsWith("audio/")) MediaPreview(preview)
                else if (preview.bundle.isNotEmpty()) {
                    Text("合集中的 ${preview.bundle.size} 个文件。点图片名称可预览；完整取回请解密导出 ZIP。")
                    var selected by remember { mutableStateOf<Bitmap?>(null) }
                    var message by remember { mutableStateOf("") }
                    var working by remember { mutableStateOf(false) }
                    val scope = rememberCoroutineScope()
                    val displayed = selected
                    DisposableEffect(displayed) { onDispose { displayed?.recycle() } }
                    if (selected != null && !selected!!.isRecycled) Image(selected!!.asImageBitmap(), "合集图片",
                        Modifier.fillMaxWidth().heightIn(max = 320.dp), contentScale = ContentScale.Fit)
                    Text(message)
                    preview.bundle.forEach { name ->
                        TextButton(onClick = {
                            working = true
                            scope.launch {
                                try { selected = runInterruptible(Dispatchers.IO) { preview.bundleImage(name) }; message = "" }
                                catch (_: Exception) { message = "此文件无法预览图片，可解密导出后打开。" }
                                finally { working = false }
                            }
                        }, enabled = !working, modifier = Modifier.fillMaxWidth()) { Text(name) }
                    }
                } else Text("此格式可加密保存和解密导出，本机暂不支持预览。文本大于 1 MB 时请导出查看。")
                Button(close, modifier = Modifier.fillMaxWidth()) { Text("关闭预览") }
            }
        }
    }
}

@Composable private fun MediaPreview(preview: VaultPreview) {
    var ready by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableIntStateOf(0) }
    var duration by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf("") }
    val video = preview.reader.info.mime.startsWith("video/")
    val player = remember(preview) { MediaPlayer().also(preview::attach) }
    DisposableEffect(player) {
        player.setOnPreparedListener { duration = it.duration.coerceAtLeast(1); ready = true }
        player.setOnCompletionListener { playing = false }
        player.setOnErrorListener { _, _, _ -> error = "本机不支持此媒体格式，请解密导出后打开。"; playing = false; true }
        try {
            player.setDataSource(object : MediaDataSource() {
                override fun getSize(): Long = preview.reader.info.size
                override fun readAt(pos: Long, buffer: ByteArray, offset: Int, size: Int): Int = preview.reader.readAt(pos, buffer, offset, size)
                override fun close() { /* Reader owned by preview; no plaintext files. */ }
            })
            player.prepareAsync()
        } catch (_: Exception) { error = "此媒体格式无法预览，可解密导出。" }
        onDispose { runCatching { player.release() } }
    }
    if (video) AndroidView(factory = { context -> SurfaceView(context).apply {
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { runCatching { player.setDisplay(holder) } }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) { runCatching { player.setDisplay(null) } }
        })
    } }, modifier = Modifier.fillMaxWidth().height(220.dp))
    LaunchedEffect(player, ready) {
        while (ready) { runCatching { position = player.currentPosition }; delay(500) }
    }
    if (ready) {
        Text("${position / 1000} 秒 / ${duration / 1000} 秒")
        Slider(position.toFloat(), { position = it.toInt(); runCatching { player.seekTo(position) } }, valueRange = 0f..duration.toFloat())
        Button(onClick = { runCatching { if (playing) player.pause() else player.start(); playing = !playing } }) { Text(if (playing) "暂停" else "播放") }
    } else if (error.isEmpty()) Text("正在读取媒体…")
    if (error.isNotEmpty()) Text(error)
}
