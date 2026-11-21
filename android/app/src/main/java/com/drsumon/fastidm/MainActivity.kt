package com.drsumon.fastidm

import android.Manifest
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.content.ContentUris
import android.webkit.URLUtil
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.DownloadListener
import android.webkit.WebSettings
import androidx.compose.ui.viewinterop.AndroidView
import java.net.URLConnection
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

data class DownloadItem(val id: Long, val title: String, val status: Int, val downloaded: Long, val total: Long, val reason: Int)
data class HistoryItem(val id: Long, val title: String, val mime: String, val size: Long, val date: Long, val uri: Uri)

class MainActivity : ComponentActivity() {
    private var sharedUrl by mutableStateOf("")
    private var historyRequestCount by mutableIntStateOf(0)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedUrl = extractUrl(intent)
        if (intent?.action == ACTION_OPEN_HISTORY) historyRequestCount++
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    FastIdmHome(
                        sharedUrl,
                        historyRequestCount,
                        ::enqueueDownload,
                        ::queryDownloads,
                        ::queryHistory,
                        onStartVideo = { url, quality ->
                            val service = Intent(this, YtDlpDownloadService::class.java)
                                .setAction(YtDlpDownloadService.ACTION_START)
                                .putExtra(YtDlpDownloadService.EXTRA_URL, url)
                                .putExtra(YtDlpDownloadService.EXTRA_QUALITY, quality)
                            ContextCompat.startForegroundService(this, service)
                        },
                        onStopVideo = {
                            startService(Intent(this, YtDlpDownloadService::class.java).setAction(YtDlpDownloadService.ACTION_STOP))
                        },
                        onCancel = { id -> (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).remove(id) }
                    )
                }
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); sharedUrl = extractUrl(intent); if (intent.action == ACTION_OPEN_HISTORY) historyRequestCount++
    }
    private fun extractUrl(intent: Intent?): String {
        if (intent == null) return ""
        val raw = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            Intent.ACTION_VIEW -> intent.dataString.orEmpty()
            else -> ""
        }
        return Regex("""https?://[^\s<>]+""", RegexOption.IGNORE_CASE)
            .find(raw)?.value?.replace(Regex("[.,)\\]]+$"), "") ?: ""
    }
    private fun enqueueDownload(rawUrl: String): String {
        val entered = rawUrl.trim()
        if (isYoutubeUrl(entered)) return "YouTube link detected. Choose audio or video quality in the YouTube Downloader section."
        val clean = normalizeGoogleDriveUrl(entered)
        val uri = try { Uri.parse(clean) } catch (_: Exception) { null }
        if (uri == null || (uri.scheme != "https" && uri.scheme != "http") || uri.host.isNullOrBlank()) return "Enter a valid HTTP or HTTPS URL."
        return try {
            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            val filename = URLUtil.guessFileName(clean, null, null).replace(Regex("""[\\/:*?"<>|]"""), "_").take(150).ifBlank { "download.bin" }
            val request = DownloadManager.Request(uri).setTitle(filename).setDescription("Fast IDM · File download")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true).setAllowedOverRoaming(false)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "FastIDM/$filename")
            manager.enqueue(request)
            val source = if (clean != entered) "Google Drive file" else "File"
            "$source added to downloads: $filename"
        } catch (e: Exception) { "Could not start download: ${e.localizedMessage ?: "unknown error"}" }
    }
    private fun queryHistory(): List<HistoryItem> {
        val result = mutableListOf<HistoryItem>()
        if (Build.VERSION.SDK_INT < 29) {
            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            queryDownloads().filter { it.status == DownloadManager.STATUS_SUCCESSFUL }.forEach { item ->
                try {
                    val uri = manager.getUriForDownloadedFile(item.id) ?: return@forEach
                    val mime = manager.getMimeTypeForDownloadedFile(item.id)
                        ?: URLConnection.guessContentTypeFromName(item.title)
                        ?: "application/octet-stream"
                    result += HistoryItem(-item.id, item.title, mime, item.downloaded, item.id, uri)
                } catch (_: Exception) {}
            }
        } else {
            val projection = arrayOf(
                MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.MIME_TYPE, MediaStore.Downloads.SIZE,
                MediaStore.Downloads.DATE_ADDED, MediaStore.Downloads.RELATIVE_PATH
            )
            val selection = "${MediaStore.Downloads.RELATIVE_PATH} = ?"
            val args = arrayOf(Environment.DIRECTORY_DOWNLOADS + "/FastIDM/")
            contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args,
                MediaStore.Downloads.DATE_ADDED + " DESC"
            )?.use { cur ->
                val idCol = cur.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameCol = cur.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val mimeCol = cur.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE)
                val sizeCol = cur.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                val dateCol = cur.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)
                while (cur.moveToNext()) {
                    val id = cur.getLong(idCol)
                    val name = cur.getString(nameCol) ?: "Downloaded file"
                    val mime = cur.getString(mimeCol)
                        ?.takeIf { it != "application/octet-stream" }
                        ?: URLConnection.guessContentTypeFromName(name)
                        ?: "application/octet-stream"
                    val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                    result += HistoryItem(id, name, mime, cur.getLong(sizeCol), cur.getLong(dateCol) * 1000L, uri)
                }
            }
        }
        return result.sortedByDescending { it.date }
    }

    private fun queryDownloads(): List<DownloadItem> {
        val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val cursor = manager.query(DownloadManager.Query()) ?: return emptyList()
        val result = mutableListOf<DownloadItem>()
        cursor.use { c ->
            val id = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            val title = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)
            val status = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            val current = c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val total = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val reason = c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
            while (c.moveToNext()) result += DownloadItem(c.getLong(id), c.getString(title) ?: "Download", c.getInt(status), c.getLong(current), c.getLong(total), c.getInt(reason))
        }
        return result.sortedByDescending { it.id }
    }
    companion object { const val ACTION_OPEN_HISTORY = "com.drsumon.fastidm.action.OPEN_HISTORY" }
}

