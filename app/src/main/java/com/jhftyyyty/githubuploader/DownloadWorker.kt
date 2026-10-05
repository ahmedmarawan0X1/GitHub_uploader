package com.jhftyyyty.githubuploader

import com.jhftyyyty.githubuploader.core.*

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File

class DownloadWorker(app: Context, params: WorkerParameters) : CoroutineWorker(app, params) {
    override suspend fun doWork(): Result {
        val token = TokenStore(applicationContext).get()
        if (token.isBlank()) return Result.failure(workDataOf(KEY_ERROR to "GitHub token is missing"))

        val repo = RepoInfo(
            inputData.getString(KEY_OWNER).orEmpty(),
            inputData.getString(KEY_REPO).orEmpty(),
            inputData.getString(KEY_FULL_NAME).orEmpty(),
            inputData.getString(KEY_BRANCH).orEmpty().ifBlank { "main" },
            false
        )

        return try {
            setForeground(foreground(0, 0))
            val temp = File(applicationContext.cacheDir, "download_" + System.currentTimeMillis() + ".zip")

            GitHubApi.downloadRepository(token, repo, temp) { d, t ->
                setProgress(workDataOf(KEY_DONE to d, KEY_TOTAL to t))
                notifyProgress(d, t)
            }

            val uri = if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, repo.name + "-" + System.currentTimeMillis() + ".zip")
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/GitHubUploader")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val u = applicationContext.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("Could not create download")
                applicationContext.contentResolver.openOutputStream(u)?.use { out ->
                    temp.inputStream().use { it.copyTo(out, 64 * 1024) }
                } ?: error("Could not write download")
                applicationContext.contentResolver.update(
                    u,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                    null,
                    null
                )
                temp.delete()
                u.toString()
            } else {
                val dir = applicationContext.getExternalFilesDir(null)!!.resolve("downloads").apply { mkdirs() }
                val out = dir.resolve(repo.name + "-" + System.currentTimeMillis() + ".zip")
                temp.copyTo(out, true)
                temp.delete()
                out.absolutePath
            }

            notifyFinished(false)
            Result.success(workDataOf(KEY_RESULT_PATH to uri))
        } catch (e: Exception) {
            notifyFinished(true)
            Result.failure(workDataOf(KEY_ERROR to GitHubApi.friendlyError(e.message ?: e.javaClass.simpleName)))
        }
    }

    private fun foreground(d: Long, t: Long): ForegroundInfo {
        ensureChannel()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION_ID, notification(d, t), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification(d, t))
        }
    }

    private fun notifyProgress(d: Long, t: Long) {
        ensureChannel()
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification(d, t))
    }

    private fun notifyFinished(error: Boolean) {
        ensureChannel()
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(applicationContext, CHANNEL)
                .setSmallIcon(if (error) android.R.drawable.stat_notify_error else android.R.drawable.stat_sys_download_done)
                .setContentTitle(if (error) "GitHub uploader" else "تم تنزيل المشروع")
                .setContentText(if (error) "تعذر إكمال التنزيل" else "تم حفظ الملف في Download/GitHubUploader")
                .setAutoCancel(true)
                .setOngoing(false)
                .setOnlyAlertOnce(true)
                .build()
        )
    }

    private fun notification(d: Long, t: Long) =
        NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (t > 0) "$d / $t" else "Downloading")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(
                if (t > 0) t.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0,
                if (t > 0) d.coerceIn(0, t).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0,
                t <= 0
            )
            .build()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(NotificationChannel(CHANNEL, "GitHub downloads", NotificationManager.IMPORTANCE_LOW))
        }
    }

    companion object {
        const val WORK_NAME = "github_download_job"
        const val KEY_OWNER = "owner"
        const val KEY_REPO = "repo"
        const val KEY_FULL_NAME = "full_name"
        const val KEY_BRANCH = "branch"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_RESULT_PATH = "result_path"
        const val KEY_ERROR = "error"
        const val CHANNEL = "github_downloads"
        const val NOTIFICATION_ID = 2402
    }
}
