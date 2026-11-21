package com.drsumon.fastidm

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.concurrent.withLock

class YtDlpUpdateWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            YoutubeDL.getInstance().init(applicationContext)
            val status = YtDlpOperationLock.lock.withLock {
                YoutubeDL.getInstance().updateYoutubeDL(
                    applicationContext,
                    YoutubeDL.UpdateChannel._NIGHTLY
                )
            }
            when (status) {
                YoutubeDL.UpdateStatus.DONE,
                YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> {
                    val version = YoutubeDL.getInstance().versionName(applicationContext).orEmpty()
                    applicationContext.getSharedPreferences(
                        YtDlpDownloadService.PREFS,
                        Context.MODE_PRIVATE
                    ).edit()
                        .putLong(YtDlpDownloadService.KEY_LAST_UPDATE, System.currentTimeMillis())
                        .putString("status", "yt-dlp NIGHTLY checked · $version")
                        .putFloat("progress", 100f)
                        .apply()
                    Result.success()
                }
                null -> Result.retry()
            }
        } catch (e: Exception) {
            android.util.Log.w("FastIDM-yt-dlp", "Periodic NIGHTLY update failed; current version retained", e)
            Result.retry()
        }
    }
}
