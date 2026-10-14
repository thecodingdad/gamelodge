package io.github.thecodingdad.moonlightlauncher.metadata

import io.github.thecodingdad.moonlightlauncher.data.db.ItemKind
import io.github.thecodingdad.moonlightlauncher.data.db.LauncherDatabase
import io.github.thecodingdad.moonlightlauncher.data.db.LibraryItemEntity
import io.github.thecodingdad.moonlightlauncher.data.db.clearedMetadata
import io.github.thecodingdad.moonlightlauncher.data.db.LibraryEntry
import io.github.thecodingdad.moonlightlauncher.data.db.MetaField
import io.github.thecodingdad.moonlightlauncher.data.db.MetadataState
import io.github.thecodingdad.moonlightlauncher.data.db.PlatformSource
import io.github.thecodingdad.moonlightlauncher.data.library.PlatformResolver
import io.github.thecodingdad.moonlightlauncher.data.settings.SecretStore
import io.github.thecodingdad.moonlightlauncher.data.settings.SettingsRepository
import io.github.thecodingdad.moonlightlauncher.util.Log
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

enum class ArtworkKind(val field: MetaField) { COVER(MetaField.COVER), HERO(MetaField.HERO), LOGO(MetaField.LOGO), ICON(MetaField.ICON) }

/** Where an image to choose from comes from. */
enum class ArtworkSource { COMPANION, IGDB, STEAMGRIDDB }

