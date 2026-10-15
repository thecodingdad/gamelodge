package io.github.thecodingdad.moonlightlauncher.metadata

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
data class SgdbGame(val id: Long, val name: String, val release_date: Long? = null, val verified: Boolean = false)

@Serializable
data class SgdbAsset(
    val id: Long,
    val url: String,
    val thumb: String? = null,
    val mime: String? = null,
    val style: String? = null,
    val nsfw: Boolean = false,
    val humor: Boolean = false,
    val score: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
) {
    val isAnimated: Boolean get() = mime == "image/webp" || mime == "image/gif" || mime == "video/webm"
    /**
     * What the picker can show: the thumbnail when it is an image (animated assets have WebM
     * thumbnails the image loader can't play), else the full image.
     */
    val previewImage: String
        get() = thumb?.takeIf { t -> t.substringBefore('?').substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif") } ?: url

    /** Animated formats the image loader can play (WebM would need a video player). */
    val isPlayableAnimation: Boolean get() = mime == "image/webp" || mime == "image/gif"
}

@Serializable
private data class SgdbResponse<T>(val success: Boolean = false, val data: T? = null)

enum class SgdbAssetType(val path: String) { GRID("grids"), HERO("heroes"), LOGO("logos"), ICON("icons") }

/**
 * Where a metadata request goes: straight to the provider with this device's own keys, or
 * through a paired companion / metadata server (which uses its keys).
 */
sealed interface MetadataRoute {
    data object Own : MetadataRoute
    data class Via(val server: io.github.thecodingdad.moonlightlauncher.data.companion.ProxyTarget) : MetadataRoute
}

/**
 * Minimal SteamGridDB v2 client (https://www.steamgriddb.com/api/v2). [routes] are tried in
 * order; a route that is offline or can't answer passes the request on to the next one.
 */
class SteamGridDbClient(
    private val http: OkHttpClient,
    private val apiKey: () -> String?,
    private val baseUrl: HttpUrl = "https://www.steamgriddb.com/api/v2/".toHttpUrl(),
    private val routes: () -> List<MetadataRoute> = { listOf(MetadataRoute.Own) },
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun usable(): List<MetadataRoute> = routes().filter { it != MetadataRoute.Own || !apiKey().isNullOrBlank() }

    val isConfigured: Boolean get() = usable().isNotEmpty()

    /** Only this device's own key (the key test in the settings). */
    val hasOwnKey: Boolean get() = !apiKey().isNullOrBlank()

    fun search(term: String): List<SgdbGame> {
        val url = baseUrl.newBuilder().addPathSegments("search/autocomplete").addPathSegment(term).build()
        return get<List<SgdbGame>>(url).orEmpty()
    }

    /** Search with the own key only (settings: does the key work?). */
    fun searchOwn(term: String): List<SgdbGame> {
        val url = baseUrl.newBuilder().addPathSegments("search/autocomplete").addPathSegment(term).build()
        return get<List<SgdbGame>>(url, listOf(MetadataRoute.Own)).orEmpty()
    }

    /** Portrait 600x900 grids for covers; animated ones only when [animated]. */
    fun assets(gameId: Long, type: SgdbAssetType, animated: Boolean = false, landscape: Boolean = false): List<SgdbAsset> {
        val url = baseUrl.newBuilder()
            .addPathSegment(type.path).addPathSegment("game").addPathSegment(gameId.toString())
            .apply {
                if (type == SgdbAssetType.GRID) addQueryParameter("dimensions", if (landscape) "920x430,460x215" else "600x900,342x482,660x930")
                addQueryParameter("types", if (animated) "animated" else "static")
                addQueryParameter("nsfw", "false")
                addQueryParameter("humor", "false")
            }
            .build()
        return get<List<SgdbAsset>>(url).orEmpty()
            .filter { !it.nsfw && !it.humor }
            .sortedByDescending { it.score }
    }

    private inline fun <reified T> get(url: HttpUrl, using: List<MetadataRoute> = usable()): T? {
        for (route in using) {
            when (route) {
                MetadataRoute.Own -> {
                    val key = apiKey() ?: continue
                    val request = Request.Builder().url(url).header("Authorization", "Bearer $key").build()
                    http.newCall(request).execute().use { response ->
                        if (response.code == 401) throw MetadataAuthException("SteamGridDB")
                        if (!response.isSuccessful) return null
                        val body = response.body?.string() ?: return null
                        return json.decodeFromString<SgdbResponse<T>>(body).data
                    }
                }
                is MetadataRoute.Via -> {
                    // Same path and query below /api/v2/, already escaped.
                    val relative = url.encodedPath.removePrefix(baseUrl.encodedPath) + (url.encodedQuery?.let { "?$it" } ?: "")
                    val body = runCatching {
                        route.server.rawGet("/v1/sgdb/$relative").use { r ->
                            when {
                                r.isSuccessful -> r.body?.string()
                                r.code == 404 && r.body?.string()?.contains("upstream") == true -> return null // provider: no such game
                                else -> null // offline, not configured, not allowed: next route
                            }
                        }
                    }.getOrNull() ?: continue
                    return json.decodeFromString<SgdbResponse<T>>(body).data
                }
            }
        }
        return null
    }
}

class MetadataAuthException(provider: String) : Exception("$provider rejected the API credentials")
