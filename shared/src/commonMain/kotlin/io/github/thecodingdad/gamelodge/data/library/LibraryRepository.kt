package io.github.thecodingdad.gamelodge.data.library

import io.github.thecodingdad.gamelodge.resources.*
import io.github.thecodingdad.gamelodge.data.db.withTransaction
import io.github.thecodingdad.gamelodge.data.db.ItemCollectionCrossRef
import io.github.thecodingdad.gamelodge.data.db.ItemKind
import io.github.thecodingdad.gamelodge.data.db.KindSource
import io.github.thecodingdad.gamelodge.data.db.MetaField
import io.github.thecodingdad.gamelodge.data.db.LibraryItemEntity
import io.github.thecodingdad.gamelodge.data.db.clearedMetadata
import io.github.thecodingdad.gamelodge.data.db.MetadataState
import io.github.thecodingdad.gamelodge.data.db.LauncherDatabase
import io.github.thecodingdad.gamelodge.data.db.LibraryEntry
import io.github.thecodingdad.gamelodge.data.db.PlatformSource
import io.github.thecodingdad.gamelodge.data.db.TargetType
import io.github.thecodingdad.gamelodge.data.local.LocalAppSource
import io.github.thecodingdad.gamelodge.data.remote.HostRepository
import io.github.thecodingdad.gamelodge.data.remote.HostStatus
import io.github.thecodingdad.gamelodge.data.remote.RemoteApp
import io.github.thecodingdad.gamelodge.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

/** Image hosts the companion accepts in corrections (Companion.Protocol ApiServer.ArtworkHosts). */
private val ARTWORK_HOSTS = listOf("steamgriddb.com", "images.igdb.com", "steamstatic.com", "gog-statics.com", "cdn1.epicgames.com")

/** How often the hosts' GameLodge lists are checked for changes. */
private const val COMPANION_REFRESH_MS = 60_000L

/** A change to play data (favourite, rating …) is sent this long after it. */
private const val PLAY_SYNC_DELAY_MS = 3_000L

