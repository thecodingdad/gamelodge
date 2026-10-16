package io.github.thecodingdad.moonlightlauncher.desktop.games

import io.github.thecodingdad.moonlightlauncher.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Steam: installed games from libraryfolders.vdf + appmanifest_*.acf. Owned but not installed
 * games come from the Web API (Playnite's key, when Playnite is set up with one) or the public
 * profile when its game details are public.
 */
class SteamScanner(
    private val http: OkHttpClient,
    private val steamPath: String? = null,
    private val includeOwned: Boolean = true,
    /** Signed-in Steam account: lists every owned game, also with a private profile. */
    private val account: SteamAccount? = null,
) : GameScanner {
    override val source = "steam"

    fun findSteamPath(): String? = listOfNotNull(
        steamPath,
        Reg.read("HKCU", "Software\\Valve\\Steam", "SteamPath"),
        Reg.read("HKLM", "SOFTWARE\\WOW6432Node\\Valve\\Steam", "InstallPath"),
        "C:\\Program Files (x86)\\Steam",
    ).firstOrNull { it.isNotBlank() && File(it).isDirectory }?.replace('/', File.separatorChar)

    override suspend fun scan(): List<GameEntry> = withContext(Dispatchers.IO) {
        val root = findSteamPath()
        val result = LinkedHashMap<String, GameEntry>()
        if (root == null) {
            Log.i("Steam: no installation found")
        } else {
            for (library in libraries(root)) {
                val apps = File(library, "steamapps")
                val manifests = apps.listFiles { f -> f.name.startsWith("appmanifest_") && f.name.endsWith(".acf") }.orEmpty()
                Log.d("Steam: library $library: ${manifests.size} manifests")
                for (file in manifests) {
                    runCatching { parseManifest(file.readText(), apps.path) }
                        .onFailure { Log.w("Steam: manifest $file unreadable", it) }
                        .getOrNull()?.let { result[it.id] = it }
                }
            }
        }
        if (includeOwned && root != null) {
            val token = account?.accessToken()
            val id = account?.steamId?.takeIf { token != null } ?: detectSteamId(root)
            if (id == null) {
                Log.i("Steam: owned games skipped (not logged in to Steam?)")
            } else {
                // Order: signed-in account, Playnite's API key, public profile.
                val owned = token?.let { ownedGames("access_token=$it", id) }
                    ?: playniteSteamKey()?.let { ownedGames("key=$it", id) }
                    ?: publicProfileGames(id)
                owned?.forEach { result.putIfAbsent(it.id, it) }
            }
        }
        result.values.sortedBy { it.name.lowercase() }
    }

    private fun libraries(root: String): List<String> {
        val file = File(root, "steamapps/libraryfolders.vdf")
        val paths = mutableListOf(root)
        if (file.exists()) {
            runCatching {
                val vdf = VdfNode.parse(file.readText())
                vdf["libraryfolders"]?.children?.values?.forEach { folder ->
                    // New format: { "path" "D:\\SteamLibrary" … }, old format: "1" "D:\\SteamLibrary".
                    (folder.value ?: folder.value("path"))?.takeIf { it.isNotBlank() }?.let { paths += it.replace("\\\\", "\\") }
                }
            }.onFailure { Log.w("Steam: libraryfolders.vdf unreadable", it) }
        }
        return paths.distinctBy { it.lowercase() }
    }

    /** [auth]: "key=…" or "access_token=…" (never logged). */
    private fun ownedGames(auth: String, steamId: String): List<GameEntry>? = runCatching {
        val url = "https://api.steampowered.com/IPlayerService/GetOwnedGames/v1/?$auth&steamid=$steamId&include_appinfo=1&include_played_free_games=1"
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return@use null
            val games = Json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject["response"]?.jsonObject?.get("games")?.jsonArray ?: return@use null
            games.map { g ->
                val o = g.jsonObject
                val id = o.getValue("appid").jsonPrimitive.long.toString()
                owned(id, o["name"]?.jsonPrimitive?.content ?: id)
            }.also { Log.i("Steam: Web API lists ${it.size} owned games") }
        }
    }.onFailure { Log.w("Steam: Web API request failed (${it.javaClass.simpleName})") }.getOrNull()

    /** Owned games from the public profile (works when "game details" are public). */
    private fun publicProfileGames(steamId: String): List<GameEntry>? = runCatching {
        http.newCall(Request.Builder().url("https://steamcommunity.com/profiles/$steamId/games?tab=all&xml=1").build()).execute().use { r ->
            val xml = r.body?.string().orEmpty()
            if (!xml.trimStart().startsWith("<?xml")) {
                Log.i("Steam: public profile game list not available")
                return@use null
            }
            parseProfileGames(xml).takeIf { it.isNotEmpty() }?.also { Log.i("Steam: public profile lists ${it.size} owned games") }
        }
    }.onFailure { Log.w("Steam: public profile not readable", it) }.getOrNull()

    companion object {
        /** Steam's own tools that show up as "installed apps". */
        private val toolIds = setOf("228980", "1070560", "1391110", "1628350", "1493710", "2180100", "2805730", "250820", "1007", "7")

        /** One appmanifest; null for Steam tools and broken manifests. */
        fun parseManifest(text: String, steamappsDir: String): GameEntry? {
            val state = VdfNode.parse(text)["AppState"] ?: return null
            val id = state.value("appid")?.takeIf { it.isNotEmpty() } ?: return null
            val name = state.value("name")?.takeIf { it.isNotEmpty() } ?: return null
            if (id in toolIds || name.startsWith("Proton", true) || name.startsWith("Steam Linux Runtime", true)) return null
            // StateFlags bit 4 = fully installed; others are downloads in progress or updates.
            val installed = (state.value("StateFlags")?.toIntOrNull() ?: 0) and 4 != 0
            val dir = state.value("installdir")
            return owned(id, name).copy(installed = installed, installDir = dir?.let { File(File(steamappsDir, "common"), it).path })
        }

        private fun owned(id: String, name: String) = GameEntry(
            id = "steam:$id",
            name = name,
            source = "steam",
            storeId = id,
            installed = false,
            launch = "steam://rungameid/$id",
            install = "steam://install/$id",
            covers = coverUrls(id),
            storeHeroes = listOf(
                "https://shared.steamstatic.com/store_item_assets/steam/apps/$id/library_hero.jpg",
                "https://cdn.cloudflare.steamstatic.com/steam/apps/$id/library_hero.jpg",
            ),
            storeLogos = listOf(
                "https://shared.steamstatic.com/store_item_assets/steam/apps/$id/logo.png",
                "https://cdn.cloudflare.steamstatic.com/steam/apps/$id/logo.png",
            ),
        )

        /** Steam's portrait library artwork (600x900), no key needed. */
        fun coverUrls(id: String) = listOf(
            "https://shared.steamstatic.com/store_item_assets/steam/apps/$id/library_600x900_2x.jpg",
            "https://cdn.cloudflare.steamstatic.com/steam/apps/$id/library_600x900_2x.jpg",
            "https://cdn.cloudflare.steamstatic.com/steam/apps/$id/library_600x900.jpg",
        )

        /** SteamID64 of the most recent Steam login (config/loginusers.vdf). */
        fun detectSteamId(steamRoot: String): String? {
            val file = File(steamRoot, "config/loginusers.vdf")
            if (!file.exists()) return null
            return runCatching {
                val users = VdfNode.parse(file.readText())["users"]?.children ?: return null
                (users.entries.firstOrNull { it.value.value("MostRecent") == "1" } ?: users.entries.firstOrNull())?.key
            }.getOrNull()
        }

        fun parseProfileGames(xml: String): List<GameEntry> {
            val doc = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }.newDocumentBuilder().parse(xml.byteInputStream())
            val games = doc.getElementsByTagName("game")
            return (0 until games.length).mapNotNull { i ->
                val g = games.item(i) as org.w3c.dom.Element
                val id = g.getElementsByTagName("appID").item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                owned(id, g.getElementsByTagName("name").item(0)?.textContent?.trim() ?: id)
            }
        }

        /** Web API key from Playnite's Steam library plugin, when Playnite is set up with one. */
        fun playniteSteamKey(): String? {
            val appData = System.getenv("APPDATA") ?: return null
            val file = File(appData, "Playnite/ExtensionsData/cb91dfc9-b977-43bf-8e70-55f46e410fab/config.json")
            if (!file.exists()) return null
            return runCatching {
                (Json.parseToJsonElement(file.readText()) as? JsonObject)?.get("ApiKey")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            }.getOrNull()
        }
    }
}