/** One image in the picker: [url] is stored, [thumb] previewed. */
data class ArtworkOption(val url: String, val thumb: String, val source: ArtworkSource, val animated: Boolean = false, val bytes: Long? = null)

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
    private val servers: io.github.thecodingdad.moonlightlauncher.data.companion.ServerRepository? = null,
) {
    /**
     * Called after a hand correction (the library sends it on to the host's companion);
     * [reset] names the fields handed back to automatic (HostGameCorrection.reset).
     */
    var onCorrected: (suspend (itemId: String, reset: List<String>) -> Unit)? = null

    /** The host game behind an item, when its companion delivers it (images to choose from). */
    var companionGame: (suspend (itemId: String) -> io.github.thecodingdad.moonlightlauncher.data.companion.HostGame?)? = null

    private suspend fun corrected(itemId: String, reset: List<String> = emptyList()) {
        runCatching { onCorrected?.invoke(itemId, reset) }.onFailure { Log.w("Correction not passed on", it) }
    }

    private val dao = db.libraryDao()
    private val mutex = Mutex()

    private val _progress = MutableStateFlow<MetadataProgress?>(null)
    val progress: StateFlow<MetadataProgress?> = _progress.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    @Volatile private var sources: List<String> = io.github.thecodingdad.moonlightlauncher.data.settings.LauncherSettings().metadataSources

    /** Animated artwork is wanted (Display settings): only then it is looked up. */
    @Volatile private var animated: Boolean = true

    init {
        scope.launch {
            settings.settings.collect {
                sources = it.metadataSources
                val switchedOn = it.animatedArtwork && !animated
                animated = it.animatedArtwork
                if (switchedOn && !it.animatedBackfillDone) launch(Dispatchers.IO) { backfillAnimated() }
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
    private fun routes(own: String, provider: (io.github.thecodingdad.moonlightlauncher.data.companion.PairedServer) -> Boolean): List<MetadataRoute> =
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
            runCatching { fillArtworkGaps() }.onFailure { Log.w("Artwork gap fill failed", it) }
            runCatching { backfillAnimated() }.onFailure { Log.w("Animated artwork backfill failed", it) }
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
        game.platforms.map { it.name }, playsOnAndroid = localTarget != null,
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
                    _progress.value = MetadataProgress(index, total)
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
                    _progress.value = MetadataProgress(pending.size + index, total)
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
            } finally {
                _progress.value = null
                mutex.unlock()
            }
        }
    }

    /** Forgets automatic matches (not locked ones) and looks everything up again. */
    suspend fun resetAutomatic(includeNotFound: Boolean = true) = withContext(Dispatchers.IO) {
        val states = buildList {
            add(MetadataState.OK); add(MetadataState.REVIEW)
            if (includeNotFound) add(MetadataState.NOT_FOUND)
        }
        dao.getItemsWithMetadataState(states).forEach { dao.upsertItem(it.clearedMetadata()) }
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
        var updated = fresh.withIgdb(game).withMissingArtwork(game).copy(metadataVersion = ART_FILL_VERSION)
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
        item.metadataQuery ?: io.github.thecodingdad.moonlightlauncher.data.library.TitleNormalizer.searchTerm(item.title)

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
        val complete = igdbBest?.first?.let { withArt.withMissingArtwork(it).copy(metadataVersion = ART_FILL_VERSION) } ?: withArt
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
    }

    /** Applies a user-chosen candidate and locks the item against automatic changes. */
    suspend fun applyCandidate(itemId: String, candidate: MetadataCandidate) = withContext(Dispatchers.IO) {
        val item = dao.getItem(itemId) ?: return@withContext
        var updated = item.clearedMetadata()
        when (candidate.source) {
            MetadataSource.IGDB -> {
                igdb.byId(candidate.id)?.let { game ->
                    updated = updated.withIgdb(game)
                    platformFor(item, game)?.let { updated = updated.copy(platform = it.first, platformSource = it.second) }
                }
                if (sgdb.isConfigured) {
                    sgdb.search(candidate.name).maxByOrNull { MatchScorer.score(candidate.name, it.name) }
                        ?.let { updated = updated.withSgdbArtwork(it.id) }
                }
            }
            MetadataSource.STEAMGRIDDB -> updated = updated.withSgdbArtwork(candidate.id)
        }
        dao.upsertItem(updated.copy(metadataState = MetadataState.LOCKED, matchConfidence = 1f, metadataQuery = candidate.name))
        corrected(itemId)
    }

    /**
     * Everything to choose from for one kind of image: the companion's (host games), IGDB's
     * (cover; artworks and screenshots as background) and SteamGridDB's (animated ones for cover
     * and background while animated artwork is switched on).
     */
    suspend fun artworkOptions(item: LibraryItemEntity, kind: ArtworkKind): List<ArtworkOption> = withContext(Dispatchers.IO) {
        val result = mutableListOf<ArtworkOption>()
        runCatching { companionGame?.invoke(item.id) }.getOrNull()?.let { game ->
            val (still, moving) = when (kind) {
                ArtworkKind.COVER -> game.cover to game.animatedCover
                ArtworkKind.HERO -> game.hero to game.animatedHero
                ArtworkKind.LOGO -> game.logo to null
                ArtworkKind.ICON -> game.icon to null
            }
            still?.takeIf { it.startsWith("https://") }?.let { result += ArtworkOption(it, it, ArtworkSource.COMPANION) }
            moving?.takeIf { animated && it.startsWith("https://") }?.let { result += ArtworkOption(it, it, ArtworkSource.COMPANION, animated = true) }
        }
        if (igdb.isConfigured && (kind == ArtworkKind.COVER || kind == ArtworkKind.HERO)) {
            runCatching { item.igdbId?.let { igdb.byId(it) } ?: igdb.search(searchQuery(item), limit = 1).firstOrNull() }.getOrNull()?.let { game ->
                if (kind == ArtworkKind.COVER) game.coverUrl?.let { result += ArtworkOption(it, it.replace("t_cover_big_2x", "t_cover_big"), ArtworkSource.IGDB) }
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
                val gameId = item.sgdbId ?: sgdb.search(searchQuery(item)).firstOrNull()?.id ?: return@runCatching
                val type = when (kind) {
                    ArtworkKind.COVER -> SgdbAssetType.GRID
                    ArtworkKind.HERO -> SgdbAssetType.HERO
                    ArtworkKind.LOGO -> SgdbAssetType.LOGO
                    ArtworkKind.ICON -> SgdbAssetType.ICON
                }
                sgdb.assets(gameId, type).forEach { result += ArtworkOption(it.url, it.thumb ?: it.url, ArtworkSource.STEAMGRIDDB) }
                if (animated && (kind == ArtworkKind.COVER || kind == ArtworkKind.HERO)) {
                    sgdb.assets(gameId, type, animated = true).filter { it.isPlayableAnimation }
                        .forEach { result += ArtworkOption(it.url, it.thumb ?: it.url, ArtworkSource.STEAMGRIDDB, animated = true) }
                }
            }.onFailure { Log.w("SteamGridDB artwork for ${item.title} failed", it) }
        }
        // In the order of the sources for this kind (background: SteamGridDB before IGDB).
        val rank = if (kind == ArtworkKind.HERO) listOf(ArtworkSource.COMPANION, ArtworkSource.STEAMGRIDDB, ArtworkSource.IGDB)
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

    /** Landscape SteamGridDB grids for an app's start tab tile. */
    suspend fun tileOptions(item: LibraryItemEntity): List<SgdbAsset> = withContext(Dispatchers.IO) {
        if (!sgdb.isConfigured) return@withContext emptyList()
        runCatching {
            val gameId = sgdb.search(searchQuery(item)).firstOrNull()?.id ?: return@runCatching emptyList()
            sgdb.assets(gameId, SgdbAssetType.GRID, landscape = true)
        }.getOrDefault(emptyList())
    }

    /** Tile of an app: SteamGridDB URL, own image file, or null for the default tile. */
    suspend fun setTile(itemId: String, url: String?) = withContext(Dispatchers.IO) {
        val item = dao.getItem(itemId) ?: return@withContext
        // An own image replaced or removed: its copy isn't needed any more.
        item.tileUrl?.takeIf { !it.startsWith("http") && it != url }?.let { java.io.File(it).delete() }
        dao.upsertItem(item.copy(tileUrl = url))
    }

    /** A hand-picked image: protected against automatic changes and sent to the companion. */
    suspend fun setArtwork(itemId: String, kind: ArtworkKind, option: ArtworkOption) = withContext(Dispatchers.IO) {
        val item = dao.getItem(itemId) ?: return@withContext
        val updated = when (kind) {
            ArtworkKind.COVER -> if (option.animated) item.copy(animatedCoverUrl = option.url) else item.copy(coverUrl = option.url)
            ArtworkKind.HERO -> if (option.animated) item.copy(animatedHeroUrl = option.url) else item.copy(heroUrl = option.url)
            ArtworkKind.LOGO -> item.copy(logoUrl = option.url)
            ArtworkKind.ICON -> item.copy(iconUrl = option.url)
        }
        dao.upsertItem(updated.withManual(updated.manual + kind.field))
        corrected(itemId)
    }

    /** Hands an image (with its animated variant) back to automatic: looked up again in the fixed order. */
    suspend fun resetArtwork(itemId: String, kind: ArtworkKind) = withContext(Dispatchers.IO) {
        val item = dao.getItem(itemId) ?: return@withContext
        var auto = item.withManual(item.manual - kind.field).let {
            when (kind) {
                ArtworkKind.COVER -> it.copy(coverUrl = null, animatedCoverUrl = null)
                ArtworkKind.HERO -> it.copy(heroUrl = null, animatedHeroUrl = null)
                ArtworkKind.LOGO -> it.copy(logoUrl = null)
                ArtworkKind.ICON -> it.copy(iconUrl = null)
            }
        }
        runCatching {
            item.igdbId?.takeIf { igdb.isConfigured }?.let { igdb.byId(it) }?.let { auto = auto.withIgdbArtwork(it) }
            item.sgdbId?.takeIf { sgdb.isConfigured }?.let { auto = auto.withSgdbArtwork(it) }
        }.onFailure { Log.w("Artwork for ${item.title} not looked up again", it) }
        dao.upsertItem(auto)
        val reset = when (kind) {
            ArtworkKind.COVER -> listOf("cover", "animatedCover")
            ArtworkKind.HERO -> listOf("hero", "animatedHero")
            ArtworkKind.LOGO -> listOf("logo")
            ArtworkKind.ICON -> listOf("icon")
        }
        corrected(itemId, reset)
    }

    private fun LibraryItemEntity.withManual(fields: Set<MetaField>) =
        copy(manualFields = fields.joinToString(",") { it.name }.ifEmpty { null })

    /** Marks a REVIEW match as correct. */
    suspend fun confirm(itemId: String) = withContext(Dispatchers.IO) {
        dao.getItem(itemId)?.let { dao.upsertItem(it.copy(metadataState = MetadataState.LOCKED)) }
    }

    /** IGDB data for every field not edited by hand. */
    private fun LibraryItemEntity.withIgdb(game: IgdbGame): LibraryItemEntity {
        val m = manual
        return copy(
            igdbId = game.id,
            description = if (MetaField.DESCRIPTION in m) description else game.summary,
            genres = if (MetaField.GENRES in m) genres else game.genres.joinToString(", ") { it.name }.ifEmpty { null },
            developer = if (MetaField.DEVELOPER in m) developer else game.developer,
            releaseYear = if (MetaField.RELEASE in m) releaseYear else game.releaseYear,
            releaseDate = if (MetaField.RELEASE in m) releaseDate else game.releaseMillis,
            rating = if (MetaField.RATING in m) rating else game.total_rating?.toFloat(),
        ).withIgdbArtwork(game).withIgdbDetails(game)
    }

    /** IGDB's cover comes first; its background only fills in (SteamGridDB's is preferred). */
    private fun LibraryItemEntity.withIgdbArtwork(game: IgdbGame): LibraryItemEntity {
        val m = manual
        return copy(
            coverUrl = if (MetaField.COVER in m) coverUrl else game.coverUrl ?: coverUrl,
            heroUrl = if (MetaField.HERO in m) heroUrl else heroUrl ?: game.heroUrl,
        )
    }

    /** The fields added in schema v3 (publisher, series, features, age ratings). */
    private fun LibraryItemEntity.withIgdbDetails(game: IgdbGame): LibraryItemEntity {
        val m = manual
        return copy(
            publisher = if (MetaField.PUBLISHER in m) publisher else game.publisher,
            series = if (MetaField.SERIES in m) series else game.series,
            features = if (MetaField.FEATURES in m) features else game.features,
            ageRatings = if (MetaField.AGE_RATING in m) ageRatings else game.ageRatings,
            releaseDate = if (MetaField.RELEASE in m) releaseDate else releaseDate ?: game.releaseMillis,
            metadataVersion = 1,
        )
    }

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
        val restored = when {
            // The name back to the one the source (app / host) reports.
            field == MetaField.TITLE -> unlocked.copy(title = dao.getTargetsForItem(itemId).firstOrNull()?.name ?: item.title)
            game != null -> unlocked.withIgdb(game)
            // No IGDB match (or offline): nothing to restore, the field becomes empty.
            else -> when (field) {
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
                MetaField.COVER, MetaField.HERO, MetaField.LOGO, MetaField.ICON -> unlocked
                MetaField.TITLE -> unlocked
            }
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
        val hero = if (MetaField.HERO in m) null else sgdb.assets(gameId, SgdbAssetType.HERO).firstOrNull()
        val logo = if (MetaField.LOGO in m) null else sgdb.assets(gameId, SgdbAssetType.LOGO).firstOrNull()
        val icon = if (MetaField.ICON in m) null else sgdb.assets(gameId, SgdbAssetType.ICON).firstOrNull()
        return copy(
            sgdbId = gameId,
            // Cover: IGDB's first (SteamGridDB only fills in); background, logo, icon: SteamGridDB's.
            coverUrl = coverUrl ?: grid?.url,
            heroUrl = hero?.url ?: heroUrl,
            logoUrl = logo?.url ?: logoUrl,
            iconUrl = icon?.url ?: iconUrl,
        ).withSgdbAnimated(gameId)
    }

    /** Animated cover and background from SteamGridDB, only while animated artwork is switched on. */
    private fun LibraryItemEntity.withSgdbAnimated(gameId: Long): LibraryItemEntity {
        if (!animated) return this
        val m = manual
        val grid = if (MetaField.COVER in m) null else sgdb.assets(gameId, SgdbAssetType.GRID, animated = true)
            .firstOrNull { it.isPlayableAnimation && it.height > it.width }
        val hero = if (MetaField.HERO in m) null else sgdb.assets(gameId, SgdbAssetType.HERO, animated = true).firstOrNull { it.isPlayableAnimation }
        return copy(animatedCoverUrl = grid?.url ?: animatedCoverUrl, animatedHeroUrl = hero?.url ?: animatedHeroUrl)
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
                    updated = updated.withIgdb(best.first)
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

/** Games with an IGDB match got the SteamGridDB gap fill via their IGDB titles (3). */
private const val ART_FILL_VERSION = 3

/** Version of the automatic platform rules (1: only unambiguous platforms). */
private const val PLATFORM_RULE = 1

/** Library re-evaluated after mods / ROM hacks rank lower (shares the platform rule counter). */
private const val REMATCH_RULE = 2