private fun isYoutubeUrl(raw: String): Boolean {
    val host = try { Uri.parse(raw.trim()).host?.lowercase().orEmpty() } catch (_: Exception) { "" }
    return host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com") ||
        host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com")
}

private fun isVideoPlatformUrl(raw: String): Boolean {
    val uri = try { Uri.parse(raw.trim()) } catch (_: Exception) { return false }
    val host = uri.host?.lowercase().orEmpty().removePrefix("www.")
    val supportedHosts = listOf(
        "youtube.com", "youtu.be", "youtube-nocookie.com",
        "vimeo.com", "dailymotion.com", "tiktok.com",
        "instagram.com", "facebook.com", "fb.watch",
        "twitch.tv", "x.com", "twitter.com"
    )
    return supportedHosts.any { host == it || host.endsWith(".$it") }
}

private fun normalizeGoogleDriveUrl(raw: String): String {
    val uri = try { Uri.parse(raw.trim()) } catch (_: Exception) { return raw.trim() }
    val host = uri.host?.lowercase().orEmpty()
    if (host != "drive.google.com" && host != "docs.google.com") return raw.trim()
    val pathId = Regex("/file/d/([^/]+)").find(uri.path.orEmpty())?.groupValues?.getOrNull(1)
    val id = pathId ?: uri.getQueryParameter("id")
    return if (!id.isNullOrBlank()) {
        Uri.parse("https://drive.google.com/uc").buildUpon()
            .appendQueryParameter("export", "download")
            .appendQueryParameter("id", id)
            .build().toString()
    } else raw.trim()
}


