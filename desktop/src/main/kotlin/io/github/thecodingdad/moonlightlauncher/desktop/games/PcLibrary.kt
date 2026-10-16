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
class PcLibrary(private val http: OkHttpClient) : LocalAppSource {
    private val scanners: List<GameScanner> = listOf(
        SteamScanner(http), EpicScanner(), GogScanner(), XboxScanner(), EaScanner(), UbisoftScanner(), BattleNetScanner(), AmazonScanner(),
    )
    private val entries = ConcurrentHashMap<String, GameEntry>()
    private val rescans = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The scanned entry behind a library target's package name. */
    fun entry(id: String): GameEntry? = entries[id]

    /** Whether the store reports [entry] as installed now (local data only, no online calls). */
    suspend fun installedNow(entry: GameEntry): Boolean {
        val scanner = if (entry.source == "steam") SteamScanner(http, includeOwned = false) else scanners.firstOrNull { it.source == entry.source }
        return runCatching { scanner?.scan().orEmpty().any { it.id == entry.id && it.installed } }.getOrDefault(false)
    }

    /** Looks again now (e.g. after an install or when a game ended). */
    fun rescan() {
        rescans.tryEmit(Unit)
    }

    override suspend fun scan(): List<LocalApp> = withContext(Dispatchers.IO) {
        val found = coroutineScope {
            scanners.map { scanner ->
                async {
                    runCatching { scanner.scan() }
                        .onFailure { Log.w("${scanner.source}: scan failed", it) }
                        .getOrDefault(emptyList())
                        .also { if (it.isNotEmpty()) Log.i("${scanner.source}: ${it.size} games (${it.count { g -> g.installed }} installed)") }
                }
            }.awaitAll().flatten()
        }
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

    private companion object {
        const val RESCAN_MS = 5 * 60_000L
    }
}
