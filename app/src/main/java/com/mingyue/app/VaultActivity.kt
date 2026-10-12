package com.mingyue.app

import android.content.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.*
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.io.*
import java.security.MessageDigest

class VaultActivity : ComponentActivity() {
    private val store by lazy { VaultStore(this) }
    // Only the derived key for the current result remains until save/exit.
    // A new operation always requires a fresh, manually entered password.
    private var resultReader: VaultCrypto.Reader? = null
    private var draft by mutableStateOf("")
    private var page by mutableStateOf("首页")
    private var picked by mutableStateOf<List<Uri>>(emptyList())
    private var cover by mutableStateOf<Uri?>(null)
    private var textMode by mutableStateOf(false)
    private var text by mutableStateOf("")
    private var result by mutableStateOf<VaultEntry?>(null)
    private var preview by mutableStateOf<VaultPreview?>(null)
    private var busy by mutableStateOf(false)
    private var status by mutableStateOf("")
    private var showMore by mutableStateOf(false)
    private var showContact by mutableStateOf(false)
    private var showLicense by mutableStateOf(false)
    private var discard by mutableStateOf(false)
    private var plainWarning by mutableStateOf(false)
    private var legacyCount by mutableIntStateOf(0)
    private var pending: VaultEntry? = null
    private var pendingPlain = false
    private var pickerActive = false
    private var session = 0
    private var job: Job? = null
    private val handler = Handler(Looper.getMainLooper())
    private val pickerTimeout = Runnable { lock() }
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if (intent?.action == Intent.ACTION_SCREEN_OFF) lock() }
    }

    private val chooseFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        pickerDone()
        if (uris.isNotEmpty()) { picked = uris; textMode = false; status = "已选择 ${uris.size} 个文件" }
    }
    private val chooseCover = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pickerDone(); if (uri != null) { cover = uri; status = "已选择照片封面" }
    }
    private val save = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        pickerDone()
        val item = pending; val plain = pendingPlain; pending = null
        val epoch = session
        val reader = resultReader
        if (uri != null && item != null && item.file.exists() && (!plain || reader != null)) operate { _ ->
            requireLocal(uri)
            try {
                val expected = if (plain) {
                    checkNotNull(reader).verify()
                    val digest = MessageDigest.getInstance("SHA-256")
                    contentResolver.openOutputStream(uri, "wt")!!.use { output ->
                        java.security.DigestOutputStream(output, digest).use { reader.copyTo(it) }
                    }
                    digest.digest()
                } else {
                    val expected = item.file.inputStream().use(::digest)
                    item.file.inputStream().use { input -> contentResolver.openOutputStream(uri, "wt")!!.use { copy(input, it) } }
                    expected
                }
                check(contentResolver.openInputStream(uri)!!.use(::digest).contentEquals(expected)) { "保存校验失败" }
                interrupted()
                runOnUiThread { if (epoch == session) { clearResult(); draft = ""; picked = emptyList(); text = ""; status = "保存成功，临时密文已清理。下一次操作请重新输入密码。" } }
                store.clearTemporary()
            } catch (error: Exception) { runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }; throw error }
        } else if (uri != null && plain) status = "已锁定，请重新选择文件并解密。"
    }
    // Upgrade escape hatch only; old user data is never silently deleted.
    private val backup = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        pickerDone()
        if (tree != null) operate { _ ->
            requireLocal(tree)
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            store.legacyFiles().forEachIndexed { index, file ->
                interrupted()
                val uri = DocumentsContract.createDocument(contentResolver, parent, "image/png", file.name) ?: error("无法创建备份")
                try {
                    file.inputStream().use { input -> contentResolver.openOutputStream(uri, "wt")!!.use { copy(input, it) } }
                    check(contentResolver.openInputStream(uri)!!.use(::digest).contentEquals(file.inputStream().use(::digest)))
                } catch (error: Exception) { runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }; throw error }
                report("已导出旧版文件 ${index + 1}")
            }
            report("旧版文件已导出并校验，可在“解密”中打开。")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.content.ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        store.clearTemporary(); legacyCount = store.legacyFiles().size
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF5A4FCF), surface = Color(0xFFFBF8FF))) {
                Surface(Modifier.fillMaxSize()) { Screen() }
                preview?.let { VaultPreviewDialog(it, ::closePreview) }
                if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("结果还没保存") }, text = { Text("返回会清理临时结果。原来的文件不会删除。") },
                    confirmButton = { TextButton({ discard = false; home() }) { Text("返回并清理") } }, dismissButton = { Button({ discard = false }) { Text("继续保存") } })
                if (plainWarning) AlertDialog(onDismissRequest = { plainWarning = false }, title = { Text("保存原文件") }, text = { Text("保存后的原文件不再受密码保护。请选择手机本地位置。") },
                    confirmButton = { Button({ plainWarning = false; startSave(true) }) { Text("选择保存位置") } }, dismissButton = { TextButton({ plainWarning = false }) { Text("取消") } })
            }
        }
    }

    @Composable private fun Screen() {
        androidx.activity.compose.BackHandler(page != "首页" && !busy) { back() }
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (page == "首页") {
                    Text("明月加密", style = MaterialTheme.typography.headlineLarge)
                    Text("文件由你保存，加密解密都在本机完成。")
                    Spacer(Modifier.height(12.dp))
                    HomeButton("加密", "图片、视频、音乐、文件或文字") { page = "加密"; status = "" }
                    HomeButton("解密", "从加密图片取回原内容") { page = "解密"; status = "" }
                    TextButton({ showContact = true }) { Text("联系开发者") }
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        TextButton({ back() }, enabled = !busy) { Text("返回") }
                        Text(page, style = MaterialTheme.typography.headlineMedium)
                        TextButton({ showMore = true }, enabled = !busy) { Text("更多") }
                    }
                    val completed = result
                    if (completed != null) {
                        Text(if (page == "加密") "加密完成" else "解密完成", style = MaterialTheme.typography.titleLarge)
                        if (page == "加密") {
                            Picture(Uri.fromFile(completed.file), "加密图片预览")
                            Text("这是可以保存、发送的加密图片。请按原文件发送。")
                        } else {
                            Text(completed.info?.name ?: "原文件")
                            OutlinedButton({ open(completed) }, Modifier.fillMaxWidth(), enabled = !busy) { Text("预览原内容") }
                        }
                        Button({ if (page == "解密") plainWarning = true else startSave(false) }, Modifier.fillMaxWidth().heightIn(min = 64.dp), enabled = !busy) {
                            Text(if (page == "解密") "保存原文件" else "保存加密图片")
                        }
                        Text("请选择保存位置。退出或锁屏会清理尚未保存的临时结果。", style = MaterialTheme.typography.bodySmall)
                    } else {
                        if (page == "加密" && textMode) {
                            OutlinedTextField(text, { if (it.length <= 250_000) text = it }, label = { Text("输入或粘贴文字") }, minLines = 5, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                            TextButton({ textMode = false; text = "" }, enabled = !busy) { Text("改为选择文件") }
                        } else {
                            OutlinedButton({ pickerActive = true; chooseFiles.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth().heightIn(min = 64.dp), enabled = !busy) { Text(if (page == "加密") "选择图片或文件" else "选择加密图片") }
                            if (picked.isNotEmpty()) Text("已选 ${picked.size} 个文件")
                            if (page == "加密") {
                                Text("PNG、JPG 等图片均可加密，多文件默认合成一张加密图。", style = MaterialTheme.typography.bodySmall)
                                TextButton({ textMode = true; picked = emptyList() }, enabled = !busy) { Text("也可以直接输入文字") }
                            } else if (picked.size == 1) Picture(picked.first(), "加密图片预览")
                        }
                        if (page == "加密") {
                            Text(if (cover == null) "封面：噪点（默认）" else "封面：你选择的照片")
                            OutlinedButton({ pickerActive = true; chooseCover.launch(arrayOf("image/*")) }, enabled = !busy) { Text(if (cover == null) "选择照片作封面（可选）" else "更换封面照片") }
                            cover?.let { Picture(it, "照片封面"); Text("封面公开可见，请选不含隐私的照片。", style = MaterialTheme.typography.bodySmall); TextButton({ cover = null }, enabled = !busy) { Text("改回噪点") } }
                        }
                        OutlinedTextField(draft, { draft = it }, label = { Text(if (page == "解密") "输入这张加密图的密码" else "输入本次加密密码") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                        Text("每次加密或解密都要输入密码，不预设、不记住。", style = MaterialTheme.typography.bodySmall)
                        Button({ process() }, Modifier.fillMaxWidth().heightIn(min = 64.dp), enabled = !busy && draft.isNotEmpty() && draft.length <= 1024 && (picked.isNotEmpty() || page == "加密" && textMode && text.isNotEmpty())) { Text(if (busy) "正在处理…" else page) }
                    }
                }
                if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton({ lock(); status = "已取消，原文件保留。" }) { Text("取消") } }
                if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (showMore) AlertDialog(onDismissRequest = { showMore = false }, title = { Text("更多") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (legacyCount > 0) OutlinedButton({ showMore = false; pickerActive = true; backup.launch(null) }, enabled = result == null) { Text("导出旧版文件（$legacyCount 份）") }
            Text("退到后台或锁屏后自动锁定；密码不会保存。", style = MaterialTheme.typography.bodySmall)
            OutlinedButton({ showMore = false; showLicense = true }) { Text("使用许可：免费使用，禁止售卖") }
            OutlinedButton({ showMore = false; showContact = true }) { Text("联系开发者") }
        } }, confirmButton = { TextButton({ showMore = false }) { Text("关闭") } })
        if (showLicense) AlertDialog(onDismissRequest = { showLicense = false }, title = { Text("使用许可") }, text = {
            val license = remember { resources.openRawResource(R.raw.license_zh).bufferedReader(Charsets.UTF_8).use { it.readText() } }
            Text(license, Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()))
        }, confirmButton = { TextButton({ showLicense = false }) { Text("关闭") } })
        if (showContact) AlertDialog(onDismissRequest = { showContact = false }, title = { Text("联系开发者") }, text = { Text("QQ：2703568134") }, confirmButton = { Button({
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("开发者 QQ", "2703568134"))
            android.widget.Toast.makeText(this@VaultActivity, "QQ 已复制", android.widget.Toast.LENGTH_SHORT).show()
        }) { Text("复制 QQ") } }, dismissButton = { TextButton({ showContact = false }) { Text("关闭") } })
    }

    @Composable private fun HomeButton(title: String, subtitle: String, action: () -> Unit) {
        Button(action, Modifier.fillMaxWidth().heightIn(min = 110.dp), enabled = !busy, shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) { Text(title, style = MaterialTheme.typography.headlineMedium); Text(subtitle) }
        }
    }
    @Composable private fun Picture(uri: Uri, description: String) {
        var bitmap by remember(uri, session) { mutableStateOf<Bitmap?>(null) }
        var done by remember(uri, session) { mutableStateOf(false) }
        LaunchedEffect(uri, session) { try { bitmap = runInterruptible(Dispatchers.IO) { decodePicture(uri, 720) } } catch (_: Exception) {} finally { done = true } }
        val displayed = bitmap
        DisposableEffect(displayed) { onDispose { displayed?.recycle() } }
        if (displayed != null && !displayed.isRecycled) Image(displayed.asImageBitmap(), description, Modifier.fillMaxWidth().heightIn(max = 280.dp), contentScale = ContentScale.Fit)
        else if (done) Text("本机无法预览此图片格式，仍可按原文件加密或解密。", style = MaterialTheme.typography.bodySmall)
        else Text("正在加载预览…")
    }
    private fun decodePicture(uri: Uri, maxSize: Int): Bitmap {
        if (uri.scheme != "file") requireLocal(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "本机无法读取此封面，请换 PNG 或 JPG" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSize) sample *= 2
        return contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: error("本机无法读取此图片")
    }
    private fun process() {
        if (busy || draft.isEmpty() || draft.length > 1024) return
        val operationPassword = draft.toCharArray(); draft = ""
        clearResult()
        val decrypt = page == "解密"; val files = picked; val coverUri = cover; val epoch = session
        val content = if (!decrypt && textMode) text.toByteArray(Charsets.UTF_8) else null
        operate(operationPassword) { secret ->
            var coverPng: ByteArray? = null
            try {
                store.clearTemporary()
                if (!decrypt && coverUri != null) {
                    requireLocal(coverUri)
                    val bitmap = decodePicture(coverUri, 1280)
                    try { coverPng = ByteArrayOutputStream().apply { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, this)) }.toByteArray() } finally { bitmap.recycle() }
                }
                files.forEach(::requireLocal)
                val file = if (decrypt) {
                    require(files.size == 1) { "每次请选择一张加密图片" }; store.importCipher(files.first(), secret, ::progress)
                } else if (content != null) ByteArrayInputStream(content).use { store.encrypt(it, secret, VaultInfo("我的文本.txt", "text/plain", content.size.toLong()), ::progress, coverPng) }
                else if (files.size > 1) store.importBundle(files, secret, ::progress, coverPng)
                else store.importPlain(files.first(), secret, ::progress, coverPng)
                val reader = VaultCrypto.Reader(file, secret)
                val info = reader.info
                if (!decrypt) reader.close()
                runOnUiThread {
                    if (epoch == session) {
                        resultReader = if (decrypt) reader else null
                        result = VaultEntry(file, info); picked = emptyList(); text = ""; status = "处理完成，请保存。"
                    } else reader.close()
                }
            } finally { content?.fill(0); coverPng?.fill(0) }
        }
    }
    private fun startSave(plain: Boolean) {
        val item = result ?: return
        pending = item; pendingPlain = plain; pickerActive = true
        save.launch(if (plain) item.info?.name ?: "明月文件" else "明月-${item.file.name}")
    }
    private fun open(item: VaultEntry) { val epoch = session; val reader = resultReader ?: return; operate { _ ->
        val content = VaultPreview.openReader(reader, ownsReader = false)
        runOnUiThread { if (epoch == session && result?.file == item.file) { closePreview(); preview = content } else content.close() }
    } }
    private fun operate(secret: CharArray = charArrayOf(), action: (CharArray) -> Unit) {
        if (busy) { secret.fill('\u0000'); return }
        val epoch = session; busy = true
        job = lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { runInterruptible(Dispatchers.IO) { action(secret) } }
            catch (_: CancellationException) {}
            catch (error: Exception) { if (epoch == session) status = if (error is javax.crypto.AEADBadTagException) "密码错误或加密文件已损坏，请检查原密码和原图。" else error.message?.take(160) ?: "未完成，请检查密码、文件和剩余空间。" }
            finally { secret.fill('\u0000'); if (epoch == session) busy = false else store.clearTemporary() }
        }
    }
    private fun closePreview() { val old = preview; preview = null; old?.close() }
    private fun clearResult() { closePreview(); resultReader?.close(); resultReader = null; result = null }
    private fun back() { if (result != null) discard = true else home() }
    private fun home() { clearResult(); draft = ""; store.clearTemporary(); picked = emptyList(); text = ""; textMode = false; cover = null; page = "首页"; status = "" }
    private fun lock() {
        session++; job?.cancel(); job = null; busy = false
        draft = ""
        showMore = false; showContact = false; showLicense = false; discard = false; plainWarning = false; pending = null
        home()
    }
    private var lastProgress = 0L
    private fun progress(bytes: Long) { if (SystemClock.uptimeMillis() - lastProgress > 250) { lastProgress = SystemClock.uptimeMillis(); report("已处理 ${humanSize(bytes)}，请保持应用在前台。") } }
    private fun report(value: String) { val epoch = session; runOnUiThread { if (epoch == session) status = value } }
    private fun pickerDone() { pickerActive = false; handler.removeCallbacks(pickerTimeout) }
    override fun onStop() { if (pickerActive) handler.postDelayed(pickerTimeout, 60_000) else lock(); super.onStop() }
    override fun onResume() { super.onResume(); handler.removeCallbacks(pickerTimeout) }
    override fun onDestroy() { lock(); handler.removeCallbacks(pickerTimeout); runCatching { unregisterReceiver(screenOff) }; super.onDestroy() }
    private fun requireLocal(uri: Uri) {
        require(uri.scheme == "content" && uri.authority in setOf("com.android.externalstorage.documents", "com.android.providers.downloads.documents", "com.android.providers.media.documents", "media")) { "请选择手机本地文件，暂不支持云盘位置" }
    }
    private fun interrupted() { if (Thread.currentThread().isInterrupted) throw InterruptedIOException("操作已取消") }
    private fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(VaultCrypto.BLOCK)
        try { while (true) { interrupted(); val n = input.read(buffer); if (n < 0) break; output.write(buffer, 0, n) } } finally { buffer.fill(0) }
    }
    private fun digest(input: InputStream): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(8192)
        try { while (true) { interrupted(); val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) } } finally { buffer.fill(0) }
        return hash.digest()
    }
}
