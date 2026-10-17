package io.github.thecodingdad.gamelodge.data.emulation

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import io.github.thecodingdad.gamelodge.util.Log
import java.io.File

/** One game found in a ROM folder. */
data class RomFile(
    val name: String,
    val platform: CatalogPlatform,
    /** Document URI (inside the granted folder tree). */
    val uri: String,
    /** Real path when it can be derived (internal storage / SD card). */
    val path: String?,
    /** Image next to the ROM or from ES-DE's media folder, as URI or path. */
    val image: String?,
)

/**
 * Finds games in the user's ROM folders. Works through the storage access framework (the folder
 * the user picked); with "all files" access the same folders are read directly, which is faster.
 * Naming follows the companion: tags like "(Europe) (Rev 1)" go, generic file names ("Default",
 * "EBOOT") are replaced by their folder, playlists (.m3u) win over single discs, a .bin with a
 * .cue next to it is part of that game, Switch updates/DLC are no games.
 */
class RomScanner(private val context: Context, private val catalog: EmulatorCatalog) {
    private val resolver get() = context.contentResolver

    /** A file or folder in a tree, by document id. */
    private data class Node(val docId: String, val name: String, val dir: Boolean)

    /** Games found, and the folders that couldn't be read (their games must be kept, not dropped). */
    data class Result(val roms: List<RomFile>, val unreadable: List<RomFolder>)

    fun scan(config: EmulationConfig): Result {
        val unreadable = ArrayList<RomFolder>()
        val roms = config.folders.flatMap { folder ->
            runCatching { scanFolder(folder) }.getOrElse {
                Log.w("ROM folder ${folder.name} not readable: ${it.message}")
                unreadable += folder
                emptyList()
            }
        }.distinctBy { it.uri }
        return Result(roms, unreadable)
    }

    private fun scanFolder(folder: RomFolder): List<RomFile> {
        val tree = Uri.parse(folder.uri)
        // Access lost (e.g. restored backup on a new device): the folder must be picked again.
        check(resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }) { "no access" }
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val platform = catalog.platform(folder.platform)
        if (platform != null) return scanPlatform(tree, rootId, platform)
        // One subfolder per system, recognised by its name.
        return children(tree, rootId).filter { it.dir }.flatMap { sub ->
            catalog.platformForFolder(sub.name)?.let { scanPlatform(tree, sub.docId, it) }.orEmpty()
        }
    }

    private fun scanPlatform(tree: Uri, rootId: String, platform: CatalogPlatform): List<RomFile> {
        val files = ArrayList<Pair<Node, List<String>>>() // file, folder names from the platform root down
        val images = HashMap<String, String>() // "<dir docId>/<base name>" → image doc id
        fun walk(id: String, trail: List<String>, depth: Int) {
            for (node in children(tree, id)) {
                if (node.dir) {
                    // Games stored as folders carry an extension ("Game.ps3").
                    if (platform.extensions.any { node.name.endsWith(it, ignoreCase = true) }) files += node to trail
                    else if (depth < MAX_DEPTH) walk(node.docId, trail + node.name, depth + 1)
                    continue
                }
                val ext = extension(node.name)
                if (ext in IMAGE_EXTENSIONS) images["$id/${baseName(node.name).lowercase()}"] = node.docId
                if (ext in platform.extensions) files += node to trail
            }
        }
        walk(rootId, emptyList(), 0)

        val names = files.mapTo(HashSet()) { it.first.name.lowercase() }
        // Best file per game name: lower priority wins.
        val picked = HashMap<String, Triple<Node, Int, String>>()
        for ((node, trail) in files) {
            val ext = extension(node.name)
            val base = baseName(node.name)
            if (ext == ".bin" && "${base.lowercase()}.cue" in names) continue
            if (platform.id == "switch" && RomNames.isSwitchAddon(node.name)) continue
            val name = when {
                // Wii U: "<game>/code/<title>.rpx" — the game folder names it.
                ext == ".rpx" -> trail.takeIf { it.size >= 2 && it.last().equals("code", true) }?.let { RomNames.clean(it[it.size - 2]) } ?: continue
                RomNames.isGeneric(base) -> RomNames.folderName(trail)?.let(RomNames::clean) ?: RomNames.clean(base)
                else -> RomNames.clean(base)
            }.ifBlank { continue }
            val disc = RomNames.disc(node.name)
            val priority = platform.extensions.indexOf(ext).coerceAtLeast(0) + (if (ext == ".m3u") -100 else 0) + (if (disc != null && disc > 1) 1000 else 0)
            val have = picked[name.lowercase()]
            if (have == null || priority < have.second) picked[name.lowercase()] = Triple(node, priority, name)
        }

        return picked.values.map { (node, _, name) ->
            val parentId = node.docId.substringBeforeLast('/', rootId)
            val image = images["$parentId/${baseName(node.name).lowercase()}"]?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it).toString() }
            val path = pathOf(node.docId)
            RomFile(
                name = name,
                platform = platform,
                uri = DocumentsContract.buildDocumentUriUsingTree(tree, node.docId).toString(),
                path = path,
                image = image ?: path?.let { esDeMedia(platform.id, baseName(node.name)) },
            )
        }.sortedBy { it.name.lowercase() }
    }

    private fun children(tree: Uri, parentId: String): List<Node> {
        // "All files" access: list the real folder (much faster than the provider).
        if (allFilesAccess()) pathOf(parentId)?.let { path ->
            val dir = File(path)
            val prefix = if (parentId.endsWith(':')) parentId else "$parentId/"
            if (dir.isDirectory) return dir.listFiles().orEmpty().map { Node(prefix + it.name, it.name, it.isDirectory) }
        }
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        return resolver.query(uri, columns, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(Node(c.getString(0), c.getString(1) ?: continue, c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR))
                }
            }
        }.orEmpty()
    }

    /** Cover downloaded by ES-DE (readable with "all files" access). */
    private fun esDeMedia(platformId: String, base: String): String? {
        if (!allFilesAccess()) return null
        val dir = File(Environment.getExternalStorageDirectory(), "ES-DE/downloaded_media/$platformId/covers")
        return IMAGE_EXTENSIONS.map { File(dir, base + it) }.firstOrNull { it.isFile }?.absolutePath
    }

    companion object {
        private const val MAX_DEPTH = 4
        private val SD_VOLUME = Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")
        private val IMAGE_EXTENSIONS = listOf(".png", ".jpg", ".jpeg", ".webp")

        fun allFilesAccess(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

        /**
         * Real path of an external storage document id: "primary:ROMs/snes/x.sfc" is on internal
         * storage, "1234-ABCD:…" on that SD card. Null for anything else (cloud providers …).
         */
        fun pathOf(docId: String): String? {
            val volume = docId.substringBefore(':', "")
            // Only real volumes: internal storage or an SD card id ("1234-ABCD"), not e.g. "msf:42".
            if (volume != "primary" && !SD_VOLUME.matches(volume)) return null
            val rest = docId.substringAfter(':')
            val root = if (volume == "primary") Environment.getExternalStorageDirectory().path else "/storage/$volume"
            return if (rest.isEmpty()) root else "$root/$rest"
        }

        private fun extension(name: String) = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".${it.lowercase()}" }
        private fun baseName(name: String) = name.substringBeforeLast('.', name)
    }
}
