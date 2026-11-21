package com.drsumon.fastidm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.util.Log
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDL.UpdateChannel
import java.io.File
import java.util.concurrent.Executors

class YtDlpDownloadService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
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
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_URL).orEmpty()
                val quality = intent.getStringExtra(EXTRA_QUALITY) ?: "720p"
                if (url.isBlank()) {
                    saveState("No URL provided", 0f)
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, buildNotification("Preparing video download", 0, true))
                saveState("Initializing yt-dlp and FFmpeg…", 0f)
                executor.execute { runDownload(url, quality) }
            }
        }
        return START_NOT_STICKY
    }

    private fun runDownload(url: String, quality: String) {
        val outputDir = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "FastIDM")
        if (!outputDir.exists()) outputDir.mkdirs()
        val before = outputDir.listFiles()?.associate { it.name to it.lastModified() } ?: emptyMap()
        val startedAt = System.currentTimeMillis()
        var updateAttemptedDuringRun = false
        try {
            YoutubeDL.getInstance().init(applicationContext)
            FFmpeg.getInstance().init(applicationContext)

            // Do not perform a network update before every first download of the week.
            // That update can add a long, unpredictable delay before extraction starts.
            // Use the bundled yt-dlp immediately; extractor updates can be handled separately.
            val cleanUrl = url.trim()
            val request = YoutubeDLRequest(cleanUrl)
                .addOption("--no-mtime")
                .addOption("--no-playlist")
                .addOption("--newline")
                .addOption("--concurrent-fragments", "4")
                .addOption("--retries", "3")
                .addOption("--fragment-retries", "3")
                .addOption("--socket-timeout", "15")
                .addOption("-o", File(outputDir, "%(title).150B [%(id)s].%(ext)s").absolutePath)
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
            fun executeDownload() = YtDlpOperationLock.withLock {
                YoutubeDL.getInstance().execute(request, PROCESS_ID) { progress, eta, line ->
                    val pct = progress.coerceIn(0f, 100f)
                    saveState(if (line.isBlank()) "Downloading · ETA ${eta}s" else line.take(180), pct)
                    notificationManager.notify(NOTIFICATION_ID, buildNotification("Downloading · ${pct.toInt()}%", pct.toInt(), true))
                }
            }
            val response = try {
                executeDownload()
            } catch (firstError: Exception) {
                val details = (firstError.message ?: firstError.toString()).lowercase()
                if (!isOutdatedExtractorError(details)) throw firstError
                saveState("Updating outdated YouTube support…", 0f)
                updateAttemptedDuringRun = true
                try {
                    val updateStatus = YtDlpOperationLock.withLock {
                        YoutubeDL.getInstance().updateYoutubeDL(applicationContext, UpdateChannel._NIGHTLY)
                    }
                    if (updateStatus != YoutubeDL.UpdateStatus.DONE &&
                        updateStatus != YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE
                    ) {
                        throw IllegalStateException("yt-dlp update returned $updateStatus")
                    }
                    getSharedPreferences(UPDATE_PREFS, MODE_PRIVATE).edit()
                        .putLong(LAST_SUCCESSFUL_UPDATE, System.currentTimeMillis()).apply()
                } catch (updateError: Exception) {
                    throw IllegalStateException(
                        "yt-dlp পুরোনো এবং update ব্যর্থ হয়েছে: ${updateError.message ?: "network/update error"}",
                        firstError
                    )
                }
                saveState("Update complete · retrying video download…", 0f)
                executeDownload()
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
            // Check after this download so the current download never waits for a network update.
            if (!updateAttemptedDuringRun) maybeUpdateYtDlpIfDue()
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    private fun maybeUpdateYtDlpIfDue(force: Boolean = false) {
        val prefs = getSharedPreferences(UPDATE_PREFS, MODE_PRIVATE)
        val lastSuccess = prefs.getLong(LAST_SUCCESSFUL_UPDATE, 0L)
        val now = System.currentTimeMillis()
        if (!force && now - lastSuccess < UPDATE_INTERVAL_MS) return
        try {
            YoutubeDL.getInstance().init(applicationContext)
            val status = YtDlpOperationLock.withLock {
                YoutubeDL.getInstance().updateYoutubeDL(applicationContext, UpdateChannel._NIGHTLY)
            }
            when (status) {
                YoutubeDL.UpdateStatus.DONE,
                YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> {
                    prefs.edit()
                        .putLong(LAST_SUCCESSFUL_UPDATE, now)
                        .putString("last_update_status", status.name)
                        .apply()
                }
                else -> Log.w("FastIDM-YTDLP", "Update not successful: $status")
            }
        } catch (e: Exception) {
            // A background update must never turn a completed download into a failure.
            Log.w("FastIDM-YTDLP", "Background yt-dlp update failed; keep current version", e)
        }
    }

    private fun isOutdatedExtractorError(message: String): Boolean {
        return message.contains("older than 90 days") ||
            message.contains("yt-dlp is too old") ||
            message.contains("please update yt-dlp") ||
            message.contains("update yt-dlp")
    }

    private fun publishToDownloads(source: File): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, source.name)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType(source.extension))
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FastIDM")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
                try {
                    contentResolver.openOutputStream(uri)?.use { output -> source.inputStream().use { it.copyTo(output) } }
                        ?: throw IllegalStateException("Could not open output file")
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                    true
                } catch (e: Exception) {
                    contentResolver.delete(uri, null, null)
                    false
                }
            } else {
                val targetDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "FastIDM")
                if (!targetDir.exists()) targetDir.mkdirs()
                source.copyTo(File(targetDir, source.name), overwrite = true)
                true
            }
        } catch (_: Exception) { false }
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
        const val EXTRA_URL = "extra_url"
        const val EXTRA_QUALITY = "extra_quality"
        const val PREFS = "fastidm_ytdlp_state"
        const val UPDATE_PREFS = "fastidm_ytdlp_updates"
        const val LAST_SUCCESSFUL_UPDATE = "last_ytdlp_update"
        private const val CHANNEL_ID = "fastidm_video_downloads"
        private const val NOTIFICATION_ID = 4201
        private const val PROCESS_ID = "fastidm-video-process"
        private const val UPDATE_INTERVAL_MS = 3L * 24L * 60L * 60L * 1000L
    }
}