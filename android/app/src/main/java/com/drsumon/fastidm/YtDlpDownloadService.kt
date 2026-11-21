package com.drsumon.fastidm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.net.Uri
import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal object YtDlpOperationLock {
    val lock = ReentrantLock()
}

class YtDlpDownloadService : Service() {
    private val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue())
    private val notificationManager by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                try { YoutubeDL.getInstance().destroyProcessById(PROCESS_ID) } catch (_: Exception) { }
                saveState("Canceled", 0f)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_UPDATE -> {
                executor.execute { runYtDlpUpdate() }
            }
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_URL).orEmpty()
                val quality = intent.getStringExtra(EXTRA_QUALITY) ?: "720p"
                val speedLimit = intent.getStringExtra(EXTRA_SPEED_LIMIT) ?: "Unlimited"
                if (url.isBlank()) {
                    saveState("No URL provided", 0f)
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, buildNotification("Preparing instant video download…", 0, true))
                saveState("Starting instant download…", 0f)
                executor.execute { runDownload(url, quality, speedLimit) }
            }
        }
        return START_NOT_STICKY
    }

    private fun runDownload(url: String, quality: String, speedLimit: String = "Unlimited") {
        val outputDir = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "FastIDM")
        if (!outputDir.exists()) outputDir.mkdirs()
        val before = outputDir.listFiles()?.associate { it.name to it.lastModified() } ?: emptyMap()
        val startedAt = System.currentTimeMillis()
        try {
            YoutubeDL.getInstance().init(applicationContext)
            FFmpeg.getInstance().init(applicationContext)

            val cleanUrl = url.trim()
            val request = YoutubeDLRequest(cleanUrl)
                .addOption("--no-mtime")
                .addOption("--no-playlist")
                .addOption("--newline")
                .addOption("--concurrent-fragments", "4")
                .addOption("--retries", "3")
                .addOption("--fragment-retries", "3")
                .addOption("--socket-timeout", "10")
                .addOption("--no-check-certificates")
                .addOption("--http-chunk-size", "10485760")
                .addOption("-o", File(outputDir, "%(title).150B [%(id)s].%(ext)s").absolutePath)
                // Instant YouTube Extraction: Use mobile clients to bypass heavy player JS evaluation
                .addOption("--extractor-args", "youtube:player_client=android,ios,web;player_skip=configs,webpage")

            if (speedLimit != "Unlimited" && speedLimit.isNotBlank()) {
                val limitArg = when (speedLimit) {
                    "500 KB/s" -> "500K"
                    "1 MB/s" -> "1M"
                    "2 MB/s" -> "2M"
                    "5 MB/s" -> "5M"
                    "10 MB/s" -> "10M"
                    else -> speedLimit.replace("/s", "").replace(" ", "").trim()
                }
                request.addOption("--limit-rate", limitArg)
            }
            when (quality) {
                "Audio MP3" -> {
                    request.addOption("-x")
                    request.addOption("--audio-format", "mp3")
                    request.addOption("--audio-quality", "0")
                }
                else -> {
                    val height = when (quality) {
                        "1080p" -> 1080
                        "720p" -> 720
                        "480p" -> 480
                        "360p" -> 360
                        else -> 4320
                    }
                    // Prefer a ready-to-play combined stream first to avoid a mandatory
                    // separate audio/video merge. Fall back to separate streams when needed.
                    val format = if (quality == "Best") {
                        "best/bestvideo+bestaudio"
                    } else {
                        "best[height<=${height}]/bestvideo[height<=${height}]+bestaudio/best[height<=${height}]"
                    }
                    request.addOption("-f", format)
                    request.addOption("--merge-output-format", "mkv")
                }
            }
            val response = YtDlpOperationLock.lock.withLock {
                YoutubeDL.getInstance().execute(request, PROCESS_ID) { progress, eta, line ->
                    val pct = progress.coerceIn(0f, 100f)
                    saveState(if (line.isBlank()) "Downloading · ETA ${eta}s" else line.take(180), pct)
                    notificationManager.notify(NOTIFICATION_ID, buildNotification("Downloading · ${pct.toInt()}%", pct.toInt(), true))
                }
            }
            if (response.out.isNotBlank()) saveState("Finishing video file…", 99f)
            val files = outputDir.listFiles()?.filter { file ->
                file.isFile && !file.name.endsWith(".part") &&
                    (file.lastModified() >= startedAt || before[file.name] != file.lastModified())
            }.orEmpty()
            var copied = 0
            files.forEach { if (publishToDownloads(it)) copied++ }
            val finalMessage = if (copied > 0) "Completed · saved to Downloads/FastIDM"
                else "Completed, but could not publish the output file. Check app storage."
            saveState(finalMessage, 100f)
            notificationManager.notify(NOTIFICATION_ID, buildNotification(finalMessage, 100, false))
        } catch (e: Exception) {
            val message = (e.message ?: "Unknown yt-dlp error").take(220)
            saveState("Failed: $message", 0f)
            notificationManager.notify(NOTIFICATION_ID, buildNotification("Video download failed", 0, false))
        } finally {
            // Keep the service alive if a Settings update was queued behind this download.
            if (executor.queue.isEmpty()) {
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
    }

    private fun runYtDlpUpdate() {
        startForeground(NOTIFICATION_ID, buildNotification("Updating yt-dlp NIGHTLY…", 0, true))
        try {
            YoutubeDL.getInstance().init(applicationContext)
            val status = YtDlpOperationLock.lock.withLock {
                YoutubeDL.getInstance().updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel._NIGHTLY)
            }
            when (status) {
                YoutubeDL.UpdateStatus.DONE,
                YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply()
                    val version = YoutubeDL.getInstance().versionName(applicationContext).orEmpty()
                    val message = if (status == YoutubeDL.UpdateStatus.DONE) "yt-dlp NIGHTLY updated · $version"
                        else "yt-dlp NIGHTLY is up to date · $version"
                    saveState(message, 100f)
                    notificationManager.notify(NOTIFICATION_ID, buildNotification(message, 100, false))
                }
                null -> {
                    val message = "yt-dlp update returned no status; continuing with installed runtime"
                    saveState(message, 0f)
                    notificationManager.notify(NOTIFICATION_ID, buildNotification(message, 0, false))
                }
            }
        } catch (e: Exception) {
            val message = "yt-dlp update failed; continuing with installed/bundled yt-dlp: ${(e.message ?: "network or server error").take(140)}"
            saveState(message, 0f)
            notificationManager.notify(NOTIFICATION_ID, buildNotification("yt-dlp update failed · using installed version", 0, false))
            android.util.Log.w(TAG, message, e)
        } finally {
            if (executor.queue.isEmpty()) {
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
    }

    private fun publishToDownloads(source: File): Boolean {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, source.name)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType(source.extension))
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FastIDM")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    try {
                        contentResolver.openOutputStream(uri)?.use { output -> source.inputStream().use { it.copyTo(output) } }
                            ?: throw IllegalStateException("Could not open output file")
                        values.clear()
                        values.put(MediaStore.Downloads.IS_PENDING, 0)
                        contentResolver.update(uri, values, null, null)
                        recordDownloadHistory(source.name, mimeType(source.extension), source.length(), uri.toString())
                        return true
                    } catch (e: Exception) {
                        try { contentResolver.delete(uri, null, null) } catch (_: Exception) {}
                    }
                }
            }
            val targetDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "FastIDM")
            if (!targetDir.exists()) targetDir.mkdirs()
            val targetFile = File(targetDir, source.name)
            val finalFile = try {
                source.copyTo(targetFile, overwrite = true)
                targetFile
            } catch (_: Exception) {
                source
            }
            recordDownloadHistory(finalFile.name, mimeType(finalFile.extension), finalFile.length(), Uri.fromFile(finalFile).toString())
            return true
        } catch (_: Exception) {
            recordDownloadHistory(source.name, mimeType(source.extension), source.length(), Uri.fromFile(source).toString())
            return false
        }
    }

    private fun recordDownloadHistory(title: String, mime: String, size: Long, uriString: String) {
        try {
            val prefs = getSharedPreferences(PREFS_HISTORY, MODE_PRIVATE)
            val existing = prefs.getString("items", "[]") ?: "[]"
            val array = org.json.JSONArray(existing)
            val item = org.json.JSONObject().apply {
                put("id", System.currentTimeMillis())
                put("title", title)
                put("mime", mime)
                put("size", size)
                put("date", System.currentTimeMillis())
                put("uri", uriString)
            }
            array.put(item)
            prefs.edit().putString("items", array.toString()).apply()
        } catch (_: Exception) {}
    }

    private fun mimeType(ext: String): String = when (ext.lowercase()) {
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "opus" -> "audio/ogg"
        else -> "application/octet-stream"
    }

    private fun saveState(status: String, progress: Float) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("status", status).putFloat("progress", progress).apply()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Video downloads", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(text: String, progress: Int, ongoing: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Fast IDM · Video")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
        if (ongoing) {
            builder.setProgress(100, progress.coerceIn(0, 100), progress == 0)
            .setSubText(if (progress > 0) "$progress% complete" else "Preparing")
            val stopIntent = PendingIntent.getService(
                this, 2, Intent(this, YtDlpDownloadService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            val historyIntent = PendingIntent.getActivity(
                this, 3,
                Intent(this, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_OPEN_HISTORY)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_menu_recent_history, "History", historyIntent)
        } else builder.setProgress(0, 0, false)
        return builder.build()
    }

    override fun onBind(intent: Intent?) = null

    companion object {
        const val ACTION_START = "com.drsumon.fastidm.action.START_YTDLP"
        const val ACTION_STOP = "com.drsumon.fastidm.action.STOP_YTDLP"
        const val ACTION_UPDATE = "com.drsumon.fastidm.action.UPDATE_YTDLP"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_QUALITY = "extra_quality"
        const val EXTRA_SPEED_LIMIT = "extra_speed_limit"
        const val PREFS = "fastidm_ytdlp_state"
        const val PREFS_HISTORY = "fastidm_ytdlp_history"
        const val KEY_LAST_UPDATE = "yt_dlp_last_successful_update"
        private const val TAG = "FastIDM-yt-dlp"
        private const val CHANNEL_ID = "fastidm_video_downloads"
        private const val NOTIFICATION_ID = 4201
        private const val PROCESS_ID = "fastidm-video-process"
    }
}