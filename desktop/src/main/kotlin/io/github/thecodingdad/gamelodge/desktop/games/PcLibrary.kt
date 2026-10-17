package io.github.thecodingdad.gamelodge.desktop.games

import io.github.thecodingdad.gamelodge.resources.*
import io.github.thecodingdad.gamelodge.data.local.LocalApp
import io.github.thecodingdad.gamelodge.data.local.LocalAppSource
import io.github.thecodingdad.gamelodge.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
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
    /** Sources that are always on (emulated games, games added by hand). */
    private val others: List<GameScanner> = emptyList(),
    /** Start commands the user changed (entry id → override). */
    private val overrides: () -> Map<String, LaunchOverride> = { emptyMap() },
) : LocalAppSource {
    private val stores: List<GameScanner> = listOf(
        SteamScanner(http, account = steam), EpicScanner(), GogScanner(), XboxScanner(), EaScanner(), UbisoftScanner(), BattleNetScanner(), AmazonScanner(),
    )
    private val scanners: List<GameScanner> = stores + others

    /** Games per store at the last scan: (all, installed). */
    private val _counts = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Pair<Int, Int>>>(emptyMap())
    val counts: kotlinx.coroutines.flow.StateFlow<Map<String, Pair<Int, Int>>> = _counts
    private val entries = ConcurrentHashMap<String, GameEntry>()
    /** As scanned, before the user's overrides. */
    private val originals = ConcurrentHashMap<String, GameEntry>()
    private val rescans = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The scanned entry behind a library target's package name (with the user's override). */
    fun entry(id: String): GameEntry? = entries[id]

    /** The entry as the scan found it (defaults of the launch options dialog). */
    fun original(id: String): GameEntry? = originals[id]

    /** The stores in the order the settings list them. */
    val sources: List<String> get() = stores.map { it.source }

    /** Whether the store reports [entry] as installed now (local data only, no online calls). */
    suspend fun installedNow(entry: GameEntry): Boolean = installedNow(entry, http)

    /** Stores whose last scan failed (their games may be missing, not gone). */
    @Volatile var incompleteSources: Set<String> = emptySet()
        private set

    /** Looks again now (e.g. after an install or when a game ended). */
    fun rescan() {
        rescans.tryEmit(Unit)
    }

    /** Stores still being scanned (null: no scan running), for the game sources page and the task list. */
    private val _scanning = kotlinx.coroutines.flow.MutableStateFlow<ScanProgress?>(null)
    val scanning: kotlinx.coroutines.flow.StateFlow<ScanProgress?> = _scanning

    data class ScanProgress(val pending: List<String>, val done: Int, val total: Int)

    override suspend fun scan(): List<LocalApp> = withContext(Dispatchers.IO) {
        val off = disabled()
        val failed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val active = scanners.filter { it.source !in off }
        val pending = java.util.Collections.synchronizedSet(LinkedHashSet(active.map { it.source }))
        fun report() {
            val left = synchronized(pending) { pending.toList() }
            _scanning.value = ScanProgress(left, active.size - left.size, active.size)
        }
        report()
        // Shown as a task only when it takes a moment (the regular rescans are usually instant).
        val task = launch {
            kotlinx.coroutines.delay(TASK_DELAY_MS)
            _scanning.collect { p ->
                if (p == null) return@collect
                io.github.thecodingdad.gamelodge.data.tasks.BackgroundTasks.set(
                    io.github.thecodingdad.gamelodge.data.tasks.BackgroundTask(
                        TASK_ID, io.github.thecodingdad.gamelodge.resources.Res.string.task_pc_scan, done = p.done, total = p.total,
                        detail = p.pending.joinToString { io.github.thecodingdad.gamelodge.desktop.storeNames[it] ?: it },
                    ),
                )
            }
        }
        val found = try {
            coroutineScope {
                active.map { scanner ->
                    async {
                        runCatching { scanner.scan() }
                            .onFailure { Log.w("${scanner.source}: scan failed", it); failed += scanner.source }
                            .getOrDefault(emptyList())
                            .also { if (it.isNotEmpty()) Log.i("${scanner.source}: ${it.size} games (${it.count { g -> g.installed }} installed)") }
                            .also { pending -= scanner.source; report() }
                    }
                }.awaitAll().flatten()
            }
        } finally {
            task.cancel()
            _scanning.value = null
            io.github.thecodingdad.gamelodge.data.tasks.BackgroundTasks.remove(TASK_ID)
        }
        incompleteSources = failed.toSet()
        _counts.value = found.groupBy { it.source }.mapValues { (_, games) -> games.size to games.count { it.installed } }
        val ids = found.map { it.id }.toSet()
        entries.keys.retainAll(ids)
        originals.keys.retainAll(ids)
        val changed = overrides()
        found.forEach { e ->
            originals[e.id] = e
            entries[e.id] = changed[e.id]?.applyTo(e) ?: e
        }
        found.map { entries[it.id] ?: it }.map { e ->
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
        private const val RESCAN_MS = 30 * 60_000L
        private const val TASK_DELAY_MS = 1_000L
        private const val TASK_ID = "pc-scan"

        /** Whether the store reports [entry] as installed now (local data only; also used by the runner). */
        suspend fun installedNow(entry: GameEntry, http: OkHttpClient): Boolean {
            // Playnite games: the program (or ROM's emulator) is there; links always work.
            if (entry.source == "custom" || entry.source == "emulator") {
                val launch = entry.launch ?: return false
                return !java.io.File(launch).isAbsolute || java.io.File(launch).exists()
            }
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
