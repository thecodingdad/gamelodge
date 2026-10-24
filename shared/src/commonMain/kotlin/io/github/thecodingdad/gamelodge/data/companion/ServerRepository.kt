package io.github.thecodingdad.gamelodge.data.companion

import io.github.thecodingdad.gamelodge.resources.*
import io.github.thecodingdad.gamelodge.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Companions and metadata servers this launcher is paired with. Kept in its own file, outside the
 * settings backup: the pairing belongs to this device's Keystore key, which a backup can't carry.
 */
class ServerRepository(
    private val platform: io.github.thecodingdad.gamelodge.platform.PlatformInfo,
    private val baseHttp: OkHttpClient,
    private val scope: CoroutineScope,
    identityFactory: () -> ClientIdentity,
) {
    private val file = File(platform.filesDir, "paired_servers.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val clients = ConcurrentHashMap<String, ServerClient>()

    /** Created on first use (key store work, off the main thread). */
    val identity: ClientIdentity by lazy(identityFactory)

    private val _servers = MutableStateFlow(load())
    val servers: StateFlow<List<PairedServer>> = _servers.asStateFlow()

    /** Server id → reachable at the last check. */
    private val _online = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val online: StateFlow<Map<String, Boolean>> = _online.asStateFlow()

    fun start() {
        scope.launch(Dispatchers.IO) {
            io.github.thecodingdad.gamelodge.util.StartupSequence.turn { refreshAll() }
            while (true) {
                delay(10 * 60_000L)
                refreshAll()
            }
        }
    }

    /** The mutual-TLS client for a paired server (pinned certificate). */
    fun client(server: PairedServer): ServerClient =
        clients.getOrPut(server.id + server.fingerprint) { ServerClient(baseHttp, identity, server.baseUrl, server.fingerprint) }

    fun companionFor(hostUuid: String): PairedServer? =
        servers.value.firstOrNull { it.isCompanion && it.hostId.equals(hostUuid, ignoreCase = true) }

    /** Name shown on the server for this device. */
    fun deviceName(): String = platform.deviceName

    /**
     * Pairs with [baseUrl]: [onPin] gets the PIN to show while the user types it on the server.
     * Returns the stored server; throws [PairingFailed] / [ServerException] / IO errors.
     */
    suspend fun pair(baseUrl: String, onPin: (String) -> Unit): PairedServer = withContext(Dispatchers.IO) {
        val pin = JPakeExchange.newPin()
        // The PIN is typed on the server, often in a browser on this device: stay awake meanwhile.
        val text = org.jetbrains.compose.resources.getString(Res.string.pairing_running, pin)
        val paired = io.github.thecodingdad.gamelodge.util.BackgroundHold.during("pair-server", text) {
            ServerClient(baseHttp, identity, baseUrl, null).pair(deviceName(), pin) { onPin(pin) }
        }
        val info = runCatching { ServerClient(baseHttp, identity, baseUrl, paired.fingerprint).info() }.getOrNull()
        val stored = paired.withInfo(info)
        _servers.update { list -> list.filterNot { it.baseUrl == stored.baseUrl || it.fingerprint == stored.fingerprint } + stored }
        _online.update { it + (stored.id to (info != null)) }
        save()
        Log.d("Paired with ${stored.kind} server ${stored.name}")
        stored
    }

    /** Whether a server of [kind] answers at [baseUrl] (the pairing greeting, no pairing needed). */
    suspend fun probe(baseUrl: String, kind: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { ServerClient(baseHttp, identity, baseUrl, null).hello().kind == kind }.getOrDefault(false)
    }

    /** Forgets the server here (on the server the device stays listed until removed there). */
    fun remove(id: String) {
        _servers.update { list -> list.filterNot { it.id == id } }
        clients.keys.removeAll { it.startsWith(id) }
        save()
    }

    suspend fun refreshAll() = withContext(Dispatchers.IO) {
        servers.value.forEach { refresh(it) }
    }

    /** Reads /v1/info: reachability, what the server offers, the companion's host. */
    suspend fun refresh(server: PairedServer) = withContext(Dispatchers.IO) {
        val info = runCatching { client(server).info() }
            .onFailure { Log.d("Server ${server.name} not reachable: ${it.message}") }
            .getOrNull()
        _online.update { it + (server.id to (info != null)) }
        if (info != null) {
            val updated = server.withInfo(info)
            if (updated != server) {
                _servers.update { list -> list.map { if (it.id == server.id) updated else it } }
                save()
            }
        }
    }

    private fun PairedServer.withInfo(info: InfoResponse?): PairedServer = if (info == null) this else copy(
        name = info.name, kind = info.kind, hostId = info.hostId, steamGridDb = info.steamGridDb, igdb = info.igdb, permissions = info.permissions,
    )

    private fun load(): List<PairedServer> =
        runCatching { if (file.exists()) json.decodeFromString<List<PairedServer>>(file.readText()) else emptyList() }
            .onFailure {
                // Kept aside: the next save would otherwise overwrite it with no servers at all.
                Log.w("Paired servers unreadable, kept as ${file.name}.broken", it)
                runCatching { java.nio.file.Files.copy(file.toPath(), File(file.parentFile, file.name + ".broken").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
            }
            .getOrDefault(emptyList())

    private fun save() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(servers.value))
        // renameTo doesn't replace an existing file on Windows: the change would be lost.
        java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }
}
