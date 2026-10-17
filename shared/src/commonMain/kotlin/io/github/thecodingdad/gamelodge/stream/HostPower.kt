package io.github.thecodingdad.gamelodge.stream

import io.github.thecodingdad.gamelodge.data.db.HostConfigDao
import io.github.thecodingdad.gamelodge.data.db.HostConfigEntity
import io.github.thecodingdad.gamelodge.data.db.WakeMethod
import io.github.thecodingdad.gamelodge.data.remote.HostActions
import io.github.thecodingdad.gamelodge.data.remote.HostRepository
import io.github.thecodingdad.gamelodge.ha.HaRepository

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

/** Puts a host to sleep some minutes after the last stream ended (survives the launcher being closed). */
interface AutoSleep {
    suspend fun scheduleAfterStream(hostUuid: String)

    fun cancel(hostUuid: String)
}