@Composable
private fun DownloadHistoryScreen(
    history: List<HistoryItem>,
    onBack: () -> Unit,
    onPlayAudio: (HistoryItem) -> Unit,
    onPlayVideo: (HistoryItem) -> Unit
) {
    val ink = androidx.compose.ui.graphics.Color(0xFF211936)
    val muted = androidx.compose.ui.graphics.Color(0xFF716B7D)
    val purple = androidx.compose.ui.graphics.Color(0xFF6046A5)
    BackHandler(onBack = onBack)
    Column(
        modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFFF5F2FA))
            .statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back", color = purple) }
            Spacer(Modifier.width(8.dp))
            Text("Download History", style = MaterialTheme.typography.titleLarge, color = ink)
        }
        Text("${history.size} downloaded file(s)", color = muted, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 8.dp, bottom = 12.dp))
        if (history.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("No completed downloads yet.", color = muted)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                items(history, key = { it.id }) { file ->
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(file.title, color = ink, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                            Text("${file.mime}  ·  ${formatBytes(file.size)}", color = muted, style = MaterialTheme.typography.bodySmall)
                            Text(android.text.format.DateFormat.format("dd MMM yyyy, hh:mm a", file.date).toString(), color = muted, style = MaterialTheme.typography.labelSmall)
                            val audio = file.mime.startsWith("audio/")
                            val video = file.mime.startsWith("video/") || file.title.substringAfterLast('.', "").lowercase() in setOf("mp4", "mkv", "webm", "3gp", "mov", "m4v")
                            if (audio || video) {
                                Button(
                                    onClick = { if (audio) onPlayAudio(file) else onPlayVideo(file) },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = purple)
                                ) { Text(if (audio) "▶ Play" else "▶ Play video") }
                            } else {
                                Text("This file type has no in-app preview.", color = muted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FastIdmHome(
    initialUrl: String,
    historyRequestCount: Int,
    onEnqueue: (String) -> String,
    onRefresh: () -> List<DownloadItem>,
    onHistory: () -> List<HistoryItem>,
    onStartVideo: (String, String) -> Unit,
    onStopVideo: () -> Unit,
    onCancel: (Long) -> Unit
) {
    val context = LocalContext.current
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var history by remember { mutableStateOf(emptyList<HistoryItem>()) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var quality by rememberSaveable { mutableStateOf("Best") }
    var showVideoChooser by rememberSaveable { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf("") }
    var ytStatus by remember { mutableStateOf("Ready") }
    var ytProgress by remember { mutableFloatStateOf(0f) }
    var showBrowser by rememberSaveable { mutableStateOf(false) }
    var browserStart by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(initialUrl) { if (initialUrl.isNotBlank()) url = initialUrl }
    LaunchedEffect(historyRequestCount) { if (historyRequestCount > 0) { history = onHistory(); showHistory = true } }
    LaunchedEffect(Unit) {
        while (true) {
            onRefresh()
            val prefs = context.getSharedPreferences(YtDlpDownloadService.PREFS, android.content.Context.MODE_PRIVATE)
            ytStatus = prefs.getString("status", "Ready") ?: "Ready"
            ytProgress = prefs.getFloat("progress", 0f)
            delay(1200)
        }
    }

    val purple = androidx.compose.ui.graphics.Color(0xFF6046A5)
    val ink = androidx.compose.ui.graphics.Color(0xFF211936)
    val muted = androidx.compose.ui.graphics.Color(0xFF716B7D)
    val bg = androidx.compose.ui.graphics.Color(0xFFF5F2FA)

    if (showHistory) {
        DownloadHistoryScreen(
            history = history,
            onBack = { showHistory = false },
            onPlayAudio = { file ->
                context.startActivity(
                    Intent(context, SoulPlayerActivity::class.java)
                        .setData(file.uri)
                        .putExtra("fastidm_audio_title", file.title)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            },
            onPlayVideo = { file ->
                context.startActivity(
                    Intent(context, VideoPlayerActivity::class.java)
                        .setData(file.uri)
                        .putExtra("title", file.title)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            }
        )
        return
    }

    if (showBrowser) {
        InAppBrowserScreen(
            initialTarget = browserStart,
            onBack = { showBrowser = false },
            onDownload = { candidate -> message = onEnqueue(candidate) }
        )
        return
    }

    if (showVideoChooser) {
        AlertDialog(
            onDismissRequest = { showVideoChooser = false },
            title = { Text("Choose video quality", color = ink) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Select the available quality for this video.", color = muted, style = MaterialTheme.typography.bodySmall)
                    listOf("Best", "1080p", "720p", "360p").forEach { option ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            RadioButton(selected = quality == option, onClick = { quality = option })
                            Text(option, modifier = Modifier.weight(1f), color = ink)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showVideoChooser = false
                    onStartVideo(url.trim(), quality)
                    message = "Starting $quality download…"
                }) { Text("Download this video") }
            },
            dismissButton = { TextButton(onClick = { showVideoChooser = false }) { Text("Cancel") } }
        )
    }

    BackHandler(enabled = false) { }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                androidx.compose.ui.graphics.Brush.linearGradient(
                    listOf(
                        androidx.compose.ui.graphics.Color(0xFFDBD6EA),
                        androidx.compose.ui.graphics.Color(0xFFE9EAF4),
                        androidx.compose.ui.graphics.Color(0xFFD8E4EE)
                    )
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().align(androidx.compose.ui.Alignment.TopCenter),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            TextButton(onClick = { history = onHistory(); showHistory = true }) {
                Text("History  ↗", color = purple, style = MaterialTheme.typography.titleSmall)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(androidx.compose.ui.Alignment.Center),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.58f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.72f)),
                shadowElevation = 12.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Download everything",
                        color = ink,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it; message = "" },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Paste a video or file link") },
                            singleLine = true,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = purple,
                                unfocusedBorderColor = muted.copy(alpha = 0.35f),
                                focusedContainerColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.35f),
                                unfocusedContainerColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.25f)
                            )
                        )
                        Button(
                            onClick = {
                                val candidate = url.trim()
                                when {
                                    candidate.isBlank() -> message = "Enter a website, search term, or link."
                                    isVideoPlatformUrl(candidate) -> {
                                        quality = "Best"
                                        showVideoChooser = true
                                    }
                                    else -> {
                                        browserStart = if (candidate.matches(Regex("(?i)^https?://.*"))) candidate
                                            else "https://www.google.com/search?q=" + Uri.encode(candidate)
                                        showBrowser = true
                                    }
                                }
                            },
                            modifier = Modifier.height(56.dp),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = purple)
                        ) {
                            Text("Search")
                        }
                    }
                    if (message.isNotBlank()) {
                        Text(message, color = muted, style = MaterialTheme.typography.bodySmall)
                    } else if (ytProgress > 0f && ytProgress < 100f) {
                        Text("Video download · ${ytProgress.toInt()}%", color = muted, style = MaterialTheme.typography.bodySmall)
                    }
                    if (ytProgress > 0f && ytProgress < 100f) {
                        LinearProgressIndicator(
                            progress = { (ytProgress / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                            color = purple,
                            trackColor = purple.copy(alpha = 0.12f)
                        )
                        TextButton(onClick = onStopVideo) { Text("Stop download", color = muted) }
                    }
                }
            }
        }

        Text(
            "Soul Player",
            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(bottom = 8.dp),
            color = muted.copy(alpha = 0.82f),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun DownloadCard(item: DownloadItem, onCancel: () -> Unit) {
    val percent = if (item.total > 0) (item.downloaded * 100 / item.total).toInt().coerceIn(0, 100) else 0
    val label = when (item.status) {
        DownloadManager.STATUS_PENDING -> "Queued"
        DownloadManager.STATUS_RUNNING -> "Downloading"
        DownloadManager.STATUS_PAUSED -> "Paused"
        DownloadManager.STATUS_SUCCESSFUL -> "Completed"
        DownloadManager.STATUS_FAILED -> "Failed (code ${item.reason})"
        else -> "Checking"
    }
    val statusColor = when (item.status) {
        DownloadManager.STATUS_SUCCESSFUL -> androidx.compose.ui.graphics.Color(0xFF16866F)
        DownloadManager.STATUS_FAILED -> androidx.compose.ui.graphics.Color(0xFFB3261E)
        else -> androidx.compose.ui.graphics.Color(0xFF6046A5)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)
    ) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, color = androidx.compose.ui.graphics.Color(0xFF211936))
            Text(label, style = MaterialTheme.typography.labelMedium, color = statusColor)
            if (item.status == DownloadManager.STATUS_RUNNING || item.status == DownloadManager.STATUS_PAUSED) {
                LinearProgressIndicator(
                    progress = { if (item.total > 0) percent / 100f else 0f },
                    modifier = Modifier.fillMaxWidth(),
                    color = androidx.compose.ui.graphics.Color(0xFF16A995)
                )
                Text(
                    if (item.total > 0) "$percent%  ·  ${formatBytes(item.downloaded)} / ${formatBytes(item.total)}" else "${formatBytes(item.downloaded)} downloaded",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            if (item.status != DownloadManager.STATUS_SUCCESSFUL) {
                TextButton(onClick = onCancel) { Text("Remove", color = androidx.compose.ui.graphics.Color(0xFF6046A5)) }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    return String.format("%.2f GB", mb / 1024.0)
}

@Composable
private fun InAppBrowserScreen(
    initialTarget: String,
    onBack: () -> Unit,
    onDownload: (String) -> Unit
) {
    val purple = androidx.compose.ui.graphics.Color(0xFF6046A5)
    val ink = androidx.compose.ui.graphics.Color(0xFF211936)
    val context = LocalContext.current
    var address by rememberSaveable { mutableStateOf(initialTarget) }
    var detectedUrl by remember { mutableStateOf("") }
    var showDownloadPrompt by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageTitle by remember { mutableStateOf("Browser") }

    fun openAddress(raw: String) {
        val value = raw.trim()
        if (value.isBlank()) return
        val target = if (value.matches(Regex("(?i)^https?://.*"))) value
            else if (value.contains(".") && !value.contains(" ")) "https://$value"
            else "https://www.google.com/search?q=" + Uri.encode(value)
        address = target
        webView?.loadUrl(target)
    }

    BackHandler {
        val current = webView
        if (current?.canGoBack() == true) current.goBack() else onBack()
    }

    Column(
        Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFFF5F2FA))
            .statusBarsPadding().navigationBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            TextButton(onClick = { if (webView?.canGoBack() == true) webView?.goBack() else onBack() }) {
                Text("←", color = purple, style = MaterialTheme.typography.titleLarge)
            }
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                placeholder = { Text("Search or enter website") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Go),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { openAddress(address) })
            )
            TextButton(onClick = { openAddress(address) }) { Text("Go", color = purple) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { webView?.goBack() }, enabled = webView?.canGoBack() == true) { Text("Back") }
            TextButton(onClick = { webView?.goForward() }, enabled = webView?.canGoForward() == true) { Text("Forward") }
            TextButton(onClick = { webView?.reload() }) { Text("Reload") }
            Spacer(Modifier.weight(1f))
            Text(pageTitle, maxLines = 1, style = MaterialTheme.typography.labelSmall, color = ink)
        }
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        webView = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadsImagesAutomatically = true
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        settings.setSupportMultipleWindows(false)
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        settings.allowFileAccess = false
                        settings.allowContentAccess = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val target = request.url.toString()
                                val path = request.url.path.orEmpty().lowercase()
                                val likelyFile = Regex("""\.(pdf|zip|rar|7z|apk|exe|dmg|iso|mp3|m4a|wav|flac|mp4|mkv|webm|jpg|jpeg|png|gif|docx?|xlsx?|pptx?|txt|csv)(?:$|/)""")
                                    .containsMatchIn(path)
                                if (likelyFile && request.isForMainFrame) {
                                    detectedUrl = target
                                    showDownloadPrompt = true
                                    return true
                                }
                                return false
                            }

                            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                                address = url
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                address = url
                                pageTitle = view.title?.take(45) ?: "Browser"
                            }
                        }
                        setDownloadListener(DownloadListener { url, _, contentDisposition, mimeType, _ ->
                            detectedUrl = url
                            showDownloadPrompt = true
                        })
                        loadUrl(initialTarget)
                    }
                },
                update = { view -> webView = view }
            )
            if (showDownloadPrompt) {
                Surface(
                    modifier = Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(18.dp),
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = purple,
                    shadowElevation = 10.dp
                ) {
                    TextButton(onClick = { showDownloadPrompt = false; showDownloadPrompt = true }) {
                        Text("⬇", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
    }

    if (showDownloadPrompt && detectedUrl.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { showDownloadPrompt = false },
            title = { Text("Download detected", color = ink) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(URLUtil.guessFileName(detectedUrl, null, null), color = ink)
                    Text(detectedUrl, color = purple, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                    Text("Fast-IDM detected a downloadable file on this page.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = detectedUrl
                    showDownloadPrompt = false
                    onDownload(target)
                }) { Text("Download", color = purple) }
            },
            dismissButton = { TextButton(onClick = { showDownloadPrompt = false }) { Text("Not now") } }
        )
    }
}
