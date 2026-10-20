package io.github.thecodingdad.gamelodge.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import io.github.thecodingdad.gamelodge.data.settings.SecretStore
import io.github.thecodingdad.gamelodge.data.settings.SettingsRepository
import io.github.thecodingdad.gamelodge.stream.StreamVpn
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
) : StreamVpn, Vpn {
    private val backend by lazy { GoBackend(context) }
    private val mutex = Mutex()

    private val _state = MutableStateFlow(VpnState(VpnStatus.DOWN, null))
    override val state: StateFlow<VpnState> = _state.asStateFlow()

    private val _tunnels = MutableStateFlow(loadNames())
    override val tunnels: StateFlow<List<String>> = _tunnels.asStateFlow()

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

    override suspend fun disconnect() = mutex.withLock {
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

    override suspend fun wantedForStream(): Boolean {
        if (_state.value.status == VpnStatus.UP || defaultTunnel() == null) return false
        val s = settings.current()
        if (!s.vpnOnStreamStart) return false
        // Without home networks the unreachable host alone decides.
        return !(s.vpnOnlyOutsideHome && s.homeNetworks.isNotEmpty() && atHome(s.homeNetworks))
    }

    override suspend fun connectForStream(): Boolean = defaultTunnel()?.let { connect(it) } ?: false

    override suspend fun disconnectAfterStream() = settings.current().vpnDisconnectAfterStream

    fun atHome(homeNetworks: Collection<String>) = HomeNetworks.contains(homeNetworks, localAddresses().map { it.first })

    /**
     * The device's own IPv4 addresses with their prefix length, on every network but the VPN
     * (Wi-Fi and LAN alike).
     */
    fun localAddresses(): List<Pair<String, Int>> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        return cm.allNetworks.mapNotNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            cm.getLinkProperties(network)
        }.flatMap { it.linkAddresses }
            .filter { it.address is java.net.Inet4Address && !it.address.isLoopbackAddress }
            .map { it.address.hostAddress.orEmpty() to it.prefixLength }
            .distinct()
    }

    private companion object {
        const val PREFIX = "wg_config_"
    }
}
