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
data class IgdbCompanyRef(val company: IgdbNamed? = null, val developer: Boolean = false, val publisher: Boolean = false)

@Serializable
data class IgdbRatingCategory(val rating: String? = null)

/** Both the current (organization + rating_category) and the old (category + rating enums) form. */
@Serializable
data class IgdbAgeRating(
    val organization: IgdbNamed? = null,
    val rating_category: IgdbRatingCategory? = null,
    val category: Int? = null,
    val rating: Int? = null,
) {
    /** "PEGI" to "12", "USK" to "16", "ESRB" to "T" … or null for systems we don't show. */
    val normalized: Pair<String, String>?
        get() {
            val system = organization?.name?.uppercase()?.takeIf { it.isNotBlank() } ?: OLD_SYSTEMS[category] ?: return null
            if (system !in AgeRatings.SYSTEMS) return null
            val raw = rating_category?.rating ?: OLD_RATINGS[rating] ?: return null
            return system to AgeRatings.normalizeValue(raw)
        }

    private companion object {
        val OLD_SYSTEMS = mapOf(1 to "ESRB", 2 to "PEGI", 4 to "USK")
        val OLD_RATINGS = mapOf(
            1 to "3", 2 to "7", 3 to "12", 4 to "16", 5 to "18", 6 to "RP", 7 to "EC", 8 to "E", 9 to "E10+", 10 to "T", 11 to "M", 12 to "AO",
            28 to "0", 29 to "6", 30 to "12", 31 to "16", 32 to "18",
        )
    }
}

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
    val platforms: List<IgdbNamed> = emptyList(),
    val franchises: List<IgdbNamed> = emptyList(),
    val collections: List<IgdbNamed> = emptyList(),
    val game_modes: List<IgdbNamed> = emptyList(),
    val age_ratings: List<IgdbAgeRating> = emptyList(),
) {
    val releaseYear: Int?
        get() = first_release_date?.let {
            Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = it * 1000 }.get(Calendar.YEAR)
        }
    val developer: String? get() = involved_companies.firstOrNull { it.developer }?.company?.name
    val publisher: String? get() = involved_companies.firstOrNull { it.publisher }?.company?.name
    val series: String? get() = (franchises.firstOrNull() ?: collections.firstOrNull())?.name?.takeIf { it.isNotBlank() }
    val features: String? get() = game_modes.joinToString(", ") { it.name }.ifEmpty { null }
    val ageRatings: String? get() = AgeRatings.encode(age_ratings.mapNotNull { it.normalized }.toMap())
    val releaseMillis: Long? get() = first_release_date?.let { it * 1000 }
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
        return post("games", "search \"$term\"; fields $GAME_FIELDS; limit $limit;")
    }

    fun byId(id: Long): IgdbGame? = post("games", "fields $GAME_FIELDS; where id = $id;").firstOrNull()

    /** The text fields of several games at once (backfill of fields added later), 100 per request. */
    fun details(ids: List<Long>): List<IgdbGame> =
        if (ids.isEmpty()) emptyList()
        else post("games", "fields $GAME_FIELDS; where id = (${ids.joinToString(",")}); limit ${ids.size};")

    /** Only the platforms of several games (backfill for matches made before platforms were stored). */
    fun platforms(ids: List<Long>): List<IgdbGame> =
        if (ids.isEmpty()) emptyList()
        else post("games", "fields name,platforms.name; where id = (${ids.joinToString(",")}); limit ${ids.size};")

    private companion object {
        const val GAME_FIELDS = "name,summary,genres.name,involved_companies.company.name,involved_companies.developer," +
            "involved_companies.publisher,first_release_date,total_rating,cover.image_id,artworks.image_id," +
            "screenshots.image_id,platforms.name,franchises.name,collections.name,game_modes.name," +
            "age_ratings.organization.name,age_ratings.rating_category.rating,age_ratings.category,age_ratings.rating"
    }

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
