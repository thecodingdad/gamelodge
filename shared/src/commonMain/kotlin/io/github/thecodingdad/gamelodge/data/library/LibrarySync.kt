package io.github.thecodingdad.gamelodge.data.library

import io.github.thecodingdad.gamelodge.data.db.withTransaction
import io.github.thecodingdad.gamelodge.data.db.ItemKind
import io.github.thecodingdad.gamelodge.data.db.KindSource
import io.github.thecodingdad.gamelodge.data.db.LaunchTargetEntity
import io.github.thecodingdad.gamelodge.data.db.LauncherDatabase
import io.github.thecodingdad.gamelodge.data.db.LibraryItemEntity
import io.github.thecodingdad.gamelodge.data.db.TargetType
import io.github.thecodingdad.gamelodge.data.db.clearedMetadata
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** One launchable entry as reported by a source (package manager or a host's app list). */
data class SourceEntry(
    val targetId: String,
    val type: TargetType,
    val name: String,
    val packageName: String? = null,
    val hostUuid: String? = null,
    val hostName: String? = null,
    val appId: Int? = null,
    val appUuid: String? = null,
    val hdrSupported: Boolean = false,
    val androidGameFlag: Boolean = false,
    /** Companion data: game (true) / app (false), null = unmanaged entry. */
    val companionGame: Boolean? = null,
    /** Companion data: false = owned only, the entry installs the game. */
    val installed: Boolean = true,
    /** ROMs: game file, its real path if known, catalog platform id and platform name. */
    val romUri: String? = null,
    val romPath: String? = null,
    val romPlatform: String? = null,
    val platform: String? = null,
    /** Fallback cover: an image next to the ROM or from ES-DE's media folder (URI or path). */
    val boxArt: String? = null,
) {
    companion object {
        fun localId(packageName: String) = "local:$packageName"
        fun remoteId(hostUuid: String, appId: Int) = "remote:$hostUuid:$appId"
        fun romId(uri: String) = "rom:$uri"
    }
}

/**
 * Reconciles the library with one source at a time. A source owns a set of targets
 * (all local targets, or all targets of one host); targets it no longer reports are removed,
 * and items left without any target disappear.
 */
class LibrarySync(
    private val db: LauncherDatabase,
    /** Packages of known emulators (they are apps, not games). */
    private val emulatorPackages: () -> Set<String> = { emptySet() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.libraryDao()
    private val mutex = Mutex()

    suspend fun syncLocal(entries: List<SourceEntry>) =
        sync(entries) { it.type == TargetType.LOCAL }

    suspend fun syncHost(hostUuid: String, entries: List<SourceEntry>) =
        sync(entries) { it.type == TargetType.REMOTE && it.hostUuid == hostUuid }

    /** All ROMs found in the configured folders (one source); ROMs under [keepUnder] stay as they are. */
    suspend fun syncRoms(entries: List<SourceEntry>, keepUnder: List<String> = emptyList()) =
        sync(entries) { t -> t.type == TargetType.ROM && keepUnder.none { t.romUri.orEmpty().startsWith(it) } }

    /** Drops everything that belongs to a removed host. */
    suspend fun removeHost(hostUuid: String) = sync(emptyList()) {
        it.type == TargetType.REMOTE && it.hostUuid == hostUuid
    }

    private suspend fun sync(
        entries: List<SourceEntry>,
        ownedBySource: (LaunchTargetEntity) -> Boolean,
    ) = mutex.withLock {
        db.withTransaction {
            val now = clock()
            val existing = dao.getAllTargets().filter(ownedBySource).associateBy { it.id }

            val reportedIds = entries.mapTo(HashSet()) { it.targetId }
            for (entry in entries.distinctBy { it.targetId }) {
                // Hosts may derive the numeric app id from the name: a renamed app is found again
                // by its UUID (Apollo/Vibepollo) and keeps its item with play time and metadata.
                val known = existing[entry.targetId] ?: entry.appUuid?.let { uuid ->
                    existing.values.firstOrNull { it.appUuid.equals(uuid, ignoreCase = true) && it.id !in reportedIds }
                }
                if (known != null && known.name != entry.name) followRename(known, entry)
                val itemId = if (known != null) {
                    reclassifyIfAuto(known.itemId, entry)
                    splitIfSharedWithSameSource(known, entry, now)
                } else {
                    findOrCreateItem(entry, now)
                }
                dao.upsertTargets(listOf(entry.toTarget(itemId, known, now)))
            }

            val gone = existing.keys.filterNot { it in reportedIds }
            if (gone.isNotEmpty()) dao.deleteTargets(gone)
            dao.deleteItemsWithoutTargets()
            relinkSessions()
        }
    }

    /**
     * Play sessions of an item that is gone go to the item now behind the same launch target (a
     * game removed and found again): its play time and last played come back from them.
     */
    private suspend fun relinkSessions() {
        val sessions = db.sessionDao()
        val orphans = sessions.getOrphans()
        if (orphans.isEmpty()) return
        val targets = dao.getAllTargets().associateBy { it.id }
        orphans.groupBy { targets[it.targetId]?.itemId }.forEach { (itemId, list) ->
            if (itemId == null) return@forEach
            list.forEach { sessions.relink(it.id, itemId) }
            val seconds = list.sumOf { s -> s.endedAt?.let { (it - s.startedAt).coerceAtLeast(0) / 1000 } ?: 0L }
            if (seconds > 0) dao.addPlaytime(itemId, seconds)
            list.mapNotNull { it.endedAt }.maxOrNull()?.let { dao.setLastPlayedIfNewer(itemId, it) }
        }
    }

    /**
     * The item takes the source's new name unless the user set the title (or another source named
     * it). Metadata found automatically for the old name is dropped and looked up again (a rename
     * usually corrects a wrongly recognised game); a match picked by hand stays.
     */
    private suspend fun followRename(known: LaunchTargetEntity, entry: SourceEntry) {
        val item = dao.getItem(known.itemId) ?: return
        if (item.title != known.name || io.github.thecodingdad.gamelodge.data.db.MetaField.TITLE in item.manual) return
        val base = if (item.metadataState == io.github.thecodingdad.gamelodge.data.db.MetadataState.LOCKED) item
        else item.clearedMetadata()
        dao.upsertItem(base.copy(title = entry.name, normalizedTitle = TitleNormalizer.normalize(entry.name)))
    }

    private suspend fun findOrCreateItem(entry: SourceEntry, now: Long): String {
        val kind = Classifier.classify(entry.toClassificationInput())
        val normalized = TitleNormalizer.normalize(entry.name)
        // Merge with an item of the same title from *another* source. Two same-named entries
        // of one host (e.g. two "Desktop" apps) are different apps and stay separate.
        val sameSource: (LaunchTargetEntity) -> Boolean = { t ->
            t.type == entry.type && (entry.type == TargetType.LOCAL || t.hostUuid == entry.hostUuid)
        }
        // ROMs only merge with an item of the same platform (or one without a known platform):
        // "Tetris" for the Game Boy is not "Tetris" for the NES.
        val samePlatform: (LibraryItemEntity) -> Boolean = { item ->
            entry.platform == null || item.platform == null || item.platform.equals(entry.platform, ignoreCase = true)
        }
        val candidate = dao.findByNormalizedTitle(normalized)
            .firstOrNull { item -> samePlatform(item) && dao.getTargetsForItem(item.id).none(sameSource) }
        candidate?.let { existing ->
            // Same title from another source: merge. Many Android games lack the game flag,
            // so if any source says "game" an automatically classified item becomes a game.
            if (existing.kindSource == KindSource.AUTO && kind == ItemKind.GAME && existing.kind != ItemKind.GAME) {
                dao.setKind(existing.id, ItemKind.GAME, KindSource.AUTO)
            }
            return existing.id
        }

        val item = LibraryItemEntity(
            id = UUID.randomUUID().toString(),
            title = entry.name,
            normalizedTitle = normalized,
            kind = kind,
            addedAt = now,
        )
        dao.upsertItem(item)
        return item.id
    }

    /**
     * Repairs items that hold two targets of the same source (older merge rule): the target
     * with the larger id moves to an item of its own. Returns the item the target belongs to.
     */
    private suspend fun splitIfSharedWithSameSource(known: LaunchTargetEntity, entry: SourceEntry, now: Long): String {
        val siblings = dao.getTargetsForItem(known.itemId).filter {
            it.id != known.id && it.type == known.type && (known.type == TargetType.LOCAL || it.hostUuid == known.hostUuid)
        }
        if (siblings.none { it.id < known.id }) return known.itemId
        val source = dao.getItem(known.itemId)
        val item = LibraryItemEntity(
            id = UUID.randomUUID().toString(),
            title = entry.name,
            normalizedTitle = TitleNormalizer.normalize(entry.name),
            kind = source?.kind ?: Classifier.classify(entry.toClassificationInput()),
            kindSource = source?.kindSource ?: KindSource.AUTO,
            addedAt = now,
        )
        dao.upsertItem(item)
        return item.id
    }

    /** New facts (e.g. a Playnite id appearing) may change an automatic decision. */
    private suspend fun reclassifyIfAuto(itemId: String, entry: SourceEntry) {
        val item = dao.getItem(itemId) ?: return
        if (item.kindSource != KindSource.AUTO) return
        val input = entry.toClassificationInput()
        val kind = Classifier.classify(input)
        when {
            // Never downgrade a merged game just because one source looks like an app...
            kind == ItemKind.GAME && item.kind != ItemKind.GAME -> dao.setKind(itemId, kind, KindSource.AUTO)
            // ...unless it is a known tool (e.g. a streaming client or emulator flagged as game).
            // Metadata found for it as a game (often a wrong cover) is dropped; a hand-picked match stays.
            Classifier.isKnownApp(input) && item.kind != ItemKind.APP -> {
                dao.setKind(itemId, ItemKind.APP, KindSource.AUTO)
                dao.getItem(itemId)?.takeIf { it.metadataState != io.github.thecodingdad.gamelodge.data.db.MetadataState.LOCKED }
                    ?.let { dao.upsertItem(it.clearedMetadata()) }
            }
        }
    }

    private fun SourceEntry.toClassificationInput() = ClassificationInput(
        title = name,
        isRemote = type == TargetType.REMOTE,
        remoteAppUuid = appUuid,
        androidGameFlag = androidGameFlag,
        // A ROM is a game by definition.
        companionGame = if (type == TargetType.ROM) true else companionGame,
        packageName = packageName,
        isEmulator = type == TargetType.LOCAL && packageName != null && packageName in emulatorPackages(),
    )

    private fun SourceEntry.toTarget(itemId: String, known: LaunchTargetEntity?, now: Long) = LaunchTargetEntity(
        id = targetId,
        itemId = itemId,
        type = type,
        name = name,
        packageName = packageName,
        hostUuid = hostUuid,
        hostName = hostName,
        appId = appId,
        appUuid = appUuid,
        hdrSupported = hdrSupported,
        boxArtPath = boxArt ?: known?.boxArtPath,
        lastSeenAt = now,
        installed = installed,
        romUri = romUri,
        romPath = romPath,
        romPlatform = romPlatform,
        // Chosen by the user: kept across rescans.
        emulatorKey = known?.emulatorKey,
    )
}
