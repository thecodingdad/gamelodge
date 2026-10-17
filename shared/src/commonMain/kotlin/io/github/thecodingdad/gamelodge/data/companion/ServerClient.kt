package io.github.thecodingdad.gamelodge.data.companion

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** A paired companion or metadata server. */
@kotlinx.serialization.Serializable
data class PairedServer(
    val id: String,
    /** "metadata" or "companion". */
    val kind: String,
    val name: String,
    /** "https://host:port" */
    val baseUrl: String,
    /** Pinned SHA-256 fingerprint of the server certificate. */
    val fingerprint: String,
    /** Companion: unique id of its streaming host (matches the Moonlight host uuid). */
    val hostId: String? = null,
    /** What the server offers this device (last /v1/info). */
    val steamGridDb: Boolean = false,
    val igdb: Boolean = false,
    val permissions: List<String> = emptyList(),
) {
    val isCompanion: Boolean get() = kind == KIND_COMPANION

    companion object {
        const val KIND_METADATA = "metadata"
        const val KIND_COMPANION = "companion"
        const val METADATA_PORT = 47900
        const val COMPANION_PORT = 47910

        /** "host", "host:port", "[v6]:port", "https://host:port" → "https://host:port". Null if unusable. */
        fun normalizeAddress(input: String, defaultPort: Int): String? {
            val raw = input.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
            if (raw.isEmpty() || raw.any { it.isWhitespace() || it in "/?#@" }) return null
            val (host, port) = when {
                raw.startsWith("[") -> raw.substringBefore("]") + "]" to raw.substringAfter("]").removePrefix(":").ifEmpty { null }
                raw.count { it == ':' } == 1 -> raw.substringBefore(':') to raw.substringAfter(':')
                raw.count { it == ':' } > 1 -> "[$raw]" to null
                else -> raw to null
            }
            val p = port?.toIntOrNull() ?: if (port == null) defaultPort else return null
            if (p !in 1..65535 || host.isEmpty()) return null
            return "https://$host:$p"
        }
    }
}

/** A server that forwards SteamGridDB / IGDB requests (paths below /v1/). */
interface ProxyTarget {
    fun rawGet(pathAndQuery: String): Response
    fun rawPost(path: String, body: String, mediaType: String = "text/plain"): Response
}

class ServerException(val status: Int, val code: String?) : Exception("HTTP $status${code?.let { " ($it)" } ?: ""}")

/**
 * HTTP to one server: mutual TLS with this launcher's [ClientIdentity], accepting exactly the
 * pinned server certificate ([pinned] null only while pairing: then any certificate is accepted
 * and its fingerprint remembered for the binding check).
 */
class ServerClient(
    base: OkHttpClient,
    private val identity: ClientIdentity,
    val baseUrl: String,
    pinned: String?,
) : ProxyTarget {
    @Volatile var seenFingerprint: String? = null
        private set

    private val trust = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException()
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val cert = chain?.firstOrNull() ?: throw CertificateException("No server certificate")
            val fp = Fingerprint.of(cert)
            seenFingerprint = fp
            if (pinned != null && fp != pinned) throw CertificateException("Server certificate changed")
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    val http: OkHttpClient = base.newBuilder()
        .sslSocketFactory(SSLContext.getInstance("TLS").apply { init(arrayOf(identity.keyManager), arrayOf(trust), SecureRandom()) }.socketFactory, trust)
        // Identity is the pinned fingerprint, not a name (servers use self-signed certificates).
        .hostnameVerifier { _, _ -> true }
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun info(): InfoResponse = get("/v1/info")
    fun hello(): HelloResponse = get("/v1/pair/hello")

    /**
     * Pairs with the server. [onWaiting] gets the PIN to show; the user types it on the server.
     * Returns the server to store with its pinned fingerprint.
     */
    suspend fun pair(deviceName: String, pin: String, onWaiting: () -> Unit = {}): PairedServer {
        val exchange = JPakeExchange(JPakeExchange.CLIENT_ID, pin)
        val start: PairStartResponse = post("/v1/pair/start", PairStartRequest(deviceName, exchange.createRound1()))
        val serverFp = seenFingerprint ?: throw PairingFailed("No server certificate")
        onWaiting()
        val deadline = System.currentTimeMillis() + 3 * 60_000L
        var serverRound1: Round1Message? = null
        while (serverRound1 == null) {
            if (System.currentTimeMillis() > deadline) throw PairingFailed("timeout")
            delay(1500)
            val poll: PairPollResponse = get("/v1/pair/${start.requestId}")
            if (poll.state == "ready") serverRound1 = poll.round1
        }
        if (serverRound1.id != JPakeExchange.SERVER_ID) throw PairingFailed("Unexpected participant")
        exchange.receiveRound1(serverRound1)
        val serverRound2: Round2Message = post("/v1/pair/${start.requestId}/round2", exchange.createRound2())
        exchange.receiveRound2(serverRound2)
        val binding = exchange.bindingMac(JPakeExchange.CLIENT_ID, serverFp, identity.fingerprint)
        val done: PairRound3Response = post("/v1/pair/${start.requestId}/round3", PairRound3Request(exchange.createRound3(), binding))
        exchange.receiveRound3(done.round3)
        if (!exchange.checkBindingMac(JPakeExchange.SERVER_ID, serverFp, identity.fingerprint, done.binding)) throw PairingFailed("Server certificate binding failed")
        if (seenFingerprint != serverFp) throw PairingFailed("Server certificate changed during pairing")
        return PairedServer(java.util.UUID.randomUUID().toString(), done.kind, done.serverName, baseUrl, serverFp)
    }

    /** Raw GET (proxy and images): the caller closes the response. */
    override fun rawGet(pathAndQuery: String): Response = http.newCall(Request.Builder().url(baseUrl + pathAndQuery).build()).execute()

    override fun rawPost(path: String, body: String, mediaType: String): Response =
        http.newCall(Request.Builder().url(baseUrl + path).post(body.toRequestBody(mediaType.toMediaType())).build()).execute()

    fun rawPut(path: String, jsonBody: String): Response =
        http.newCall(Request.Builder().url(baseUrl + path).put(jsonBody.toRequestBody("application/json".toMediaType())).build()).execute()

    private inline fun <reified T> get(path: String): T =
        http.newCall(Request.Builder().url(baseUrl + path).build()).execute().use { decode(it) }

    private inline fun <reified B, reified T> post(path: String, body: B): T =
        http.newCall(Request.Builder().url(baseUrl + path).post(json.encodeToString(body).toRequestBody("application/json".toMediaType())).build())
            .execute().use { decode(it) }

    private inline fun <reified T> decode(response: Response): T {
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw ServerException(response.code, runCatching { json.decodeFromString<ErrorResponse>(text).error }.getOrNull())
        }
        return json.decodeFromString(text)
    }
}
