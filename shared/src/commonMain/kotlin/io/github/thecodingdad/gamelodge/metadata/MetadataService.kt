package io.github.thecodingdad.gamelodge.metadata

import io.github.thecodingdad.gamelodge.resources.*
import io.github.thecodingdad.gamelodge.data.db.ItemKind
import io.github.thecodingdad.gamelodge.data.db.LauncherDatabase
import io.github.thecodingdad.gamelodge.data.db.LibraryItemEntity
import io.github.thecodingdad.gamelodge.data.db.clearedMetadata
import io.github.thecodingdad.gamelodge.data.db.LibraryEntry
import io.github.thecodingdad.gamelodge.data.db.MetaField
import io.github.thecodingdad.gamelodge.data.db.MetadataState
import io.github.thecodingdad.gamelodge.data.db.PlatformSource
import io.github.thecodingdad.gamelodge.data.library.PlatformResolver
import io.github.thecodingdad.gamelodge.data.settings.SecretStore
import io.github.thecodingdad.gamelodge.data.settings.SettingsRepository
import io.github.thecodingdad.gamelodge.data.settings.TileFormat
import io.github.thecodingdad.gamelodge.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

enum class MetadataSource { IGDB, STEAMGRIDDB }

/** A search result shown in the "fix metadata" dialog. */
data class MetadataCandidate(
    val source: MetadataSource,
    val id: Long,
    val name: String,
    val year: Int?,
    val coverUrl: String?,
    val score: Float,
)

data class MetadataProgress(val done: Int, val total: Int)

/** The images of an entry; the animated cover / background are chosen on their own. */
enum class ArtworkKind(val field: MetaField, val animated: Boolean = false, val correction: String?) {
    COVER(MetaField.COVER, correction = "cover"),
    ANIMATED_COVER(MetaField.ANIMATED_COVER, animated = true, correction = "animatedCover"),
    HERO(MetaField.HERO, correction = "hero"),
    ANIMATED_HERO(MetaField.ANIMATED_HERO, animated = true, correction = "animatedHero"),
    LOGO(MetaField.LOGO, correction = "logo"),
    ICON(MetaField.ICON, correction = "icon"),
    /** Tiles for the landscape / square tile format (the launcher's own: no companion correction). */
    LANDSCAPE(MetaField.LANDSCAPE, correction = null),
    SQUARE(MetaField.SQUARE, correction = null);

    /** Cover-shaped or background-shaped (animated ones share the still one's shape and sources). */
    val base: ArtworkKind get() = when (this) {
        ANIMATED_COVER -> COVER
        ANIMATED_HERO -> HERO
        else -> this
    }
}

/** Where an image to choose from comes from. */
enum class ArtworkSource { HOST, ROM, COMPANION, IGDB, STEAMGRIDDB, TV_BANNER, GENERATED }

/** One image in the picker: [url] is stored, [thumb] previewed. */
data class ArtworkOption(
    val url: String, val thumb: String, val source: ArtworkSource, val animated: Boolean = false, val bytes: Long? = null,
    /** More about the origin (host name, the store behind the companion's image). */
    val detail: String? = null,
)

/**
 * Looks up descriptions (IGDB) and artwork (IGDB, SteamGridDB) for games. Confident matches are
 * applied automatically; uncertain ones are applied but flagged REVIEW; a hand-picked match is
 * LOCKED, hand-edited fields and images (manual) are never touched again. Order per field:
 * texts IGDB; cover IGDB, then SteamGridDB; background SteamGridDB, then IGDB; logo and icon
 * SteamGridDB; animated cover / background SteamGridDB (only while switched on). Host games
 * get all of it from their companion, which also takes the store's data first.
 */
