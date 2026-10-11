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
            runCatching { backfillPlatforms() }.onFailure { Log.w("Platform backfill failed", it) }
            runCatching { backfillDetails() }.onFailure { Log.w("Metadata details backfill failed", it) }
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
        game.platforms.map { it.name }, playsOnPc = remoteTargets.isNotEmpty(), playsOnAndroid = localTarget != null,
    )

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
                pending.forEachIndexed { index, item ->
                    _progress.value = MetadataProgress(index, pending.size)
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

    private suspend fun lookupAndApply(item: LibraryItemEntity) {
        val query = item.metadataQuery ?: item.title
        val igdbBest = if (igdb.isConfigured) {
            igdb.search(query).map { it to MatchScorer.score(query, it.name) }.maxByOrNull { it.second }
        } else null
        val sgdbBest = if (sgdb.isConfigured) {
            sgdb.search(igdbBest?.first?.name ?: query).map { it to MatchScorer.score(query, it.name) }.maxByOrNull { it.second }
        } else null

        val confidence = igdbBest?.second ?: sgdbBest?.second ?: 0f
        if (confidence < MatchScorer.REVIEW) {
            dao.upsertItem(item.copy(metadataState = MetadataState.NOT_FOUND, matchConfidence = confidence))
            return
        }
        val state = if (confidence >= MatchScorer.AUTO_ACCEPT) MetadataState.OK else MetadataState.REVIEW
        val withText = igdbBest?.first?.let { game ->
            val platform = platformFor(item, game)
            item.withIgdb(game).let { if (platform != null) it.copy(platform = platform.first, platformSource = platform.second) else it }
        } ?: item
        val withArt = sgdbBest?.first?.takeIf { sgdbBest.second >= MatchScorer.REVIEW }
            ?.let { withText.withSgdbArtwork(it.id) } ?: withText
        dao.upsertItem(withArt.copy(metadataState = state, matchConfidence = confidence))
    }

    /** Manual search for the correction dialog (IGDB preferred, SteamGridDB otherwise). */
    suspend fun search(query: String): List<MetadataCandidate> = withContext(Dispatchers.IO) {
        if (igdb.isConfigured) {
            igdb.search(query, limit = 12).map {
                MetadataCandidate(MetadataSource.IGDB, it.id, it.name, it.releaseYear, it.coverUrl, MatchScorer.score(query, it.name))
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
        val gameId = item.sgdbId ?: sgdb.search(item.metadataQuery ?: item.title).firstOrNull()?.id ?: return@withContext emptyList()
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
