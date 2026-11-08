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
    /** Xbox consoles (`xbox:<id>` hosts) are woken and turned off through Xbox Live. */
    private val xbox: () -> io.github.thecodingdad.gamelodge.xbox.XboxRepository? = { null },
) {
    suspend fun config(hostUuid: String): HostConfigEntity = configs.get(hostUuid) ?: HostConfigEntity(hostUuid)

    suspend fun save(config: HostConfigEntity) = configs.upsert(config)

    /** Sends every configured wake signal; true if at least one was sent. */
    suspend fun wake(hostUuid: String): Boolean {
        val config = config(hostUuid)
        var sent = false
        val console = io.github.thecodingdad.gamelodge.xbox.XboxRepository.consoleId(hostUuid)
        // A console in standby wakes through Xbox Live (in energy saving mode only Home Assistant can, e.g. by HDMI-CEC).
        if (console != null) sent = xbox()?.wakeUp(console) == true
        if (console == null && (config.wakeMethod == WakeMethod.WOL || config.wakeMethod == WakeMethod.BOTH)) {
            hosts.hosts.value[hostUuid]?.let { sent = actions.wakeOnLan(it) || sent }
        }
        if (console != null || config.wakeMethod == WakeMethod.HOME_ASSISTANT || config.wakeMethod == WakeMethod.BOTH) {
            config.haWakeEntity?.let { action ->
                // Once more on a fresh connection: the one at hand may be from another network (VPN just up).
                val done = ha.runAction(action) || run {
                    ha.client.restart()
                    ha.runAction(action)
                }
                sent = done || sent
            }
        }
        // The state shows within seconds instead of at the next 30 s poll.
        if (console != null && sent) xbox()?.watchPower(console, on = true)
        return sent
    }

    fun haActionFor(config: HostConfigEntity, action: PowerAction): String? = when (action) {
        PowerAction.SHUTDOWN -> config.haShutdownEntity
        PowerAction.RESTART -> config.haRestartEntity
        PowerAction.SLEEP -> config.haSleepEntity
    }

    suspend fun power(hostUuid: String, action: PowerAction): Boolean {
        val entity = haActionFor(config(hostUuid), action)
        val console = io.github.thecodingdad.gamelodge.xbox.XboxRepository.consoleId(hostUuid)
        // Consoles turn off to standby through Xbox Live when no Home Assistant action is set.
        if (entity == null && console != null && action != PowerAction.RESTART) {
            return (xbox()?.turnOff(console) == true).also { if (it) xbox()?.watchPower(console, on = false) }
        }
        return (entity != null && ha.runAction(entity)).also { done ->
            if (done && console != null) xbox()?.watchPower(console, on = action == PowerAction.RESTART)
        }
    }

    /** The host can be put to sleep after a stream (a Home Assistant action, or a console). */
    fun canSleep(config: HostConfigEntity): Boolean =
        config.haSleepEntity != null || config.haShutdownEntity != null ||
            io.github.thecodingdad.gamelodge.xbox.XboxRepository.consoleId(config.hostUuid) != null
}

/** Puts a host to sleep some minutes after the last stream ended (survives the launcher being closed). */
interface AutoSleep {
    suspend fun scheduleAfterStream(hostUuid: String)

    fun cancel(hostUuid: String)
}
