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
    /** Animated formats the image loader can play (WebM would need a video player). */
    val isPlayableAnimation: Boolean get() = mime == "image/webp" || mime == "image/gif"
}

@Serializable
private data class SgdbResponse<T>(val success: Boolean = false, val data: T? = null)

enum class SgdbAssetType(val path: String) { GRID("grids"), HERO("heroes"), LOGO("logos"), ICON("icons") }

/** Minimal SteamGridDB v2 client (https://www.steamgriddb.com/api/v2). */
class SteamGridDbClient(
    private val http: OkHttpClient,
    private val apiKey: () -> String?,
    private val baseUrl: HttpUrl = "https://www.steamgriddb.com/api/v2/".toHttpUrl(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    val isConfigured: Boolean get() = !apiKey().isNullOrBlank()

    fun search(term: String): List<SgdbGame> {
        val url = baseUrl.newBuilder().addPathSegments("search/autocomplete").addPathSegment(term).build()
        return get<List<SgdbGame>>(url).orEmpty()
    }

    /** Portrait 600x900 grids for covers; animated ones only when [animated]. */
    fun assets(gameId: Long, type: SgdbAssetType, animated: Boolean = false): List<SgdbAsset> {
        val url = baseUrl.newBuilder()
            .addPathSegment(type.path).addPathSegment("game").addPathSegment(gameId.toString())
            .apply {
                if (type == SgdbAssetType.GRID) addQueryParameter("dimensions", "600x900,342x482,660x930")
                addQueryParameter("types", if (animated) "animated" else "static")
                addQueryParameter("nsfw", "false")
                addQueryParameter("humor", "false")
            }
            .build()
        return get<List<SgdbAsset>>(url).orEmpty()
            .filter { !it.nsfw && !it.humor }
            .sortedByDescending { it.score }
    }

    private inline fun <reified T> get(url: HttpUrl): T? {
        val key = apiKey() ?: return null
        val request = Request.Builder().url(url).header("Authorization", "Bearer $key").build()
        http.newCall(request).execute().use { response ->
            if (response.code == 401) throw MetadataAuthException("SteamGridDB")
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            return json.decodeFromString<SgdbResponse<T>>(body).data
        }
    }
}

class MetadataAuthException(provider: String) : Exception("$provider rejected the API credentials")
