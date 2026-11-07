package io.github.thecodingdad.gamelodge.desktop

import io.github.thecodingdad.gamelodge.data.emulation.EmulatorOption
import io.github.thecodingdad.gamelodge.data.emulation.Emulators
import io.github.thecodingdad.gamelodge.stream.AutoSleep
import kotlinx.coroutines.launch

/** The Android emulator presets: the PC sets up its emulators separately. */
object NoEmulators : Emulators {
    override fun choices(platformId: String) = emptyList<EmulatorOption>()
    override fun resolve(platformId: String, gameKey: String?): EmulatorOption? = null
    override fun platformName(platformId: String): String? = null
}

/**
 * Puts an Xbox console to sleep some minutes after its last stream (while GameLodge runs); PC hosts'
 * auto sleep is Android only.
 */
class XboxAutoSleep(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val power: () -> io.github.thecodingdad.gamelodge.stream.HostPower,
) : AutoSleep {
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    override suspend fun scheduleAfterStream(hostUuid: String) {
        if (io.github.thecodingdad.gamelodge.xbox.XboxRepository.consoleId(hostUuid) == null) return
        val minutes = power().config(hostUuid).autoSleepMinutes ?: return
        jobs.remove(hostUuid)?.cancel()
        jobs[hostUuid] = scope.launch {
            kotlinx.coroutines.delay(minutes * 60_000L)
            if (io.github.thecodingdad.gamelodge.xbox.XboxStreams.current == null) {
                val config = power().config(hostUuid)
                val action = if (config.haSleepEntity != null) io.github.thecodingdad.gamelodge.stream.PowerAction.SLEEP else io.github.thecodingdad.gamelodge.stream.PowerAction.SHUTDOWN
                power().power(hostUuid, action)
            }
            jobs.remove(hostUuid)
        }
    }

    override fun cancel(hostUuid: String) {
        jobs.remove(hostUuid)?.cancel()
    }
}

/** Host auto sleep after a stream is Android only. */
object NoAutoSleep : AutoSleep {
    override suspend fun scheduleAfterStream(hostUuid: String) = Unit
    override fun cancel(hostUuid: String) = Unit
}
