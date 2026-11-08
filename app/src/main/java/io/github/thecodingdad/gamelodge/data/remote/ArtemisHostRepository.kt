package io.github.thecodingdad.gamelodge.data.remote

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.limelight.binding.PlatformBinding
import com.limelight.computers.ComputerManagerListener
import com.limelight.computers.ComputerManagerService
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvApp
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PairingManager
import io.github.thecodingdad.gamelodge.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.StringReader

/**
 * Single owner of Artemis' [ComputerManagerService]: it keeps the binder, receives all
 * host updates (the service supports exactly one listener) and exposes them as flows.
 */
class ArtemisHostRepository(
    private val context: Context,
    private val scope: CoroutineScope,
) : HostRepository {
    private val _hosts = MutableStateFlow<Map<String, HostState>>(emptyMap())
    override val hosts: StateFlow<Map<String, HostState>> = _hosts.asStateFlow()

    private val _appLists = MutableStateFlow<Map<String, List<RemoteApp>>>(emptyMap())
    override val appLists: StateFlow<Map<String, List<RemoteApp>>> = _appLists.asStateFlow()

    /** Artemis' live objects behind [hosts] and [appLists], for API calls and stream starts. */
    private val details = java.util.concurrent.ConcurrentHashMap<String, ComputerDetails>()
    private val nvApps = java.util.concurrent.ConcurrentHashMap<String, Map<Int, NvApp>>()
    private val boxArtCache = BoxArtCache(context)

    fun details(uuid: String): ComputerDetails? = details[uuid]

    fun nvApp(hostUuid: String, appId: Int): NvApp? = nvApps[hostUuid]?.get(appId)

    private var binder: ComputerManagerService.ComputerManagerBinder? = null
    private val binderReady = CompletableDeferred<ComputerManagerService.ComputerManagerBinder>()
    private val appListPollers = mutableMapOf<String, ComputerManagerService.ApplistPoller>()
    private val lastRawAppLists = mutableMapOf<String, String>()
    private var polling = false
    /** mDNS discovery runs only while the hosts screen is open (jmDNS caches everything it hears). */
    private var discovery = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val b = service as ComputerManagerService.ComputerManagerBinder
            scope.launch(Dispatchers.IO) {
                b.waitForReady()
                binder = b
                binderReady.complete(b)
                if (polling) startPollingInternal(b)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            binder = null
        }
    }

    private val listener = ComputerManagerListener { details -> onComputerUpdated(details) }

    override fun bind() {
        context.bindService(
            Intent(context, ComputerManagerService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    }

    suspend fun awaitBinder(): ComputerManagerService.ComputerManagerBinder = binderReady.await()

    /** Call when the launcher becomes visible; polling costs battery, so it stops in background. */
    override fun startPolling() {
        polling = true
        pollingStartedAt = android.os.SystemClock.elapsedRealtime()
        binder?.let { startPollingInternal(it) }
    }

    override fun stopPolling() {
        polling = false
        synchronized(appListPollers) {
            appListPollers.values.forEach { it.stop() }
            appListPollers.clear()
        }
        binder?.stopPolling()
    }

    private fun startPollingInternal(b: ComputerManagerService.ComputerManagerBinder) {
        b.setDiscoveryActive(discovery)
        b.startPolling(listener)
    }

    override fun setDiscovery(active: Boolean) {
        discovery = active
        if (polling) binder?.setDiscoveryActive(active)
    }

    override fun refreshAppLists() {
        synchronized(appListPollers) { appListPollers.values.forEach { it.pollNow() } }
    }

    override fun invalidate(uuid: String) {
        binder?.invalidateStateForComputer(uuid)
    }

    override suspend fun refresh(uuid: String) {
        val host = _hosts.value[uuid]?.takeIf { it.status == HostStatus.ONLINE } ?: return
        runCatching {
            val (running, rawApps) = withContext(Dispatchers.IO) {
                val h = http(host)
                h.getCurrentGame(h.getServerInfo(true)) to runCatching { h.getAppListRaw() }.getOrNull()
            }
            details[uuid]?.runningGameId = running
            _hosts.update { m -> m[uuid]?.let { m + (uuid to it.copy(runningAppId = running, foreignCopy = null)) } ?: m }
            // The app list too: another client's game shows there only (Vibepollo).
            if (rawApps != null) {
                synchronized(lastRawAppLists) { lastRawAppLists.remove(uuid) }
                parseAppList(uuid, host.name, rawApps)
            } else {
                _hosts.update { m -> m[uuid]?.let { m + (uuid to it.withForeignSession(_appLists.value[uuid])) } ?: m }
            }
        }.onFailure { Log.w("Host state of ${host.name} not refreshed: ${it.message}") }
    }

    /**
     * Artemis drops a host to OFFLINE at once on a brief default-network flap (Wi-Fi roaming
     * between access points, Android switching the default network) and on a couple of missed
     * polls, even when the host never actually went away. Hold a non-online state this long before
     * showing it, so a blip that recovers never appears as offline; a real outage still shows after.
     * Right after polling (re)starts (screen on, launcher back in front) Artemis resets every host to
     * UNKNOWN and gives it only two tries while the Wi-Fi is often still waking: longer then.
     */
    private val offlineGraceMs = 15_000L
    private val offlineGraceAfterStartMs = 30_000L
    @Volatile private var pollingStartedAt = 0L
    private val offlineDebounce = mutableMapOf<String, kotlinx.coroutines.Job>()

    private fun hostStateFrom(details: ComputerDetails): HostState {
        val uuid = details.uuid!!
        // Live state while the host answers; otherwise the stored server certificate says it was paired.
        val paired = details.pairState == PairingManager.PairState.PAIRED ||
            (details.state != ComputerDetails.State.ONLINE && details.serverCert != null)
        return HostState(
            uuid = uuid,
            name = details.name ?: uuid,
            status = when (details.state) {
                ComputerDetails.State.ONLINE -> HostStatus.ONLINE
                ComputerDetails.State.OFFLINE -> HostStatus.OFFLINE
                else -> HostStatus.UNKNOWN
            },
            paired = paired,
            runningAppId = details.runningGameId,
            address = details.activeAddress?.address,
            macAddress = details.macAddress,
            virtualDisplaySupported = details.vDisplaySupported,
            serverCommands = details.serverCommands?.toList().orEmpty(),
            permission = details.permission,
        ).withForeignSession(_appLists.value[uuid])
    }

    private fun onComputerUpdated(details: ComputerDetails) {
        val uuid = details.uuid ?: return
        this.details[uuid] = details
        val state = hostStateFrom(details)
        val wasOnline = _hosts.value[uuid]?.status == HostStatus.ONLINE
        // A host leaving ONLINE is held back: publish it only if it is still not online once the
        // grace window passes (checking the live state then), so a recovering flap stays invisible.
        if (state.status != HostStatus.ONLINE && wasOnline) {
            synchronized(offlineDebounce) {
                if (offlineDebounce.containsKey(uuid)) return
                val sinceStart = android.os.SystemClock.elapsedRealtime() - pollingStartedAt
                val grace = maxOf(offlineGraceMs, offlineGraceAfterStartMs - sinceStart)
                offlineDebounce[uuid] = scope.launch {
                    kotlinx.coroutines.delay(grace)
                    // Not answered yet at all (UNKNOWN): wait for the poll's verdict rather than guess.
                    var waited = 0L
                    while (this@ArtemisHostRepository.details[uuid]?.state == ComputerDetails.State.UNKNOWN && waited < offlineGraceMs) {
                        kotlinx.coroutines.delay(1_000)
                        waited += 1_000
                    }
                    synchronized(offlineDebounce) { offlineDebounce.remove(uuid) }
                    val live = this@ArtemisHostRepository.details[uuid]
                    if (live != null && live.state != ComputerDetails.State.ONLINE) {
                        Log.i("Host ${live.name} offline after ${(grace + waited) / 1000} s without an answer")
                        publishHost(uuid, hostStateFrom(live), live)
                    }
                }
            }
            return
        }
        // Back online (or a genuine still-offline update, or first sight): show it now.
        synchronized(offlineDebounce) { offlineDebounce.remove(uuid)?.cancel() }
        publishHost(uuid, state, details)
    }

    private fun publishHost(uuid: String, state: HostState, details: ComputerDetails) {
        val cameOnline = state.status == HostStatus.ONLINE && _hosts.value[uuid]?.status != HostStatus.ONLINE
        _hosts.update { it + (uuid to state) }

        if (state.status == HostStatus.ONLINE && state.paired) ensureAppListPoller(details)
        // App lists are polled every 30 s: a host that just came online sends its list now.
        if (cameOnline) synchronized(appListPollers) { appListPollers[uuid]?.pollNow() }

        details.rawAppList?.let { raw -> parseAppList(uuid, state.name, raw) }
    }

    private fun ensureAppListPoller(details: ComputerDetails) {
        val b = binder ?: return
        synchronized(appListPollers) {
            if (!polling || appListPollers.containsKey(details.uuid)) return
            val poller = b.createAppListPoller(details)
            appListPollers[details.uuid] = poller
            poller.start()
        }
    }

    private fun parseAppList(uuid: String, hostName: String, raw: String) {
        synchronized(lastRawAppLists) {
            if (lastRawAppLists[uuid] == raw) return
            lastRawAppLists[uuid] = raw
        }
        val apps = runCatching { NvHTTP.getAppListByReader(StringReader(raw)) }
            .onFailure { Log.w("Unparseable app list from $hostName", it) }
            .getOrNull() ?: return
        nvApps[uuid] = apps.associateBy { it.appId }
        val list = apps.map { app -> RemoteApp(uuid, hostName, HostApp(app.appId, app.appName, app.appUUID, app.isHdrSupported)) }
        _appLists.update { it + (uuid to list) }
        // Another client's game shows in the app list only (Vibepollo).
        _hosts.update { m -> m[uuid]?.let { m + (uuid to it.withForeignSession(list)) } ?: m }
    }

    /** Opens an authenticated connection to the host (blocking work, call off the main thread). */
    suspend fun http(host: HostState): NvHTTP = withContext(Dispatchers.IO) {
        val b = awaitBinder()
        val details = details[host.uuid] ?: error("Host ${host.name} unknown")
        NvHTTP(
            details.activeAddress ?: error("Host ${host.name} has no reachable address"),
            details.httpsPort,
            b.uniqueId,
            details.serverCert,
            PlatformBinding.getCryptoProvider(context),
        )
    }

    /** Paired again (or added again), the host sends the same list: it must count as new then. */
    fun forgetAppList(uuid: String) {
        synchronized(lastRawAppLists) { lastRawAppLists.remove(uuid) }
    }

    override fun removeHost(host: HostState) {
        details[host.uuid]?.let { binder?.removeComputer(it) }
        details.remove(host.uuid)
        nvApps.remove(host.uuid)
        forgetAppList(host.uuid)
        _hosts.update { it - host.uuid }
        _appLists.update { it - host.uuid }
    }

    override suspend fun boxArt(host: HostState, app: HostApp): File? {
        boxArtCache.file(host.uuid, app.appId).takeIf { it.length() > 0 }?.let { return it }
        val nv = nvApp(host.uuid, app.appId) ?: return null
        val http = runCatching { http(host) }.getOrNull() ?: return null
        return withContext(Dispatchers.IO) { boxArtCache.fetch(http, host.uuid, nv) }
    }

    override fun forgetBoxArt(hostUuid: String) = boxArtCache.deleteForHost(hostUuid)
}
