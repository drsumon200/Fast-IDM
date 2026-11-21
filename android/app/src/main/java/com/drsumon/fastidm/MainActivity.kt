package com.drsumon.fastidm

import android.Manifest
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.webkit.URLUtil
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
        val clean = rawUrl.trim()
        val uri = try { Uri.parse(clean) } catch (_: Exception) { null }
        if (uri == null || (uri.scheme != "https" && uri.scheme != "http") || uri.host.isNullOrBlank()) return "Enter a valid HTTP or HTTPS URL."
        return try {
            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            val filename = URLUtil.guessFileName(clean, null, null).replace(Regex("""[\\/:*?"<>|]"""), "_").take(150).ifBlank { "download.bin" }
            val request = DownloadManager.Request(uri).setTitle(filename).setDescription("Fast IDM · Downloads")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true).setAllowedOverRoaming(false)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS + "/FastIDM", filename)
            manager.enqueue(request)
            "Direct download added: $filename"
        } catch (e: Exception) { "Could not start download: ${e.localizedMessage ?: "unknown error"}" }
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

@Composable
private fun FastIdmHome(
    initialUrl: String,
    onEnqueue: (String) -> String,
    onRefresh: () -> List<DownloadItem>,
    onStartVideo: (String, String) -> Unit,
    onStopVideo: () -> Unit,
    onCancel: (Long) -> Unit
) {
    val context = LocalContext.current
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var message by rememberSaveable { mutableStateOf("Paste a direct file URL or share a video link from your browser.") }
    var downloads by remember { mutableStateOf(emptyList<DownloadItem>()) }
    var quality by rememberSaveable { mutableStateOf("720p") }
    var menuOpen by remember { mutableStateOf(false) }
    var ytStatus by remember { mutableStateOf("Ready for video link") }
    var ytProgress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(initialUrl) { if (initialUrl.isNotBlank()) url = initialUrl }
    LaunchedEffect(Unit) {
        while (true) {
            downloads = onRefresh()
            val prefs = context.getSharedPreferences(YtDlpDownloadService.PREFS, android.content.Context.MODE_PRIVATE)
            ytStatus = prefs.getString("status", "Ready for video link") ?: "Ready for video link"
            ytProgress = prefs.getFloat("progress", 0f)
            delay(900)
        }
    }
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Fast IDM", style = MaterialTheme.typography.headlineLarge)
        Text("Android Edition · 0.3.1", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("New download", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("URL") }, placeholder = { Text("https://example.com/file.mp4") }, minLines = 2)
                Button(onClick = { message = onEnqueue(url); downloads = onRefresh() }, modifier = Modifier.fillMaxWidth()) { Text("Direct file download") }
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Website video download", style = MaterialTheme.typography.titleLarge)
                Text("Uses yt-dlp + FFmpeg for supported websites and separate audio/video streams.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("Quality: $quality") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        listOf("Best", "1080p", "720p", "480p", "Audio MP3").forEach { item ->
                            DropdownMenuItem(text = { Text(item) }, onClick = { quality = item; menuOpen = false })
                        }
                    }
                }
                Button(onClick = { onStartVideo(url.trim(), quality); ytStatus = "Starting yt-dlp…"; ytProgress = 0f }, modifier = Modifier.fillMaxWidth(), enabled = url.startsWith("http")) { Text("Download video") }
                OutlinedButton(onClick = onStopVideo, modifier = Modifier.fillMaxWidth()) { Text("Stop video download") }
                LinearProgressIndicator(progress = { ytProgress / 100f }, modifier = Modifier.fillMaxWidth())
                Text(ytStatus, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Downloads", style = MaterialTheme.typography.titleLarge)
        if (downloads.isEmpty()) Text("No direct downloads yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            items(downloads, key = { it.id }) { item -> DownloadCard(item) { onCancel(item.id); downloads = onRefresh() } }
        }
        Text("Video engine and format availability depend on the target website. YouTube and some sites may block requests or require cookies.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (item.status == DownloadManager.STATUS_RUNNING || item.status == DownloadManager.STATUS_PAUSED) {
                LinearProgressIndicator(progress = { if (item.total > 0) percent / 100f else 0f }, modifier = Modifier.fillMaxWidth())
                Text(if (item.total > 0) "$percent% · ${formatBytes(item.downloaded)} / ${formatBytes(item.total)}" else "${formatBytes(item.downloaded)} downloaded", style = MaterialTheme.typography.labelSmall)
            }
            if (item.status != DownloadManager.STATUS_SUCCESSFUL) TextButton(onClick = onCancel) { Text("Remove") }
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