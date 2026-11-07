package io.github.thecodingdad.gamelodge.xbox

import io.github.thecodingdad.gamelodge.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

/** Power state of a console as Xbox Live reports it. */
enum class XboxPower { ON, STANDBY, OFF, UNKNOWN;
    companion object {
        fun of(s: String?): XboxPower = when (s) {
            "On" -> ON
            "ConnectedStandby", "Standby" -> STANDBY
            "Off" -> OFF
            else -> UNKNOWN
        }
    }
}

data class XboxConsole(
    val id: String,
    val name: String,
    /** "XboxOne", "XboxOneS", "XboxOneX", "XboxSeriesS", "XboxSeriesX" … */
    val type: String,
    val power: XboxPower,
    val remoteManagement: Boolean,
    val streaming: Boolean,
) {
    /** Xbox One consoles stream 720p at most. */
    val isSeries: Boolean get() = type.startsWith("XboxSeries", ignoreCase = true) || type.contains("Scarlett", ignoreCase = true)
}

/** An installed game (or DLC/app) of a console. */
data class XboxApp(
    val productId: String,
    val titleId: Long?,
    val name: String,
    /** "Game", "Dlc", "App" … */
    val contentType: String,
    val aumid: String?,
    val sizeBytes: Long?,
) {
    val isGame: Boolean get() = contentType.equals("Game", ignoreCase = true)
}

/** A license of the account ([satisfiedBy]: products that give it, e.g. a bundle or a pass). */
data class XboxLicense(val productId: String, val acquisitionType: String, val active: Boolean)

/** Store artwork and text of a product. */
data class XboxProductInfo(
    val productId: String,
    /** Portrait cover. */
    val cover: String?,
    /** Square box art. */
    val square: String?,
    /** Background without text. */
    val hero: String?,
    val logo: String?,
    val description: String?,
    val developer: String?,
    val publisher: String?,
)

class XboxApiException(message: String, val code: Int = 0) : Exception(message)

/**
 * Xbox Live calls used by GameLodge: the account's consoles and their power state, remote commands
 * (wake, turn off, start a game), installed games, licenses, presence (which game runs) and store
 * artwork. All unofficial APIs of the Xbox apps.
 */
