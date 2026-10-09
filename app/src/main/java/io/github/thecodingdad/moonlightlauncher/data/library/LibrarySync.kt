package io.github.thecodingdad.moonlightlauncher.data.library

import androidx.room.withTransaction
import io.github.thecodingdad.moonlightlauncher.data.db.ItemKind
import io.github.thecodingdad.moonlightlauncher.data.db.KindSource
import io.github.thecodingdad.moonlightlauncher.data.db.LaunchTargetEntity
import io.github.thecodingdad.moonlightlauncher.data.db.LauncherDatabase
import io.github.thecodingdad.moonlightlauncher.data.db.LibraryItemEntity
import io.github.thecodingdad.moonlightlauncher.data.db.TargetType
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
    val hasPlayniteId: Boolean = false,
) {
    companion object {
        fun localId(packageName: String) = "local:$packageName"
        fun remoteId(hostUuid: String, appId: Int) = "remote:$hostUuid:$appId"
    }
}

/**
 * Reconciles the library with one source at a time. A source owns a set of targets
 * (all local targets, or all targets of one host); targets it no longer reports are removed,
 * and items left without any target disappear.
 */
class LibrarySync(
    private val db: LauncherDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.libraryDao()
    private val mutex = Mutex()

    suspend fun syncLocal(entries: List<SourceEntry>) =
        sync(entries) { it.type == TargetType.LOCAL }

    suspend fun syncHost(hostUuid: String, entries: List<SourceEntry>) =
        sync(entries) { it.type == TargetType.REMOTE && it.hostUuid == hostUuid }

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

            for (entry in entries.distinctBy { it.targetId }) {
                val known = existing[entry.targetId]
                val itemId = if (known != null) {
                    reclassifyIfAuto(known.itemId, entry)
                    known.itemId
                } else {
                    findOrCreateItem(entry, now)
                }
                dao.upsertTargets(listOf(entry.toTarget(itemId, known?.boxArtPath, now)))
            }

            val reported = entries.mapTo(HashSet()) { it.targetId }
            val gone = existing.keys.filterNot { it in reported }
            if (gone.isNotEmpty()) dao.deleteTargets(gone)
            dao.deleteItemsWithoutTargets()
        }
    }

    private suspend fun findOrCreateItem(entry: SourceEntry, now: Long): String {
        val kind = Classifier.classify(entry.toClassificationInput())
        val normalized = TitleNormalizer.normalize(entry.name)
        dao.findByNormalizedTitle(normalized)?.let { existing ->
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

    /** New facts (e.g. a Playnite id appearing) may change an automatic decision. */
    private suspend fun reclassifyIfAuto(itemId: String, entry: SourceEntry) {
        val item = dao.getItem(itemId) ?: return
        if (item.kindSource != KindSource.AUTO) return
        val kind = Classifier.classify(entry.toClassificationInput())
        // Never downgrade a merged game because one of its sources looks like an app.
        if (kind == ItemKind.GAME && item.kind != ItemKind.GAME) dao.setKind(itemId, kind, KindSource.AUTO)
    }

    private fun SourceEntry.toClassificationInput() = ClassificationInput(
        title = name,
        isRemote = type == TargetType.REMOTE,
        remoteAppUuid = appUuid,
        androidGameFlag = androidGameFlag,
        hasPlayniteId = hasPlayniteId,
    )

    private fun SourceEntry.toTarget(itemId: String, boxArtPath: String?, now: Long) = LaunchTargetEntity(
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
        boxArtPath = boxArtPath,
        lastSeenAt = now,
    )
}
