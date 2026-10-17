package io.github.thecodingdad.gamelodge.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.net.wifi.WifiInfo
import android.os.Build
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import io.github.thecodingdad.gamelodge.data.remote.HostState
import io.github.thecodingdad.gamelodge.data.settings.AutoVpnTrigger
import io.github.thecodingdad.gamelodge.data.settings.SecretStore
import io.github.thecodingdad.gamelodge.data.settings.SettingsRepository
import io.github.thecodingdad.gamelodge.stream.VpnAutoConnect
import io.github.thecodingdad.gamelodge.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Built-in WireGuard client (official wireguard-android tunnel library, Go backend).
 * Tunnel configs live in the encrypted [SecretStore].
 */
class VpnManager(
    private val context: Context,
    private val secrets: SecretStore,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) : VpnAutoConnect, Vpn {
    private val backend by lazy { GoBackend(context) }
    private val mutex = Mutex()

    private val _state = MutableStateFlow(VpnState(VpnStatus.DOWN, null))
    override val state: StateFlow<VpnState> = _state.asStateFlow()

    private val _tunnels = MutableStateFlow(loadNames())
    override val tunnels: StateFlow<List<String>> = _tunnels.asStateFlow()

    private val _currentSsid = MutableStateFlow<String?>(null)
    val currentSsid: StateFlow<String?> = _currentSsid.asStateFlow()

    private inner class NamedTunnel(private val tunnelName: String) : Tunnel {
        override fun getName() = tunnelName
        override fun onStateChange(newState: Tunnel.State) {
            _state.value = VpnState(if (newState == Tunnel.State.UP) VpnStatus.UP else VpnStatus.DOWN, tunnelName)
        }
    }
    private var active: NamedTunnel? = null

    private fun loadNames(): List<String> = secrets.keys()
        .filter { it.startsWith(PREFIX) }
        .map { it.removePrefix(PREFIX) }
        .sorted()

    /** Validates and stores a WireGuard config (`.conf` content). Returns an error message or null. */
    fun import(name: String, text: String): String? {
        val clean = name.trim().replace(Regex("[^A-Za-z0-9_=+.-]"), "_").take(15).ifEmpty { "wg0" }
        runCatching { Config.parse(text.byteInputStream()) }.onFailure { return it.message ?: "Invalid config" }
        secrets.put(SecretStore.Keys.wireguardConfig(clean), text)
        _tunnels.value = loadNames()
        return null
    }

    fun delete(name: String) {
        if (active?.name == name) scope.launch { disconnect() }
        secrets.put(SecretStore.Keys.wireguardConfig(name), null)
        _tunnels.value = loadNames()
    }

    /** Intent for the one-time system VPN consent, or null when already granted. */
    fun consentIntent(): Intent? = VpnService.prepare(context)

    override fun ready() = consentIntent() == null

    suspend fun connect(name: String): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (consentIntent() != null) {
                _state.value = VpnState(VpnStatus.ERROR, name, "consent")
                return@withContext false
            }
            val text = secrets.get(SecretStore.Keys.wireguardConfig(name)) ?: return@withContext false
            _state.value = VpnState(VpnStatus.CONNECTING, name)
            runCatching {
                active?.takeIf { it.name != name }?.let { backend.setState(it, Tunnel.State.DOWN, null) }
                val tunnel = NamedTunnel(name)
                backend.setState(tunnel, Tunnel.State.UP, Config.parse(text.byteInputStream()))
                active = tunnel
                _state.value = VpnState(VpnStatus.UP, name)
                true
            }.getOrElse {
                Log.w("WireGuard connect failed", it)
                _state.value = VpnState(VpnStatus.ERROR, name, it.message)
                false
            }
        }
    }

    suspend fun disconnect() = mutex.withLock {
        withContext(Dispatchers.IO) {
            active?.let { runCatching { backend.setState(it, Tunnel.State.DOWN, null) } }
            active = null
            _state.value = VpnState(VpnStatus.DOWN, null)
        }
    }

    override suspend fun toggle() {
        if (_state.value.status == VpnStatus.UP) disconnect() else defaultTunnel()?.let { connect(it) }
    }

    private suspend fun defaultTunnel(): String? =
        settings.current().activeTunnel.takeIf { it in _tunnels.value } ?: _tunnels.value.firstOrNull()

    /** Launch flow hook: connect when the host is unreachable and that trigger is enabled. */
    override suspend fun connectIfNeeded(host: HostState?): Boolean {
        if (_state.value.status == VpnStatus.UP) return false
        if (AutoVpnTrigger.HOST_UNREACHABLE !in settings.current().autoVpnTriggers) return false
        return defaultTunnel()?.let { connect(it) } ?: false
    }

    /** Watches Wi-Fi changes for the "outside home Wi-Fi" trigger. Reading the SSID needs location permission. */
    fun startNetworkWatch() {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = onNetwork(caps)
                override fun onLost(network: Network) = onNetwork(null)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = onNetwork(caps)
                override fun onLost(network: Network) = onNetwork(null)
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching { cm.registerNetworkCallback(request, callback) }
            .onFailure { Log.w("Network watch unavailable", it) }
    }

    private fun onNetwork(caps: NetworkCapabilities?) {
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) return
        val ssid = if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
            (caps.transportInfo as? WifiInfo)?.ssid?.trim('"')?.takeIf { it != WifiManagerUnknownSsid }
        } else null
        if (ssid == _currentSsid.value && caps != null) return
        _currentSsid.value = ssid
        scope.launch {
            val s = settings.current()
            if (AutoVpnTrigger.OUTSIDE_HOME !in s.autoVpnTriggers || s.homeSsids.isEmpty()) return@launch
            val atHome = ssid != null && ssid in s.homeSsids
            when {
                !atHome && caps != null && _state.value.status == VpnStatus.DOWN -> defaultTunnel()?.let { connect(it) }
                atHome && s.vpnDisconnectAtHome && _state.value.status == VpnStatus.UP -> disconnect()
            }
        }
    }

    private companion object {
        const val PREFIX = "wg_config_"
        const val WifiManagerUnknownSsid = "<unknown ssid>"
    }
}
