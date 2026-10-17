package io.github.thecodingdad.gamelodge.host

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsExchange
import com.sun.net.httpserver.HttpsParameters
import com.sun.net.httpserver.HttpsServer
import io.github.thecodingdad.gamelodge.data.companion.ErrorResponse
import io.github.thecodingdad.gamelodge.data.companion.Fingerprint
import io.github.thecodingdad.gamelodge.data.companion.HelloResponse
import io.github.thecodingdad.gamelodge.data.companion.HostGameCorrection
import io.github.thecodingdad.gamelodge.data.companion.HostGamesList
import io.github.thecodingdad.gamelodge.data.companion.InfoResponse
import io.github.thecodingdad.gamelodge.data.companion.PairPollResponse
import io.github.thecodingdad.gamelodge.data.companion.PairRound3Request
import io.github.thecodingdad.gamelodge.data.companion.PairRound3Response
import io.github.thecodingdad.gamelodge.data.companion.PairStartRequest
import io.github.thecodingdad.gamelodge.data.companion.PairStartResponse
import io.github.thecodingdad.gamelodge.data.companion.PairingFailed
import io.github.thecodingdad.gamelodge.data.companion.Round2Message
import io.github.thecodingdad.gamelodge.util.Log
import kotlinx.serialization.json.Json
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** SteamGridDB / IGDB through this PC (the metadata proxy). */
interface MetadataProxyApi {
    val steamGridDbConfigured: Boolean
    val igdbConfigured: Boolean
    fun steamGridDb(path: String, query: List<Pair<String, String>>): ProxyResult
    fun igdb(endpoint: String, query: String): ProxyResult
}

/** What a proxied request gave: the body to pass on, or why not. */
data class ProxyResult(val status: Status, val httpStatus: Int = 200, val body: ByteArray? = null) {
    enum class Status { OK, NOT_CONFIGURED, NOT_ALLOWED, UPSTREAM_AUTH, UPSTREAM_ERROR }
}

/** What the PC offers behind /v1/hostgames and /v1/images. */
interface HostGamesSource {
    /** Unique id of this PC's streaming host (from its serverinfo), linking the launchers' host to it. */
    val hostId: String?
    fun games(): HostGamesList
    /** Image bytes and content type for an "image:<id>" reference, or null. */
    fun image(id: String): Pair<ByteArray, String>?
    /** False: unknown game. */
    fun correct(gameId: String, correction: HostGameCorrection, deviceName: String): Boolean
}

/**
 * The API paired launchers talk to (the companion's protocol, version 1): HTTPS with the
 * server's own certificate and a client certificate on every connection (mutual TLS). Only the
 * pairing endpoints work for certificates that aren't paired; everything else answers 401.
 */
