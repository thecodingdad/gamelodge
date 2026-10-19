package io.github.thecodingdad.gamelodge.desktop.games

import io.github.thecodingdad.gamelodge.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

private fun JsonObject.text(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

/**
 * itch.io: installed games of the itch app from its butler database (caves = installs), started
 * through the app (itch://caves/<id>/launch) so it handles sandboxing and updates.
 */
class ItchScanner(private val db: File = defaultDb()) : GameScanner {
    override val source = "itch"

    override suspend fun scan(): List<GameEntry> = withContext(Dispatchers.IO) {
        if (!db.exists()) return@withContext emptyList()
        runCatching { readInstalled(db) }.onFailure { Log.w("itch: database unreadable", it) }.getOrDefault(emptyList())
    }

    companion object {
        fun defaultDb() = File(System.getenv("APPDATA") ?: "", "itch/db/butler.db")

        fun readInstalled(db: File): List<GameEntry> = readSqliteCopy(db) { c ->
            c.rows(
                """
                SELECT caves.id, games.id, games.title, games.classification, games.cover_url, games.still_cover_url,
                       install_locations.path, caves.install_folder_name, caves.verdict
                FROM caves JOIN games ON games.id = caves.game_id
                LEFT JOIN install_locations ON install_locations.id = caves.install_location_id
                """.trimIndent(),
            ).mapNotNull { r ->
                val caveId = r[0] ?: return@mapNotNull null
                val title = r[2] ?: return@mapNotNull null
                val dir = if (r[6] != null && r[7] != null) File(r[6]!!, r[7]!!).path else null
                create(caveId, r[1], title, r[3], listOfNotNull(r[4], r[5]), dir, r[8])
            }
        }

        /** [verdict]: butler's JSON with the launch candidates ({"candidates":[{"path":"Game.exe"}]}). */
        fun create(caveId: String, gameId: String?, title: String, classification: String?, covers: List<String>, dir: String?, verdict: String?): GameEntry {
            val exe = verdict?.let { runCatching { lenientJson.parseToJsonElement(it) as? JsonObject }.getOrNull() }
                ?.let { v -> (v["candidates"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.text("path") }?.firstOrNull { p -> p.endsWith(".exe", true) } }
            return GameEntry(
                id = "itch:$caveId", name = title, source = "itch", storeId = gameId,
                // Tools and assets are apps, not games.
                game = classification == null || classification == "game",
                installed = true, installDir = dir,
                launch = "itch://caves/$caveId/launch",
                processNames = listOfNotNull(exe?.let(WinPath::fileName)),
                covers = covers.filter { it.startsWith("http") },
            )
        }
    }
}

/**
 * Rockstar Games Launcher: installed titles from the uninstall entries ("-uninstall=<title>"),
 * started through the launcher (it signs in and updates).
 */
class RockstarScanner : GameScanner {
    override val source = "rockstar"

    override suspend fun scan(): List<GameEntry> = withContext(Dispatchers.IO) {
        val launcherDir = Reg.read("HKLM", "SOFTWARE\\WOW6432Node\\Rockstar Games\\Launcher", "InstallFolder")
        val launcher = launcherDir?.let { File(it, "Launcher.exe") }?.takeIf { it.exists() }?.path
        Reg.uninstallEntries().mapNotNull { (_, get) ->
            val uninstall = get("UninstallString").orEmpty()
            if (!uninstall.contains("Rockstar Games", true) || !uninstall.contains("-uninstall=", true)) return@mapNotNull null
            val title = titleId(uninstall) ?: return@mapNotNull null
            if (title.equals("launcher", true) || title.equals("socialclub", true)) return@mapNotNull null
            val dir = get("InstallLocation")?.trim('"')?.takeIf { it.isNotEmpty() && File(it).isDirectory } ?: return@mapNotNull null
            create(title, get("DisplayName") ?: title, dir, launcher)
        }
    }

    companion object {
        fun titleId(uninstallString: String): String? {
            val i = uninstallString.indexOf("-uninstall=", ignoreCase = true)
            if (i < 0) return null
            return uninstallString.substring(i + "-uninstall=".length).takeWhile { it != ' ' && it != '"' }.takeIf { it.isNotEmpty() }
        }

        fun create(title: String, name: String, dir: String, launcher: String?) = GameEntry(
            id = "rockstar:${title.lowercase()}", name = name, source = "rockstar", storeId = title, installed = true, installDir = dir,
            launch = launcher, launchArgs = launcher?.let { "-launchTitleInFolder \"$dir\"" },
        )
    }
}

/**
 * Riot Client: installed products (VALORANT, League of Legends, Legends of Runeterra) from their
 * uninstall entries, started through the Riot Client with product and patchline.
 */
class RiotScanner : GameScanner {
    override val source = "riot"

    override suspend fun scan(): List<GameEntry> = withContext(Dispatchers.IO) {
        Reg.uninstallEntries().mapNotNull { (_, get) ->
            val uninstall = get("UninstallString").orEmpty()
            val product = argument(uninstall, "--uninstall-product") ?: return@mapNotNull null
            val patchline = argument(uninstall, "--uninstall-patchline") ?: "live"
            val client = uninstall.substringBefore("--uninstall-product").trim().trim('"').takeIf { it.endsWith(".exe", true) }
            val dir = get("InstallLocation")?.trim('"')?.takeIf { it.isNotEmpty() }
            create(product, patchline, get("DisplayName") ?: product, dir, client)
        }
    }

    companion object {
        fun argument(text: String, name: String): String? {
            val i = text.indexOf("$name=", ignoreCase = true)
            if (i < 0) return null
            return text.substring(i + name.length + 1).takeWhile { it != ' ' && it != '"' }.takeIf { it.isNotEmpty() }
        }

        fun create(product: String, patchline: String, name: String, dir: String?, client: String?) = GameEntry(
            id = "riot:$product.$patchline", name = name, source = "riot", storeId = product, installed = true, installDir = dir,
            launch = client, launchArgs = "--launch-product=$product --launch-patchline=$patchline",
        )
    }
}

/**
 * Humble App: the games of its local configuration (Humble Choice and Trove, owned), installed
 * ones with their folder; started and installed through the app (humble://).
 */
class HumbleScanner(private val config: File = defaultConfig()) : GameScanner {
    override val source = "humble"

    override suspend fun scan(): List<GameEntry> = withContext(Dispatchers.IO) {
        if (!config.exists()) return@withContext emptyList()
        runCatching { parse(config.readText()) }.onFailure { Log.w("Humble: configuration unreadable", it) }.getOrDefault(emptyList())
    }

    companion object {
        fun defaultConfig() = File(System.getenv("APPDATA") ?: "", "Humble App/config.json")

        fun parse(text: String): List<GameEntry> {
            val root = lenientJson.parseToJsonElement(text) as? JsonObject ?: return emptyList()
            val games = root.entries.filter { it.key.startsWith("game-collection") }.flatMap { (it.value as? JsonArray).orEmpty() }
            return games.mapNotNull { g -> (g as? JsonObject)?.let(::create) }.distinctBy { it.id }
        }

        private fun create(g: JsonObject): GameEntry? {
            val machine = g.text("downloadMachineName") ?: g.text("machineName") ?: return null
            val name = g.text("gameName") ?: g.text("game-name") ?: return null
            val dir = g.text("filePath")?.takeIf { File(it).isDirectory }
            val installed = dir != null && (g.text("status") ?: "").let { it.isEmpty() || it.equals("downloaded", true) || it.equals("installed", true) }
            val exe = g.text("executablePath")
            return GameEntry(
                id = "humble:$machine", name = name, source = "humble", storeId = machine, installed = installed,
                installDir = dir.takeIf { installed },
                launch = "humble://launch/$machine", install = "humble://download/$machine",
                processNames = listOfNotNull(exe?.let(WinPath::fileName)),
                covers = listOfNotNull(g.text("imagePath"), g.text("iconPath")).filter { it.startsWith("http") },
            )
        }

        private fun JsonElement?.orEmpty(): List<JsonElement> = (this as? JsonArray)?.toList() ?: emptyList()
    }
}

/**
 * Programs added to Steam by hand ("non-Steam games", config/shortcuts.vdf): started through
 * Steam so its overlay and Steam Input stay, with the artwork set in Steam's library.
 */
class SteamShortcutsScanner(private val steamRoot: () -> String? = { SteamScanner(okhttp3.OkHttpClient()).findSteamPath() }) : GameScanner {
    override val source = "steamshortcuts"

    override suspend fun scan(): List<GameEntry> = withContext(Dispatchers.IO) {
        val root = steamRoot() ?: return@withContext emptyList()
        val userdata = File(root, "userdata")
        // The most recent Steam user first; other users' shortcuts too (shared PC).
        val recent = SteamScanner.detectSteamId(root)?.toLongOrNull()?.let { (it - STEAM_ID64_BASE).toString() }
        val users = userdata.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { if (it.name == recent) 0 else 1 }
        users.flatMap { user ->
            val file = File(user, "config/shortcuts.vdf")
            if (!file.exists()) return@flatMap emptyList()
            runCatching { parse(file.readBytes(), File(user, "config/grid")) }
                .onFailure { Log.w("Steam: shortcuts of ${user.name} unreadable", it) }.getOrDefault(emptyList())
        }.distinctBy { it.id }
    }

    companion object {
        private const val STEAM_ID64_BASE = 76561197960265728L

        fun parse(bytes: ByteArray, grid: File?): List<GameEntry> {
            val shortcuts = BinaryVdf.parse(bytes)["shortcuts"] as? Map<*, *> ?: return emptyList()
            return shortcuts.values.mapNotNull { s ->
                val m = s as? Map<*, *> ?: return@mapNotNull null
                fun str(vararg keys: String) = keys.firstNotNullOfOrNull { k -> m.entries.firstOrNull { it.key.toString().equals(k, true) }?.value as? String }?.takeIf { it.isNotBlank() }
                val appId = (m.entries.firstOrNull { it.key.toString().equals("appid", true) }?.value as? Int) ?: return@mapNotNull null
                val name = str("AppName", "appname") ?: return@mapNotNull null
                val exe = str("Exe", "exe")?.trim('"')
                val unsigned = appId.toLong() and 0xFFFFFFFFL
                // Steam's game id of a shortcut: app id in the upper half, 0x02000000 below.
                val gameId = (unsigned shl 32) or 0x02000000L
                GameEntry(
                    id = "steamshortcut:$unsigned", name = name, source = "steamshortcuts", storeId = unsigned.toString(),
                    installed = true, installDir = str("StartDir", "startdir")?.trim('"'),
                    launch = "steam://rungameid/$gameId",
                    processNames = listOfNotNull(exe?.takeIf { it.endsWith(".exe", true) }?.let(WinPath::fileName)),
                    covers = gridImages(grid, unsigned, "p"),
                    storeHeroes = gridImages(grid, unsigned, "_hero"),
                    storeLogos = gridImages(grid, unsigned, "_logo"),
                )
            }
        }

        /** Artwork set in Steam's library: grid/<appid>p.png (cover), _hero, _logo. */
        private fun gridImages(grid: File?, appId: Long, suffix: String): List<String> =
            if (grid == null) emptyList()
            else listOf("png", "jpg", "jpeg", "webp").map { File(grid, "$appId$suffix.$it") }.filter { it.isFile }.map { it.path }
    }
}

/**
 * Valve's binary KeyValues (shortcuts.vdf): 0x00 opens a map, 0x01 a string, 0x02 an int32,
 * 0x08 closes the map. Maps become [Map]s of name → String / Int / Map.
 */
object BinaryVdf {
    fun parse(bytes: ByteArray): Map<String, Any> {
        var pos = 0
        fun cString(): String {
            val start = pos
            while (pos < bytes.size && bytes[pos] != 0.toByte()) pos++
            return String(bytes, start, pos - start, Charsets.UTF_8).also { pos++ }
        }
        fun readMap(): Map<String, Any> {
            val map = LinkedHashMap<String, Any>()
            while (pos < bytes.size) {
                when (bytes[pos++].toInt()) {
                    0x00 -> { val name = cString(); map[name] = readMap() }
                    0x01 -> { val name = cString(); map[name] = cString() }
                    0x02 -> {
                        val name = cString()
                        if (pos + 4 > bytes.size) return map
                        map[name] = (bytes[pos].toInt() and 0xFF) or ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
                            ((bytes[pos + 2].toInt() and 0xFF) shl 16) or ((bytes[pos + 3].toInt() and 0xFF) shl 24)
                        pos += 4
                    }
                    0x08 -> return map
                    else -> return map // unknown type: stop rather than misread
                }
            }
            return map
        }
        return readMap()
    }
}
