package io.github.thecodingdad.gamelodge.stream

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.thecodingdad.gamelodge.container
import io.github.thecodingdad.gamelodge.data.db.HostConfigDao
import java.util.concurrent.TimeUnit

/** Puts a host to sleep some minutes after the last stream ended (survives the app being closed). */
class WorkManagerAutoSleep(private val context: Context, private val configs: HostConfigDao) : AutoSleep {
    override suspend fun scheduleAfterStream(hostUuid: String) {
        val config = configs.get(hostUuid) ?: return
        val minutes = config.autoSleepMinutes ?: return
        if (config.haSleepEntity == null && config.haShutdownEntity == null) return
        val request = OneTimeWorkRequestBuilder<AutoSleepWorker>()
            .setInitialDelay(minutes.toLong(), TimeUnit.MINUTES)
            .setInputData(workDataOf(KEY_HOST to hostUuid))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(name(hostUuid), ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancel(hostUuid: String) {
        WorkManager.getInstance(context).cancelUniqueWork(name(hostUuid))
    }

    private fun name(hostUuid: String) = "auto_sleep_$hostUuid"

    companion object {
        const val KEY_HOST = "host"
    }
}

class AutoSleepWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val hostUuid = inputData.getString(WorkManagerAutoSleep.KEY_HOST) ?: return Result.failure()
        val c = applicationContext.container
        // Someone started streaming again in the meantime: leave the host alone.
        if (c.hosts.hosts.value[hostUuid]?.isStreaming == true) return Result.success()
        val config = c.hostPower.config(hostUuid)
        val action = if (config.haSleepEntity != null) PowerAction.SLEEP else PowerAction.SHUTDOWN
        return if (c.hostPower.power(hostUuid, action)) Result.success() else Result.retry()
    }
}