class ApiServer(
    private val identity: ServerIdentity,
    private val devices: DeviceRegistry,
    private val pairing: PairingManager,
    private val serverName: () -> String,
    private val proxy: () -> MetadataProxyApi? = { null },
    private val hostGames: () -> HostGamesSource? = { null },
    /** What this server is to clients: [KIND] (Windows launcher, companion) or [KIND_METADATA]. */
    private val kind: String = KIND,
) {
    private var server: HttpsServer? = null
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val pairingLimits = RateLimiter(perMinute = 120)
    private val proxyLimits = TokenBuckets(capacity = 60, perSecond = 20)

    val port: Int? get() = server?.address?.port

    fun start(port: Int) {
        if (server != null) return
        val ssl = SSLContext.getInstance("TLS")
        val keys = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("server", identity.privateKey, CharArray(0), arrayOf(identity.certificate))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, CharArray(0)) }
        // Self-signed client certificates: who they are is decided by their fingerprint per request.
        val anyClient = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (chain.isNullOrEmpty()) throw CertificateException("No client certificate")
            }
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException()
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        ssl.init(kmf.keyManagers, arrayOf(anyClient), SecureRandom())
        server = HttpsServer.create(InetSocketAddress(port), 0).apply {
            httpsConfigurator = object : HttpsConfigurator(ssl) {
                override fun configure(params: HttpsParameters) {
                    params.setSSLParameters(ssl.defaultSSLParameters.apply {
                        needClientAuth = true
                        protocols = arrayOf("TLSv1.3", "TLSv1.2")
                    })
                }
            }
            createContext("/") { exchange -> runCatching { handle(exchange as HttpsExchange) }.onFailure { Log.w("API request failed", it); exchange.close() } }
            executor = Executors.newFixedThreadPool(8) { r -> Thread(r, "api").apply { isDaemon = true } }
            start()
        }
        Log.i("API listening on port $port, server fingerprint ${Fingerprint.pretty(identity.fingerprint)}")
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    private fun handle(ex: HttpsExchange) {
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.responseHeaders.add("X-Content-Type-Options", "nosniff")
        val cert = runCatching { ex.sslSession.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull()
        val path = ex.requestURI.rawPath
        if (cert == null) return empty(ex, 401)
        if (!path.startsWith("/v1/")) return empty(ex, 404)
        val fingerprint = Fingerprint.of(cert)
        val address = address(ex)
        if (path.startsWith("/v1/pair/")) {
            if (!pairingLimits.allow(address ?: "?")) return error(ex, 429, "rate_limited")
            return pairingRoute(ex, path.removePrefix("/v1/pair/"), fingerprint, address)
        }
        val device = devices.find(fingerprint) ?: return empty(ex, 401)
        devices.touch(device, address)
        apiRoute(ex, path.removePrefix("/v1/"), device)
    }

    private fun pairingRoute(ex: HttpsExchange, rest: String, fingerprint: String, address: String?) = guard(ex) {
        val parts = rest.split('/')
        when {
            ex.requestMethod == "GET" && rest == "hello" -> respond(ex, HelloResponse(VERSION, kind, pairing.isOpen))
            ex.requestMethod == "POST" && rest == "start" -> {
                val body = read<PairStartRequest>(ex) ?: return@guard error(ex, 400, "invalid")
                if (body.deviceName.isBlank() || body.deviceName.length > 64) return@guard error(ex, 400, "bad_name")
                val id = pairing.start(body.deviceName, fingerprint, address, body.round1)
                Log.i("Pairing request from $address")
                respond(ex, PairStartResponse(id))
            }
            ex.requestMethod == "GET" && parts.size == 1 && validId(parts[0]) -> {
                val (state, round1) = pairing.poll(parts[0], fingerprint)
                respond(ex, PairPollResponse(state.name.replace("_", "").lowercase(), round1))
            }
            ex.requestMethod == "POST" && parts.size == 2 && validId(parts[0]) && parts[1] == "round2" -> {
                val body = read<Round2Message>(ex) ?: return@guard error(ex, 400, "invalid")
                respond(ex, pairing.round2(parts[0], fingerprint, body))
            }
            ex.requestMethod == "POST" && parts.size == 2 && validId(parts[0]) && parts[1] == "round3" -> {
                val body = read<PairRound3Request>(ex) ?: return@guard error(ex, 400, "invalid")
                val (round3, binding, device) = pairing.round3(parts[0], fingerprint, body.round3, body.binding)
                Log.i("Paired ${device.name}")
                respond(ex, PairRound3Response(round3, binding, serverName(), kind))
            }
            else -> empty(ex, 404)
        }
    }

    private fun apiRoute(ex: HttpsExchange, rest: String, device: Device) {
        when {
            ex.requestMethod == "GET" && rest == "info" -> {
                val p = proxy()
                respond(
                    ex,
                    InfoResponse(
                        VERSION, kind, serverName(), device.name, device.permissions.toList(),
                        steamGridDb = p?.steamGridDbConfigured == true && device.may(Permissions.METADATA),
                        igdb = p?.igdbConfigured == true && device.may(Permissions.METADATA),
                        hostId = hostGames()?.hostId,
                    ),
                )
            }
            ex.requestMethod == "GET" && rest.startsWith("sgdb/") -> {
                if (!device.may(Permissions.METADATA)) return empty(ex, 403)
                val p = proxy() ?: return error(ex, 503, "not_available")
                if (!proxyLimits.take(device.fingerprint)) return error(ex, 429, "rate_limited")
                val query = ex.requestURI.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.map {
                    val (k, v) = (it.split('=', limit = 2) + "").take(2)
                    URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
                }
                proxied(ex, p.steamGridDb(URLDecoder.decode(rest.removePrefix("sgdb/"), "UTF-8"), query))
            }
            ex.requestMethod == "POST" && rest.startsWith("igdb/") && !rest.removePrefix("igdb/").contains('/') -> {
                if (!device.may(Permissions.METADATA)) return empty(ex, 403)
                val p = proxy() ?: return error(ex, 503, "not_available")
                if (!proxyLimits.take(device.fingerprint)) return error(ex, 429, "rate_limited")
                val query = readText(ex, MAX_IGDB_QUERY) ?: return error(ex, 413, "too_large")
                proxied(ex, p.igdb(rest.removePrefix("igdb/"), query))
            }
            ex.requestMethod == "GET" && rest == "hostgames" -> {
                if (!device.may(Permissions.HOST_GAMES)) return empty(ex, 403)
                val source = hostGames() ?: return error(ex, 404, "not_available")
                val bytes = json.encodeToString(source.games()).toByteArray()
                val etag = "\"" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02X".format(it) }.take(32) + "\""
                if (ex.requestHeaders["If-None-Match"].orEmpty().any { etag in it }) return empty(ex, 304)
                ex.responseHeaders.add("ETag", etag)
                bytes(ex, 200, bytes, "application/json")
            }
            ex.requestMethod == "GET" && rest.startsWith("images/") -> {
                if (!device.may(Permissions.HOST_GAMES)) return empty(ex, 403)
                val id = rest.removePrefix("images/")
                if (id.length > 128 || !id.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }) return empty(ex, 404)
                val (data, type) = hostGames()?.image(id) ?: return empty(ex, 404)
                ex.responseHeaders.set("Cache-Control", "private, max-age=86400")
                bytes(ex, 200, data, type)
            }
            ex.requestMethod == "PUT" && rest.startsWith("hostgames/") && rest.endsWith("/metadata") -> {
                if (!device.may(Permissions.HOST_GAMES_WRITE)) return empty(ex, 403)
                val id = URLDecoder.decode(rest.removePrefix("hostgames/").removeSuffix("/metadata"), "UTF-8")
                val body = read<HostGameCorrection>(ex)
                if (id.length > 256 || body == null || !valid(body)) return error(ex, 400, "invalid")
                val source = hostGames() ?: return error(ex, 404, "not_available")
                if (source.correct(id, body, device.name)) empty(ex, 204) else empty(ex, 404)
            }
            else -> empty(ex, 404)
        }
    }

    private inline fun guard(ex: HttpsExchange, block: () -> Unit) {
        try {
            block()
        } catch (e: PairingClosedException) {
            error(ex, 403, "pairing_closed")
        } catch (e: PairingFailed) {
            error(ex, 400, "pairing_failed")
        }
    }

    private fun proxied(ex: HttpsExchange, r: ProxyResult) = when (r.status) {
        ProxyResult.Status.OK -> bytes(ex, 200, r.body ?: ByteArray(0), "application/json")
        ProxyResult.Status.NOT_CONFIGURED -> error(ex, 503, "not_configured")
        ProxyResult.Status.NOT_ALLOWED -> error(ex, r.httpStatus, "not_allowed")
        ProxyResult.Status.UPSTREAM_AUTH -> error(ex, 502, "upstream_auth")
        ProxyResult.Status.UPSTREAM_ERROR -> error(ex, r.httpStatus, "upstream_error")
    }

    private inline fun <reified T> read(ex: HttpsExchange): T? =
        readText(ex, MAX_BODY)?.let { text -> runCatching { json.decodeFromString<T>(text) }.getOrNull() }

    /** The request body, or null when it is larger than [limit]. */
    private fun readText(ex: HttpsExchange, limit: Int): String? {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        ex.requestBody.use { input ->
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                if (buffer.size() + n > limit) return null
                buffer.write(chunk, 0, n)
            }
        }
        return buffer.toString(Charsets.UTF_8)
    }

    private inline fun <reified T> respond(ex: HttpsExchange, value: T) = bytes(ex, 200, json.encodeToString(value).toByteArray(), "application/json")

    private fun error(ex: HttpsExchange, status: Int, code: String) =
        bytes(ex, status, json.encodeToString(ErrorResponse(code)).toByteArray(), "application/json")

    private fun empty(ex: HttpsExchange, status: Int) {
        ex.sendResponseHeaders(status, -1)
        ex.close()
    }

    private fun bytes(ex: HttpsExchange, status: Int, data: ByteArray, type: String) {
        ex.responseHeaders.set("Content-Type", type)
        ex.sendResponseHeaders(status, data.size.toLong())
        ex.responseBody.use { it.write(data) }
    }

    private fun address(ex: HttpsExchange): String? {
        val a = ex.remoteAddress?.address ?: return null
        return if (a is Inet6Address && a.isIPv4CompatibleAddress) java.net.InetAddress.getByAddress(a.address.takeLast(4).toByteArray()).hostAddress else a.hostAddress
    }

    private fun validId(id: String) = id.length in 1..64 && id.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }

    companion object {
        const val VERSION = 1
        const val KIND = "companion"
        const val KIND_METADATA = "metadata"
        private const val MAX_BODY = 64 * 1024
        private const val MAX_IGDB_QUERY = 8 * 1024

        /** Image servers of SteamGridDB, IGDB and the stores (and their sub domains). */
        val artworkHosts = listOf("steamgriddb.com", "images.igdb.com", "steamstatic.com", "gog-statics.com", "cdn1.epicgames.com")

        private val correctionFields = setOf("name", "cover", "hero", "logo", "icon", "animatedCover", "animatedHero", "summary", "genres", "developers", "publishers", "release")

        /** Strict checks of a correction: sizes, no control characters, artwork only from the providers' image servers. */
        fun valid(c: HostGameCorrection): Boolean {
            fun s(v: String?, max: Int) = v == null || (v.length <= max && v.none { it.isISOControl() && it !in "\n\r\t" })
            fun l(v: List<String>?) = v == null || (v.size <= 32 && v.all { s(it, 128) })
            fun u(v: String?): Boolean {
                if (v == null) return true
                if (v.length > 1024) return false
                val uri = runCatching { URI(v) }.getOrNull() ?: return false
                val host = uri.host ?: return false
                return uri.scheme == "https" && uri.port == -1 &&
                    artworkHosts.any { host.equals(it, ignoreCase = true) || host.endsWith(".$it", ignoreCase = true) }
            }
            return s(c.name, 256) && s(c.summary, 8192) && l(c.genres) && l(c.developers) && l(c.publishers) &&
                u(c.cover) && u(c.hero) && u(c.logo) && u(c.icon) && u(c.animatedCover) && u(c.animatedHero) &&
                (c.kind == null || c.kind == "game" || c.kind == "app") &&
                c.reset.let { r -> r == null || (r.size <= 16 && r.all { it in correctionFields }) } &&
                c.clear.let { l -> l == null || (l.size <= 2 && l.all { it == "animatedCover" || it == "animatedHero" }) } &&
                c.igdbId.let { it == null || it > 0 } && c.steamGridDbId.let { it == null || it > 0 }
        }
    }
}

