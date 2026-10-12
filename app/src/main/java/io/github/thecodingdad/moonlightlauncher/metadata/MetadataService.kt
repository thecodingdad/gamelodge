package io.github.thecodingdad.moonlightlauncher.metadata

import io.github.thecodingdad.moonlightlauncher.data.db.ItemKind
import io.github.thecodingdad.moonlightlauncher.data.db.LauncherDatabase
import io.github.thecodingdad.moonlightlauncher.data.db.LibraryItemEntity
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

enum class ArtworkKind { COVER, HERO, LOGO, ICON }

/**
 * Looks up descriptions (IGDB) and artwork (SteamGridDB) for games. Confident matches are
 * applied automatically; uncertain ones are applied but flagged REVIEW; manual choices are
 * LOCKED and never touched again.
 */
class MetadataService(
    private val db: LauncherDatabase,
    private val secrets: SecretStore,
    private val settings: SettingsRepository,
    http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val dao = db.libraryDao()
    private val mutex = Mutex()

    private val _progress = MutableStateFlow<MetadataProgress?>(null)
    val progress: StateFlow<MetadataProgress?> = _progress.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    val sgdb = SteamGridDbClient(http, { secrets.get(SecretStore.Keys.SGDB_API_KEY) })
    val igdb = IgdbClient(http, object : IgdbCredentials {
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
        if (!sgdb.isConfigured) return@withContext null
        try {
            sgdb.search("Mario")
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
        if (!igdb.isConfigured) return@withContext null
        try {
            igdb.search("Mario", limit = 1)
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
    }

    /** Alternative images for the artwork picker. */
    suspend fun artworkOptions(item: LibraryItemEntity, kind: ArtworkKind): List<SgdbAsset> = withContext(Dispatchers.IO) {
        if (!sgdb.isConfigured) return@withContext emptyList()
        val gameId = item.sgdbId ?: sgdb.search(searchQuery(item)).firstOrNull()?.id ?: return@withContext emptyList()
        val type = when (kind) {
            ArtworkKind.COVER -> SgdbAssetType.GRID
            ArtworkKind.HERO -> SgdbAssetType.HERO
            ArtworkKind.LOGO -> SgdbAssetType.LOGO
            ArtworkKind.ICON -> SgdbAssetType.ICON
        }
        sgdb.assets(gameId, type) + if (kind == ArtworkKind.COVER || kind == ArtworkKind.HERO) {
            sgdb.assets(gameId, type, animated = true).filter { it.isPlayableAnimation }
        } else emptyList()
    }

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

    suspend fun setArtwork(itemId: String, kind: ArtworkKind, asset: SgdbAsset) = withContext(Dispatchers.IO) {
        val item = dao.getItem(itemId) ?: return@withContext
        val updated = when (kind) {
            ArtworkKind.COVER -> if (asset.isAnimated) item.copy(animatedCoverUrl = asset.url) else item.copy(coverUrl = asset.url, animatedCoverUrl = null)
            ArtworkKind.HERO -> if (asset.isAnimated) item.copy(animatedHeroUrl = asset.url) else item.copy(heroUrl = asset.url, animatedHeroUrl = null)
            ArtworkKind.LOGO -> item.copy(logoUrl = asset.url)
            ArtworkKind.ICON -> item.copy(iconUrl = asset.url)
        }
        dao.upsertItem(updated.copy(metadataState = MetadataState.LOCKED))
    }

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
            coverUrl = coverUrl ?: game.coverUrl,
            heroUrl = heroUrl ?: game.heroUrl,
        ).withIgdbDetails(game)
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
                MetaField.TITLE -> unlocked
            }
        }
        dao.upsertItem(restored)
    }

    private fun LibraryItemEntity.withSgdbArtwork(gameId: Long): LibraryItemEntity {
        val grid = sgdb.assets(gameId, SgdbAssetType.GRID).firstOrNull { it.height > it.width }
        val hero = sgdb.assets(gameId, SgdbAssetType.HERO).firstOrNull()
        val logo = sgdb.assets(gameId, SgdbAssetType.LOGO).firstOrNull()
        val icon = sgdb.assets(gameId, SgdbAssetType.ICON).firstOrNull()
        val animatedGrid = sgdb.assets(gameId, SgdbAssetType.GRID, animated = true)
            .firstOrNull { it.isPlayableAnimation && it.height > it.width }
        val animatedHero = sgdb.assets(gameId, SgdbAssetType.HERO, animated = true).firstOrNull { it.isPlayableAnimation }
        return copy(
            sgdbId = gameId,
            // SteamGridDB artwork is usually nicer than IGDB's, so it wins when present.
            coverUrl = grid?.url ?: coverUrl,
            heroUrl = hero?.url ?: heroUrl,
            logoUrl = logo?.url ?: logoUrl,
            iconUrl = icon?.url ?: iconUrl,
            animatedCoverUrl = animatedGrid?.url,
            animatedHeroUrl = animatedHero?.url,
        )
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
    private fun LibraryItemEntity.clearedMetadata(): LibraryItemEntity {
        val m = manual
        return copy(
            metadataState = MetadataState.NONE, matchConfidence = 0f, igdbId = null, sgdbId = null, metadataVersion = 0,
            description = description.takeIf { MetaField.DESCRIPTION in m },
            genres = genres.takeIf { MetaField.GENRES in m },
            developer = developer.takeIf { MetaField.DEVELOPER in m },
            publisher = publisher.takeIf { MetaField.PUBLISHER in m },
            series = series.takeIf { MetaField.SERIES in m },
            features = features.takeIf { MetaField.FEATURES in m },
            ageRatings = ageRatings.takeIf { MetaField.AGE_RATING in m },
            releaseYear = releaseYear.takeIf { MetaField.RELEASE in m },
            releaseDate = releaseDate.takeIf { MetaField.RELEASE in m },
            rating = rating.takeIf { MetaField.RATING in m },
            coverUrl = null, heroUrl = null, logoUrl = null, iconUrl = null, animatedCoverUrl = null, animatedHeroUrl = null,
        )
    }
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