class XboxClient(
    private val http: OkHttpClient,
    private val auth: XboxAuth,
    private val endpoints: XboxEndpoints = XboxEndpoints(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun xccsHeaders(contract: String): Map<String, String> = mapOf(
        "Authorization" to auth.web().header,
        "Accept-Language" to "en-US",
        "skillplatform" to "RemoteManagement",
        "x-xbl-contract-version" to contract,
        "x-xbl-client-name" to "XboxApp",
        "x-xbl-client-type" to "UWA",
        "x-xbl-client-version" to "39.39.22001.0",
    )

    suspend fun consoles(): List<XboxConsole> {
        val o = call("GET", "${endpoints.xccs}/lists/devices?queryCurrentDevice=false&includeStorageDevices=true", xccsHeaders("2"))
        return (o["result"] as? JsonArray).orEmpty().map { it.jsonObject }.map { c ->
            XboxConsole(
                id = c.str("id").orEmpty(),
                name = c.str("name").orEmpty(),
                type = c.str("consoleType").orEmpty(),
                power = XboxPower.of(c.str("powerState")),
                remoteManagement = c.str("remoteManagementEnabled").equals("true", ignoreCase = true),
                streaming = c.str("consoleStreamingEnabled").equals("true", ignoreCase = true),
            )
        }.filter { it.id.isNotEmpty() }
    }

    suspend fun power(consoleId: String): XboxPower {
        val o = call("GET", "${endpoints.xccs}/consoles/$consoleId", xccsHeaders("4"))
        return XboxPower.of(o.str("powerState"))
    }

    suspend fun installedApps(consoleId: String): List<XboxApp> {
        val o = call("GET", "${endpoints.xccs}/lists/installedApps?deviceId=$consoleId", xccsHeaders("2"))
        return (o["result"] as? JsonArray).orEmpty().map { it.jsonObject }.mapNotNull { a ->
            val productId = a.str("oneStoreProductId") ?: return@mapNotNull null
            XboxApp(
                productId = productId,
                titleId = a.str("titleId")?.toDoubleOrNull()?.toLong(),
                name = a.str("name") ?: return@mapNotNull null,
                contentType = a.str("contentType").orEmpty(),
                aumid = a.str("aumid"),
                sizeBytes = a.str("sizeInBytes")?.toDoubleOrNull()?.toLong(),
            )
        }
    }

    /** Sends a SmartGlass command; true when the console took it. */
    suspend fun command(consoleId: String, type: String, command: String, parameters: JsonArray = buildJsonArray { add(JsonObject(emptyMap())) }): Boolean {
        val body = buildJsonObject {
            put("destination", "Xbox")
            put("type", type)
            put("command", command)
            put("sessionId", UUID.randomUUID().toString())
            put("sourceId", "com.microsoft.smartglass")
            put("parameters", parameters)
            put("linkedXboxId", consoleId)
        }
        val o = call("POST", "${endpoints.xccs}/commands", xccsHeaders("4"), body.toString())
        val code = runCatching { o["status"]!!.jsonObject.str("errorCode") }.getOrNull()
        if (code != "OK") Log.w("Xbox command $type/$command: $code")
        return code == "OK"
    }

    suspend fun wakeUp(consoleId: String) = command(consoleId, "Power", "WakeUp")

    suspend fun turnOff(consoleId: String) = command(consoleId, "Power", "TurnOff")

    /** Starts a game on the console (the profile must be signed in there, else the console asks for it). */
    suspend fun launch(consoleId: String, productId: String) = command(
        consoleId, "Shell", "ActivateApplicationWithOneStoreProductId",
        buildJsonArray { add(buildJsonObject { put("oneStoreProductId", productId) }) },
    )

    /** Title id of the game in front on the account's console (Xbox Live presence), null when none. */
    suspend fun activeTitle(): Long? {
        val o = call("GET", "${endpoints.presence}/users/me?level=all", mapOf(
            "Authorization" to auth.web().header,
            "x-xbl-contract-version" to "3",
            "Accept" to "application/json",
        ))
        val devices = (o["devices"] as? JsonArray).orEmpty().map { it.jsonObject }
        return devices.filter { it.str("type")?.startsWith("Xbox", ignoreCase = true) == true }
            .flatMap { (it["titles"] as? JsonArray).orEmpty().map { t -> t.jsonObject } }
            .firstOrNull { it.str("placement") == "Full" && it.str("state") == "Active" && it.str("name") != "Home" }
            ?.str("id")?.toLongOrNull()
    }

    /**
     * Which of [productIds] the account owns for good (bought, a bundle, backward compatible
     * Xbox 360 titles). Games only playable through Game Pass / EA Play have no license here. Asked
     * one product at a time: the store returned nothing for larger batches.
     */
    suspend fun ownedProducts(productIds: Collection<String>): Set<String> = coroutineScope {
        val header = auth.licensing().header
        val gate = Semaphore(4)
        productIds.distinct().map { id ->
            async {
                gate.withPermit {
                    val body = buildJsonObject {
                        put("maxPageSize", 100)
                        put("excludeDuplicates", true)
                        put("expandSatisfyingItems", true)
                        put("market", "neutral")
                        put("entitlementFilters", buildJsonArray { listOf("*:Game", "*:Durable", "*:Pass").forEach { add(JsonPrimitive(it)) } })
                        put("productSkuIds", buildJsonArray { add(buildJsonObject { put("productId", id) }) })
                    }
                    val o = runCatching {
                        call("POST", "${endpoints.collections}/v8.0/collections/b2bLicensePreview", mapOf("Authorization" to header), body.toString())
                    }.getOrNull() ?: return@withPermit null
                    val items = (o["items"] as? JsonArray).orEmpty().map { it.jsonObject }
                    id.takeIf {
                        items.any { item -> item.str("productId").equals(id, true) && item.str("status") == "Active" && item.str("acquisitionType") != "Recurring" }
                    }
                }
            }
        }.awaitAll().filterNotNull().toSet()
    }

    /** Store artwork and texts of [productIds] (public catalog, no sign-in). */
    suspend fun productInfo(productIds: Collection<String>, market: String, language: String): Map<String, XboxProductInfo> {
        val out = HashMap<String, XboxProductInfo>()
        productIds.distinct().chunked(20).forEach { chunk ->
            val url = "${endpoints.catalog}/v7.0/products?bigIds=${chunk.joinToString(",")}&market=$market&languages=$language,en-US&MS-CV=DGU1mcuYo0WMMp+F.1"
            val o = runCatching { call("GET", url, emptyMap()) }.getOrNull() ?: return@forEach
            (o["Products"] as? JsonArray).orEmpty().map { it.jsonObject }.forEach { p ->
                val id = p.str("ProductId") ?: return@forEach
                val loc = (p["LocalizedProperties"] as? JsonArray)?.firstOrNull()?.jsonObject ?: return@forEach
                val images = (loc["Images"] as? JsonArray).orEmpty().map { it.jsonObject }
                fun image(vararg purposes: String): String? = purposes.firstNotNullOfOrNull { purpose ->
                    images.filter { it.str("ImagePurpose").equals(purpose, true) }
                        .maxByOrNull { (it.str("Width")?.toIntOrNull() ?: 0) }
                        ?.str("Uri")?.let { if (it.startsWith("//")) "https:$it" else it }
                }
                out[id] = XboxProductInfo(
                    productId = id,
                    cover = image("Poster", "BrandedKeyArt"),
                    square = image("BoxArt", "FeaturePromotionalSquareArt"),
                    hero = image("SuperHeroArt", "TitledHeroArt", "BrandedKeyArt"),
                    logo = image("Logo"),
                    description = loc.str("ProductDescription") ?: loc.str("ShortDescription"),
                    developer = loc.str("DeveloperName")?.takeIf { it.isNotBlank() },
                    publisher = loc.str("PublisherName")?.takeIf { it.isNotBlank() },
                )
            }
        }
        return out
    }

    private suspend fun call(method: String, url: String, headers: Map<String, String>, body: String? = null): JsonObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url)
            .method(method, body?.toRequestBody(JSON))
            .header("Accept", "application/json")
        headers.forEach { (k, v) -> request.header(k, v) }
        val response = http.newCall(request.build()).execute()
        val text = response.use { it.body?.string().orEmpty() }
        if (!response.isSuccessful) {
            Log.w("Xbox ${url.substringAfter("://").substringBefore('?')}: HTTP ${response.code}")
            throw XboxApiException("HTTP ${response.code}", response.code)
        }
        if (text.isBlank()) JsonObject(emptyMap())
        else (json.parseToJsonElement(text) as? JsonObject) ?: JsonObject(emptyMap())
    }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    companion object {
        private val JSON = "application/json".toMediaType()
    }
}
