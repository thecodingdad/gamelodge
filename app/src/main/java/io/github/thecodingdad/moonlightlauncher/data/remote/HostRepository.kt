package io.github.thecodingdad.moonlightlauncher.data.remote

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
import io.github.thecodingdad.moonlightlauncher.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.StringReader

enum class HostStatus { ONLINE, OFFLINE, UNKNOWN }

/** Immutable snapshot of a host for the UI; [details] is Artemis' live object for API calls. */
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
    val details: ComputerDetails,
) {
    val missingPermissions: List<HostPermission> get() = HostPermission.missingEssential(permission)
    val isStreaming: Boolean get() = runningAppId != 0
}

data class RemoteApp(
    val hostUuid: String,
    val hostName: String,
    val app: NvApp,
)

/**
 * Single owner of Artemis' [ComputerManagerService]: it keeps the binder, receives all
 * host updates (the service supports exactly one listener) and exposes them as flows.
 */
class HostRepository(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val _hosts = MutableStateFlow<Map<String, HostState>>(emptyMap())
    val hosts: StateFlow<Map<String, HostState>> = _hosts.asStateFlow()

    /** Latest app list per host uuid (only hosts that delivered a list). */
    private val _appLists = MutableStateFlow<Map<String, List<RemoteApp>>>(emptyMap())
    val appLists: StateFlow<Map<String, List<RemoteApp>>> = _appLists.asStateFlow()

    private var binder: ComputerManagerService.ComputerManagerBinder? = null
    private val binderReady = CompletableDeferred<ComputerManagerService.ComputerManagerBinder>()
    private val appListPollers = mutableMapOf<String, ComputerManagerService.ApplistPoller>()
    private val lastRawAppLists = mutableMapOf<String, String>()
    private var polling = false

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

    fun bind() {
        context.bindService(
            Intent(context, ComputerManagerService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    }

    suspend fun awaitBinder(): ComputerManagerService.ComputerManagerBinder = binderReady.await()

    /** Call when the launcher becomes visible; polling costs battery, so it stops in background. */
    fun startPolling() {
        polling = true
        binder?.let { startPollingInternal(it) }
    }

    fun stopPolling() {
        polling = false
        synchronized(appListPollers) {
            appListPollers.values.forEach { it.stop() }
            appListPollers.clear()
        }
        binder?.stopPolling()
    }

    private fun startPollingInternal(b: ComputerManagerService.ComputerManagerBinder) {
        b.startPolling(listener)
    }

    /** Forces an immediate app list refresh for every reachable host. */
    fun refreshAppLists() {
        synchronized(appListPollers) { appListPollers.values.forEach { it.pollNow() } }
    }

    fun invalidate(uuid: String) {
        binder?.invalidateStateForComputer(uuid)
    }

    private fun onComputerUpdated(details: ComputerDetails) {
        val uuid = details.uuid ?: return
        val paired = details.pairState == PairingManager.PairState.PAIRED
        val state = HostState(
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
            details = details,
        )
        _hosts.update { it + (uuid to state) }

        if (state.status == HostStatus.ONLINE && paired) ensureAppListPoller(details)

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
        _appLists.update { it + (uuid to apps.map { app -> RemoteApp(uuid, hostName, app) }) }
    }

    /** Opens an authenticated connection to the host (blocking work, call off the main thread). */
    suspend fun http(host: HostState): NvHTTP = withContext(Dispatchers.IO) {
        val b = awaitBinder()
        val details = host.details
        NvHTTP(
            details.activeAddress ?: error("Host ${host.name} has no reachable address"),
            details.httpsPort,
            b.uniqueId,
            details.serverCert,
            PlatformBinding.getCryptoProvider(context),
        )
    }

    fun removeHost(host: HostState) {
        binder?.removeComputer(host.details)
        _hosts.update { it - host.uuid }
        _appLists.update { it - host.uuid }
    }
}
