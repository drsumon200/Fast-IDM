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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedUrl = extractUrl(intent)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    FastIdmHome(
                        sharedUrl,
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
        super.onNewIntent(intent); setIntent(intent); sharedUrl = extractUrl(intent)
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
        if (Build.VERSION.SDK_INT < 29) return emptyList()
        val result = mutableListOf<HistoryItem>()
        val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.MIME_TYPE, MediaStore.Downloads.SIZE, MediaStore.Downloads.DATE_ADDED, MediaStore.Downloads.RELATIVE_PATH)
        val selection = "${MediaStore.Downloads.RELATIVE_PATH} = ?"
        val args = arrayOf(Environment.DIRECTORY_DOWNLOADS + "/FastIDM/")
        contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args, MediaStore.Downloads.DATE_ADDED + " DESC")?.use { cur ->
            val idCol = cur.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameCol = cur.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val mimeCol = cur.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE)
            val sizeCol = cur.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
            val dateCol = cur.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)
            while (cur.moveToNext()) {
                val id = cur.getLong(idCol)
                val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                result += HistoryItem(id, cur.getString(nameCol) ?: "Downloaded file", cur.getString(mimeCol) ?: "application/octet-stream", cur.getLong(sizeCol), cur.getLong(dateCol) * 1000L, uri)
            }
        }
        return result
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
}

