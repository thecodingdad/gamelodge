package io.github.thecodingdad.moonlightlauncher.stream

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.thecodingdad.moonlightlauncher.container
import io.github.thecodingdad.moonlightlauncher.data.db.HostConfigDao
import io.github.thecodingdad.moonlightlauncher.data.db.HostConfigEntity
import io.github.thecodingdad.moonlightlauncher.data.db.WakeMethod
import io.github.thecodingdad.moonlightlauncher.data.remote.HostActions
import io.github.thecodingdad.moonlightlauncher.data.remote.HostRepository
import io.github.thecodingdad.moonlightlauncher.ha.HaRepository
import java.util.concurrent.TimeUnit

enum class PowerAction { SHUTDOWN, RESTART, SLEEP }

/** Wake and power control of streaming hosts via Wake-on-LAN and Home Assistant. */
class HostPower(
    private val configs: HostConfigDao,
    private val hosts: HostRepository,
    private val actions: HostActions,
    private val ha: HaRepository,
) {
    suspend fun config(hostUuid: String): HostConfigEntity = configs.get(hostUuid) ?: HostConfigEntity(hostUuid)

    suspend fun save(config: HostConfigEntity) = configs.upsert(config)

    /** Sends every configured wake signal; true if at least one was sent. */
    suspend fun wake(hostUuid: String): Boolean {
        val config = config(hostUuid)
        var sent = false
        if (config.wakeMethod == WakeMethod.WOL || config.wakeMethod == WakeMethod.BOTH) {
            hosts.hosts.value[hostUuid]?.let { sent = actions.wakeOnLan(it) || sent }
        }
        if (config.wakeMethod == WakeMethod.HOME_ASSISTANT || config.wakeMethod == WakeMethod.BOTH) {
            config.haWakeEntity?.let { sent = ha.runAction(it) || sent }
        }
        return sent
    }

    fun haActionFor(config: HostConfigEntity, action: PowerAction): String? = when (action) {
        PowerAction.SHUTDOWN -> config.haShutdownEntity
        PowerAction.RESTART -> config.haRestartEntity
        PowerAction.SLEEP -> config.haSleepEntity
    }

    suspend fun power(hostUuid: String, action: PowerAction): Boolean {
        val entity = haActionFor(config(hostUuid), action) ?: return false
        return ha.runAction(entity)
    }
}

/** Puts a host to sleep some minutes after the last stream ended (survives the app being closed). */
class AutoSleep(private val context: Context, private val configs: HostConfigDao) {
    suspend fun scheduleAfterStream(hostUuid: String) {
        val config = configs.get(hostUuid) ?: return
        val minutes = config.autoSleepMinutes ?: return
        if (config.haSleepEntity == null && config.haShutdownEntity == null) return
        val request = OneTimeWorkRequestBuilder<AutoSleepWorker>()
            .setInitialDelay(minutes.toLong(), TimeUnit.MINUTES)
            .setInputData(workDataOf(KEY_HOST to hostUuid))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(name(hostUuid), ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(hostUuid: String) {
        WorkManager.getInstance(context).cancelUniqueWork(name(hostUuid))
    }

    private fun name(hostUuid: String) = "auto_sleep_$hostUuid"

    companion object {
        const val KEY_HOST = "host"
    }
}

class AutoSleepWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val hostUuid = inputData.getString(AutoSleep.KEY_HOST) ?: return Result.failure()
        val c = applicationContext.container
        // Someone started streaming again in the meantime: leave the host alone.
        if (c.hosts.hosts.value[hostUuid]?.isStreaming == true) return Result.success()
        val config = c.hostPower.config(hostUuid)
        val action = if (config.haSleepEntity != null) PowerAction.SLEEP else PowerAction.SHUTDOWN
        return if (c.hostPower.power(hostUuid, action)) Result.success() else Result.retry()
    }
}