/** Fixed window per key (pairing per client address). */
class RateLimiter(private val perMinute: Int, private val clock: () -> Long = System::currentTimeMillis) {
    private val windows = ConcurrentHashMap<String, Pair<Long, Int>>()

    fun allow(key: String): Boolean {
        val minute = clock() / 60_000
        val (window, count) = windows.compute(key) { _, old -> if (old == null || old.first != minute) minute to 1 else old.first to old.second + 1 }!!
        if (windows.size > 10_000) windows.entries.removeIf { it.value.first != window }
        return count <= perMinute
    }
}

/** Token bucket per key (proxy requests per device). */
class TokenBuckets(private val capacity: Int, private val perSecond: Int, private val clock: () -> Long = System::currentTimeMillis) {
    private class Bucket(var tokens: Double, var at: Long)
    private val buckets = ConcurrentHashMap<String, Bucket>()

    fun take(key: String): Boolean {
        val now = clock()
        val bucket = buckets.computeIfAbsent(key) { Bucket(capacity.toDouble(), now) }
        synchronized(bucket) {
            bucket.tokens = minOf(capacity.toDouble(), bucket.tokens + (now - bucket.at) / 1000.0 * perSecond)
            bucket.at = now
            if (bucket.tokens < 1) return false
            bucket.tokens -= 1
            return true
        }
    }
}
