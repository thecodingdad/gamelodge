package io.github.thecodingdad.moonlightlauncher.metadata

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Calendar
import java.util.TimeZone

@Serializable
data class IgdbNamed(val name: String = "")

@Serializable
data class IgdbImage(val image_id: String = "")

@Serializable
data class IgdbCompanyRef(val company: IgdbNamed? = null, val developer: Boolean = false)

@Serializable
data class IgdbGame(
    val id: Long,
    val name: String,
    val summary: String? = null,
    val genres: List<IgdbNamed> = emptyList(),
    val involved_companies: List<IgdbCompanyRef> = emptyList(),
    val first_release_date: Long? = null,
    val total_rating: Double? = null,
    val cover: IgdbImage? = null,
    val artworks: List<IgdbImage> = emptyList(),
    val screenshots: List<IgdbImage> = emptyList(),
) {
    val releaseYear: Int?
        get() = first_release_date?.let {
            Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = it * 1000 }.get(Calendar.YEAR)
        }
    val developer: String? get() = involved_companies.firstOrNull { it.developer }?.company?.name
    val coverUrl: String? get() = cover?.image_id?.takeIf { it.isNotEmpty() }?.let { "https://images.igdb.com/igdb/image/upload/t_cover_big_2x/$it.jpg" }
    val heroUrl: String?
        get() = (artworks.firstOrNull() ?: screenshots.firstOrNull())?.image_id?.takeIf { it.isNotEmpty() }
            ?.let { "https://images.igdb.com/igdb/image/upload/t_1080p/$it.jpg" }
}

@Serializable
private data class TwitchToken(val access_token: String, val expires_in: Long)

/** Credentials and the cached app token, persisted by the caller (SecretStore). */
interface IgdbCredentials {
    val clientId: String?
    val clientSecret: String?
    var token: String?
    var tokenExpiresAt: Long
}

/** IGDB v4 client using Twitch client-credentials auth (https://api-docs.igdb.com). */
class IgdbClient(
    private val http: OkHttpClient,
    private val credentials: IgdbCredentials,
    private val apiUrl: HttpUrl = "https://api.igdb.com/v4/".toHttpUrl(),
    private val tokenUrl: HttpUrl = "https://id.twitch.tv/oauth2/token".toHttpUrl(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }

    val isConfigured: Boolean
        get() = !credentials.clientId.isNullOrBlank() && !credentials.clientSecret.isNullOrBlank()

    fun search(title: String, limit: Int = 8): List<IgdbGame> {
        // Apicalypse: quotes in the search term must be escaped.
        val term = title.replace("\\", "").replace("\"", "\\\"")
        val body = """
            search "$term";
            fields name,summary,genres.name,involved_companies.company.name,involved_companies.developer,
                   first_release_date,total_rating,cover.image_id,artworks.image_id,screenshots.image_id;
            limit $limit;
        """.trimIndent()
        return post("games", body)
    }

    fun byId(id: Long): IgdbGame? = post(
        "games",
        "fields name,summary,genres.name,involved_companies.company.name,involved_companies.developer," +
            "first_release_date,total_rating,cover.image_id,artworks.image_id,screenshots.image_id; where id = $id;",
    ).firstOrNull()

    private fun post(endpoint: String, query: String, retried: Boolean = false): List<IgdbGame> {
        val token = token() ?: return emptyList()
        val request = Request.Builder()
            .url(apiUrl.newBuilder().addPathSegment(endpoint).build())
            .header("Client-ID", credentials.clientId!!)
            .header("Authorization", "Bearer $token")
            .post(query.toRequestBody("text/plain".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 401 && !retried) {
                credentials.token = null
                return post(endpoint, query, retried = true)
            }
            if (response.code == 401) throw MetadataAuthException("IGDB")
            if (!response.isSuccessful) return emptyList()
            return json.decodeFromString(response.body?.string() ?: "[]")
        }
    }

    private fun token(): String? {
        credentials.token?.takeIf { clock() < credentials.tokenExpiresAt - 60_000 }?.let { return it }
        val id = credentials.clientId ?: return null
        val secret = credentials.clientSecret ?: return null
        val request = Request.Builder().url(tokenUrl).post(
            FormBody.Builder()
                .add("client_id", id)
                .add("client_secret", secret)
                .add("grant_type", "client_credentials")
                .build(),
        ).build()
        http.newCall(request).execute().use { response ->
            if (response.code in 400..403) throw MetadataAuthException("IGDB")
            if (!response.isSuccessful) return null
            val token = json.decodeFromString<TwitchToken>(response.body?.string() ?: return null)
            credentials.token = token.access_token
            credentials.tokenExpiresAt = clock() + token.expires_in * 1000
            return token.access_token
        }
    }
}