/** Entry point for everything library related: keeps sources in sync and exposes entries. */
class LibraryRepository(
    private val db: LauncherDatabase,
    private val sync: LibrarySync,
    private val localApps: LocalAppSource,
    private val hosts: HostRepository,
    private val scope: CoroutineScope,
    private val companion: io.github.thecodingdad.gamelodge.data.companion.CompanionFeed? = null,
    private val settings: io.github.thecodingdad.gamelodge.data.settings.SettingsRepository? = null,
) {
    private val dao = db.libraryDao()

    /** Box art downloads running per host: (done, total). */
    private val _boxArtProgress = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Pair<Int, Int>>>(emptyMap())
    val boxArtProgress: StateFlow<Map<String, Pair<Int, Int>>> = _boxArtProgress

    /** Hosts removed with their games kept: those games stay out of sight until the host returns. */
    private val detached: Flow<Set<String>> =
        settings?.settings?.map { it.detachedHosts }?.distinctUntilChanged() ?: kotlinx.coroutines.flow.flowOf(emptySet())

    /** All visible entries (games and apps), sorted by title. */
    val entries: StateFlow<List<LibraryEntry>> =
        combine(dao.observeVisible(), detached, ::withoutDetached).stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Including hidden items, for the "hidden" filter and settings. */
    val allEntries: Flow<List<LibraryEntry>> = combine(dao.observeAll(), detached, ::withoutDetached)

    private fun withoutDetached(list: List<LibraryEntry>, hosts: Set<String>): List<LibraryEntry> {
        if (hosts.isEmpty()) return list
        return list.mapNotNull { e ->
            if (e.targets.none { it.hostUuid in hosts }) return@mapNotNull e
            val rest = e.targets.filterNot { it.type == TargetType.REMOTE && it.hostUuid in hosts }
            if (rest.isEmpty()) null else e.copy(targets = rest)
        }
    }

    fun entry(id: String): Flow<LibraryEntry?> = dao.observeEntry(id)

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch(Dispatchers.IO) {
            localApps.packageChanges()
                .onStart { emit(Unit) }
                .debounce(500)
                .collectIndexed { i, _ -> if (i == 0) io.github.thecodingdad.gamelodge.util.StartupSequence.turn { syncLocal() } else syncLocal() }
        }
        scope.launch(Dispatchers.IO) {
            var previous = emptyMap<String, List<RemoteApp>>()
            hosts.appLists.collect { lists ->
                lists.filter { (uuid, apps) -> previous[uuid] !== apps }
                    .forEach { (uuid, apps) -> syncHost(uuid, apps) }
                previous = lists
            }
        }
        // Changes on a host's GameLodge (a cover picked on another device) don't change the app
        // list: asked for once a minute (a cheap "not modified" while nothing changed).
        scope.launch(Dispatchers.IO) {
            while (true) {
                kotlinx.coroutines.delay(COMPANION_REFRESH_MS)
                checkHostChanges()
            }
        }
        // Paired hosts that are online but haven't sent their app list yet: a running task each.
        scope.launch {
            var shown = emptySet<String>()
            kotlinx.coroutines.flow.combine(hosts.hosts, hosts.appLists) { all, lists ->
                all.values.filter { it.paired && it.status == HostStatus.ONLINE && lists[it.uuid] == null }
            }.collect { loading ->
                loading.forEach { h -> io.github.thecodingdad.gamelodge.data.tasks.BackgroundTasks.set(io.github.thecodingdad.gamelodge.data.tasks.BackgroundTask("applist-${h.uuid}", Res.string.task_app_list, listOf(h.name))) }
                (shown - loading.map { it.uuid }.toSet()).forEach { io.github.thecodingdad.gamelodge.data.tasks.BackgroundTasks.remove("applist-$it") }
                shown = loading.map { it.uuid }.toSet()
            }
        }
    }

    /** Applies what changed on the reachable hosts' GameLodge since the last look (also by hand: "fetch now"). */
    suspend fun checkHostChanges() = withContext(Dispatchers.IO) {
        val online = hosts.hosts.value.values.filter { it.paired && it.status == HostStatus.ONLINE }.map { it.uuid }
        for (uuid in online) {
            if (hosts.appLists.value[uuid] == null) continue
            val games = runCatching { companion?.changedGames(uuid) }.getOrNull() ?: continue
            Log.d("Host $uuid: companion list changed")
            applyCompanion(uuid, games)
        }
        syncPlayData()
    }

    /** Play data shared through the hosts (set by the platform; null: not shared). */
    @Volatile var playData: io.github.thecodingdad.gamelodge.data.sync.PlayDataSync? = null

    /** The metadata keys, shared with the hosts that allow it (set by the platform). */
    @Volatile var keySync: io.github.thecodingdad.gamelodge.data.sync.KeySync? = null

    /** The Windows host's own store, synced like a remote host (null elsewhere or when it isn't a host). */
    @Volatile var ownPlayDataHost: (suspend () -> io.github.thecodingdad.gamelodge.data.sync.SyncHost?)? = null

    /** Syncs play data (playtime, ratings, favourites, hidden, collections) with every reachable GameLodge host. */
    suspend fun syncPlayData() = withContext(Dispatchers.IO) {
        val sync = playData ?: return@withContext
        val feed = companion
        if (feed != null) {
            val online = hosts.hosts.value.values.filter { it.paired && it.status == HostStatus.ONLINE }.map { it.uuid }
            for (uuid in online) {
                keySync?.let { feed.exchangeKeys(uuid, it, settings?.current()?.sendKeysToHost == true) }
                if (!feed.sharesPlayData(uuid)) continue
                val games = runCatching { feed.games(uuid) }.getOrNull() ?: continue
                val targets = dao.getAllTargets().filter { it.type == TargetType.REMOTE && it.hostUuid == uuid && it.appUuid != null }
                val gameOfTarget = targets.mapNotNull { t -> games[t.appUuid!!.uppercase()]?.let { t.id to it.id } }.toMap()
                val gameItems = targets.mapNotNull { t -> games[t.appUuid!!.uppercase()]?.let { it.id to t.itemId } }.toMap()
                sync.sync(
                    io.github.thecodingdad.gamelodge.data.sync.SyncHost(
                        uuid,
                        object : io.github.thecodingdad.gamelodge.data.sync.PlayDataTransport {
                            override suspend fun pull(after: Long) = feed.pullPlayData(uuid, after)
                            override suspend fun push(changes: io.github.thecodingdad.gamelodge.data.companion.PlayDataChanges) = feed.pushPlayData(uuid, changes)
                        },
                        gameItems, gameOfTarget,
                    ),
                )
            }
        }
        ownPlayDataHost?.invoke()?.let { sync.sync(it) }
    }

    suspend fun syncLocal() {
        val apps = localApps.scan()
        sync.syncLocal(
            apps.map {
                SourceEntry(
                    targetId = SourceEntry.localId(it.packageName),
                    type = TargetType.LOCAL,
                    name = it.label,
                    packageName = it.packageName,
                    androidGameFlag = it.isGame,
                    companionGame = it.knownGame,
                    installed = it.installed,
                    platform = it.platform,
                    boxArt = it.cover,
                )
            },
        )
        // The source's platform (PC store games) like the companion's: trusted over guesses.
        val platforms = apps.filter { it.platform != null }.associate { SourceEntry.localId(it.packageName) to it.platform!! }
        if (platforms.isNotEmpty()) {
            for (target in dao.getAllTargets().filter { it.type == TargetType.LOCAL && it.id in platforms }) {
                val platform = platforms.getValue(target.id)
                val item = dao.getItem(target.itemId) ?: continue
                if (PlatformResolver.mayReplace(item, PlatformSource.COMPANION, platform)) dao.setPlatform(item.id, platform, PlatformSource.COMPANION)
            }
        }
        Log.d("Local sync: ${apps.size} apps")
        suggestPlatforms()
    }

    /**
     * Games found in the ROM folders. The folder decides the platform (trusted like the companion
     * data), so a ROM merges only with entries of the same platform.
     */
    suspend fun syncRoms(roms: List<RomGame>, keepUnder: List<String> = emptyList()) {
        val entries = roms.map { rom ->
            SourceEntry(
                targetId = SourceEntry.romId(rom.uri),
                type = TargetType.ROM,
                name = rom.name,
                romUri = rom.uri,
                romPath = rom.path,
                romPlatform = rom.platformId,
                platform = rom.platformName,
                boxArt = rom.image,
            )
        }
        sync.syncRoms(entries, keepUnder)
        val byTarget = entries.associateBy { it.targetId }
        for (target in dao.getAllTargets().filter { it.type == TargetType.ROM }) {
            val platform = byTarget[target.id]?.platform ?: continue
            val item = dao.getItem(target.itemId) ?: continue
            if (PlatformResolver.mayReplace(item, PlatformSource.COMPANION, platform)) dao.setPlatform(item.id, platform, PlatformSource.COMPANION)
        }
        Log.d("ROM sync: ${roms.size} games")
    }

    /** Re-applies every host's app list (all metadata fetched again by hand). */
    suspend fun resyncHosts() {
        hosts.appLists.value.forEach { (uuid, apps) -> syncHost(uuid, apps) }
    }

    /** Re-applies a host's app list, e.g. after a companion was paired. */
    suspend fun resyncHost(hostUuid: String) {
        hosts.appLists.value[hostUuid]?.let { syncHost(hostUuid, it) }
    }

    private suspend fun syncHost(hostUuid: String, all: List<RemoteApp>) {
        // Vibepollo's entries for another client's session (resume, quit, the game's copy): the
        // launcher resumes and quits itself, they'd only be extra tiles.
        val apps = all.filterNot { io.github.thecodingdad.gamelodge.data.remote.ForeignSessions.isHidden(it.app) }
        // Not an app list: a sync would delete every game of the host with its play time,
        // favourites and corrections. Apollo/Vibepollo answer a device without the "list
        // applications" permission (also briefly, e.g. while reloading) with one fake entry; an
        // empty list while the host has games is a hiccup, not every app removed at once.
        if (isPermissionPlaceholder(apps)) {
            Log.w("Host ${apps.first().hostName}: app list refused (no list permission), library left as it is")
            return
        }
        if (apps.isEmpty() && dao.getAllTargets().any { it.type == TargetType.REMOTE && it.hostUuid == hostUuid }) {
            Log.w("Host $hostUuid: empty app list, library left as it is")
            return
        }
        // The host is back (paired again): its kept games return.
        if (settings != null && hostUuid in settings.settings.first().detachedHosts) {
            settings.update { it.copy(detachedHosts = it.detachedHosts - hostUuid) }
        }
        // Optional: the host's paired companion knows what each entry is (game/app, platform,
        // store, groups, installed) and brings its metadata.
        val feed = companion?.games(hostUuid)
        sync.syncHost(
            hostUuid,
            apps.map { remote ->
                val info = remote.app.appUUID?.uppercase()?.let { feed?.get(it) }
                SourceEntry(
                    targetId = SourceEntry.remoteId(hostUuid, remote.app.appId),
                    type = TargetType.REMOTE,
                    name = remote.app.appName,
                    hostUuid = hostUuid,
                    hostName = remote.hostName,
                    appId = remote.app.appId,
                    appUuid = remote.app.appUUID,
                    hdrSupported = remote.app.isHdrSupported,
                    companionGame = info?.isGame,
                    installed = info?.installed ?: true,
                )
            },
        )
        Log.d("Host sync ${apps.firstOrNull()?.hostName}: ${apps.size} apps (${feed?.size ?: 0} from the companion)")
        feed?.let { applyCompanion(hostUuid, it) }
        suggestPlatforms()
        fetchBoxArt(hostUuid, apps)
    }

    /**
     * Groups, store, platform and metadata of each host entry the companion knows. Items whose
     * metadata was chosen here by hand (locked) keep it; hand-edited fields always stay.
     */
    private suspend fun applyCompanion(hostUuid: String, games: Map<String, io.github.thecodingdad.gamelodge.data.companion.HostGame>) {
        val feed = companion ?: return
        for (target in dao.getAllTargets().filter { it.hostUuid == hostUuid }) {
            val game = target.appUuid?.uppercase()?.let { games[it] } ?: continue
            val item = dao.getItem(target.itemId) ?: continue
            val groups = game.collections.joinToString(", ").ifEmpty { null }
            if (item.playniteCategories != groups || item.playnitePlugin != game.sourceLabel) {
                dao.setPlayniteInfo(item.id, groups, game.sourceLabel)
            }
            val platform = game.platform?.let(PlatformResolver::normalizeIgdb)
            if (PlatformResolver.mayReplace(item, PlatformSource.COMPANION, platform)) {
                dao.setPlatform(item.id, platform, PlatformSource.COMPANION)
            }
            applyHandPicked(hostUuid, item.id, game)
            val hasMetadata = game.igdbId != null || game.summary != null || game.cover != null
            if (!hasMetadata || item.metadataState == MetadataState.LOCKED) continue
            val fresh = dao.getItem(item.id) ?: continue
            val m = fresh.manual
            fun list(values: List<String>) = values.joinToString(", ").ifEmpty { null }
            // Only what is still empty: data set once is never replaced by a sync ("fetch all
            // again" in the metadata settings starts over by hand).
            fun <T> fill(field: MetaField, now: T?, new: T?): T? = if (field in m) now else now ?: new
            val updated = fresh.copy(
                igdbId = fresh.igdbId ?: game.igdbId,
                sgdbId = fresh.sgdbId ?: game.steamGridDbId,
                description = fill(MetaField.DESCRIPTION, fresh.description, game.summary),
                genres = fill(MetaField.GENRES, fresh.genres, list(game.genres)),
                developer = fill(MetaField.DEVELOPER, fresh.developer, list(game.developers)),
                publisher = fill(MetaField.PUBLISHER, fresh.publisher, list(game.publishers)),
                series = fill(MetaField.SERIES, fresh.series, list(game.series)),
                releaseDate = fill(MetaField.RELEASE, fresh.releaseDate, game.releaseDate?.times(1000)),
                releaseYear = fill(MetaField.RELEASE, fresh.releaseYear, game.releaseDate?.let { java.time.Instant.ofEpochSecond(it).atZone(java.time.ZoneOffset.UTC).year }),
                rating = fill(MetaField.RATING, fresh.rating, game.rating?.toFloat()),
                communityRating = fresh.communityRating ?: game.communityRating?.toFloat(),
                coverUrl = fill(MetaField.COVER, fresh.coverUrl, if (fresh.coverUrl == null) feed.artwork(hostUuid, game.cover) else null),
                heroUrl = fill(MetaField.HERO, fresh.heroUrl, if (fresh.heroUrl == null) feed.artwork(hostUuid, game.hero) else null),
                logoUrl = fill(MetaField.LOGO, fresh.logoUrl, if (fresh.logoUrl == null) feed.artwork(hostUuid, game.logo) else null),
                iconUrl = fill(MetaField.ICON, fresh.iconUrl, if (fresh.iconUrl == null) feed.artwork(hostUuid, game.icon) else null),
                animatedCoverUrl = fill(MetaField.ANIMATED_COVER, fresh.animatedCoverUrl, game.animatedCover),
                animatedHeroUrl = fill(MetaField.ANIMATED_HERO, fresh.animatedHeroUrl, game.animatedHero),
                metadataState = MetadataState.OK,
                matchConfidence = 1f,
            )
            if (updated != fresh) dao.upsertItem(updated)
        }
    }

    /**
     * What was set by hand on another device (the host lists it as manual) replaces the value here
     * and counts as set by hand here too; a new version of the host's own box art replaces the
     * older one. Everything else stays as it is.
     */
    private suspend fun applyHandPicked(hostUuid: String, itemId: String, game: io.github.thecodingdad.gamelodge.data.companion.HostGame) {
        val feed = companion ?: return
        val fresh = dao.getItem(itemId) ?: return
        val manual = game.manual.toSet()
        var u = fresh
        val picked = mutableSetOf<MetaField>()
        fun isManual(field: MetaField) = field.hostName in manual
        suspend fun image(field: MetaField, now: String?, ref: String?, set: (LibraryItemEntity, String?) -> LibraryItemEntity) {
            when {
                isManual(field) -> {
                    val value = feed.artwork(hostUuid, ref)
                    // A failed download keeps what is there.
                    if (ref == null || value != null) {
                        u = set(u, value)
                        picked += field
                    }
                }
                feed.isOlderVersion(now, ref) -> feed.artwork(hostUuid, ref)?.let { u = set(u, it) }
            }
        }
        fun text(field: MetaField, apply: (LibraryItemEntity) -> LibraryItemEntity) {
            if (isManual(field)) {
                u = apply(u)
                picked += field
            }
        }
        fun list(values: List<String>) = values.joinToString(", ").ifEmpty { null }
        image(MetaField.COVER, fresh.coverUrl, game.cover) { i, v -> i.copy(coverUrl = v) }
        image(MetaField.HERO, fresh.heroUrl, game.hero) { i, v -> i.copy(heroUrl = v) }
        image(MetaField.LOGO, fresh.logoUrl, game.logo) { i, v -> i.copy(logoUrl = v) }
        image(MetaField.ICON, fresh.iconUrl, game.icon) { i, v -> i.copy(iconUrl = v) }
        if (isManual(MetaField.ANIMATED_COVER)) { u = u.copy(animatedCoverUrl = game.animatedCover); picked += MetaField.ANIMATED_COVER }
        if (isManual(MetaField.ANIMATED_HERO)) { u = u.copy(animatedHeroUrl = game.animatedHero); picked += MetaField.ANIMATED_HERO }
        text(MetaField.TITLE) { it.copy(title = game.name, normalizedTitle = TitleNormalizer.normalize(game.name)) }
        text(MetaField.DESCRIPTION) { it.copy(description = game.summary) }
        text(MetaField.GENRES) { it.copy(genres = list(game.genres)) }
        text(MetaField.DEVELOPER) { it.copy(developer = list(game.developers)) }
        text(MetaField.PUBLISHER) { it.copy(publisher = list(game.publishers)) }
        text(MetaField.RELEASE) {
            it.copy(
                releaseDate = game.releaseDate?.times(1000),
                releaseYear = game.releaseDate?.let { s -> java.time.Instant.ofEpochSecond(s).atZone(java.time.ZoneOffset.UTC).year },
            )
        }
        if (picked.isNotEmpty()) u = u.copy(manualFields = (u.manual + picked).joinToString(",") { it.name })
        if (u != fresh) dao.upsertItem(u)
    }

    /**
     * A correction made here (match, artwork, edited fields, game/app) goes to the companion of
     * every host this item is on, so the other launchers get it too.
     */
    suspend fun pushCorrection(itemId: String, reset: List<String> = emptyList()) {
        val feed = companion ?: return
        val item = dao.getItem(itemId) ?: return
        val targets = dao.getTargetsForItem(itemId).filter { it.type == TargetType.REMOTE && it.hostUuid != null && it.appUuid != null }
        if (targets.isEmpty()) return
        // The companion only takes artwork from the providers' image servers (and no port).
        fun https(url: String?) = url?.takeIf { u ->
            val uri = runCatching { java.net.URI(u) }.getOrNull() ?: return@takeIf false
            val host = uri.host?.lowercase() ?: return@takeIf false
            uri.scheme == "https" && uri.port == -1 && u.length <= 1024 &&
                ARTWORK_HOSTS.any { host == it || host.endsWith(".$it") }
        }
        fun split(value: String?) = value?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        // Only what was picked or edited by hand: the companion keeps choosing the rest itself.
        val m = item.manual
        val matched = item.metadataState == MetadataState.LOCKED
        val correction = io.github.thecodingdad.gamelodge.data.companion.HostGameCorrection(
            igdbId = item.igdbId.takeIf { matched },
            steamGridDbId = item.sgdbId.takeIf { matched },
            name = item.title.takeIf { MetaField.TITLE in m },
            cover = https(item.coverUrl).takeIf { MetaField.COVER in m },
            hero = https(item.heroUrl).takeIf { MetaField.HERO in m },
            logo = https(item.logoUrl).takeIf { MetaField.LOGO in m },
            icon = https(item.iconUrl).takeIf { MetaField.ICON in m },
            animatedCover = https(item.animatedCoverUrl).takeIf { MetaField.ANIMATED_COVER in m },
            animatedHero = https(item.animatedHeroUrl).takeIf { MetaField.ANIMATED_HERO in m },
            summary = item.description.takeIf { MetaField.DESCRIPTION in m },
            genres = split(item.genres).takeIf { MetaField.GENRES in m },
            developers = split(item.developer).takeIf { MetaField.DEVELOPER in m },
            publishers = split(item.publisher).takeIf { MetaField.PUBLISHER in m },
            releaseDate = item.releaseDate?.div(1000).takeIf { MetaField.RELEASE in m },
            kind = if (item.kindSource == KindSource.MANUAL) (if (item.kind == ItemKind.APP) "app" else "game") else null,
            reset = reset.ifEmpty { null },
            // Picked by hand as "none": no animated image at all.
            clear = listOfNotNull(
                "animatedCover".takeIf { MetaField.ANIMATED_COVER in m && item.animatedCoverUrl == null },
                "animatedHero".takeIf { MetaField.ANIMATED_HERO in m && item.animatedHeroUrl == null },
            ).ifEmpty { null },
        )
        targets.forEach { feed.correct(it.hostUuid!!, it.appUuid!!, correction) }
    }

    /** The host game behind [itemId] as its companion delivers it (first host that has one). */
    suspend fun companionGame(itemId: String): io.github.thecodingdad.gamelodge.data.companion.HostGame? {
        val feed = companion ?: return null
        for (t in dao.getTargetsForItem(itemId).filter { it.type == TargetType.REMOTE && it.hostUuid != null && it.appUuid != null }) {
            feed.games(t.hostUuid!!)?.get(t.appUuid!!.uppercase())?.let { return it }
        }
        return null
    }

    /** Items nobody knows a platform for get a suggestion from how they are launched. */
    private suspend fun suggestPlatforms() {
        dao.observeAll().first().filter { it.item.platform == null }.forEach { entry ->
            PlatformResolver.suggest(entry)?.let { dao.setPlatform(entry.item.id, it, PlatformSource.SUGGESTED) }
        }
    }

    /** Manual platform choice; null returns the item to automatic detection. */
    suspend fun setPlatform(id: String, platform: String?) {
        if (platform != null) {
            dao.setPlatform(id, platform, PlatformSource.MANUAL)
        } else {
            dao.setPlatform(id, null, null)
            suggestPlatforms()
        }
    }

    private suspend fun fetchBoxArt(hostUuid: String, apps: List<RemoteApp>) {
        val host = hosts.hosts.value[hostUuid]?.takeIf { it.status == HostStatus.ONLINE } ?: return
        val targets = dao.getAllTargets().filter { it.hostUuid == hostUuid }.associateBy { it.id }
        val missing = apps.mapNotNull { remote ->
            val target = targets[SourceEntry.remoteId(hostUuid, remote.app.appId)] ?: return@mapNotNull null
            if (target.boxArtPath != null && java.io.File(target.boxArtPath).exists()) null else target to remote
        }
        // One after the other, each shown right away: a host with many apps takes a while, and
        // parallel downloads make it slow for everything else (pairing, state, streams).
        if (missing.isEmpty()) return
        var done = 0
        val taskId = "boxart-$hostUuid"
        fun report() = io.github.thecodingdad.gamelodge.data.tasks.BackgroundTasks.set(io.github.thecodingdad.gamelodge.data.tasks.BackgroundTask(taskId, Res.string.task_box_art, listOf(host.name), done, missing.size))
        report()
        _boxArtProgress.value = _boxArtProgress.value + (hostUuid to (0 to missing.size))
        try { missing.chunked(BOX_ART_PARALLEL).forEach { batch ->
            val updated = kotlinx.coroutines.coroutineScope {
                batch.map { (target, remote) ->
                    async {
                        val file = runCatching { hosts.boxArt(host, remote.app) }.getOrNull()
                        file?.let { target.copy(boxArtPath = it.absolutePath) }
                    }
                }.awaitAll().filterNotNull()
            }
            if (updated.isNotEmpty()) dao.upsertTargets(updated)
            done += batch.size
            report()
            _boxArtProgress.value = _boxArtProgress.value + (hostUuid to (done to missing.size))
        } } finally {
            io.github.thecodingdad.gamelodge.data.tasks.BackgroundTasks.remove(taskId)
            _boxArtProgress.value = _boxArtProgress.value - hostUuid
        }
    }

    /**
     * The host is gone: [keepGames] hides its games (with play time, metadata and corrections) until
     * it is paired again; otherwise they are deleted with their box art.
     */
    suspend fun removeHost(hostUuid: String, keepGames: Boolean = false) {
        if (keepGames && settings != null) {
            settings.update { it.copy(detachedHosts = it.detachedHosts + hostUuid) }
            return
        }
        settings?.update { it.copy(detachedHosts = it.detachedHosts - hostUuid) }
        sync.removeHost(hostUuid)
        hosts.forgetBoxArt(hostUuid)
    }

    /** Games of hosts removed with "keep": how many are hidden that way. */
    suspend fun detachedGameCount(): Int {
        val hosts = settings?.settings?.first()?.detachedHosts.orEmpty()
        if (hosts.isEmpty()) return 0
        return dao.getAllTargets().filter { it.type == TargetType.REMOTE && it.hostUuid in hosts }.map { it.itemId }.distinct().size
    }

    /** Deletes the kept games of removed hosts for good. */
    suspend fun deleteDetached() {
        val hosts = settings?.settings?.first()?.detachedHosts.orEmpty()
        hosts.forEach { removeHost(it, keepGames = false) }
    }

    suspend fun setFavorite(id: String, favorite: Boolean) {
        dao.setFavorite(id, favorite)
        playDataChanged()
    }

    /** The user's own rating, 1–5 stars; null clears it. */
    suspend fun setUserRating(id: String, stars: Int?) {
        dao.setUserRating(id, stars?.coerceIn(1, 5))
        playDataChanged()
    }

    /**
     * Hides or shows an entry. [here]: hidden on this device only, not on the other GameLodges of
     * its host; showing it again ends both.
     */
    suspend fun setHidden(id: String, hidden: Boolean, here: Boolean = false) {
        dao.setHiddenState(id, hidden, hidden && here)
        playDataChanged()
    }

    /** True when [entry] is a host game whose play data is shared with the host's other GameLodges. */
    fun sharesPlayData(entry: LibraryEntry): Boolean =
        playData != null && entry.remoteTargets.any { t -> t.hostUuid?.let { companion?.sharesPlayData(it) } == true }

    private var pendingPlaySync: kotlinx.coroutines.Job? = null

    /** A change to share: synced a moment later (several changes in a row go together). */
    private fun playDataChanged() {
        if (playData == null) return
        pendingPlaySync?.cancel()
        pendingPlaySync = scope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(PLAY_SYNC_DELAY_MS)
            syncPlayData()
        }
    }

    val collections = db.collectionDao().observeCollections()
    val memberships = db.collectionDao().observeMemberships()

    suspend fun setInCollection(itemId: String, collectionId: String, member: Boolean) {
        val dao = db.collectionDao()
        if (member) dao.addItem(ItemCollectionCrossRef(itemId, collectionId, dao.maxPosition(collectionId) + 1))
        else dao.removeItem(ItemCollectionCrossRef(itemId, collectionId))
        playDataChanged()
    }

    /** Stores the manual order of a group's members. */
    suspend fun reorderCollection(collectionId: String, itemIds: List<String>) = db.withTransaction {
        itemIds.forEachIndexed { index, id -> db.collectionDao().setPosition(id, collectionId, index) }
    }

    /** User correction "this is a game / an app"; survives every future sync. */
    suspend fun setKind(id: String, kind: ItemKind) {
        dao.setKind(id, kind, KindSource.MANUAL)
        // An app needs no game metadata: what was found for it as a game (often a wrong cover)
        // goes; a match picked by hand and hand-edited fields stay.
        if (kind == ItemKind.APP) dao.getItem(id)?.takeIf { it.metadataState != MetadataState.LOCKED && it.metadataState != MetadataState.NONE }
            ?.let { dao.upsertItem(it.clearedMetadata()) }
        pushCorrection(id)
    }
}

/** Box art downloads running at the same time. */
private const val BOX_ART_PARALLEL = 1

/** Apollo/Vibepollo's stand-in list for a device that may not list the apps: one fake entry. */
internal fun isPermissionPlaceholder(apps: List<RemoteApp>): Boolean {
    val only = apps.singleOrNull() ?: return false
    return only.app.appId == PERMISSION_PLACEHOLDER_ID || only.app.appName.startsWith("Permission denied", ignoreCase = true)
}

private const val PERMISSION_PLACEHOLDER_ID = 114514
