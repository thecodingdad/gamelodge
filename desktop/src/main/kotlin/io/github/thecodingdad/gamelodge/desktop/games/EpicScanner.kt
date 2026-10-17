package io.github.thecodingdad.gamelodge.desktop.games

import io.github.thecodingdad.gamelodge.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.File
import java.net.URLEncoder
import java.util.Base64

/**
 * Epic Games: installed games from the launcher's *.item manifests; owned games from its local
 * catalog cache (catcache.bin, base64 JSON), so no Epic login is needed.
 */
class EpicScanner(private val dataDir: File = defaultDataDir()) : GameScanner {
    override val source = "epic"

    override suspend fun scan(): List<GameEntry> = withContext(Dispatchers.IO) {
        val result = LinkedHashMap<String, GameEntry>()
        File(dataDir, "Manifests").listFiles { f -> f.name.endsWith(".item") }.orEmpty().forEach { file ->
            runCatching { parseManifest(file.readText()) }.onFailure { Log.w("Epic: manifest $file unreadable", it) }
                .getOrNull()?.let { result[it.id] = it }
        }
        val cache = File(dataDir, "Catalog/catcache.bin")
        if (cache.exists()) {
            runCatching { parseCatalogCache(cache.readText()) }.onFailure { Log.w("Epic: catalog cache unreadable", it) }
                .getOrNull()?.forEach { owned ->
                    val installed = result[owned.id]
                    result[owned.id] = installed?.copy(covers = owned.covers, storeLogos = owned.storeLogos, storeSummary = owned.storeSummary) ?: owned
                }
        }
        result.values.sortedBy { it.name.lowercase() }
    }

    companion object {
        fun defaultDataDir() = File(System.getenv("ProgramData") ?: "C:\\ProgramData", "Epic/EpicGamesLauncher/Data")

        private fun esc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        private fun launchUri(ns: String, item: String, app: String, action: String) =
            "com.epicgames.launcher://apps/${esc(ns)}%3A${esc(item)}%3A${esc(app)}?action=$action&silent=true"

        private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonObject.text(name: String) = this[name].text()

        fun parseManifest(json: String): GameEntry? {
            val r = Json.parseToJsonElement(json) as? JsonObject ?: return null
            val ns = r.text("CatalogNamespace") ?: return null
            val item = r.text("CatalogItemId") ?: return null
            val app = r.text("AppName") ?: return null
            val name = r.text("DisplayName") ?: return null
            val categories = (r["AppCategories"] as? JsonArray)?.mapNotNull { it.text() }.orEmpty()
            // DLCs and add-ons have their own manifests; only "games" are listed.
            if (categories.isNotEmpty() && "games" !in categories) return null
            val incomplete = (r["bIsIncompleteInstall"] as? JsonPrimitive)?.booleanOrNull == true
            return GameEntry(
                id = "epic:$ns:$item", name = name, source = "epic", storeId = app,
                installed = !incomplete, installDir = r.text("InstallLocation"),
                launch = launchUri(ns, item, app, "launch"), install = launchUri(ns, item, app, "install"),
            )
        }

        /** catcache.bin: base64 of a JSON array of catalog items (games, DLC, add-ons). */
        fun parseCatalogCache(base64: String): List<GameEntry> {
            val items = Json.parseToJsonElement(String(Base64.getMimeDecoder().decode(base64.trim()), Charsets.UTF_8)) as? JsonArray ?: return emptyList()
            return items.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val id = item.text("id") ?: return@mapNotNull null
                val ns = item.text("namespace") ?: return@mapNotNull null
                val title = item.text("title") ?: return@mapNotNull null
                // Base games carry an empty mainGameItem; only a filled one marks a DLC.
                if (!(item["mainGameItem"] as? JsonObject)?.text("id").isNullOrEmpty()) return@mapNotNull null
                val cats = (item["categories"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.text("path") }.orEmpty()
                if ("games" !in cats || "software" in cats) return@mapNotNull null
                val appId = (item["releaseInfo"] as? JsonArray)?.firstNotNullOfOrNull { (it as? JsonObject)?.text("appId") } ?: return@mapNotNull null
                val images = (item["keyImages"] as? JsonArray)?.mapNotNull { img ->
                    val o = img as? JsonObject ?: return@mapNotNull null
                    (o.text("type") ?: return@mapNotNull null) to (o.text("url") ?: return@mapNotNull null)
                }.orEmpty()
                fun of(vararg types: String) = types.flatMap { t -> images.filter { it.first == t }.map { it.second } }.distinct()
                GameEntry(
                    id = "epic:$ns:$id", name = title, source = "epic", storeId = appId, installed = false,
                    launch = launchUri(ns, id, appId, "launch"), install = launchUri(ns, id, appId, "install"),
                    // No background from Epic: its wide images carry the title, which the logo repeats.
                    covers = of("DieselGameBoxTall", "OfferImageTall"),
                    storeLogos = of("DieselGameBoxLogo"),
                    storeSummary = item.text("description")?.takeIf { it.isNotEmpty() && it != title },
                )
            }
        }
    }
}