class MetadataService(
    private val db: LauncherDatabase,
    private val secrets: SecretStore,
    private val settings: SettingsRepository,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val servers: io.github.thecodingdad.gamelodge.data.companion.ServerRepository? = null,
) {
    /**
     * Called after a hand correction (the library sends it on to the host's companion);
     * [reset] names the fields handed back to automatic (HostGameCorrection.reset).
     */
    var onCorrected: (suspend (itemId: String, reset: List<String>) -> Unit)? = null

    /** Full refresh by hand: the library reads the host games (companion data) again. */
    var onFullRefresh: (suspend () -> Unit)? = null

    /** The host game behind an item, when its companion delivers it (images to choose from). */
    var companionGame: (suspend (itemId: String) -> io.github.thecodingdad.gamelodge.data.companion.HostGame?)? = null

    private suspend fun corrected(itemId: String, reset: List<String> = emptyList()) {
        // While the metadata editor is open the changes are a draft: sent with "Save".
        if (editing?.id == itemId) return
        runCatching { onCorrected?.invoke(itemId, reset) }.onFailure { Log.w("Correction not passed on", it) }
    }

    /** The item as it was when the metadata editor opened (its changes are a draft until saved). */
    @Volatile private var editing: LibraryItemEntity? = null

    /** The metadata editor opens: everything changed from here on can be cancelled. */
    suspend fun beginEdit(itemId: String) = withContext(Dispatchers.IO) {
        editing = dao.getItem(itemId)
    }

    /** "Save": the changes stay and go to the companion; replaced own images are deleted. */
    suspend fun commitEdit() = withContext(Dispatchers.IO) {
        val before = editing ?: return@withContext
        editing = null
        val now = dao.getItem(before.id) ?: return@withContext
        ownFiles(before).minus(ownFiles(now)).forEach { java.io.File(it).delete() }
        if (now == before) return@withContext
        // Hand-picked before, automatic now: the companion follows its own choice again.
        val reset = (before.manual - now.manual).mapNotNull { field ->
            ArtworkKind.entries.firstOrNull { it.field == field }?.correction ?: when (field) {
                MetaField.TITLE -> "name"
                MetaField.DESCRIPTION -> "summary"
                MetaField.GENRES -> "genres"
                MetaField.DEVELOPER -> "developers"
                MetaField.PUBLISHER -> "publishers"
                MetaField.RELEASE -> "release"
                else -> null
            }
        }
        corrected(now.id, reset)
    }

    /** "Cancel": the item goes back to how it was when the editor opened; new own images are deleted. */
    suspend fun cancelEdit() = withContext(Dispatchers.IO) {
        val before = editing ?: return@withContext
        editing = null
        val now = dao.getItem(before.id)
        if (now != null) ownFiles(now).minus(ownFiles(before)).forEach { java.io.File(it).delete() }
        if (now != before) dao.upsertItem(before)
    }

    private fun ownFiles(i: LibraryItemEntity) =
        listOfNotNull(i.coverUrl, i.animatedCoverUrl, i.heroUrl, i.animatedHeroUrl, i.logoUrl, i.iconUrl, i.tileUrl, i.squareUrl).filter { it.contains("/own-artwork/") }.toSet()

    private val dao = db.libraryDao()
    private val mutex = Mutex()

    private val _progress = MutableStateFlow<MetadataProgress?>(null)
    val progress: StateFlow<MetadataProgress?> = _progress.asStateFlow()

    private fun setProgress(p: MetadataProgress?) {
        _progress.value = p
        val t = io.github.thecodingdad.gamelodge.data.tasks.BackgroundTasks
        if (p == null) t.remove(TASK_ID)
        else t.set(io.github.thecodingdad.gamelodge.data.tasks.BackgroundTask(TASK_ID, io.github.thecodingdad.gamelodge.resources.Res.string.task_metadata, done = p.done, total = p.total))
    }

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    @Volatile private var sources: List<String> = io.github.thecodingdad.gamelodge.data.settings.LauncherSettings().metadataSources

    /** Animated artwork is wanted (Display settings): only then it is looked up. */
    @Volatile private var animated: Boolean = true

    /** Landscape / square tiles are shown somewhere (tile format setting): only then they are looked up. */
    @Volatile private var landscapeTiles: Boolean = false
    @Volatile private var squareTiles: Boolean = false

    init {
        scope.launch {
            settings.settings.collect {
                sources = it.metadataSources
                val switchedOn = it.animatedArtwork && !animated
                animated = it.animatedArtwork
                if (switchedOn && !it.animatedBackfillDone) launch(Dispatchers.IO) { backfillAnimated() }
                val formats = setOf(it.homeTileFormat, it.libraryTileFormat)
                val landscapeOn = TileFormat.LANDSCAPE in formats && !landscapeTiles
                val squareOn = TileFormat.SQUARE in formats && !squareTiles
                landscapeTiles = TileFormat.LANDSCAPE in formats
                squareTiles = TileFormat.SQUARE in formats
                if ((landscapeOn && !it.landscapeBackfillDone) || (squareOn && !it.squareBackfillDone)) launch(Dispatchers.IO) { backfillTiles() }
            }
        }
    }

    /** Configured sources in the user's order (see [MetadataSources]). */
    fun sourceEntries(): List<MetadataSources.Entry> = MetadataSources.resolve(
        sources, servers?.servers?.value.orEmpty(),
        hasSgdb = !secrets.get(SecretStore.Keys.SGDB_API_KEY).isNullOrBlank(),
        hasIgdb = !secrets.get(SecretStore.Keys.IGDB_CLIENT_ID).isNullOrBlank() && !secrets.get(SecretStore.Keys.IGDB_CLIENT_SECRET).isNullOrBlank(),
    )

    /** Whether the source [id] is configured and switched on (a switched-off companion isn't used at all). */
    fun isEnabled(id: String): Boolean = sourceEntries().any { it.id == id && it.enabled }

    /** Routes for one provider in the user's order: its own key ([own]) and the servers offering it. */
    private fun routes(own: String, provider: (io.github.thecodingdad.gamelodge.data.companion.PairedServer) -> Boolean): List<MetadataRoute> =
        sourceEntries().filter { it.enabled }.mapNotNull { entry ->
            when {
                entry.id == own -> MetadataRoute.Own
                entry.server != null && provider(entry.server) -> MetadataRoute.Via(servers!!.client(entry.server))
                else -> null
            }
        }

    val sgdb = SteamGridDbClient(http, { secrets.get(SecretStore.Keys.SGDB_API_KEY) }, routes = { routes(MetadataSources.SGDB) { it.steamGridDb } })
    val igdb = IgdbClient(http, routes = { routes(MetadataSources.IGDB) { it.igdb } }, credentials = object : IgdbCredentials {
        override val clientId get() = secrets.get(SecretStore.Keys.IGDB_CLIENT_ID)
        override val clientSecret get() = secrets.get(SecretStore.Keys.IGDB_CLIENT_SECRET)
        override var token: String?
            get() = secrets.get(SecretStore.Keys.IGDB_ACCESS_TOKEN)
            set(value) = secrets.put(SecretStore.Keys.IGDB_ACCESS_TOKEN, value)
        override var tokenExpiresAt: Long
            get() = secrets.get("igdb_token_expiry")?.toLongOrNull() ?: 0
            set(value) = secrets.put("igdb_token_expiry", value.toString())
    })

    val isConfigured: Boolean get() = sgdb.isConfigured || igdb.isConfigured

    /** Watches the library and processes new games in the background. */
    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            dao.observeAll()
                .map { list -> list.count { it.item.kind == ItemKind.GAME && it.item.metadataState == MetadataState.NONE } }
                .debounce(2_000)
                .collect { pending -> if (pending > 0) refreshPending() }
        }
        scope.launch(Dispatchers.IO) {
            delay(10_000)
            runCatching { reevaluatePlatforms() }.onFailure { Log.w("Platform re-evaluation failed", it) }
            runCatching { rematchUnofficial() }.onFailure { Log.w("Re-matching mods failed", it) }
            runCatching { backfillPlatforms() }.onFailure { Log.w("Platform backfill failed", it) }
            runCatching { backfillDetails() }.onFailure { Log.w("Metadata details backfill failed", it) }
            runCatching { refreshTimesToBeat() }.onFailure { Log.w("Time to beat lookup failed", it) }
            runCatching { fillArtworkGaps() }.onFailure { Log.w("Artwork gap fill failed", it) }
            runCatching { backfillAnimated() }.onFailure { Log.w("Animated artwork backfill failed", it) }
            runCatching { backfillTiles() }.onFailure { Log.w("Tile backfill failed", it) }
            runCatching { dropAppMetadata() }.onFailure { Log.w("App metadata cleanup failed", it) }
            // Also catches games matched before IGDB keys were entered.
            refreshPending()
        }
    }

    /** Matches from before platforms were stored: fetch just the platforms, 100 games per request. */
    private suspend fun backfillPlatforms() {
        if (!igdb.isConfigured) return
        val entries = dao.observeAll().first().filter {
            it.item.igdbId != null && (it.item.platformSource == null || it.item.platformSource == PlatformSource.SUGGESTED)
        }.associateBy { it.item.igdbId!! }
        entries.keys.chunked(100).forEach { ids ->
            igdb.platforms(ids).forEach { game ->
                val entry = entries[game.id] ?: return@forEach
                val platform = entry.igdbPlatform(game)
                if (PlatformResolver.mayReplace(entry.item, PlatformSource.IGDB, platform)) {
                    dao.setPlatform(entry.item.id, platform, PlatformSource.IGDB)
                }
            }
            delay(300)
        }
    }

    /**
     * Times to beat (main story, completely) for games whose IGDB match has none yet, 100 per
     * request; games without times on IGDB are asked again after a while (IGDB adds them over time).
     */
    private suspend fun refreshTimesToBeat() {
        if (!igdb.isConfigured) return
        val now = System.currentTimeMillis()
        val due = dao.observeAll().first().map { it.item }.filter { item ->
            val id = item.igdbId ?: return@filter false
            item.timeToBeatIgdbId != id ||
                (item.timeToBeatMain == null && item.timeToBeatComplete == null && now - (item.timeToBeatCheckedAt ?: 0L) > TIME_TO_BEAT_RECHECK_MS)
        }
        due.groupBy { it.igdbId!! }.entries.chunked(100).forEach { chunk ->
            val times = igdb.timesToBeat(chunk.map { it.key }).associateBy { it.game_id }
            chunk.forEach { (igdbId, items) ->
                val t = times[igdbId]
                items.forEach { dao.setTimeToBeat(it.id, t?.normally?.takeIf { s -> s > 0 }, t?.completely?.takeIf { s -> s > 0 }, igdbId, now) }
            }
            delay(300)
        }
    }

    /** Matches from before publisher, series, features and age ratings were stored. */
    private suspend fun backfillDetails() {
        if (!igdb.isConfigured) return
        val items = dao.observeAll().first().map { it.item }.filter { it.igdbId != null && it.metadataVersion < 1 }.associateBy { it.igdbId!! }
        items.keys.chunked(100).forEach { ids ->
            igdb.details(ids).forEach { game ->
                val item = items[game.id] ?: return@forEach
                // Only the new fields: everything else may have been chosen differently since.
                val fresh = dao.getItem(item.id) ?: return@forEach
                dao.upsertItem(fresh.withIgdbDetails(game))
            }
            delay(300)
        }
    }

    private fun LibraryEntry.igdbPlatform(game: IgdbGame) = PlatformResolver.fromIgdb(
        game.platforms.map { it.name }, playsLocally = localTarget != null,
    )

    /**
     * One-time re-evaluation with the stricter rules (v1): guessed platforms ("streamed = PC")
     * and IGDB picks among several platforms are removed; manual and Playnite ones stay.
     */
    private suspend fun reevaluatePlatforms() {
        if (settings.current().platformRule >= PLATFORM_RULE) return
        val entries = dao.observeAll().first()
        entries.filter { it.item.platformSource == PlatformSource.SUGGESTED }.forEach { entry ->
            val suggestion = PlatformResolver.suggest(entry)
            if (suggestion != entry.item.platform) dao.setPlatform(entry.item.id, suggestion, suggestion?.let { PlatformSource.SUGGESTED })
        }
        val fromIgdb = entries.filter { it.item.platformSource == PlatformSource.IGDB && it.item.igdbId != null }.associateBy { it.item.igdbId!! }
        if (fromIgdb.isNotEmpty()) {
            if (!igdb.isConfigured) return // try again once the keys are there
            fromIgdb.keys.chunked(100).forEach { ids ->
                igdb.platforms(ids).forEach { game ->
                    val entry = fromIgdb[game.id] ?: return@forEach
                    val platform = entry.igdbPlatform(game)
                    if (platform != entry.item.platform) dao.setPlatform(entry.item.id, platform, platform?.let { PlatformSource.IGDB })
                }
                delay(300)
            }
        }
        settings.update { it.copy(platformRule = PLATFORM_RULE) }
    }

    private suspend fun platformFor(item: LibraryItemEntity, game: IgdbGame): Pair<String, PlatformSource>? {
        val entry = dao.observeEntry(item.id).first() ?: return null
        val platform = entry.igdbPlatform(game)
        return if (PlatformResolver.mayReplace(item, PlatformSource.IGDB, platform)) platform!! to PlatformSource.IGDB else null
    }

    /** Processes every game that was never looked up. */
    fun refreshPending() {
        scope.launch(Dispatchers.IO) {
            if (!isConfigured || !settings.current().autoMetadata) return@launch
            if (!mutex.tryLock()) return@launch
            try {
                val pending = dao.getItemsWithMetadataState(listOf(MetadataState.NONE)).filter { it.kind == ItemKind.GAME }
                _lastError.value = null
                // Games matched before IGDB keys existed (e.g. SteamGridDB only) get their IGDB text once.
                val withoutIgdb = if (igdb.isConfigured) {
                    dao.getItemsWithMetadataState(listOf(MetadataState.OK, MetadataState.REVIEW, MetadataState.NOT_FOUND, MetadataState.LOCKED))
                        .filter { it.kind == ItemKind.GAME && it.igdbId == null && it.metadataVersion < IGDB_LOOKUP_VERSION }
                } else emptyList()
                val total = pending.size + withoutIgdb.size
                pending.forEachIndexed { index, item ->
                    setProgress(MetadataProgress(index, total))
                    try {
                        lookupAndApply(item)
                    } catch (e: MetadataAuthException) {
                        _lastError.value = e.message
                        return@launch
                    } catch (e: Exception) {
                        Log.w("Metadata lookup for ${item.title} failed", e)
                    }
                    delay(250) // stay well below IGDB's 4 requests/s
                }
                withoutIgdb.forEachIndexed { index, item ->
                    setProgress(MetadataProgress(pending.size + index, total))
                    try {
                        addIgdb(item)
                    } catch (e: MetadataAuthException) {
                        _lastError.value = e.message
                        return@launch
                    } catch (e: Exception) {
                        Log.w("IGDB lookup for ${item.title} failed", e)
                    }
                    delay(250)
                }
                runCatching { refreshTimesToBeat() }.onFailure { Log.w("Time to beat lookup failed", it) }
            } finally {
                setProgress(null)
                mutex.unlock()
            }
        }
    }

    /**
     * The only full refresh, by hand: forgets automatic matches (not locked ones, not hand-picked
     * fields) and fetches everything again; host games first get their companion's data.
     */
    suspend fun resetAutomatic(includeNotFound: Boolean = true) = withContext(Dispatchers.IO) {
        val states = buildList {
            add(MetadataState.OK); add(MetadataState.REVIEW)
            if (includeNotFound) add(MetadataState.NOT_FOUND)
        }
        dao.getItemsWithMetadataState(states).forEach { dao.upsertItem(it.clearedMetadata()) }
        runCatching { onFullRefresh?.invoke() }.onFailure { Log.w("Host games not read again", it) }
        refreshPending()
    }

    /**
     * IGDB text for a game that already has a (SteamGridDB) match: artwork stays. Uncertain
     * hits only fill games nothing was found for (they go to the review list).
     */
    private suspend fun addIgdb(item: LibraryItemEntity) {
        val query = searchQuery(item)
        val best = igdbBest(query)
        val fresh = dao.getItem(item.id) ?: return
        val confident = best != null && best.second >= MatchScorer.AUTO_ACCEPT
        val usable = best != null && best.second >= MatchScorer.REVIEW && fresh.metadataState == MetadataState.NOT_FOUND
        if (best == null || !(confident || usable)) {
            // Tried once: don't search again on every start ("fetch all again" retries).
            dao.upsertItem(fresh.copy(metadataVersion = IGDB_LOOKUP_VERSION))
            return
        }
        val game = best.first
        var updated = fresh.withIgdb(game).withMissingArtwork(game).withIgdbHero(game).copy(metadataVersion = ART_FILL_VERSION)
        platformFor(fresh, game)?.let { updated = updated.copy(platform = it.first, platformSource = it.second) }
        val state = when (fresh.metadataState) {
            MetadataState.NOT_FOUND -> if (confident) MetadataState.OK else MetadataState.REVIEW
            else -> fresh.metadataState
        }
        dao.upsertItem(updated.copy(metadataState = state, matchConfidence = maxOf(fresh.matchConfidence, best.second)))
    }

    /** Same check for the SteamGridDB key. */
    suspend fun testSgdb(): Boolean? = withContext(Dispatchers.IO) {
        if (!sgdb.hasOwnKey) return@withContext null
        try {
            sgdb.searchOwn("Mario")
            true
        } catch (e: MetadataAuthException) {
            false
        } catch (e: Exception) {
            null
        }
    }

    /**
     * A game's best score over its main, alternative and localized titles. Mods and ROM hacks
     * rank lower (they often carry the exact title of the real game); among equal names the
     * better-known game wins.
     */
    private fun scoreAll(query: String, game: IgdbGame): Float {
        val name = game.allNames.maxOf { MatchScorer.score(query, it) }
        val unofficial = if (game.isUnofficial) 0.25f else 0f
        val known = minOf(game.total_rating_count ?: 0, 1000) / 100_000f // tie-breaker only (≤ 0.01)
        return (name - unofficial + known).coerceIn(0f, 1f)
    }

    /**
     * Best IGDB hit for a title: the regular search first, then alternative / localized titles
     * when that finds nothing confident (e.g. German titles from Playnite).
     */
    private fun searchQuery(item: LibraryItemEntity) =
        item.metadataQuery ?: io.github.thecodingdad.gamelodge.data.library.TitleNormalizer.searchTerm(item.title)

    private suspend fun igdbBest(query: String): Pair<IgdbGame, Float>? {
        val regular = igdb.search(query).map { it to scoreAll(query, it) }.maxByOrNull { it.second }
        if (regular != null && regular.second >= MatchScorer.AUTO_ACCEPT) return regular
        delay(250)
        val other = igdb.searchOtherTitles(query).map { it to scoreAll(query, it) }.maxByOrNull { it.second }
        return listOfNotNull(regular, other).maxByOrNull { it.second }
    }

    /** Checks the IGDB keys with one tiny request: true = works, false = rejected, null = not set / offline. */
    suspend fun testIgdb(): Boolean? = withContext(Dispatchers.IO) {
        if (!igdb.hasOwnCredentials) return@withContext null
        try {
            igdb.searchOwn("Mario")
            true
        } catch (e: MetadataAuthException) {
            false
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun lookupAndApply(item: LibraryItemEntity) {
        val query = searchQuery(item)
        val igdbBest = if (igdb.isConfigured) {
            igdbBest(query)
        } else null
        val sgdbBest = if (sgdb.isConfigured) {
            sgdb.search(igdbBest?.first?.name ?: query).map { it to MatchScorer.score(query, it.name) }.maxByOrNull { it.second }
        } else null

        // The lookups take a while: apply the result to the row as it is now (play time, favourite
        // or a restored backup may have changed meanwhile) and leave games that got metadata
        // from elsewhere alone.
        val fresh = dao.getItem(item.id)?.takeIf { it.metadataState == MetadataState.NONE } ?: return
        val confidence = igdbBest?.second ?: sgdbBest?.second ?: 0f
        if (confidence < MatchScorer.REVIEW) {
            dao.upsertItem(fresh.copy(metadataState = MetadataState.NOT_FOUND, matchConfidence = confidence))
            return
        }
        val state = if (confidence >= MatchScorer.AUTO_ACCEPT) MetadataState.OK else MetadataState.REVIEW
        val withText = igdbBest?.first?.let { game ->
            val platform = platformFor(fresh, game)
            fresh.withIgdb(game).let { if (platform != null) it.copy(platform = platform.first, platformSource = platform.second) else it }
        } ?: fresh
        val withArt = sgdbBest?.first?.takeIf { sgdbBest.second >= MatchScorer.REVIEW }
            ?.let { withText.withSgdbArtwork(it.id) } ?: withText
        val complete = igdbBest?.first?.let { withArt.withMissingArtwork(it).withIgdbHero(it).copy(metadataVersion = ART_FILL_VERSION) } ?: withArt
        dao.upsertItem(complete.copy(metadataState = state, matchConfidence = confidence))
    }

    /** Manual search for the correction dialog (IGDB preferred, SteamGridDB otherwise). */
    suspend fun search(query: String): List<MetadataCandidate> = withContext(Dispatchers.IO) {
        if (igdb.isConfigured) {
            (igdb.search(query, limit = 12) + igdb.searchOtherTitles(query, limit = 6)).distinctBy { it.id }.map {
                MetadataCandidate(MetadataSource.IGDB, it.id, it.name, it.releaseYear, it.coverUrl, scoreAll(query, it))
            }
        } else if (sgdb.isConfigured) {
            sgdb.search(query).take(12).map {
                MetadataCandidate(MetadataSource.STEAMGRIDDB, it.id, it.name, null, null, MatchScorer.score(query, it.name))
            }
        } else emptyList()
    }.sortedByDescending { it.score } // best match first

    /** Applies a user-chosen candidate and locks the item against automatic changes. */
    suspend fun applyCandidate(itemId: String, candidate: MetadataCandidate) = withContext(Dispatchers.IO) {
        val item = dao.getItem(itemId) ?: return@withContext
        var updated = item.clearedMetadata()
        when (candidate.source) {
            MetadataSource.IGDB -> {
                val game = igdb.byId(candidate.id)
                if (game != null) {
                    updated = updated.withIgdb(game, replace = true)
                    platformFor(item, game)?.let { updated = updated.copy(platform = it.first, platformSource = it.second) }
                }
                if (sgdb.isConfigured) {
                    sgdb.search(candidate.name).maxByOrNull { MatchScorer.score(candidate.name, it.name) }
                        ?.let { updated = updated.withSgdbArtwork(it.id) }
                }
                if (game != null) updated = updated.withIgdbHero(game)
            }
            MetadataSource.STEAMGRIDDB -> updated = updated.withSgdbArtwork(candidate.id)
        }
        dao.upsertItem(updated.copy(metadataState = MetadataState.LOCKED, matchConfidence = 1f, metadataQuery = candidate.name))
        corrected(itemId)
        // The new match's times to beat.
        runCatching { refreshTimesToBeat() }.onFailure { Log.w("Time to beat lookup failed", it) }
    }

    /**
     * Everything to choose from for one kind of image: the companion's (host games), IGDB's
     * (cover; artworks and screenshots as background) and SteamGridDB's (animated ones for cover
     * and background while animated artwork is switched on).
     */
    suspend fun artworkOptions(item: LibraryItemEntity, kind: ArtworkKind, query: String? = null): List<ArtworkOption> = withContext(Dispatchers.IO) {
        val result = mutableListOf<ArtworkOption>()
        val base = kind.base
        // A search term typed in the picker: its first hits instead of the matched game.
        val search = query?.trim()?.takeIf { it.isNotEmpty() && it != searchQuery(item) }
        val tile = kind == ArtworkKind.LANDSCAPE || kind == ArtworkKind.SQUARE
        if (!tile) runCatching { companionGame?.invoke(item.id) }.getOrNull()?.let { game ->
            val url = when (kind) {
                ArtworkKind.COVER -> game.cover
                ArtworkKind.ANIMATED_COVER -> game.animatedCover
                ArtworkKind.HERO -> game.hero
                ArtworkKind.ANIMATED_HERO -> game.animatedHero
                ArtworkKind.LOGO -> game.logo
                ArtworkKind.ICON -> game.icon
                ArtworkKind.LANDSCAPE, ArtworkKind.SQUARE -> null
            }
            url?.takeIf { it.startsWith("https://") }?.let { result += ArtworkOption(it, it, ArtworkSource.COMPANION, animated = kind.animated, detail = game.sourceLabel) }
        }
        // IGDB has still images only: its cover, and artworks / screenshots as background.
        if (igdb.isConfigured && !kind.animated && (base == ArtworkKind.COVER || base == ArtworkKind.HERO)) {
            runCatching { (if (search == null) item.igdbId?.let { igdb.byId(it) } else null) ?: igdb.search(search ?: searchQuery(item), limit = 1).firstOrNull() }.getOrNull()?.let { game ->
                if (base == ArtworkKind.COVER) game.coverUrl?.let { result += ArtworkOption(it, it.replace("t_cover_big_2x", "t_cover_big"), ArtworkSource.IGDB) }
                else (game.artworks + game.screenshots).mapNotNull { it.image_id?.takeIf { id -> id.isNotEmpty() } }.forEach { id ->
                    result += ArtworkOption(
                        "https://images.igdb.com/igdb/image/upload/t_1080p/$id.jpg",
                        "https://images.igdb.com/igdb/image/upload/t_screenshot_med/$id.jpg", ArtworkSource.IGDB,
                    )
                }
            }
        }
        if (sgdb.isConfigured) {
            runCatching {
                val gameId = (if (search == null) item.sgdbId else null) ?: sgdb.search(search ?: searchQuery(item)).firstOrNull()?.id ?: return@runCatching
                val type = when (base) {
                    ArtworkKind.HERO -> SgdbAssetType.HERO
                    ArtworkKind.LOGO -> SgdbAssetType.LOGO
                    ArtworkKind.ICON -> SgdbAssetType.ICON
                    else -> SgdbAssetType.GRID
                }
                if (tile) {
                    sgdb.assets(gameId, type, landscape = kind == ArtworkKind.LANDSCAPE, square = kind == ArtworkKind.SQUARE)
                        .forEach { result += ArtworkOption(it.url, it.previewImage, ArtworkSource.STEAMGRIDDB) }
                } else if (kind.animated) {
                    sgdb.assets(gameId, type, animated = true).filter { it.isPlayableAnimation }
                        .forEach { result += ArtworkOption(it.url, it.previewImage, ArtworkSource.STEAMGRIDDB, animated = true) }
                } else {
                    sgdb.assets(gameId, type).forEach { result += ArtworkOption(it.url, it.previewImage, ArtworkSource.STEAMGRIDDB) }
                }
            }.onFailure { Log.w("SteamGridDB artwork for ${item.title} failed", it) }
        }
        // In the order of the sources for this kind (background: SteamGridDB before IGDB).
        val rank = if (base == ArtworkKind.HERO) listOf(ArtworkSource.COMPANION, ArtworkSource.STEAMGRIDDB, ArtworkSource.IGDB)
        else listOf(ArtworkSource.COMPANION, ArtworkSource.IGDB, ArtworkSource.STEAMGRIDDB)
        result.distinctBy { it.url }.sortedBy { rank.indexOf(it.source) }.map { o ->
            // Animated files are large: their size helps choosing (from the server's answer, when it tells).
            if (o.animated) o.copy(bytes = sizeOf(o.url)) else o
        }
    }

    private fun sizeOf(url: String): Long? = runCatching {
        http.newCall(okhttp3.Request.Builder().url(url).head().build()).execute().use { r ->
            r.header("Content-Length")?.toLongOrNull()?.takeIf { r.isSuccessful && it > 0 }
        }
    }.getOrNull()

    /** A hand-picked image: protected against automatic changes and sent to the companion. */
    suspend fun setArtwork(itemId: String, kind: ArtworkKind, option: ArtworkOption) = setOwnArtwork(itemId, kind, option.url)

    /** An image by hand: an address or an own file (own-artwork; only addresses reach the companion). */
    suspend fun setOwnArtwork(itemId: String, kind: ArtworkKind, url: String) = withContext(Dispatchers.IO) {
        val item = dao.getItem(itemId) ?: return@withContext
        dropOwnFile(item, kind, keep = url)
        dao.upsertItem(item.withArtwork(kind, url).let { it.withManual(it.manual + kind.field) })
        corrected(itemId)
    }

    /** An own image file that is replaced is not needed any more. */
    private fun dropOwnFile(item: LibraryItemEntity, kind: ArtworkKind, keep: String? = null) {
        val old = when (kind) {
            ArtworkKind.COVER -> item.coverUrl
            ArtworkKind.ANIMATED_COVER -> item.animatedCoverUrl
            ArtworkKind.HERO -> item.heroUrl
            ArtworkKind.ANIMATED_HERO -> item.animatedHeroUrl
            ArtworkKind.LOGO -> item.logoUrl
            ArtworkKind.ICON -> item.iconUrl
            ArtworkKind.LANDSCAPE -> item.tileUrl
            ArtworkKind.SQUARE -> item.squareUrl
        } ?: return
        // In the editor the old file may still come back with "Cancel" (cleaned up on save).
        if (old != keep && old.contains("/own-artwork/") && editing?.id != item.id) java.io.File(old).delete()
    }

    /** No image of [kind] by hand (an animated one: the still one shows instead); automatic lookups leave it empty. */
    suspend fun clearArtwork(itemId: String, kind: ArtworkKind) = withContext(Dispatchers.IO) {
        val item = dao.getItem(itemId) ?: return@withContext
        dropOwnFile(item, kind)
        dao.upsertItem(item.withArtwork(kind, null).let { it.withManual(it.manual + kind.field) })
        corrected(itemId)
    }

    /** Hands one image back to automatic: looked up again in the fixed order. */
    suspend fun resetArtwork(itemId: String, kind: ArtworkKind) = withContext(Dispatchers.IO) {
        val item = dao.getItem(itemId) ?: return@withContext
        dropOwnFile(item, kind)
        var auto = item.withManual(item.manual - kind.field).withArtwork(kind, null)
        runCatching {
            // In the order of the sources: IGDB's cover, SteamGridDB, then IGDB's background.
            val game = item.igdbId?.takeIf { igdb.isConfigured }?.let { igdb.byId(it) }
            if (game != null && kind == ArtworkKind.COVER) auto = auto.copy(coverUrl = auto.coverUrl ?: game.coverUrl)
            item.sgdbId?.takeIf { sgdb.isConfigured }?.let { auto = auto.withSgdbArtwork(it) }
            if (game != null) auto = auto.withIgdbHero(game)
        }.onFailure { Log.w("Artwork for ${item.title} not looked up again", it) }
        dao.upsertItem(auto)
        corrected(itemId, listOfNotNull(kind.correction))
    }

    private fun LibraryItemEntity.withArtwork(kind: ArtworkKind, url: String?) = when (kind) {
        ArtworkKind.COVER -> copy(coverUrl = url)
        ArtworkKind.ANIMATED_COVER -> copy(animatedCoverUrl = url)
        ArtworkKind.HERO -> copy(heroUrl = url)
        ArtworkKind.ANIMATED_HERO -> copy(animatedHeroUrl = url)
        ArtworkKind.LOGO -> copy(logoUrl = url)
        ArtworkKind.ICON -> copy(iconUrl = url)
        ArtworkKind.LANDSCAPE -> copy(tileUrl = url)
        ArtworkKind.SQUARE -> copy(squareUrl = url)
    }

    private fun LibraryItemEntity.withManual(fields: Set<MetaField>) =
        copy(manualFields = fields.joinToString(",") { it.name }.ifEmpty { null })

    /** Marks a REVIEW match as correct. */
    suspend fun confirm(itemId: String) = withContext(Dispatchers.IO) {
        dao.getItem(itemId)?.let { dao.upsertItem(it.copy(metadataState = MetadataState.LOCKED)) }
    }

    /**
     * IGDB's texts and cover for every field not edited by hand. Automatic lookups only fill what
     * is still empty ([replace] false); a match chosen by hand replaces (the item was cleared first).
     */
    private fun LibraryItemEntity.withIgdb(game: IgdbGame, replace: Boolean = false): LibraryItemEntity {
        val m = manual
        fun <T> pick(field: MetaField, now: T?, new: T?): T? = if (field in m) now else if (replace) new ?: now else now ?: new
        return copy(
            igdbId = if (replace) game.id else igdbId ?: game.id,
            description = pick(MetaField.DESCRIPTION, description, game.summary),
            genres = pick(MetaField.GENRES, genres, game.genres.joinToString(", ") { it.name }.ifEmpty { null }),
            developer = pick(MetaField.DEVELOPER, developer, game.developer),
            releaseYear = pick(MetaField.RELEASE, releaseYear, game.releaseYear),
            releaseDate = pick(MetaField.RELEASE, releaseDate, game.releaseMillis),
            rating = pick(MetaField.RATING, rating, game.total_rating?.toFloat()),
            publisher = pick(MetaField.PUBLISHER, publisher, game.publisher),
            series = pick(MetaField.SERIES, series, game.series),
            features = pick(MetaField.FEATURES, features, game.features),
            ageRatings = pick(MetaField.AGE_RATING, ageRatings, game.ageRatings),
            coverUrl = pick(MetaField.COVER, coverUrl, game.coverUrl),
            metadataVersion = maxOf(metadataVersion, 1),
        )
    }

    /** IGDB's background, only where SteamGridDB had none (it comes last in the order). */
    private fun LibraryItemEntity.withIgdbHero(game: IgdbGame): LibraryItemEntity =
        if (MetaField.HERO in manual) this else copy(heroUrl = heroUrl ?: game.heroUrl)

    /** The fields added in schema v3 (publisher, series, features, age ratings): only filled in. */
    private fun LibraryItemEntity.withIgdbDetails(game: IgdbGame): LibraryItemEntity = withIgdb(game)

    /** Hand edit of one item: [transform] sets the value(s), [field] becomes protected. */
    suspend fun edit(itemId: String, field: MetaField?, transform: (LibraryItemEntity) -> LibraryItemEntity) = withContext(Dispatchers.IO) {
        val item = dao.getItem(itemId) ?: return@withContext
        val edited = transform(item)
        val fields = if (field == null) edited.manual else edited.manual + field
        dao.upsertItem(edited.copy(manualFields = fields.joinToString(",") { it.name }.ifEmpty { null }))
        corrected(itemId)
    }

    /** Drops a hand edit: the field follows IGDB again (fetched right away when matched). */
    suspend fun resetField(itemId: String, field: MetaField) = withContext(Dispatchers.IO) {
        val item = dao.getItem(itemId) ?: return@withContext
        val unlocked = item.copy(manualFields = (item.manual - field).joinToString(",") { it.name }.ifEmpty { null })
        val game = item.igdbId?.takeIf { igdb.isConfigured }?.let { runCatching { igdb.byId(it) }.getOrNull() }
        // The field is emptied and filled again from IGDB (stays empty without a match / offline).
        val cleared = when (field) {
                MetaField.DESCRIPTION -> unlocked.copy(description = null)
                MetaField.GENRES -> unlocked.copy(genres = null)
                MetaField.DEVELOPER -> unlocked.copy(developer = null)
                MetaField.PUBLISHER -> unlocked.copy(publisher = null)
                MetaField.SERIES -> unlocked.copy(series = null)
                MetaField.FEATURES -> unlocked.copy(features = null)
                MetaField.RELEASE -> unlocked.copy(releaseDate = null, releaseYear = null)
                MetaField.RATING -> unlocked.copy(rating = null)
                MetaField.AGE_RATING -> unlocked.copy(ageRatings = null)
                // Images go through resetArtwork.
                MetaField.COVER, MetaField.ANIMATED_COVER, MetaField.HERO, MetaField.ANIMATED_HERO, MetaField.LOGO, MetaField.ICON, MetaField.LANDSCAPE, MetaField.SQUARE -> unlocked
                MetaField.TITLE -> unlocked
        }
        val restored = when {
            // The name back to the one the source (app / host) reports.
            field == MetaField.TITLE -> unlocked.copy(title = dao.getTargetsForItem(itemId).firstOrNull()?.name ?: item.title)
            game != null -> cleared.withIgdb(game)
            else -> cleared
        }
        dao.upsertItem(restored)
        // The companion follows its automatic choice again too.
        val name = when (field) {
            MetaField.TITLE -> "name"
            MetaField.DESCRIPTION -> "summary"
            MetaField.GENRES -> "genres"
            MetaField.DEVELOPER -> "developers"
            MetaField.PUBLISHER -> "publishers"
            MetaField.RELEASE -> "release"
            else -> null
        }
        corrected(itemId, listOfNotNull(name))
    }

    private fun LibraryItemEntity.withSgdbArtwork(gameId: Long): LibraryItemEntity {
        val m = manual
        val grid = if (MetaField.COVER in m || coverUrl != null) null else sgdb.assets(gameId, SgdbAssetType.GRID).firstOrNull { it.height > it.width }
        val hero = if (MetaField.HERO in m || heroUrl != null) null else sgdb.assets(gameId, SgdbAssetType.HERO).firstOrNull()
        val logo = if (MetaField.LOGO in m || logoUrl != null) null else sgdb.assets(gameId, SgdbAssetType.LOGO).firstOrNull()
        val icon = if (MetaField.ICON in m || iconUrl != null) null else sgdb.assets(gameId, SgdbAssetType.ICON).firstOrNull()
        return copy(
            sgdbId = gameId,
            // Only what is still empty: set images are never replaced automatically.
            coverUrl = coverUrl ?: grid?.url,
            heroUrl = heroUrl ?: hero?.url,
            logoUrl = logoUrl ?: logo?.url,
            iconUrl = iconUrl ?: icon?.url,
        ).withSgdbAnimated(gameId).withSgdbTiles(gameId)
    }

    /** Landscape and square tiles from SteamGridDB, only for the tile formats in use; games only. */
    private fun LibraryItemEntity.withSgdbTiles(gameId: Long): LibraryItemEntity {
        if (kind != ItemKind.GAME) return this
        val m = manual
        val wide = if (!landscapeTiles || MetaField.LANDSCAPE in m || tileUrl != null) null else sgdb.assets(gameId, SgdbAssetType.GRID, landscape = true).firstOrNull()
        val square = if (!squareTiles || MetaField.SQUARE in m || squareUrl != null) null else sgdb.assets(gameId, SgdbAssetType.GRID, square = true).firstOrNull()
        return copy(tileUrl = tileUrl ?: wide?.url, squareUrl = squareUrl ?: square?.url)
    }

    /**
     * Once a tile format is chosen: its tiles for the games matched before. Games without a
     * SteamGridDB match (e.g. host games described by their companion) are searched by title.
     */
    private suspend fun backfillTiles() {
        val s = settings.current()
        val wide = landscapeTiles && !s.landscapeBackfillDone
        val square = squareTiles && !s.squareBackfillDone
        if (!(wide || square) || !sgdb.isConfigured) return
        val items = dao.observeAll().first().map { it.item }.filter {
            it.kind == ItemKind.GAME && it.metadataState != MetadataState.NONE &&
                ((wide && it.tileUrl == null && MetaField.LANDSCAPE !in it.manual) || (square && it.squareUrl == null && MetaField.SQUARE !in it.manual))
        }
        for (item in items) {
            if (!landscapeTiles && !squareTiles) return
            try {
                val fresh = dao.getItem(item.id) ?: continue
                val gameId = fresh.sgdbId ?: sgdb.search(searchQuery(fresh)).firstOrNull()?.id ?: continue
                val updated = fresh.withSgdbTiles(gameId)
                if (updated != fresh) dao.upsertItem(updated)
            } catch (e: MetadataAuthException) {
                _lastError.value = e.message
                return
            } catch (e: Exception) {
                Log.w("Tiles for ${item.title} failed", e)
            }
            delay(300)
        }
        settings.update { it.copy(landscapeBackfillDone = it.landscapeBackfillDone || wide, squareBackfillDone = it.squareBackfillDone || square) }
    }

    /** Animated cover and background from SteamGridDB, only while animated artwork is switched on. */
    private fun LibraryItemEntity.withSgdbAnimated(gameId: Long): LibraryItemEntity {
        if (!animated) return this
        val m = manual
        val grid = if (MetaField.ANIMATED_COVER in m || animatedCoverUrl != null) null else sgdb.assets(gameId, SgdbAssetType.GRID, animated = true)
            .firstOrNull { it.isPlayableAnimation && it.height > it.width }
        val hero = if (MetaField.ANIMATED_HERO in m || animatedHeroUrl != null) null else sgdb.assets(gameId, SgdbAssetType.HERO, animated = true).firstOrNull { it.isPlayableAnimation }
        return copy(animatedCoverUrl = animatedCoverUrl ?: grid?.url, animatedHeroUrl = animatedHeroUrl ?: hero?.url)
    }

    /**
     * Local apps (e.g. emulators recognised as games earlier) keep no game metadata found
     * automatically; host entries are left alone (their companion describes them).
     */
    private suspend fun dropAppMetadata() {
        dao.observeAll().first().filter { e ->
            e.item.kind == ItemKind.APP && e.item.metadataState in setOf(MetadataState.OK, MetadataState.REVIEW, MetadataState.NOT_FOUND) &&
                e.targets.isNotEmpty() && e.targets.all { it.type == io.github.thecodingdad.gamelodge.data.db.TargetType.LOCAL }
        }.forEach { dao.upsertItem(it.item.clearedMetadata()) }
    }

    /** Games matched while animated artwork was off: their animated cover / background once. */
    private suspend fun backfillAnimated() {
        val s = settings.current()
        if (!s.animatedArtwork || s.animatedBackfillDone || !sgdb.isConfigured) return
        val items = dao.observeAll().first().map { it.item }.filter {
            it.kind == ItemKind.GAME && it.sgdbId != null && it.animatedCoverUrl == null && it.animatedHeroUrl == null
        }
        for (item in items) {
            if (!animated) return
            try {
                val fresh = dao.getItem(item.id) ?: continue
                val updated = fresh.withSgdbAnimated(fresh.sgdbId ?: continue)
                if (updated != fresh) dao.upsertItem(updated)
            } catch (e: MetadataAuthException) {
                _lastError.value = e.message
                return
            } catch (e: Exception) {
                Log.w("Animated artwork for ${item.title} failed", e)
            }
            delay(300)
        }
        settings.update { it.copy(animatedBackfillDone = true) }
    }

    /**
     * Fills missing artwork (cover, background, logo, icon) from SteamGridDB, searching with
     * the IGDB titles (English main title first): SteamGridDB knows no localized names, so a
     * German title may have found an entry without a logo or none at all. Existing art stays.
     */
    private suspend fun LibraryItemEntity.withMissingArtwork(game: IgdbGame): LibraryItemEntity {
        if (!sgdb.isConfigured) return this
        fun missing(i: LibraryItemEntity) = listOf(i.coverUrl, i.heroUrl, i.logoUrl, i.iconUrl).any { it == null }
        if (!missing(this)) return this
        var item = this
        // Entries to look at: the current one, then the best hit for each IGDB title.
        val candidates = LinkedHashSet<Long>()
        item.sgdbId?.let(candidates::add)
        for (name in game.allNames.take(3)) {
            sgdb.search(name).map { it to MatchScorer.score(name, it.name) }
                .filter { it.second >= MatchScorer.AUTO_ACCEPT }.maxByOrNull { it.second }
                ?.let { candidates += it.first.id }
            delay(200)
        }
        for (id in candidates) {
            if (!missing(item)) break
            val cover = if (item.coverUrl == null) sgdb.assets(id, SgdbAssetType.GRID).firstOrNull { it.height > it.width } else null
            val hero = if (item.heroUrl == null) sgdb.assets(id, SgdbAssetType.HERO).firstOrNull() else null
            val logo = if (item.logoUrl == null) sgdb.assets(id, SgdbAssetType.LOGO).firstOrNull() else null
            val icon = if (item.iconUrl == null) sgdb.assets(id, SgdbAssetType.ICON).firstOrNull() else null
            item = item.copy(
                sgdbId = item.sgdbId ?: id,
                coverUrl = item.coverUrl ?: cover?.url,
                heroUrl = item.heroUrl ?: hero?.url,
                logoUrl = item.logoUrl ?: logo?.url,
                iconUrl = item.iconUrl ?: icon?.url,
            )
        }
        return item
    }

    /**
     * Automatic IGDB matches that turn out to be mods / ROM hacks (from before they ranked
     * lower) are looked up again once; hand-picked ones stay.
     */
    private suspend fun rematchUnofficial() {
        if (!igdb.isConfigured || settings.current().platformRule >= REMATCH_RULE) return
        val items = dao.observeAll().first().map { it.item }
            .filter { it.igdbId != null && it.metadataState != MetadataState.LOCKED }.associateBy { it.igdbId!! }
        items.keys.chunked(100).forEach { ids ->
            igdb.details(ids).filter { it.isUnofficial }.forEach { game ->
                val item = dao.getItem(items[game.id]?.id ?: return@forEach) ?: return@forEach
                val best = igdbBest(searchQuery(item))
                if (best != null && best.first.id != game.id && best.second >= MatchScorer.REVIEW) {
                    var updated = item.copy(platform = item.platform.takeIf { item.platformSource in setOf(PlatformSource.MANUAL, PlatformSource.PLAYNITE, PlatformSource.COMPANION) },
                        platformSource = item.platformSource.takeIf { it in setOf(PlatformSource.MANUAL, PlatformSource.PLAYNITE, PlatformSource.COMPANION) })
                    updated = updated.withIgdb(best.first, replace = true)
                    platformFor(updated, best.first)?.let { updated = updated.copy(platform = it.first, platformSource = it.second) }
                    dao.upsertItem(updated.copy(matchConfidence = best.second, metadataState = if (best.second >= MatchScorer.AUTO_ACCEPT) MetadataState.OK else MetadataState.REVIEW))
                }
                delay(300)
            }
        }
        settings.update { it.copy(platformRule = REMATCH_RULE) }
    }

    /** Games with an IGDB match but gaps in their artwork: try the IGDB titles on SteamGridDB once. */
    private suspend fun fillArtworkGaps() {
        if (!igdb.isConfigured || !sgdb.isConfigured) return
        val items = dao.observeAll().first().map { it.item }.filter {
            it.kind == ItemKind.GAME && it.igdbId != null && it.metadataVersion < ART_FILL_VERSION &&
                listOf(it.coverUrl, it.heroUrl, it.logoUrl, it.iconUrl).any { url -> url == null }
        }
        for (item in items) {
            try {
                val game = igdb.byId(item.igdbId!!) ?: continue
                val fresh = dao.getItem(item.id) ?: continue
                dao.upsertItem(fresh.withMissingArtwork(game).copy(metadataVersion = ART_FILL_VERSION))
            } catch (e: MetadataAuthException) {
                _lastError.value = e.message
                return
            } catch (e: Exception) {
                Log.w("Artwork gap fill for ${item.title} failed", e)
            }
            delay(300)
        }
    }

    /** Forgets the automatic match; hand-edited fields stay. */
}

/**
 * Games without an IGDB match are looked up again when this grows (2: alternative and
 * localized titles are searched too; 4: search terms without ™/®; 3 is taken by ART_FILL_VERSION
 * on the same counter).
 */
private const val IGDB_LOOKUP_VERSION = 4

/** Games without times to beat on IGDB are asked again after 30 days. */
private const val TIME_TO_BEAT_RECHECK_MS = 30L * 24 * 60 * 60 * 1000

/** Games with an IGDB match got the SteamGridDB gap fill via their IGDB titles (3). */
private const val ART_FILL_VERSION = 3

/** Version of the automatic platform rules (1: only unambiguous platforms). */
private const val PLATFORM_RULE = 1

/** Library re-evaluated after mods / ROM hacks rank lower (shares the platform rule counter). */
private const val REMATCH_RULE = 2

/** Background task id of the metadata lookups. */
private const val TASK_ID = "metadata"
