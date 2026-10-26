package io.github.thecodingdad.gamelodge.desktop.games

import io.github.thecodingdad.gamelodge.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The launch options Steam knows per game (Steam's local app info cache, appcache/appinfo.vdf):
 * besides the default, e.g. "Play DX11", "Launch VR mode" or the game's settings program. Read
 * for installed games only, cached as long as the file stays the same.
 */
object SteamLaunchOptions {
    /** One Windows launch option of a game (paths relative to its install folder). */
    data class Option(val key: String, val description: String, val executable: String, val arguments: String?, val workingDir: String?)

    private var cachedStamp: Pair<Long, Long>? = null
    private var cached: Map<Int, List<Option>> = emptyMap()
    private var cachedFor: Set<Int> = emptySet()
    private var cachedLanguage = ""

    /** Options of [appIds] (other than the default one), in [language] where Steam has a translation ("german" …). */
    @Synchronized
    fun read(steamRoot: String, appIds: Set<Int>, language: String = "english"): Map<Int, List<Option>> {
        val file = File(steamRoot, "appcache/appinfo.vdf")
        if (!file.isFile || appIds.isEmpty()) return emptyMap()
        val stamp = file.length() to file.lastModified()
        if (stamp != cachedStamp || language != cachedLanguage || !cachedFor.containsAll(appIds)) {
            cached = runCatching { parse(file.readBytes(), appIds, language) }
                .onFailure { Log.w("Steam: launch options unreadable", it) }
                .getOrDefault(emptyMap())
            cachedStamp = stamp
            cachedFor = appIds
            cachedLanguage = language
        }
        return cached.filterKeys { it in appIds }
    }

    /** Parses appinfo.vdf (format 28 and 29) for [appIds]; others are skipped by their size. */
    internal fun parse(bytes: ByteArray, appIds: Set<Int>, language: String = "english"): Map<Int, List<Option>> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buf.int
        buf.int // universe
        val version = magic and 0xFF
        require(magic ushr 8 == 0x075644 && version in 0x27..0x29) { "unknown appinfo.vdf format ${Integer.toHexString(magic)}" }
        // Format 29 names keys by index into a string table at the end.
        val strings: List<String>? = if (version >= 0x29) {
            val tableAt = buf.long.toInt()
            val table = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN).position(tableAt) as ByteBuffer
            List(table.int) { cString(table) }
        } else null
        val result = HashMap<Int, List<Option>>()
        while (buf.remaining() >= 8) {
            val appId = buf.int
            if (appId == 0) break
            val size = buf.int
            val end = buf.position() + size
            if (appId in appIds) {
                // infoState, lastUpdated, picsToken, sha1 (text), changeNumber, sha1 (binary data; 28+).
                buf.position(buf.position() + 4 + 4 + 8 + 20 + 4 + (if (version >= 0x28) 20 else 0))
                val root = readNode(buf, strings)
                options(root, language).takeIf { it.isNotEmpty() }?.let { result[appId] = it }
            }
            buf.position(end)
        }
        return result
    }

    /** A binary key/value node: maps for children, strings and ints as values. */
    private fun readNode(buf: ByteBuffer, strings: List<String>?): Map<String, Any> {
        val map = LinkedHashMap<String, Any>()
        while (true) {
            val type = buf.get().toInt()
            if (type == 0x08) return map
            val key = if (strings != null) strings.getOrElse(buf.int) { "" } else cString(buf)
            when (type) {
                0x00 -> map[key] = readNode(buf, strings)
                0x01 -> map[key] = cString(buf)
                0x02, 0x04, 0x06 -> map[key] = buf.int
                0x03 -> map[key] = buf.float
                0x07 -> map[key] = buf.long
                0x0A -> map[key] = buf.long
                else -> error("unknown key/value type $type")
            }
        }
    }

    private fun cString(buf: ByteBuffer): String {
        val start = buf.position()
        while (buf.get() != 0.toByte()) Unit
        val bytes = ByteArray(buf.position() - start - 1)
        buf.duplicate().position(start).get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any>.node(key: String) = this[key] as? Map<String, Any>

    /** The Windows launch options with a description, except the default one (that is "play"). */
    private fun options(root: Map<String, Any>, language: String): List<Option> {
        val launch = root.node("appinfo")?.node("config")?.node("launch") ?: return emptyList()
        val all = launch.entries.mapNotNull { (key, value) ->
            @Suppress("UNCHECKED_CAST")
            val o = value as? Map<String, Any> ?: return@mapNotNull null
            val exe = (o["executable"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val os = (o.node("config")?.get("oslist") as? String)?.lowercase()
            if (os != null && "windows" !in os) return@mapNotNull null
            val type = (o["type"] as? String)?.lowercase()
            if (type == "none") return@mapNotNull null
            val description = (o.node("description_loc")?.get(language) as? String) ?: (o["description"] as? String)
            Triple(key, o, description) to (type == "default")
        }
        // The first default (or the first option at all) is what Steam's "Play" starts.
        val play = all.firstOrNull { it.second }?.first?.first ?: all.firstOrNull()?.first?.first
        return all.map { it.first }.filter { (key, _, description) -> key != play && !description.isNullOrBlank() }
            .map { (key, o, description) ->
                Option(
                    key, description!!.trim(), (o["executable"] as String).replace('/', '\\'),
                    (o["arguments"] as? String)?.takeIf { it.isNotBlank() }, (o["workingdir"] as? String)?.takeIf { it.isNotBlank() }?.replace('/', '\\'),
                )
            }
    }

    /** [options] of a game installed in [installDir] as start actions. */
    fun actions(appId: Int, installDir: String, options: List<Option>): List<CustomAction> = options.map { o ->
        val dir = installDir.trimEnd('\\', '/')
        val exe = "$dir\\${o.executable}"
        CustomAction(
            id = "steam:$appId:${o.key}", name = o.description, launch = exe, arguments = o.arguments,
            workingDir = o.workingDir?.let { "$dir\\$it" } ?: exe.substringBeforeLast('\\'),
            processNames = listOf(o.executable.substringAfterLast('\\')),
        )
    }
}
