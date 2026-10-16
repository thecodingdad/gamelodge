package io.github.thecodingdad.moonlightlauncher.desktop.games

import io.github.thecodingdad.moonlightlauncher.data.local.LocalApp
import io.github.thecodingdad.moonlightlauncher.data.local.LocalAppSource
import io.github.thecodingdad.moonlightlauncher.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

/**
 * The PC's own games from every installed store, as the library's local source. The entries are
 * kept for starting them (launch command, folder, process names).
 */
class PcLibrary(
    private val http: OkHttpClient,
    val steam: SteamAccount,
    /** Stores the user switched off. */
    private val disabled: () -> Set<String>,
) : LocalAppSource {
    private val scanners: List<GameScanner> = listOf(
        SteamScanner(http, account = steam), EpicScanner(), GogScanner(), XboxScanner(), EaScanner(), UbisoftScanner(), BattleNetScanner(), AmazonScanner(),
    )

    /** Games per store at the last scan: (all, installed). */
    private val _counts = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Pair<Int, Int>>>(emptyMap())
    val counts: kotlinx.coroutines.flow.StateFlow<Map<String, Pair<Int, Int>>> = _counts
    private val entries = ConcurrentHashMap<String, GameEntry>()
    private val rescans = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The scanned entry behind a library target's package name. */
    fun entry(id: String): GameEntry? = entries[id]

    /** The stores in the order the settings list them. */
    val sources: List<String> get() = scanners.map { it.source }

    /** Whether the store reports [entry] as installed now (local data only, no online calls). */
    suspend fun installedNow(entry: GameEntry): Boolean = installedNow(entry, http)

    /** Stores whose last scan failed (their games may be missing, not gone). */
    @Volatile var incompleteSources: Set<String> = emptySet()
        private set

    /** Looks again now (e.g. after an install or when a game ended). */
    fun rescan() {
        rescans.tryEmit(Unit)
    }

    override suspend fun scan(): List<LocalApp> = withContext(Dispatchers.IO) {
        val off = disabled()
        val failed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val found = coroutineScope {
            scanners.filter { it.source !in off }.map { scanner ->
                async {
                    runCatching { scanner.scan() }
                        .onFailure { Log.w("${scanner.source}: scan failed", it); failed += scanner.source }
                        .getOrDefault(emptyList())
                        .also { if (it.isNotEmpty()) Log.i("${scanner.source}: ${it.size} games (${it.count { g -> g.installed }} installed)") }
                }
            }.awaitAll().flatten()
        }
        incompleteSources = failed.toSet()
        _counts.value = found.groupBy { it.source }.mapValues { (_, games) -> games.size to games.count { it.installed } }
        entries.keys.retainAll(found.map { it.id }.toSet())
        found.forEach { entries[it.id] = it }
        found.map { e ->
            LocalApp(
                packageName = e.id, label = e.name, isGame = e.game, knownGame = e.game,
                installed = e.installed, platform = e.platform, cover = e.covers.firstOrNull(),
            )
        }
    }

    /** Stores change their libraries without telling anyone: look again every few minutes and on request. */
    override fun packageChanges(): Flow<Unit> = merge(
        rescans,
        flow {
            while (true) {
                delay(RESCAN_MS)
                emit(Unit)
            }
        },
    )

    companion object {
        private const val RESCAN_MS = 5 * 60_000L

        /** Whether the store reports [entry] as installed now (local data only; also used by the runner). */
        suspend fun installedNow(entry: GameEntry, http: OkHttpClient): Boolean {
            val scanner: GameScanner? = when (entry.source) {
                "steam" -> SteamScanner(http, includeOwned = false)
                "epic" -> EpicScanner()
                "gog" -> GogScanner()
                "xbox" -> XboxScanner()
                "ea" -> EaScanner()
                "ubisoft" -> UbisoftScanner()
                "battlenet" -> BattleNetScanner()
                "amazon" -> AmazonScanner()
                else -> null
            }
            return runCatching { scanner?.scan().orEmpty().any { it.id == entry.id && it.installed } }.getOrDefault(false)
        }
    }
}
