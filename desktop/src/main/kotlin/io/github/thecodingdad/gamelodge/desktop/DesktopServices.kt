package io.github.thecodingdad.gamelodge.desktop

import io.github.thecodingdad.gamelodge.data.emulation.EmulatorOption
import io.github.thecodingdad.gamelodge.data.emulation.Emulators
import io.github.thecodingdad.gamelodge.stream.AutoSleep

/** The Android emulator presets: the PC sets up its emulators separately. */
object NoEmulators : Emulators {
    override fun choices(platformId: String) = emptyList<EmulatorOption>()
    override fun resolve(platformId: String, gameKey: String?): EmulatorOption? = null
    override fun platformName(platformId: String): String? = null
}

/** Host auto sleep after a stream is Android only. */
object NoAutoSleep : AutoSleep {
    override suspend fun scheduleAfterStream(hostUuid: String) = Unit
    override fun cancel(hostUuid: String) = Unit
}
