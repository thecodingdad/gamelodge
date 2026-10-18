package io.github.thecodingdad.gamelodge.desktop

import io.github.thecodingdad.gamelodge.data.emulation.EmulatorOption
import io.github.thecodingdad.gamelodge.data.emulation.Emulators
import io.github.thecodingdad.gamelodge.data.remote.AddHostResult
import io.github.thecodingdad.gamelodge.data.remote.HostActions
import io.github.thecodingdad.gamelodge.data.remote.HostApp
import io.github.thecodingdad.gamelodge.data.remote.HostRepository
import io.github.thecodingdad.gamelodge.data.remote.HostState
import io.github.thecodingdad.gamelodge.data.remote.PairResult
import io.github.thecodingdad.gamelodge.data.remote.RemoteApp
import io.github.thecodingdad.gamelodge.stream.AutoSleep
import io.github.thecodingdad.gamelodge.stream.StreamProfile
import io.github.thecodingdad.gamelodge.stream.StreamProfiles
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import java.io.File
import java.util.UUID

// Streaming hosts and emulators arrive with the next phases (docs/DESKTOP_PLAN.md).

/** No streaming hosts yet. */
object NoHosts : HostRepository {
    override val hosts: StateFlow<Map<String, HostState>> = MutableStateFlow(emptyMap())
    override val appLists: StateFlow<Map<String, List<RemoteApp>>> = MutableStateFlow(emptyMap())
    override fun startPolling() = Unit
    override fun stopPolling() = Unit
    override fun setDiscovery(active: Boolean) = Unit
    override fun refreshAppLists() = Unit
    override fun invalidate(uuid: String) = Unit
    override fun removeHost(host: HostState) = Unit
    override suspend fun boxArt(host: HostState, app: HostApp): File? = null
    override fun forgetBoxArt(hostUuid: String) = Unit
}

object NoHostActions : HostActions {
    override fun generatePin(): String = (0 until 4).joinToString("") { (0..9).random().toString() }
    override suspend fun pair(host: HostState, pin: String) = PairResult.FAILED
    override suspend fun addHost(rawAddress: String) = AddHostResult.UNREACHABLE
    override suspend fun wakeOnLan(host: HostState) = false
    override suspend fun remove(host: HostState) = Unit
    override suspend fun quitApp(host: HostState) = false
}

/** Only the base profile until the streaming client brings its own. */
object BaseProfileOnly : StreamProfiles {
    override fun all(defaultName: String) = listOf(StreamProfile(null, defaultName))
    override fun active(defaultName: String) = StreamProfile(null, defaultName)
    override fun setActive(uuid: UUID?) = Unit
    override fun changes(): Flow<Unit> = flowOf(Unit)
    override fun summary(uuid: UUID?) = ""
}

object NoEmulators : Emulators {
    override fun choices(platformId: String) = emptyList<EmulatorOption>()
    override fun resolve(platformId: String, gameKey: String?): EmulatorOption? = null
    override fun platformName(platformId: String): String? = null
}

object NoAutoSleep : AutoSleep {
    override suspend fun scheduleAfterStream(hostUuid: String) = Unit
    override fun cancel(hostUuid: String) = Unit
}
