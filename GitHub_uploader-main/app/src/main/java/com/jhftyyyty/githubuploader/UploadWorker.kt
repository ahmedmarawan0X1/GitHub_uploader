package com.jhftyyyty.githubuploader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf

class UploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val filePath = inputData.getString(KEY_FILE_PATH) ?: return Result.failure(workDataOf(KEY_ERROR to "ZIP file is missing"))
        val token = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PREF_TOKEN, "")?.trim().orEmpty()
        if (token.isBlank()) return Result.failure(workDataOf(KEY_ERROR to "GitHub token is missing"))

        val mode = runCatching { UploadMode.valueOf(inputData.getString(KEY_MODE) ?: UploadMode.NEW.name) }
            .getOrDefault(UploadMode.NEW)
        val existing = if (mode == UploadMode.EXISTING) {
            RepoInfo(
                inputData.getString(KEY_OWNER).orEmpty(),
                inputData.getString(KEY_REPO).orEmpty(),
                inputData.getString(KEY_FULL_NAME).orEmpty(),
                inputData.getString(KEY_BRANCH).orEmpty().ifBlank { "main" },
                inputData.getBoolean(KEY_PRIVATE, false)
            )
        } else null

        return try {
            // Start as a foreground worker while the app may be backgrounded.
            // Keep this inside the try block so foreground-service restrictions are
            // reported as a normal WorkManager failure instead of crashing the app.
            setForeground(createForegroundInfo("Starting ZIP upload…", 0))

            val result = GitHubApi.uploadZipFile(
                applicationContext,
                filePath,
                token,
                mode,
                inputData.getString(KEY_NEW_REPO).orEmpty(),
                inputData.getString(KEY_DESCRIPTION).orEmpty(),
                inputData.getBoolean(KEY_PRIVATE, true),
                existing
            ) { done, total, text ->
                val percent = if (total <= 0) 0 else ((done.toDouble() / total) * 100).toInt().coerceIn(0, 100)
                setProgressAsync(workDataOf(KEY_DONE to done, KEY_TOTAL to total, KEY_TEXT to text))
                runCatching { setForegroundAsync(createForegroundInfo(text, percent)) }
            }
            Result.success(workDataOf(KEY_RESULT_URL to result, KEY_TEXT to "Upload completed"))
        } catch (e: Exception) {
            val message = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            Result.failure(workDataOf(KEY_ERROR to message))
        } finally {
            runCatching { java.io.File(filePath).delete() }
        }
    }

    private fun createForegroundInfo(text: String, progress: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "GitHub uploads", NotificationManager.IMPORTANCE_LOW)
            )
        }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("GitHub Uploader")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress.coerceIn(0, 100), progress <= 0)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_FILE_PATH = "file_path"
        const val KEY_MODE = "mode"
        const val KEY_NEW_REPO = "new_repo"
        const val KEY_DESCRIPTION = "description"
        const val KEY_PRIVATE = "private"
        const val KEY_OWNER = "owner"
        const val KEY_REPO = "repo"
        const val KEY_FULL_NAME = "full_name"
        const val KEY_BRANCH = "branch"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_TEXT = "text"
        const val KEY_ERROR = "error"
        const val KEY_RESULT_URL = "result_url"
        private const val CHANNEL_ID = "github_uploads"
        private const val NOTIFICATION_ID = 2401
    }
}
