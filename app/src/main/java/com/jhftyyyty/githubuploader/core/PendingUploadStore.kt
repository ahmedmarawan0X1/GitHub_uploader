package com.jhftyyyty.githubuploader.core

import android.content.Context
import java.io.File

internal object PendingUploadStore {
    private const val DIRECTORY = "pending_uploads"
    private const val MAX_AGE_MILLIS = 24L * 60L * 60L * 1000L

    fun cleanupStale(context: Context) {
        val directory = File(context.filesDir, DIRECTORY)
        val cutoff = System.currentTimeMillis() - MAX_AGE_MILLIS
        directory.listFiles()
            ?.filter { it.isFile && it.lastModified() < cutoff }
            ?.forEach { it.delete() }
    }

    fun create(context: Context): File {
        val directory = File(context.filesDir, DIRECTORY).apply { mkdirs() }
        return File(directory, "job_" + System.currentTimeMillis() + ".zip")
    }
}
