package io.github.thecodingdad.gamelodge.data.remote

import kotlinx.coroutines.flow.StateFlow
import java.io.File

enum class HostStatus { ONLINE, OFFLINE, UNKNOWN }

/** Immutable snapshot of a streaming host (Sunshine, Apollo, Vibepollo) for the UI. */
data class HostState(
    val uuid: String,
    val name: String,
    val status: HostStatus,
    val paired: Boolean,
    val runningAppId: Int,
    val address: String?,
    val macAddress: String?,
    val virtualDisplaySupported: Boolean,
    val serverCommands: List<String>,
    /** Apollo/Vibepollo permission bits for this client; -1 when unknown (Sunshine). */
    val permission: Int,
) {
    val missingPermissions: List<HostPermission> get() = HostPermission.missingEssential(permission)
    val isStreaming: Boolean get() = runningAppId != 0
}

/** An app in a host's app list. */
data class HostApp(
    val appId: Int,
    val appName: String,
    val appUUID: String?,
    val isHdrSupported: Boolean,
)

data class RemoteApp(
    val hostUuid: String,
    val hostName: String,
    val app: HostApp,
)

/** The streaming hosts known to this launcher and their app lists. */
interface HostRepository {
    val hosts: StateFlow<Map<String, HostState>>

    /** Latest app list per host uuid (only hosts that delivered a list). */
    val appLists: StateFlow<Map<String, List<RemoteApp>>>

    /** Connects to the platform's host service; call once at start. */
    fun bind() {}

    /** Call when the launcher becomes visible; polling costs battery, so it stops in background. */
    fun startPolling()

    fun stopPolling()

    /** Looks for new hosts in the network (mDNS) while [active]; known hosts are polled anyway. */
    fun setDiscovery(active: Boolean)

    /** Forces an immediate app list refresh for every reachable host. */
    fun refreshAppLists()

    fun invalidate(uuid: String)

    fun removeHost(host: HostState)

    /** The host's own cover image for [app], cached on disk; null when it has none. Blocking. */
    suspend fun boxArt(host: HostState, app: HostApp): File?

    /** Drops the cached box art of a host. */
    fun forgetBoxArt(hostUuid: String)
}

enum class PairResult { PAIRED, WRONG_PIN, FAILED, IN_GAME, ALREADY_IN_PROGRESS, OFFLINE }

enum class AddHostResult { ADDED, INVALID_ADDRESS, UNREACHABLE }

/** Host management operations (pairing, adding, waking, removing) without any UI. */
interface HostActions {
    fun generatePin(): String

    /** Blocks until the user entered [pin] on the host (or pairing failed). */
    suspend fun pair(host: HostState, pin: String): PairResult

    /** Adds a host by IP/hostname, optionally with ":port". */
    suspend fun addHost(rawAddress: String): AddHostResult

    suspend fun wakeOnLan(host: HostState): Boolean

    suspend fun remove(host: HostState)

    /** Ends the app running on [host]; true when it is gone. */
    suspend fun quitApp(host: HostState): Boolean
}

/**
 * Who wants the network searched for hosts right now (the hosts screen, the search after start):
 * discovery runs while anyone does, so one ending doesn't stop the other's.
 */
object HostDiscovery {
    private val holders = HashMap<HostRepository, MutableSet<String>>()

    @Synchronized
    fun hold(hosts: HostRepository, key: String, active: Boolean) {
        val set = holders.getOrPut(hosts) { mutableSetOf() }
        val before = set.isNotEmpty()
        if (active) set += key else set -= key
        if (set.isNotEmpty() != before) hosts.setDiscovery(set.isNotEmpty())
    }
}
