package com.drsumon.fastidm

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.webkit.URLUtil
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import android.webkit.DownloadListener
import android.webkit.WebResourceRequest
import androidx.compose.ui.viewinterop.AndroidView
import java.net.URLConnection
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

data class DownloadItem(
    val id: Long,
    val title: String,
    val status: Int,
    val downloaded: Long,
    val total: Long,
    val reason: Int
)

data class HistoryItem(
    val id: Long,
    val title: String,
    val mime: String,
    val size: Long,
    val date: Long,
    val uri: Uri
)

class MainActivity : ComponentActivity() {
    private var sharedUrl by mutableStateOf("")
    private var historyRequestCount by mutableIntStateOf(0)
    private var darkMode by mutableStateOf(true) // Default to modern sleek dark mode
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedUrl = extractUrl(intent)
        darkMode = getSharedPreferences("fastidm_settings", MODE_PRIVATE).getBoolean("dark_mode", true)
        if (intent?.action == ACTION_OPEN_HISTORY) historyRequestCount++
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val darkScheme = darkColorScheme(
                primary = Color(0xFF6366F1),
                secondary = Color(0xFF38BDF8),
                background = Color(0xFF0B0E14),
                surface = Color(0xFF161C2C),
                onBackground = Color(0xFFF8FAFC),
                onSurface = Color(0xFFF8FAFC)
            )
            val lightScheme = lightColorScheme(
                primary = Color(0xFF6366F1),
                secondary = Color(0xFF0284C7),
                background = Color(0xFFF1F5F9),
                surface = Color(0xFFFFFFFF),
                onBackground = Color(0xFF0F172A),
                onSurface = Color(0xFF0F172A)
            )

