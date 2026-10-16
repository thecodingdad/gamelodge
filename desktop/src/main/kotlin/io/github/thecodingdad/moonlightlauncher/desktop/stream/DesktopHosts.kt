package io.github.thecodingdad.moonlightlauncher.desktop.stream

import io.github.thecodingdad.moonlightlauncher.data.remote.AddHostResult
import io.github.thecodingdad.moonlightlauncher.data.remote.HostActions
import io.github.thecodingdad.moonlightlauncher.data.remote.HostApp
import io.github.thecodingdad.moonlightlauncher.data.remote.HostRepository
import io.github.thecodingdad.moonlightlauncher.data.remote.HostState
import io.github.thecodingdad.moonlightlauncher.data.remote.HostStatus
import io.github.thecodingdad.moonlightlauncher.data.remote.PairResult
import io.github.thecodingdad.moonlightlauncher.data.remote.RemoteApp
import io.github.thecodingdad.moonlightlauncher.desktop.restrictToOwner
import io.github.thecodingdad.moonlightlauncher.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.SecureRandom

/** A host as stored (hosts.json): where it is and the certificate pinned at pairing. */
@Serializable
data class StoredHost(
    val uuid: String,
    val name: String,
    val address: String,
    val port: Int = 47989,
    val httpsPort: Int = 47984,
    val mac: String? = null,
    /** PEM; null while not paired. */
    val serverCert: String? = null,
)

/**
 * The streaming hosts of the desktop launcher (Sunshine, Apollo, Vibepollo), spoken to directly
 * over the GameStream API with the launcher's own identity. Hosts are added by address; their
 * state is polled while the launcher is in front, their app lists less often.
 */
