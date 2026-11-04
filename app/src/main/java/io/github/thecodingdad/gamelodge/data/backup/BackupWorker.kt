package io.github.thecodingdad.gamelodge.data.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.thecodingdad.gamelodge.container
import io.github.thecodingdad.gamelodge.util.Log
import java.util.concurrent.TimeUnit

/** Daily automatic backup into the folder chosen in settings (without credentials). */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val s = c.settings.current()
        if (!s.autoBackup || s.backupFolderUri.isEmpty()) return Result.success()
        return runCatching { c.backupStorage.writeToFolder(s.backupFolderUri, s.backupKeepCount) { c.backup.export(null, it) } }
            .onFailure { Log.w("Auto backup failed", it) }
            .fold({ Result.success() }, { Result.retry() })
    }

    companion object {
        private const val NAME = "auto_backup"

        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(NAME)
                return
            }
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS).build())
        }
    }
}
