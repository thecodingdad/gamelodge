package io.github.thecodingdad.moonlightlauncher.desktop.host

import io.github.thecodingdad.moonlightlauncher.data.companion.PairedServer
import io.github.thecodingdad.moonlightlauncher.data.settings.SecretStore
import io.github.thecodingdad.moonlightlauncher.desktop.restrictToOwner
import io.github.thecodingdad.moonlightlauncher.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/** What the host role runs (host.json); everything is off until the user switches it on. */
@Serializable
data class HostSettings(
    /** Connection for paired launchers (port 47910). */
    val serverEnabled: Boolean = false,
    /** Web UI for pairing from another device (port 47912). */
    val webEnabled: Boolean = false,
    /** Paired launchers may use this PC's SteamGridDB / IGDB keys. */
    val proxyEnabled: Boolean = false,
)

/** Which servers run; "failed" means the port couldn't be opened (in use). */
data class HostStatus(val serverRunning: Boolean = false, val serverFailed: Boolean = false, val webRunning: Boolean = false, val webFailed: Boolean = false)

/**
 * The PC as host for other launchers (what the companion did): the API for paired launchers,
 * pairing, the paired devices and the web UI. Started once; follows [settings].
 */
class HostRole(private val dataDir: File, private val secrets: SecretStore, private val serverName: () -> String) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file = File(dataDir, "host.json")
    private val _settings = MutableStateFlow(load())
    private val _status = MutableStateFlow(HostStatus())

    val settings: StateFlow<HostSettings> = _settings.asStateFlow()
    val status: StateFlow<HostStatus> = _status.asStateFlow()

    val identity: ServerIdentity by lazy { ServerIdentity.loadOrCreate(secrets, serverName()) }
    val devices: DeviceRegistry by lazy { DeviceRegistry(File(dataDir, "devices.json")) }
    val pairing: PairingManager by lazy { PairingManager(devices, { identity.fingerprint }, Permissions.all) }
    val auth: AdminAuth by lazy { AdminAuth(File(dataDir, "admin.json"), secrets) }

    /** The PC's games for paired launchers (set once the library is ready). */
    @Volatile var hostGames: HostGamesSource? = null

    /** SteamGridDB / IGDB with this PC's keys. */
    @Volatile var proxy: MetadataProxyApi? = null

    private val api by lazy {
        ApiServer(identity, devices, pairing, serverName, { proxy.takeIf { _settings.value.proxyEnabled } }, { hostGames })
    }
    private val web by lazy {
        WebAdmin(identity, auth, devices, pairing, object : PairingGate {
            override val ready get() = _status.value.serverRunning
            override fun fix() = update { it.copy(serverEnabled = true) }
        })
    }

    fun start() = apply()

    fun stop() = synchronized(this) {
        if (_status.value.serverRunning) api.stop()
        if (_status.value.webRunning) web.stop()
        _status.value = HostStatus()
    }

    fun update(change: (HostSettings) -> HostSettings) {
        val next = change(_settings.value)
        if (next == _settings.value) return
        _settings.value = next
        save(next)
        apply()
    }

    /** Addresses of this PC in the local network, for the web UI's address. */
    fun localAddresses(): List<String> = runCatching {
        NetworkInterface.networkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { WebAdmin.isPrivate(it) && !it.isLinkLocalAddress }
            .map { it.hostAddress }
    }.getOrDefault(emptyList())

    @Synchronized
    private fun apply() {
        val s = _settings.value
        var status = _status.value
        if (s.serverEnabled && !status.serverRunning) {
            status = runCatching { api.start(PairedServer.COMPANION_PORT) }
                .fold({ status.copy(serverRunning = true, serverFailed = false) }) {
                    Log.w("Launcher API couldn't start", it)
                    status.copy(serverFailed = true)
                }
        } else if (!s.serverEnabled) {
            if (status.serverRunning) {
                api.stop()
                pairing.close()
            }
            status = status.copy(serverRunning = false, serverFailed = false)
        }
        if (s.webEnabled && !status.webRunning) {
            status = runCatching { web.start(WebAdmin.DEFAULT_PORT) }
                .fold({ status.copy(webRunning = true, webFailed = false) }) {
                    Log.w("Web UI couldn't start", it)
                    status.copy(webFailed = true)
                }
        } else if (!s.webEnabled) {
            if (status.webRunning) web.stop()
            status = status.copy(webRunning = false, webFailed = false)
        }
        _status.value = status
    }

    private fun load(): HostSettings = runCatching { json.decodeFromString<HostSettings>(file.readText()) }.getOrDefault(HostSettings())

    private fun save(s: HostSettings) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(s))
        restrictToOwner(file)
    }
}