class DesktopHosts(
    private val dataDir: File,
    private val identity: () -> StreamIdentity,
    private val http: OkHttpClient,
    private val deviceName: () -> String,
    private val scope: CoroutineScope,
) : HostRepository, HostActions {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file = File(dataDir, "hosts.json")
    private val boxArtDir = File(dataDir, "boxart")
    private val stored = MutableStateFlow(load())
    private val clients = mutableMapOf<String, NvHttp>()
    private val _hosts = MutableStateFlow(stored.value.associate { it.uuid to offline(it) })
    private val _appLists = MutableStateFlow<Map<String, List<RemoteApp>>>(emptyMap())
    private val pairing = Mutex()
    private var polling: Job? = null
    private val appListAt = mutableMapOf<String, Long>()
    /** Hosts found in the network, not added or paired yet (kept in memory only). */
    private val discovered = java.util.concurrent.ConcurrentHashMap<String, StoredHost>()
    private val discovery = HostDiscovery { address, port -> scope.launch(Dispatchers.IO) { found(address, port) } }

    override val hosts: StateFlow<Map<String, HostState>> = _hosts.asStateFlow()
    override val appLists: StateFlow<Map<String, List<RemoteApp>>> = _appLists.asStateFlow()

    /** The stored data of a host (for the streaming client: address, certificate). */
    fun stored(uuid: String): StoredHost? = stored.value.firstOrNull { it.uuid == uuid }

    /** Stored or just discovered. */
    private fun known(uuid: String): StoredHost? = stored(uuid) ?: discovered[uuid]
    val storedHosts: StateFlow<List<StoredHost>> get() = stored

    override fun startPolling() {
        if (polling?.isActive == true) return
        polling = scope.launch(Dispatchers.IO) {
            while (isActive) {
                pollAll()
                delay(POLL_MS)
            }
        }
    }

    override fun stopPolling() {
        polling?.cancel()
        polling = null
    }

    /** Looks for hosts (mDNS "_nvstream._tcp") while the hosts page is open. */
    override fun setDiscovery(active: Boolean) {
        if (active) discovery.start() else discovery.stop()
    }

    /** A host announced itself: shown (unpaired) until the user pairs or adds it. */
    private suspend fun found(address: String, port: Int) {
        if (stored.value.any { it.address == address && it.port == port } || discovered.values.any { it.address == address }) return
        val info = runCatching { NvHttp(address, port, identity(), null, http, deviceName()).serverInfo() }.getOrNull() ?: return
        if (stored(info.uuid) != null) return
        Log.i("Host found in the network: ${info.name} ($address)")
        val host = StoredHost(info.uuid, info.name, address, port, info.httpsPort, info.mac)
        discovered[info.uuid] = host
        poll(host)
    }

    override fun refreshAppLists() {
        appListAt.clear()
        scope.launch(Dispatchers.IO) { pollAll() }
    }

    override fun invalidate(uuid: String) {
        appListAt.remove(uuid)
        scope.launch(Dispatchers.IO) { known(uuid)?.let { poll(it) } }
    }

    override fun removeHost(host: HostState) {
        scope.launch { remove(host) }
    }

    override suspend fun boxArt(host: HostState, app: HostApp): File? = withContext(Dispatchers.IO) {
        val file = File(boxArtDir, "${safe(host.uuid)}/${app.appId}.png")
        if (file.isFile) return@withContext file
        val s = stored(host.uuid)?.takeIf { it.serverCert != null } ?: return@withContext null
        val bytes = client(s).boxArt(app.appId) ?: return@withContext null
        file.parentFile.mkdirs()
        file.writeBytes(bytes)
        file
    }

    override fun forgetBoxArt(hostUuid: String) {
        File(boxArtDir, safe(hostUuid)).deleteRecursively()
    }

    // ---- HostActions ----

    override fun generatePin(): String = (0 until 4).joinToString("") { SecureRandom().nextInt(10).toString() }

    override suspend fun pair(host: HostState, pin: String): PairResult = withContext(Dispatchers.IO) {
        if (!pairing.tryLock()) return@withContext PairResult.ALREADY_IN_PROGRESS
        try {
            val s = known(host.uuid) ?: return@withContext PairResult.FAILED
            // An own client: polling goes on undisturbed with the current state.
            val c = NvHttp(s.address, s.port, identity(), null, http, deviceName()).also { it.httpsPort = s.httpsPort }
            val info = runCatching { c.serverInfo() }.getOrElse { return@withContext PairResult.OFFLINE }
            if (info.runningAppId != 0) return@withContext PairResult.IN_GAME
            val major = info.appVersion.substringBefore('.').toIntOrNull() ?: 7
            val (result, cert) = runCatching { NvPairing.pair(c, identity(), pin, major) }.getOrElse {
                Log.w("Pairing with ${s.name} failed", it)
                NvPairing.Result.FAILED to null
            }
            when (result) {
                NvPairing.Result.PAIRED -> {
                    Log.i("Paired with ${s.name}")
                    val paired = s.copy(httpsPort = c.httpsPort, serverCert = StreamIdentity.pem("CERTIFICATE", cert!!.encoded))
                    discovered.remove(s.uuid)
                    save(stored.value.filter { it.uuid != s.uuid } + paired)
                    clients.remove(s.uuid)
                    appListAt.remove(s.uuid)
                    poll(stored(s.uuid)!!)
                    PairResult.PAIRED
                }
                NvPairing.Result.PIN_WRONG -> PairResult.WRONG_PIN.also { Log.i("Pairing with ${s.name}: wrong PIN") }
                NvPairing.Result.ALREADY_IN_PROGRESS -> PairResult.ALREADY_IN_PROGRESS
                NvPairing.Result.FAILED -> PairResult.FAILED
            }
        } finally {
            pairing.unlock()
        }
    }

    override suspend fun addHost(rawAddress: String): AddHostResult = withContext(Dispatchers.IO) {
        val (address, port) = parseAddress(rawAddress) ?: return@withContext AddHostResult.INVALID_ADDRESS
        val probe = NvHttp(address, port, identity(), null, http, deviceName())
        val info = runCatching { probe.serverInfo() }.getOrElse { return@withContext AddHostResult.UNREACHABLE }
        val existing = stored(info.uuid)
        val host = existing?.copy(name = info.name, address = address, port = port, httpsPort = info.httpsPort, mac = info.mac ?: existing.mac)
            ?: StoredHost(info.uuid, info.name, address, port, info.httpsPort, info.mac)
        save(stored.value.filter { it.uuid != info.uuid } + host)
        discovered.remove(info.uuid)
        clients.remove(info.uuid)
        poll(host)
        AddHostResult.ADDED
    }

    override suspend fun wakeOnLan(host: HostState): Boolean = withContext(Dispatchers.IO) {
        val mac = (host.macAddress ?: stored(host.uuid)?.mac)?.split(':', '-')?.mapNotNull { it.toIntOrNull(16)?.toByte() }
        if (mac == null || mac.size != 6) return@withContext false
        val packet = ByteArray(6) { 0xFF.toByte() } + (0 until 16).flatMap { mac }.toByteArray()
        val targets = listOfNotNull("255.255.255.255", host.address ?: stored(host.uuid)?.address)
        runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                for (t in targets) for (p in WOL_PORTS) {
                    runCatching { socket.send(DatagramPacket(packet, packet.size, InetAddress.getByName(t), p)) }
                }
            }
        }.isSuccess
    }

    override suspend fun unpair(host: HostState) = withContext(Dispatchers.IO) {
        val s = stored(host.uuid) ?: return@withContext
        runCatching { client(s).unpair() }
        save(stored.value.map { if (it.uuid == s.uuid) it.copy(serverCert = null) else it })
        clients.remove(s.uuid)
        _appLists.update { it - s.uuid }
        poll(stored(s.uuid)!!)
    }

    override suspend fun remove(host: HostState) = withContext(Dispatchers.IO) {
        save(stored.value.filter { it.uuid != host.uuid })
        discovered.remove(host.uuid)
        clients.remove(host.uuid)
        _hosts.update { it - host.uuid }
        _appLists.update { it - host.uuid }
        forgetBoxArt(host.uuid)
    }

    override suspend fun quitApp(host: HostState): Boolean = withContext(Dispatchers.IO) {
        val s = stored(host.uuid)?.takeIf { it.serverCert != null } ?: return@withContext false
        val ok = runCatching { client(s).quit() }.getOrDefault(false)
        poll(s)
        ok
    }

    // ---- polling ----

    private suspend fun pollAll() = kotlinx.coroutines.coroutineScope {
        (stored.value + discovered.values).map { async { poll(it) } }.awaitAll()
    }

    private suspend fun poll(s: StoredHost) {
        val c = client(s)
        val result = runCatching { c.serverInfo() }
        val info = result.getOrNull()
        if (info == null || info.uuid != s.uuid) {
            if (_hosts.value[s.uuid]?.status != HostStatus.OFFLINE) {
                Log.i("Host ${s.name} offline: ${result.exceptionOrNull()?.let { "${it::class.simpleName}: ${it.message}" } ?: "other host at ${s.address}"}")
            }
            _hosts.update { it + (s.uuid to offline(s)) }
            return
        }
        val before = _hosts.value[s.uuid]
        if (before?.status != HostStatus.ONLINE || before.paired != (info.paired && s.serverCert != null)) {
            Log.i("Host ${info.name} online, paired: ${info.paired} (certificate ${if (s.serverCert != null) "pinned" else "none"})")
        }
        val paired = info.paired && s.serverCert != null
        if (stored(s.uuid) != null && (info.name != s.name || info.httpsPort != s.httpsPort || (info.mac != null && info.mac != s.mac))) {
            save(stored.value.map { if (it.uuid == s.uuid) it.copy(name = info.name, httpsPort = info.httpsPort, mac = info.mac ?: it.mac) else it })
        }
        _hosts.update {
            it + (s.uuid to HostState(
                uuid = s.uuid, name = info.name, status = HostStatus.ONLINE, paired = paired, runningAppId = info.runningAppId,
                address = s.address, macAddress = info.mac ?: s.mac, virtualDisplaySupported = info.virtualDisplay,
                serverCommands = info.serverCommands, permission = info.permission,
            ))
        }
        val now = System.currentTimeMillis()
        if (paired && now - (appListAt[s.uuid] ?: 0) > APP_LIST_MS) {
            runCatching { c.appList() }.onSuccess { apps ->
                appListAt[s.uuid] = now
                if (_appLists.value[s.uuid]?.size != apps.size) Log.i("App list of ${info.name}: ${apps.size} apps")
                val list = apps.map { RemoteApp(s.uuid, info.name, HostApp(it.id, it.name, it.uuid, it.hdr)) }
                if (_appLists.value[s.uuid] != list) _appLists.update { m -> m + (s.uuid to list) }
            }.onFailure { Log.w("App list of ${s.name} failed: ${it.message}") }
        }
    }

    @Synchronized
    private fun client(s: StoredHost): NvHttp = clients.getOrPut(s.uuid) {
        NvHttp(s.address, s.port, identity(), s.serverCert?.let(StreamIdentity::parseCertificate), http, deviceName()).also { it.httpsPort = s.httpsPort }
    }

    private fun offline(s: StoredHost) = HostState(
        uuid = s.uuid, name = s.name, status = HostStatus.OFFLINE, paired = s.serverCert != null, runningAppId = 0,
        address = s.address, macAddress = s.mac, virtualDisplaySupported = false, serverCommands = emptyList(), permission = -1,
    )

    private fun load(): List<StoredHost> = runCatching { json.decodeFromString<List<StoredHost>>(file.readText()) }.getOrDefault(emptyList())

    @Synchronized
    private fun save(list: List<StoredHost>) {
        stored.value = list
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(list))
            restrictToOwner(file)
        }.onFailure { Log.w("Hosts not saved", it) }
    }

    private fun safe(s: String) = s.filter { it.isLetterOrDigit() || it == '-' }

    companion object {
        private const val POLL_MS = 3_000L
        private const val APP_LIST_MS = 30_000L
        private val WOL_PORTS = listOf(7, 9, 47998, 47999, 48000, 48002, 48010)

        /** "host", "host:port", "[v6]:port" → address and HTTP port. */
        fun parseAddress(raw: String): Pair<String, Int>? {
            val s = raw.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
            if (s.isEmpty() || s.any { it.isWhitespace() || it == '/' || it == '@' }) return null
            if (s.startsWith("[")) {
                val end = s.indexOf(']').takeIf { it > 0 } ?: return null
                val port = s.substring(end + 1).removePrefix(":").ifEmpty { "47989" }.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
                return s.substring(1, end) to port
            }
            val colons = s.count { it == ':' }
            if (colons > 1) return s to 47989 // bare IPv6
            val host = s.substringBefore(':')
            val port = if (colons == 1) s.substringAfter(':').toIntOrNull()?.takeIf { it in 1..65535 } ?: return null else 47989
            return host to port
        }
    }
}