private fun isYoutubeUrl(raw: String): Boolean {
    val host = try { Uri.parse(raw.trim()).host?.lowercase().orEmpty() } catch (_: Exception) { "" }
    return host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com") ||
        host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com")
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
private fun FastIdmHome(
    initialUrl: String,
    onEnqueue: (String) -> String,
    onRefresh: () -> List<DownloadItem>,
    onHistory: () -> List<HistoryItem>,
    onStartVideo: (String, String) -> Unit,
    onStopVideo: () -> Unit,
    onCancel: (Long) -> Unit
) {
    val context = LocalContext.current
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var message by rememberSaveable { mutableStateOf("Paste a direct file URL or share a video link from your browser.") }
    var downloads by remember { mutableStateOf(emptyList<DownloadItem>()) }
    var history by remember { mutableStateOf(emptyList<HistoryItem>()) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var quality by rememberSaveable { mutableStateOf("720p") }
    var showYoutubeChooser by rememberSaveable { mutableStateOf(false) }
    var lastPromptedYoutubeUrl by rememberSaveable { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var ytStatus by remember { mutableStateOf("Ready for video link") }
    var ytProgress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(initialUrl) { if (initialUrl.isNotBlank()) url = initialUrl }
    LaunchedEffect(url) {
        val candidate = url.trim()
        if (candidate.isNotBlank()) {
            delay(450)
            if (isYoutubeUrl(candidate) && candidate != lastPromptedYoutubeUrl) {
                lastPromptedYoutubeUrl = candidate
                showYoutubeChooser = true
            }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            downloads = onRefresh()
            val prefs = context.getSharedPreferences(YtDlpDownloadService.PREFS, android.content.Context.MODE_PRIVATE)
            ytStatus = prefs.getString("status", "Ready for video link") ?: "Ready for video link"
            ytProgress = prefs.getFloat("progress", 0f)
            delay(900)
        }
    }

    val purple = androidx.compose.ui.graphics.Color(0xFF6046A5)
    val ink = androidx.compose.ui.graphics.Color(0xFF211936)
    val muted = androidx.compose.ui.graphics.Color(0xFF716B7D)
    val pale = androidx.compose.ui.graphics.Color(0xFFF5F2FA)
    val teal = androidx.compose.ui.graphics.Color(0xFF16A995)

    if (showHistory) {
        AlertDialog(
            onDismissRequest = { showHistory = false },
            title = { Text("Download history") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.heightIn(max = 460.dp)) {
                    Button(onClick = { context.startActivity(Intent(context, SoulPlayerActivity::class.java)) }, modifier = Modifier.fillMaxWidth()) { Text("♫  Open Soul Player") }
                    if (history.isEmpty()) Text("No completed files found in Downloads/FastIDM yet.", color = muted)
                    else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(history, key = { it.id }) { file ->
                            Card(colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFFF5F2FA))) {
                                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(file.title, color = ink, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                                    Text("${file.mime}  ·  ${formatBytes(file.size)}", color = muted, style = MaterialTheme.typography.bodySmall)
                                    if (file.mime.startsWith("audio/")) TextButton(onClick = { context.startActivity(Intent(context, SoulPlayerActivity::class.java)) }) { Text("Play in Soul Player") }
                                    else TextButton(onClick = { try { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(file.uri, file.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } catch (_: Exception) {} }) { Text("Open file") }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showHistory = false }) { Text("Close") } }
        )
    }

    if (showYoutubeChooser) {
        AlertDialog(
            onDismissRequest = { showYoutubeChooser = false },
            title = { Text("Download video as") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("YouTube link detected", color = muted, style = MaterialTheme.typography.bodySmall)
                    listOf("Audio MP3", "360p", "720p", "1080p", "Best").forEach { option ->
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            RadioButton(selected = quality == option, onClick = { quality = option })
                            Text(option, modifier = Modifier.weight(1f), color = ink)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showYoutubeChooser = false
                    onStartVideo(url.trim(), quality)
                    ytStatus = "Starting $quality download…"
                    ytProgress = 0f
                }) { Text("Download") }
            },
            dismissButton = { TextButton(onClick = { showYoutubeChooser = false }) { Text("Cancel") } }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(androidx.compose.ui.graphics.Color(0xFFFBFAFE), pale)
                )
            ),
        contentPadding = PaddingValues(start = 18.dp, top = 20.dp, end = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("FAST IDM", style = MaterialTheme.typography.headlineMedium, color = ink)
                    Text("Your downloads, one place.", style = MaterialTheme.typography.bodyMedium, color = muted)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    TextButton(onClick = { history = onHistory(); showHistory = true }) { Text("History", color = purple) }
                    Surface(color = androidx.compose.ui.graphics.Color(0xFFE9E2F7), shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)) {
                        Text("0.4.0", modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp), color = purple, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Surface(color = androidx.compose.ui.graphics.Color(0xFFECE6F8), shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) {
                            Text("↓", modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp), color = purple, style = MaterialTheme.typography.titleLarge)
                        }
                        Column {
                            Text("New download", style = MaterialTheme.typography.titleLarge, color = ink)
                            Text("Files · Drive · APK · PDF · ZIP · more", style = MaterialTheme.typography.bodySmall, color = muted)
                        }
                    }
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Download URL") },
                        placeholder = { Text("Paste or share any file / video link") },
                        minLines = 2,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
                    )
                    Button(
                        onClick = { if (isYoutubeUrl(url.trim())) showYoutubeChooser = true else { message = onEnqueue(url); downloads = onRefresh() } },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = purple)
                    ) { Text("↓   Download file", style = MaterialTheme.typography.titleSmall) }
                    Text(message, style = MaterialTheme.typography.bodySmall, color = muted)
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFFEDE8F5))
            ) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Surface(color = androidx.compose.ui.graphics.Color.White, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) {
                            Text("▶", modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp), color = purple, style = MaterialTheme.typography.titleMedium)
                        }
                        Column {
                            Text("Website video", style = MaterialTheme.typography.titleLarge, color = ink)
                            Text("Auto-detect YouTube · MP3 · 360p–1080p", style = MaterialTheme.typography.bodySmall, color = muted)
                        }
                    }
                    Box {
                        OutlinedButton(
                            onClick = { showYoutubeChooser = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                        ) { Text("Choose format  ·  $quality") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            listOf("Best", "1080p", "720p", "480p", "Audio MP3").forEach { item ->
                                DropdownMenuItem(text = { Text(item) }, onClick = { quality = item; menuOpen = false })
                            }
                        }
                    }
                    Button(
                        onClick = { showYoutubeChooser = true },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        enabled = url.trim().startsWith("http", ignoreCase = true),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = purple)
                    ) { Text("▶   Choose download format") }
                    OutlinedButton(
                        onClick = onStopVideo,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
                    ) { Text("Stop video download", color = purple) }
                    LinearProgressIndicator(
                        progress = { (ytProgress / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = teal,
                        trackColor = androidx.compose.ui.graphics.Color(0xFFD8D0E5)
                    )
                    Text(ytStatus, style = MaterialTheme.typography.bodySmall, color = muted)
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Downloads", style = MaterialTheme.typography.titleLarge, color = ink)
                    Text("${downloads.size} item(s)", style = MaterialTheme.typography.bodySmall, color = muted)
                }
                Surface(color = androidx.compose.ui.graphics.Color(0xFFE5F5F1), shape = androidx.compose.foundation.shape.RoundedCornerShape(50)) {
                    Text("LIVE", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = teal, style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        if (downloads.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)
                ) {
                    Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("↓", color = purple, style = MaterialTheme.typography.headlineLarge)
                        Text("Nothing here yet", style = MaterialTheme.typography.titleMedium, color = ink)
                        Text("Your direct downloads will appear here.", style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                }
            }
        } else {
            items(downloads, key = { it.id }) { item ->
                DownloadCard(item) { onCancel(item.id); downloads = onRefresh() }
            }
        }

        item {
            Text(
                "Downloads are saved in Downloads/FastIDM. Website support depends on the source site.",
                style = MaterialTheme.typography.bodySmall,
                color = muted
            )
        }
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