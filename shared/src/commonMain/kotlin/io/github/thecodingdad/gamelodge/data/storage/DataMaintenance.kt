package io.github.thecodingdad.gamelodge.data.storage

import io.github.thecodingdad.gamelodge.data.artwork.ArtworkFiles
import io.github.thecodingdad.gamelodge.data.db.LibraryDao
import io.github.thecodingdad.gamelodge.data.library.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** What a part of the launcher's data is, for the overview in Settings › Data. */
enum class StorageKind {
    DATABASE,
    SETTINGS,
    /** Downloaded artwork of the library (fetched again when deleted). */
    ARTWORK,
    /** Images chosen by the user and app tiles. */
    OWN_IMAGES,
    /** Cover images the hosts provide for their apps. */
    HOST_ART,
    /** Can be deleted at any time; filled again when needed. */
    CACHE,
    LOGS,
    /** Streaming client (PC: Moonlight-Qt and its settings). */
    STREAMING,
    /** Streaming host of the PC: covers and backups of the host's app list. */
    HOST_ROLE,
}

/** Files and folders of one [kind]; sizes are summed up recursively. */
data class StorageArea(val kind: StorageKind, val files: List<File>)

/** Unused data that can go: games kept from removed hosts and images nothing refers to any more. */
data class Orphans(val detachedGames: Int, val files: List<File>) {
    val bytes: Long get() = files.sumOf { it.length() }
    val isEmpty: Boolean get() = detachedGames == 0 && files.isEmpty()
}

/**
 * Overview and clean-up of the launcher's data. The shared folders (artwork, own images, host
 * covers) live in [filesDir]; the platform adds its own ([platformAreas]).
 */
class DataMaintenance(
    private val filesDir: File,
    private val dao: LibraryDao,
    private val library: LibraryRepository,
    private val platformAreas: () -> List<StorageArea>,
) {
    private val ownImageDirs get() = listOf(File(filesDir, "own-artwork"), File(filesDir, "tiles"))
    private val hostArtDir get() = File(filesDir, "boxart")

    fun areas(): List<StorageArea> = listOf(
        StorageArea(StorageKind.ARTWORK, listOf(File(filesDir, "artwork"))),
        StorageArea(StorageKind.OWN_IMAGES, ownImageDirs),
        StorageArea(StorageKind.HOST_ART, listOf(hostArtDir)),
    ) + platformAreas()

    /** Bytes per kind (kinds without data are left out). */
    suspend fun usage(): Map<StorageKind, Long> = withContext(Dispatchers.IO) {
        areas().groupBy { it.kind }
            .mapValues { (_, list) -> list.flatMap { it.files }.distinct().sumOf(::sizeOf) }
            .filterValues { it > 0 }
    }

    suspend fun findOrphans(): Orphans = withContext(Dispatchers.IO) {
        val items = dao.getAllItems()
        val used = buildSet {
            items.forEach { i ->
                listOf(i.coverUrl, i.heroUrl, i.logoUrl, i.iconUrl, i.animatedCoverUrl, i.animatedHeroUrl, i.tileUrl)
                    .forEach { url -> url?.takeIf { it.startsWith("/") }?.let { add(File(it).absolutePath) } }
            }
            dao.getAllTargets().forEach { t -> t.boxArtPath?.let { add(File(it).absolutePath) } }
        }
        // Files just made may belong to an edit that isn't saved yet.
        val settled = System.currentTimeMillis() - SETTLE_MS
        val files = (ownImageDirs + hostArtDir)
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile }.toList() }
            .filter { it.absolutePath !in used && it.lastModified() < settled }
        Orphans(library.detachedGameCount(), files)
    }

    suspend fun deleteOrphans() = withContext(Dispatchers.IO) {
        library.deleteDetached()
        findOrphans().files.forEach { it.delete() }
        // Host folders left empty (desktop keeps one folder per host).
        hostArtDir.listFiles()?.filter { it.isDirectory && it.list().isNullOrEmpty() }?.forEach { it.delete() }
    }

    /** Empties the [StorageKind.CACHE] folders (the image loader's cache is cleared by the caller). */
    suspend fun clearCache() = withContext(Dispatchers.IO) {
        areas().filter { it.kind == StorageKind.CACHE }.flatMap { it.files }.forEach { f ->
            if (f.isDirectory) f.listFiles()?.forEach { it.deleteRecursively() } else f.delete()
        }
    }

    /** Deletes the downloaded artwork; it is downloaded again in the background. */
    suspend fun redownloadArtwork() = withContext(Dispatchers.IO) { ArtworkFiles.clear() }

    private fun sizeOf(file: File): Long = if (file.isDirectory) file.walkTopDown().filter { it.isFile }.sumOf { it.length() } else file.length()

    private companion object {
        const val SETTLE_MS = 30L * 60 * 1000
    }
}