            MaterialTheme(colorScheme = if (darkMode) darkScheme else lightScheme) {
                Surface(Modifier.fillMaxSize()) {
                    FastIdmHome(
                        initialUrl = sharedUrl,
                        historyRequestCount = historyRequestCount,
                        darkMode = darkMode,
                        onNightModeChanged = { enabled ->
                            darkMode = enabled
                            getSharedPreferences("fastidm_settings", MODE_PRIVATE)
                                .edit()
                                .putBoolean("dark_mode", enabled)
                                .apply()
                        },
                        onEnqueue = ::enqueueDownload,
                        onUpdateYtDlp = ::requestYtDlpUpdate,
                        onRefresh = ::queryDownloads,
                        onHistory = ::queryHistory,
                        onStartVideo = { url, quality, speedLimit ->
                            val service = Intent(this, YtDlpDownloadService::class.java).apply {
                                action = YtDlpDownloadService.ACTION_START
                                putExtra(YtDlpDownloadService.EXTRA_URL, url)
                                putExtra(YtDlpDownloadService.EXTRA_QUALITY, quality)
                                putExtra(YtDlpDownloadService.EXTRA_SPEED_LIMIT, speedLimit)
                            }
                            ContextCompat.startForegroundService(this, service)
                        },
                        onSchedule = ::scheduleDownload,
                        onStopVideo = {
                            startService(
                                Intent(this, YtDlpDownloadService::class.java)
                                    .setAction(YtDlpDownloadService.ACTION_STOP)
                            )
                        },
                        onCancel = { id ->
                            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).remove(id)
                        },
                        onOpenFile = ::openHistoryItem
                    )
                }
            }
        }
        scheduleYtDlpUpdates()
    }

    private fun scheduleYtDlpUpdates() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val work = PeriodicWorkRequestBuilder<YtDlpUpdateWorker>(3, TimeUnit.DAYS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "fastidm-ytdlp-nightly",
            ExistingPeriodicWorkPolicy.KEEP,
            work
        )
    }

    private fun requestYtDlpUpdate() {
        val service = Intent(this, YtDlpDownloadService::class.java).setAction(YtDlpDownloadService.ACTION_UPDATE)
        ContextCompat.startForegroundService(this, service)
        Toast.makeText(this, "yt-dlp NIGHTLY update queued", Toast.LENGTH_SHORT).show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedUrl = extractUrl(intent)
        if (intent.action == ACTION_OPEN_HISTORY) historyRequestCount++
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
        if (isYoutubeUrl(entered)) {
            return "YouTube link detected. Tap '⚡ Fast Download' to start instant stream extraction."
        }
        if (isGoogleDriveUrl(entered)) {
            lifecycleScope.launch {
                try {
                    val resolved = withContext(Dispatchers.IO) { GoogleDriveResolver.resolve(entered) }
                    val message = enqueueResolvedDownload(
                        resolved.url,
                        resolved.cookieHeader,
                        resolved.contentDisposition,
                        resolved.contentType
                    )
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(
                        this@MainActivity,
                        e.message ?: "Google Drive link could not be prepared.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            return "Google Drive file resolving (bypassing virus scan limits)…"
        }
        return enqueueResolvedDownload(entered, null, null, null)
    }

    private fun enqueueResolvedDownload(
        downloadUrl: String,
        cookieHeader: String?,
        contentDisposition: String?,
        contentType: String?
    ): String {
        val uri = try { Uri.parse(downloadUrl) } catch (_: Exception) { null }
        if (uri == null || (uri.scheme != "https" && uri.scheme != "http") || uri.host.isNullOrBlank()) {
            return "Enter a valid HTTP or HTTPS URL."
        }
        return try {
            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            val parsedName = GoogleDriveResolver.extractFilenameFromDisposition(contentDisposition)
            val filename = (parsedName ?: URLUtil.guessFileName(downloadUrl, contentDisposition, contentType))
                .replace(Regex("""[\\/:*?"<>|]"""), "_").take(150).ifBlank { "download.bin" }

            val targetFolder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "FastIDM")
            if (!targetFolder.exists()) targetFolder.mkdirs()

            val request = DownloadManager.Request(uri)
                .setTitle(filename)
                .setDescription("Fast IDM · File download")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "FastIDM/$filename")
                .addRequestHeader("User-Agent", GoogleDriveResolver.USER_AGENT)
            if (!cookieHeader.isNullOrBlank()) request.addRequestHeader("Cookie", cookieHeader)
            manager.enqueue(request)
            val source = if (isGoogleDriveUrl(downloadUrl)) "Google Drive file" else "File"
            "$source added to downloads: $filename"
        } catch (e: Exception) {
            "Could not start download: ${e.localizedMessage ?: "unknown error"}"
        }
    }

    private fun getFileUri(file: File): Uri {
        return try {
            androidx.core.content.FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.provider",
                file
            )
        } catch (_: Exception) {
            Uri.fromFile(file)
        }
    }

    private fun scheduleDownload(url: String, quality: String, isVideo: Boolean, speedLimit: String, delayMinutes: Int) {
        val data = workDataOf(
            ScheduledDownloadWorker.KEY_URL to url.trim(),
            ScheduledDownloadWorker.KEY_QUALITY to quality,
            ScheduledDownloadWorker.KEY_IS_VIDEO to isVideo,
            ScheduledDownloadWorker.KEY_SPEED_LIMIT to speedLimit
        )
        val work = OneTimeWorkRequestBuilder<ScheduledDownloadWorker>()
            .setInitialDelay(delayMinutes.toLong(), TimeUnit.MINUTES)
            .setInputData(data)
            .addTag("fastidm_scheduled")
            .build()
        WorkManager.getInstance(applicationContext).enqueue(work)
        Toast.makeText(this, "Download scheduled to start in $delayMinutes minutes", Toast.LENGTH_SHORT).show()
    }

    private fun queryHistory(): List<HistoryItem> {
        val itemsMap = LinkedHashMap<String, HistoryItem>()

        // 1. Android DownloadManager completed downloads
        try {
            val manager = getSystemService(DOWNLOAD_SERVICE) as? DownloadManager
            if (manager != null) {
                val cursor = manager.query(DownloadManager.Query().setFilterByStatus(DownloadManager.STATUS_SUCCESSFUL))
                cursor?.use { cur ->
                    val idCol = cur.getColumnIndex(DownloadManager.COLUMN_ID)
                    val titleCol = cur.getColumnIndex(DownloadManager.COLUMN_TITLE)
                    val mimeCol = cur.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE)
                    val sizeCol = cur.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val dateCol = cur.getColumnIndex(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)
                    val localUriCol = cur.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                    while (cur.moveToNext()) {
                        val id = if (idCol >= 0) cur.getLong(idCol) else System.currentTimeMillis()
                        val title = (if (titleCol >= 0) cur.getString(titleCol) else null)?.ifBlank { "Download" } ?: "Download"
                        val uri = manager.getUriForDownloadedFile(id)
                            ?: (if (localUriCol >= 0) cur.getString(localUriCol)?.let { Uri.parse(it) } else null)
                            ?: getFileUri(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "FastIDM/$title"))

                        val rawMime = if (mimeCol >= 0) cur.getString(mimeCol) else null
                        val mime = if (!rawMime.isNullOrBlank() && rawMime != "application/octet-stream") rawMime else resolveMimeType(title)
                        val size = if (sizeCol >= 0) cur.getLong(sizeCol) else 0L
                        val date = if (dateCol >= 0) cur.getLong(dateCol) else System.currentTimeMillis()

                        itemsMap[title.lowercase()] = HistoryItem(id, title, mime, size, date, uri)
                    }
                }
            }
        } catch (_: Exception) {}

        // 2. yt-dlp Video / Audio download history from SharedPreferences
        try {
            val prefs = getSharedPreferences(YtDlpDownloadService.PREFS_HISTORY, MODE_PRIVATE)
            val raw = prefs.getString("items", "[]") ?: "[]"
            val array = org.json.JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val title = obj.optString("title", "Video download")
                val uriStr = obj.optString("uri", "")
                val uri = if (uriStr.isNotBlank()) Uri.parse(uriStr) else null
                if (uri != null) {
                    val rawMime = obj.optString("mime", "")
                    val mime = if (rawMime.isNotBlank() && rawMime != "application/octet-stream") rawMime else resolveMimeType(title)
                    val size = obj.optLong("size", 0L)
                    val date = obj.optLong("date", System.currentTimeMillis())
                    val id = obj.optLong("id", System.currentTimeMillis())
                    itemsMap[title.lowercase()] = HistoryItem(id, title, mime, size, date, uri)
                }
            }
        } catch (_: Exception) {}

        // 3. Scan physical FastIDM storage directories for any downloaded files
        try {
            val scanDirs = listOf(
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "FastIDM"),
                File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "FastIDM")
            )
            for (dir in scanDirs) {
                if (dir.exists() && dir.isDirectory) {
                    dir.listFiles()?.forEach { file ->
                        if (file.isFile && !file.name.endsWith(".part") && !file.name.endsWith(".tmp") && !file.name.startsWith(".")) {
                            val key = file.name.lowercase()
                            if (!itemsMap.containsKey(key)) {
                                val mime = resolveMimeType(file.name)
                                val safeUri = getFileUri(file)
                                itemsMap[key] = HistoryItem(
                                    id = file.lastModified() + file.length(),
                                    title = file.name,
                                    mime = mime,
                                    size = file.length(),
                                    date = file.lastModified(),
                                    uri = safeUri
                                )
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return itemsMap.values.sortedByDescending { it.date }
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
            while (c.moveToNext()) {
                result += DownloadItem(
                    c.getLong(id),
                    c.getString(title) ?: "Download",
                    c.getInt(status),
                    c.getLong(current),
                    c.getLong(total),
                    c.getInt(reason)
                )
            }
        }
        return result.sortedByDescending { it.id }
    }

    private fun openHistoryItem(item: HistoryItem) {
        try {
            val uri = if (item.uri.scheme == "file") {
                val path = item.uri.path
                if (path != null) {
                    val file = File(path)
                    if (file.exists()) getFileUri(file) else item.uri
                } else item.uri
            } else item.uri

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, item.mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val ACTION_OPEN_HISTORY = "com.drsumon.fastidm.action.OPEN_HISTORY"
    }
}

private fun isYoutubeUrl(raw: String): Boolean {
    val host = try { Uri.parse(raw.trim()).host?.lowercase().orEmpty() } catch (_: Exception) { "" }
    return host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com") ||
        host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com")
}

private val supportedVideoExtensions = setOf(
    "mp4", "mkv", "avi", "mov", "wmv", "webm", "flv", "f4v", "3gp", "avchd", "ts", "m4v"
)

private val supportedAudioExtensions = setOf(
    "mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "wma", "amr", "mid", "midi"
)

private fun isSupportedVideoFile(rawUrl: String, contentDisposition: String? = null): Boolean {
    val path = try { Uri.parse(rawUrl.trim()).path.orEmpty() } catch (_: Exception) { rawUrl }
    val dispositionName = contentDisposition
        ?.let { Regex("""(?i)filename\*?=(?:UTF-8''|["']?)([^"';]+)["']?""").find(it)?.groupValues?.getOrNull(1) }
        .orEmpty()
    return listOf(path, dispositionName).any { candidate ->
        val extension = candidate.substringAfterLast('/', candidate).substringAfterLast('.', "")
            .substringBefore('?').substringBefore('#').lowercase()
        extension in supportedVideoExtensions
    }
}

private fun isSupportedAudioFile(rawUrl: String, contentDisposition: String? = null): Boolean {
    val path = try { Uri.parse(rawUrl.trim()).path.orEmpty() } catch (_: Exception) { rawUrl }
    val dispositionName = contentDisposition
        ?.let { Regex("""(?i)filename\*?=(?:UTF-8''|["']?)([^"';]+)["']?""").find(it)?.groupValues?.getOrNull(1) }
        .orEmpty()
    return listOf(path, dispositionName).any { candidate ->
        val extension = candidate.substringAfterLast('/', candidate).substringAfterLast('.', "")
            .substringBefore('?').substringBefore('#').lowercase()
        extension in supportedAudioExtensions
    }
}

private fun resolveMimeType(filename: String): String {
    val ext = filename.substringAfterLast('.', "").substringBefore('?').lowercase()
    return when (ext) {
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "3gp" -> "video/3gpp"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "ogg", "opus" -> "audio/ogg"
        "aac" -> "audio/aac"
        "apk" -> "application/vnd.android.package-archive"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        "rar" -> "application/x-rar-compressed"
        "7z" -> "application/x-7z-compressed"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "txt" -> "text/plain"
        else -> URLConnection.guessContentTypeFromName(filename) ?: "application/octet-stream"
    }
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

private fun isGoogleDriveUrl(raw: String): Boolean {
    return GoogleDriveResolver.isGoogleDrive(raw)
}

// ---------------- MODERN DARK UI HOME ----------------
@Composable
private fun FastIdmHome(
    initialUrl: String,
    historyRequestCount: Int,
    darkMode: Boolean,
    onNightModeChanged: (Boolean) -> Unit,
    onEnqueue: (String) -> String,
    onUpdateYtDlp: () -> Unit,
    onRefresh: () -> List<DownloadItem>,
    onHistory: () -> List<HistoryItem>,
    onStartVideo: (String, String, String) -> Unit,
    onSchedule: (String, String, Boolean, String, Int) -> Unit,
    onStopVideo: () -> Unit,
    onCancel: (Long) -> Unit,
    onOpenFile: (HistoryItem) -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var history by remember { mutableStateOf(emptyList<HistoryItem>()) }
    var downloads by remember { mutableStateOf(emptyList<DownloadItem>()) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var quality by rememberSaveable { mutableStateOf("Best") }
    var speedLimit by rememberSaveable { mutableStateOf("Unlimited") }
    var scheduleOption by rememberSaveable { mutableStateOf("Immediate") }
    var showVideoChooser by rememberSaveable { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf("") }
    var ytStatus by remember { mutableStateOf("Ready") }
    var ytProgress by remember { mutableFloatStateOf(0f) }
    var showBrowser by rememberSaveable { mutableStateOf(false) }
    var browserStart by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(initialUrl) { if (initialUrl.isNotBlank()) url = initialUrl }
    LaunchedEffect(historyRequestCount) {
        if (historyRequestCount > 0) {
            downloads = onRefresh()
            history = onHistory()
            showHistory = true
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            downloads = onRefresh()
            val prefs = context.getSharedPreferences(YtDlpDownloadService.PREFS, Context.MODE_PRIVATE)
            ytStatus = prefs.getString("status", "Ready") ?: "Ready"
            ytProgress = prefs.getFloat("progress", 0f)
            delay(1000)
        }
    }

    val primaryBg = if (darkMode) Color(0xFF0B0F19) else Color(0xFFF1F5F9)
    val cardBg = if (darkMode) Color(0xFF161D2F) else Color(0xFFFFFFFF)
    val cardBorder = if (darkMode) Color(0xFF26324A) else Color(0xFFE2E8F0)
    val textPrimary = if (darkMode) Color(0xFFF8FAFC) else Color(0xFF0F172A)
    val textMuted = if (darkMode) Color(0xFF94A3B8) else Color(0xFF64748B)
    val accentIndigo = Color(0xFF6366F1)
    val accentCyan = Color(0xFF38BDF8)
    val accentEmerald = Color(0xFF10B981)
    val gradientBrush = Brush.horizontalGradient(listOf(Color(0xFF6366F1), Color(0xFF8B5CF6)))

    if (showHistory) {
        DownloadHistoryScreen(
            history = history,
            downloads = downloads,
            videoStatus = ytStatus,
            videoProgress = ytProgress,
            darkMode = darkMode,
            onBack = { showHistory = false },
            onRefresh = {
                history = onHistory()
                downloads = onRefresh()
            },
            onPlayAudio = { file ->
                val safeUri = if (file.uri.scheme == "file") {
                    val path = file.uri.path
                    if (path != null && File(path).exists()) {
                        try {
                            androidx.core.content.FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.provider",
                                File(path)
                            )
                        } catch (_: Exception) { file.uri }
                    } else file.uri
                } else file.uri

                context.startActivity(
                    Intent(context, SoulPlayerActivity::class.java)
                        .setData(safeUri)
                        .putExtra("fastidm_audio_title", file.title)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            },
            onPlayVideo = { file ->
                val safeUri = if (file.uri.scheme == "file") {
                    val path = file.uri.path
                    if (path != null && File(path).exists()) {
                        try {
                            androidx.core.content.FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.provider",
                                File(path)
                            )
                        } catch (_: Exception) { file.uri }
                    } else file.uri
                } else file.uri

                context.startActivity(
                    Intent(context, VideoPlayerActivity::class.java)
                        .setData(safeUri)
                        .putExtra("title", file.title)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            },
            onOpenFile = onOpenFile
        )
        return
    }

    if (showSettings) {
        BasicSettingsScreen(
            darkMode = darkMode,
            onNightModeChanged = onNightModeChanged,
            onUpdateYtDlp = onUpdateYtDlp,
            onBack = { showSettings = false }
        )
        return
    }

    if (showBrowser) {
        InAppBrowserScreen(
            darkMode = darkMode,
            initialTarget = browserStart,
            onBack = { showBrowser = false },
            onDownload = { candidate -> message = onEnqueue(candidate) }
        )
        return
    }

    val isCandidateVideo = isVideoPlatformUrl(url)
    val isCandidateDrive = isGoogleDriveUrl(url)
    val videoRunning = ytStatus != "Ready" &&
        !ytStatus.startsWith("Completed") &&
        !ytStatus.startsWith("Failed") &&
        ytStatus != "Canceled"

    fun handleStartDownload() {
        val candidate = url.trim()
        if (candidate.isBlank()) {
            message = "Please enter or paste a valid download link."
            return
        }

        val delayMinutes = when (scheduleOption) {
            "15 min" -> 15
            "30 min" -> 30
            "1 hour" -> 60
            "2 hours" -> 120
            else -> 0
        }

        if (delayMinutes > 0) {
            onSchedule(candidate, quality, isCandidateVideo, speedLimit, delayMinutes)
            message = "Scheduled to download in $scheduleOption!"
            url = ""
            return
        }

        if (isCandidateVideo) {
            onStartVideo(candidate, quality, speedLimit)
            message = "⚡ Instant YouTube stream started ($quality)"
            url = ""
        } else {
            message = onEnqueue(candidate)
            url = ""
        }
    }

    Scaffold(
        containerColor = primaryBg,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "⚡ FAST IDM",
                        color = textPrimary,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.5.sp
                        )
                    )
                    Surface(
                        color = Color(0xFF6366F1).copy(alpha = 0.2f),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF6366F1).copy(alpha = 0.4f))
                    ) {
                        Text(
                            "TURBO",
                            color = accentCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(
                        onClick = {
                            downloads = onRefresh()
                            history = onHistory()
                            showHistory = true
                        }
                    ) {
                        Text("📁", fontSize = 20.sp)
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Text("⚙", fontSize = 20.sp)
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Main Input & Options Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = cardBg,
                shape = RoundedCornerShape(22.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, cardBorder),
                shadowElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Link Type Indicator Badge
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Download Any File or Stream",
                            color = textMuted,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        when {
                            isCandidateVideo -> {
                                Text("🎬 Instant YouTube Video", color = Color(0xFFFF5252), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            isCandidateDrive -> {
                                Text("📁 Google Drive Direct File", color = accentCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            url.isNotBlank() -> {
                                Text("📦 Direct Download", color = accentEmerald, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // URL Input Field
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it; message = "" },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(
                                "Paste YouTube, Google Drive, APK, PDF link…",
                                color = textMuted,
                                fontSize = 13.sp
                            )
                        },
                        trailingIcon = {
                            if (url.isNotBlank()) {
                                IconButton(onClick = { url = "" }) {
                                    Text("✖", color = textMuted)
                                }
                            } else {
                                TextButton(
                                    onClick = {
                                        clipboardManager.getText()?.text?.let { text ->
                                            if (text.isNotBlank()) url = text.trim()
                                        }
                                    }
                                ) {
                                    Text("📋 Paste", color = accentIndigo, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = textPrimary,
                            unfocusedTextColor = textPrimary,
                            focusedBorderColor = accentIndigo,
                            unfocusedBorderColor = cardBorder,
                            focusedContainerColor = if (darkMode) Color(0xFF0F1422) else Color(0xFFF8FAFC),
                            unfocusedContainerColor = if (darkMode) Color(0xFF0F1422) else Color(0xFFF8FAFC)
                        )
                    )

                    // Quality Selection Row
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Video / Audio Quality", color = textMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(listOf("Best", "1080p", "720p", "480p", "360p", "Audio MP3")) { opt ->
                                val selected = quality == opt
                                Surface(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { quality = opt },
                                    color = if (selected) accentIndigo else (if (darkMode) Color(0xFF222B3F) else Color(0xFFE2E8F0)),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text(
                                        opt,
                                        color = if (selected) Color.White else textPrimary,
                                        fontSize = 11.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Speed Limiter Row
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("🚀 Download Speed Limit", color = textMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(listOf("Unlimited", "500 KB/s", "1 MB/s", "2 MB/s", "5 MB/s")) { lim ->
                                val selected = speedLimit == lim
                                Surface(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { speedLimit = lim },
                                    color = if (selected) accentCyan.copy(alpha = 0.3f) else (if (darkMode) Color(0xFF222B3F) else Color(0xFFE2E8F0)),
                                    shape = RoundedCornerShape(10.dp),
                                    border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, accentCyan) else null
                                ) {
                                    Text(
                                        lim,
                                        color = if (selected) accentCyan else textPrimary,
                                        fontSize = 11.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Scheduler Row
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("⏱ Start Schedule", color = textMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(listOf("Immediate", "15 min", "30 min", "1 hour", "2 hours")) { sched ->
                                val selected = scheduleOption == sched
                                Surface(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { scheduleOption = sched },
                                    color = if (selected) accentEmerald.copy(alpha = 0.3f) else (if (darkMode) Color(0xFF222B3F) else Color(0xFFE2E8F0)),
                                    shape = RoundedCornerShape(10.dp),
                                    border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, accentEmerald) else null
                                ) {
                                    Text(
                                        sched,
                                        color = if (selected) accentEmerald else textPrimary,
                                        fontSize = 11.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Action Buttons Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = ::handleStartDownload,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = accentIndigo)
                        ) {
                            Text(
                                if (scheduleOption == "Immediate") "⚡ Fast Download" else "⏱ Schedule Start",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        Button(
                            onClick = {
                                val candidate = url.trim()
                                browserStart = if (candidate.matches(Regex("(?i)^https?://.*"))) candidate
                                    else if (candidate.isNotBlank()) "https://www.google.com/search?q=" + Uri.encode(candidate)
                                    else "https://www.google.com"
                                showBrowser = true
                            },
                            modifier = Modifier.height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = if (darkMode) Color(0xFF242E44) else Color(0xFFE2E8F0))
                        ) {
                            Text("🌐 Browser", fontSize = 13.sp, color = textPrimary)
                        }
                    }
                }
            }

            // Status message
            if (message.isNotBlank()) {
                Surface(
                    color = Color(0xFF6366F1).copy(alpha = 0.15f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        message,
                        color = accentCyan,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            // Real-Time Active Download Monitor Widget
            if (videoRunning) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = cardBg,
                    shape = RoundedCornerShape(18.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, accentIndigo.copy(alpha = 0.5f))
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("⚡ Instant Video Download", color = textPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            TextButton(onClick = onStopVideo) {
                                Text("Cancel", color = Color(0xFFFF5252), fontSize = 12.sp)
                            }
                        }
                        Text(ytStatus, color = textMuted, fontSize = 12.sp, maxLines = 1)
                        LinearProgressIndicator(
                            progress = { (ytProgress / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                            color = accentIndigo,
                            trackColor = Color(0xFF2A364F)
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${ytProgress.toInt()}% complete", color = accentCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text("Speed: $speedLimit", color = textMuted, fontSize = 11.sp)
                        }
                    }
                }
            }

            // Active direct downloads
            val activeFiles = downloads.filter { it.status != DownloadManager.STATUS_SUCCESSFUL }
            if (activeFiles.isNotEmpty()) {
                Text("Active File Downloads (${activeFiles.size})", color = textMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(activeFiles, key = { it.id }) { item ->
                        DownloadCard(item = item, onCancel = { onCancel(item.id) }, darkMode = darkMode)
                    }
                }
            } else if (!videoRunning) {
                // Empty state card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("🚀 Ready for Downloads", color = textMuted, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "Paste any YouTube video, Google Drive file, APK, PDF, or video link above",
                            color = textMuted.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadCard(item: DownloadItem, onCancel: () -> Unit, darkMode: Boolean) {
    val percent = if (item.total > 0) (item.downloaded * 100 / item.total).toInt().coerceIn(0, 100) else 0
    val label = when (item.status) {
        DownloadManager.STATUS_PENDING -> "Queued"
        DownloadManager.STATUS_RUNNING -> "Downloading · $percent%"
        DownloadManager.STATUS_PAUSED -> "Paused"
        DownloadManager.STATUS_SUCCESSFUL -> "Completed"
        DownloadManager.STATUS_FAILED -> "Failed"
        else -> "Checking"
    }
    val cardBg = if (darkMode) Color(0xFF161D2F) else Color.White
    val cardBorder = if (darkMode) Color(0xFF26324A) else Color(0xFFE2E8F0)
    val textPrimary = if (darkMode) Color(0xFFF8FAFC) else Color(0xFF0F172A)
    val textMuted = if (darkMode) Color(0xFF94A3B8) else Color(0xFF64748B)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = androidx.compose.foundation.BorderStroke(1.dp, cardBorder)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, color = textPrimary, modifier = Modifier.weight(1f))
                TextButton(onClick = onCancel, contentPadding = PaddingValues(0.dp)) {
                    Text("✖", color = Color(0xFFFF5252), fontSize = 12.sp)
                }
            }
            Text(label, fontSize = 11.sp, color = textMuted)
            if (item.status == DownloadManager.STATUS_RUNNING || item.status == DownloadManager.STATUS_PAUSED) {
                LinearProgressIndicator(
                    progress = { if (item.total > 0) percent / 100f else 0f },
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                    color = Color(0xFF6366F1),
                    trackColor = Color(0xFF242E44)
                )
                Text(
                    if (item.total > 0) "${formatBytes(item.downloaded)} / ${formatBytes(item.total)}" else "${formatBytes(item.downloaded)} downloaded",
                    fontSize = 10.sp,
                    color = textMuted
                )
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

// ---------------- DOWNLOAD HISTORY SCREEN WITH CATEGORIZED FILE OPENERS ----------------
@Composable
private fun DownloadHistoryScreen(
    history: List<HistoryItem>,
    downloads: List<DownloadItem>,
    videoStatus: String,
    videoProgress: Float,
    darkMode: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit = {},
    onPlayAudio: (HistoryItem) -> Unit,
    onPlayVideo: (HistoryItem) -> Unit,
    onOpenFile: (HistoryItem) -> Unit
) {
    var selectedCategory by remember { mutableStateOf("All") }
    val primaryBg = if (darkMode) Color(0xFF0B0F19) else Color(0xFFF1F5F9)
    val cardBg = if (darkMode) Color(0xFF161D2F) else Color.White
    val cardBorder = if (darkMode) Color(0xFF26324A) else Color(0xFFE2E8F0)
    val textPrimary = if (darkMode) Color(0xFFF8FAFC) else Color(0xFF0F172A)
    val textMuted = if (darkMode) Color(0xFF94A3B8) else Color(0xFF64748B)
    val accentIndigo = Color(0xFF6366F1)

    BackHandler(onBack = onBack)

    val filteredHistory = remember(history, selectedCategory) {
        when (selectedCategory) {
            "APK" -> history.filter { it.title.endsWith(".apk", true) || it.mime.contains("package-archive") }
            "PDF / Docs" -> history.filter { it.title.endsWith(".pdf", true) || it.mime.contains("pdf") || it.title.endsWith(".doc", true) || it.title.endsWith(".docx", true) }
            "Video" -> history.filter { it.mime.startsWith("video/") || isSupportedVideoFile(it.title) }
            "Audio" -> history.filter { it.mime.startsWith("audio/") || isSupportedAudioFile(it.title) }
            "Archives" -> history.filter { it.title.endsWith(".zip", true) || it.title.endsWith(".rar", true) || it.title.endsWith(".7z", true) }
            else -> history
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(primaryBg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back", color = accentIndigo, fontSize = 14.sp) }
            Spacer(Modifier.width(8.dp))
            Text("Download History", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = textPrimary)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onRefresh) {
                Text("🔄", fontSize = 16.sp)
            }
        }

        // Category Filter Chips
        LazyRow(
            modifier = Modifier.padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(listOf("All", "APK", "PDF / Docs", "Video", "Audio", "Archives")) { cat ->
                val active = selectedCategory == cat
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { selectedCategory = cat },
                    color = if (active) accentIndigo else (if (darkMode) Color(0xFF222B3F) else Color(0xFFE2E8F0)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        cat,
                        color = if (active) Color.White else textPrimary,
                        fontSize = 11.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        if (filteredHistory.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No downloaded files found in this category.", color = textMuted, fontSize = 13.sp)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(filteredHistory, key = { "${it.id}_${it.title}" }) { file ->
                    val isApk = file.title.endsWith(".apk", true) || file.mime.contains("package-archive")
                    val isPdf = file.title.endsWith(".pdf", true) || file.mime.contains("pdf")
                    val isAudio = file.mime.startsWith("audio/") || isSupportedAudioFile(file.title)
                    val isVideo = file.mime.startsWith("video/") || isSupportedVideoFile(file.title)

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = cardBg),
                        border = androidx.compose.foundation.BorderStroke(1.dp, cardBorder),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val icon = when {
                                    isApk -> "📦"
                                    isPdf -> "📄"
                                    isVideo -> "🎬"
                                    isAudio -> "🎵"
                                    else -> "📁"
                                }
                                Text(icon, fontSize = 20.sp)
                                Column(Modifier.weight(1f)) {
                                    Text(file.title, color = textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                                    Text("${formatBytes(file.size)}  ·  ${android.text.format.DateFormat.format("dd MMM yyyy, hh:mm a", file.date)}", color = textMuted, fontSize = 11.sp)
                                }
                            }

                            // Dynamic action button based on file type
                            when {
                                isApk -> {
                                    Button(
                                        onClick = { onOpenFile(file) },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                                    ) {
                                        Text("📦 Install APK", color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                }
                                isVideo -> {
                                    Button(
                                        onClick = { onPlayVideo(file) },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = accentIndigo)
                                    ) {
                                        Text("▶ Play Video", color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                }
                                isAudio -> {
                                    Button(
                                        onClick = { onPlayAudio(file) },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6))
                                    ) {
                                        Text("▶ Play Audio (Soul Player)", color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                }
                                else -> {
                                    Button(
                                        onClick = { onOpenFile(file) },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = if (darkMode) Color(0xFF2A364F) else Color(0xFFE2E8F0))
                                    ) {
                                        Text("📂 Open File", color = textPrimary, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------- IN-APP BROWSER ----------------
@Composable
private fun InAppBrowserScreen(
    darkMode: Boolean,
    initialTarget: String,
    onBack: () -> Unit,
    onDownload: (String) -> Unit
) {
    val accentIndigo = Color(0xFF6366F1)
    val primaryBg = if (darkMode) Color(0xFF0B0F19) else Color(0xFFF1F5F9)
    val textPrimary = if (darkMode) Color(0xFFF8FAFC) else Color(0xFF0F172A)
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
        Modifier.fillMaxSize().background(primaryBg)
            .statusBarsPadding().navigationBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { if (webView?.canGoBack() == true) webView?.goBack() else onBack() }) {
                Text("←", color = accentIndigo, style = MaterialTheme.typography.titleLarge)
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
            TextButton(onClick = { openAddress(address) }) { Text("Go", color = accentIndigo) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { webView?.goBack() }, enabled = webView?.canGoBack() == true) { Text("Back") }
            TextButton(onClick = { webView?.goForward() }, enabled = webView?.canGoForward() == true) { Text("Forward") }
            TextButton(onClick = { webView?.reload() }) { Text("Reload") }
            Spacer(Modifier.weight(1f))
            Text(pageTitle, maxLines = 1, style = MaterialTheme.typography.labelSmall, color = textPrimary)
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
                                val likelyFile = isSupportedVideoFile(target) ||
                                    Regex("""\.(pdf|zip|rar|7z|apk|exe|dmg|iso|mp3|m4a|wav|flac|jpg|jpeg|png|gif|docx?|xlsx?|pptx?|txt|csv)(?:$|/)""")
                                        .containsMatchIn(path)
                                if (likelyFile && request.isForMainFrame) {
                                    detectedUrl = target
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
                        setDownloadListener(DownloadListener { url, _, _, _, _ ->
                            detectedUrl = url
                        })
                        loadUrl(initialTarget)
                    }
                },
                update = { view -> webView = view }
            )
            if (detectedUrl.isNotBlank() && !showDownloadPrompt) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp),
                    shape = CircleShape,
                    color = accentIndigo,
                    shadowElevation = 10.dp
                ) {
                    TextButton(onClick = { showDownloadPrompt = true }) {
                        Text("⬇", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
    }

    if (showDownloadPrompt && detectedUrl.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { showDownloadPrompt = false; detectedUrl = "" },
            title = { Text("Download detected", color = textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(URLUtil.guessFileName(detectedUrl, null, null), color = textPrimary)
                    Text(detectedUrl, color = accentIndigo, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                    Text("Fast-IDM detected a downloadable file on this page.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = detectedUrl
                    showDownloadPrompt = false
                    detectedUrl = ""
                    onDownload(target)
                }) { Text("Download", color = accentIndigo) }
            },
            dismissButton = { TextButton(onClick = { showDownloadPrompt = false; detectedUrl = "" }) { Text("Not now") } }
        )
    }
}

// ---------------- BASIC SETTINGS SCREEN ----------------
@Composable
private fun BasicSettingsScreen(
    darkMode: Boolean,
    onNightModeChanged: (Boolean) -> Unit,
    onUpdateYtDlp: () -> Unit,
    onBack: () -> Unit
) {
    val bg = if (darkMode) Color(0xFF0B0F19) else Color(0xFFF1F5F9)
    val panel = if (darkMode) Color(0xFF161D2F) else Color.White
    val ink = if (darkMode) Color(0xFFF8FAFC) else Color(0xFF0F172A)
    val muted = if (darkMode) Color(0xFF94A3B8) else Color(0xFF64748B)
    val accent = Color(0xFF6366F1)
    BackHandler(onBack = onBack)
    Column(
        Modifier.fillMaxSize().background(bg).statusBarsPadding().navigationBarsPadding().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back", color = accent) }
            Text("Settings & Engine", color = ink, style = MaterialTheme.typography.titleLarge)
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = panel)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Dark Mode", color = ink, style = MaterialTheme.typography.titleMedium)
                    Text(if (darkMode) "Modern Dark appearance is active" else "Light appearance is active", color = muted, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = darkMode, onCheckedChange = onNightModeChanged)
            }
        }
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = panel)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("⚡ YouTube Instant Engine", color = ink, style = MaterialTheme.typography.titleMedium)
                Text("Preconfigured with mobile client stream extractors to bypass n-sig deciphering delays and start downloads instantaneously.", color = muted, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onUpdateYtDlp) { Text("Check yt-dlp NIGHTLY update", color = accent) }
            }
        }
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = panel)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("📁 Google Drive Virus-Scan Bypass", color = ink, style = MaterialTheme.typography.titleMedium)
                Text("Fast IDM automatically bypasses Google Drive's large file virus warning page for APKs, PDFs, videos, and zips.", color = muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.weight(1f))
        Text("Fast IDM Android · v0.4.0", color = muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}
