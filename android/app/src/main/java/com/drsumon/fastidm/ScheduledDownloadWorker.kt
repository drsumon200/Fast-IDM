package com.drsumon.fastidm

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.URLUtil
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters

class ScheduledDownloadWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    override fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val quality = inputData.getString(KEY_QUALITY) ?: "Best"
        val isVideo = inputData.getBoolean(KEY_IS_VIDEO, false)
        val speedLimit = inputData.getString(KEY_SPEED_LIMIT) ?: "Unlimited"

        if (isVideo) {
            val service = Intent(context, YtDlpDownloadService::class.java).apply {
                action = YtDlpDownloadService.ACTION_START
                putExtra(YtDlpDownloadService.EXTRA_URL, url)
                putExtra(YtDlpDownloadService.EXTRA_QUALITY, quality)
                putExtra(YtDlpDownloadService.EXTRA_SPEED_LIMIT, speedLimit)
            }
            ContextCompat.startForegroundService(context, service)
        } else {
            if (GoogleDriveResolver.isGoogleDrive(url)) {
                try {
                    val resolved = GoogleDriveResolver.resolve(url)
                    enqueueDirectDownload(
                        context,
                        resolved.url,
                        resolved.cookieHeader,
                        resolved.contentDisposition,
                        resolved.contentType
                    )
                } catch (_: Exception) {
                    enqueueDirectDownload(context, url, null, null, null)
                }
            } else {
                enqueueDirectDownload(context, url, null, null, null)
            }
        }

        return Result.success()
    }

    private fun enqueueDirectDownload(
        ctx: Context,
        downloadUrl: String,
        cookieHeader: String?,
        contentDisposition: String?,
        contentType: String?
    ) {
        val uri = try { Uri.parse(downloadUrl) } catch (_: Exception) { return }
        val manager = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val parsedName = GoogleDriveResolver.extractFilenameFromDisposition(contentDisposition)
        val filename = (parsedName ?: URLUtil.guessFileName(downloadUrl, contentDisposition, contentType))
            .replace(Regex("""[\\/:*?"<>|]"""), "_").take(150).ifBlank { "download.bin" }

        val request = DownloadManager.Request(uri)
            .setTitle(filename)
            .setDescription("Fast IDM · Scheduled download")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "FastIDM/$filename")
            .addRequestHeader("User-Agent", GoogleDriveResolver.USER_AGENT)

        if (!cookieHeader.isNullOrBlank()) {
            request.addRequestHeader("Cookie", cookieHeader)
        }
        manager.enqueue(request)
    }

    companion object {
        const val KEY_URL = "scheduled_url"
        const val KEY_QUALITY = "scheduled_quality"
        const val KEY_IS_VIDEO = "scheduled_is_video"
        const val KEY_SPEED_LIMIT = "scheduled_speed_limit"
    }
}
