package io.github.thecodingdad.gamelodge.desktop.games

import io.github.thecodingdad.gamelodge.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * GOG: installed games from the registry (GOG.com\Games), owned games from GOG Galaxy's local
 * database (galaxy-2.0.db) when Galaxy is installed.
 */
class GogScanner(private val galaxyDb: File = defaultGalaxyDb()) : GameScanner {
    override val source = "gog"

    override suspend fun scan(): List<GameEntry> = withContext(Dispatchers.IO) {
        val result = LinkedHashMap<String, GameEntry>()
        for ((id, get) in Reg.subKeys("HKLM", "SOFTWARE\\WOW6432Node\\GOG.com\\Games")) {
            val name = get("gameName") ?: continue
            // DLCs are registered too and point at their base game.
            if (!get("dependsOn").isNullOrEmpty()) continue
            result["gog:$id"] = GameEntry(
                id = "gog:$id", name = name, source = "gog", storeId = id, installed = true,
                installDir = get("path"), launch = get("exe"), launchArgs = get("launchParam"),
                workingDir = get("workingDir"), install = "goggalaxy://openGameView/$id",
            )
        }
        if (galaxyDb.exists()) {
            runCatching { readGalaxyOwned(galaxyDb) }.onFailure { Log.w("GOG: Galaxy database unreadable", it) }
                .getOrNull()?.forEach { owned ->
                    // Installed games come from the registry; Galaxy adds the store images.
                    val installed = result[owned.id]
                    result[owned.id] = installed?.copy(covers = owned.covers, storeHeroes = owned.storeHeroes, storeIcons = owned.storeIcons) ?: owned
                }
        }
        result.values.sortedBy { it.name.lowercase() }
    }

    companion object {
        fun defaultGalaxyDb() = File(System.getenv("ProgramData") ?: "C:\\ProgramData", "GOG.com/Galaxy/storage/galaxy-2.0.db")

        /**
         * Owned GOG games: release keys "gog_<id>" from LibraryReleaseKeys with their title from
         * GamePieces (type "originalTitle" or "title") and the store images ("originalImages").
         */
        fun readGalaxyOwned(db: File): List<GameEntry> = readSqliteCopy(db) { c ->
            val tables = c.rows("SELECT name FROM sqlite_master WHERE type='table'").mapNotNull { it[0] }.toSet()
            if ("GamePieces" !in tables || "GamePieceTypes" !in tables) return@readSqliteCopy emptyList()
            val keys = if ("LibraryReleaseKeys" in tables) c.rows("SELECT releaseKey FROM LibraryReleaseKeys").mapNotNull { it[0] }.toSet() else emptySet()
            fun pieces(types: String) = c.rows(
                "SELECT gp.releaseKey, gp.value FROM GamePieces gp JOIN GamePieceTypes t ON t.id = gp.gamePieceTypeId " +
                    "WHERE t.type IN ($types) AND gp.releaseKey LIKE 'gog_%'",
            ).mapNotNull { row ->
                val key = row[0] ?: return@mapNotNull null
                val json = runCatching { Json.parseToJsonElement(row[1].orEmpty()) as? JsonObject }.getOrNull() ?: return@mapNotNull null
                key to json
            }
            val titles = HashMap<String, String>()
            pieces("'originalTitle', 'title'").forEach { (key, json) ->
                (json["title"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }?.let { titles.putIfAbsent(key, it) }
            }
            val images = HashMap<String, JsonObject>()
            pieces("'originalImages'").forEach { (key, json) -> images.putIfAbsent(key, json) }
            fun image(key: String, name: String) = listOfNotNull(
                (images[key]?.get(name) as? JsonPrimitive)?.content?.takeIf { it.startsWith("https://") && '{' !in it },
            )
            keys.filter { it.startsWith("gog_") }.map { key ->
                val id = key.removePrefix("gog_")
                GameEntry(
                    id = "gog:$id", name = titles[key] ?: key, source = "gog", storeId = id, installed = false,
                    install = "goggalaxy://openGameView/$id",
                    covers = image(key, "verticalCover"), storeHeroes = image(key, "background"), storeIcons = image(key, "squareIcon"),
                )
            }
        }
    }
}
