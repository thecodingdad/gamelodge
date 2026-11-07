package io.github.thecodingdad.gamelodge.xbox

import io.github.thecodingdad.gamelodge.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import java.net.Inet6Address
import java.net.InetAddress

class XhomeException(message: String, val reason: Reason = Reason.FAILED) : Exception(message) {
    enum class Reason {
        /** The console isn't connected to Xbox Live (off, no network, remote features off). */
        CONSOLE_UNREACHABLE,
        /** Another device streams from the console. */
        BUSY,
        FAILED,
    }
}

/**
 * One home streaming session with a console: the session is set up through Microsoft's streaming
 * service, the media then run peer to peer over WebRTC (in the home network directly, from outside
 * through the console's Teredo address). The player page does the WebRTC part; this side does the
 * signaling, so tokens never reach the page.
 */
class XhomeSession(
    private val http: OkHttpClient,
    private val auth: XboxAuth,
    private val consoleId: String,
    /** 720p for Xbox One consoles, 1080p for Series consoles. */
    private val fullHd: Boolean,
    private val locale: String = "en-US",
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var sessionUrl: String? = null

    val started: Boolean get() = sessionUrl != null

    /** Starts the session and waits until the console is ready to connect. */
    suspend fun start() = withContext(Dispatchers.IO) {
        val token = auth.xhome()
        val osName = if (fullHd) "windows" else "android"
        val body = buildJsonObject {
            put("clientSessionId", "")
            put("titleId", "")
            put("systemUpdateGroup", "")
            put("settings", buildJsonObject {
                put("nanoVersion", "V3;WebrtcTransport.dll")
                put("enableTextToSpeech", false)
                put("highContrast", 0)
                put("locale", locale)
                put("useIceConnection", false)
                put("timezoneOffsetMinutes", java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000)
                put("sdkType", "web")
                put("osName", osName)
            })
            put("serverId", consoleId)
            put("fallbackRegionNames", JsonArray(emptyList()))
        }
        val play = call("POST", "${token.baseUri}/v5/sessions/home/play", body.toString(), mapOf("X-MS-Device-Info" to deviceInfo(osName)))
        val path = play.str("sessionPath") ?: throw XhomeException("No session")
        sessionUrl = "${token.baseUri}/${path.trimStart('/')}"
        repeat(STATE_POLLS) {
            val state = call("GET", url("state"))
            when (state.str("state")) {
                "Provisioned" -> return@withContext
                "Failed" -> {
                    val message = runCatching { state["errorDetails"]!!.jsonObject.str("message") }.getOrNull().orEmpty()
                    sessionUrl = null
                    throw XhomeException("Session failed: $message", reasonOf(message))
                }
            }
            delay(1000)
        }
        stop()
        throw XhomeException("The console didn't get ready", XhomeException.Reason.CONSOLE_UNREACHABLE)
    }

    private fun reasonOf(message: String) = when {
        "WaitingForServerToRegister" in message -> XhomeException.Reason.CONSOLE_UNREACHABLE
        "Busy" in message || "InUse" in message || "AlreadyStreaming" in message -> XhomeException.Reason.BUSY
        else -> XhomeException.Reason.FAILED
    }

    /** Sends the player's SDP offer and returns the console's answer. */
    suspend fun exchangeSdp(offer: String, chatRenegotiation: Boolean = false): String = withContext(Dispatchers.IO) {
        val config: JsonObject = if (chatRenegotiation) buildJsonObject { put("isMediaStreamsChatRenegotiation", true) } else SDP_CONFIG
        val body = buildJsonObject {
            put("messageType", "offer")
            put("sdp", offer)
            put("configuration", config)
        }
        call("POST", url("sdp"), body.toString())
        val answer = pollExchange("sdp")
        (json.parseToJsonElement(answer) as? JsonObject)?.str("sdp") ?: throw XhomeException("No SDP answer")
    }

    /** Sends the player's ICE candidates and returns the console's (with its Teredo address spelled out). */
    suspend fun exchangeIce(candidates: JsonArray): JsonArray = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("messageType", "iceCandidate")
            put("candidate", candidates)
        }
        call("POST", url("ice"), body.toString())
        val remote = json.parseToJsonElement(pollExchange("ice")).jsonArray
        Teredo.expand(remote)
    }

    suspend fun keepalive() = withContext(Dispatchers.IO) {
        runCatching { call("POST", url("keepalive"), "{}") }.onFailure { Log.w("Xbox keepalive failed: ${it.message}") }
        Unit
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        val url = sessionUrl ?: return@withContext
        sessionUrl = null
        runCatching { call("DELETE", url) }.onFailure { Log.w("Xbox session stop failed: ${it.message}") }
        Unit
    }

    private fun url(resource: String) = "${sessionUrl ?: throw XhomeException("No session")}/$resource"

    private suspend fun pollExchange(resource: String): String {
        repeat(EXCHANGE_POLLS) {
            val o = call("GET", url(resource))
            o.str("exchangeResponse")?.let { return it }
            delay(500)
        }
        throw XhomeException("No $resource answer from the console")
    }

    private suspend fun call(method: String, url: String, body: String? = null, headers: Map<String, String> = emptyMap()): JsonObject {
        val token = auth.xhome().gsToken
        val request = Request.Builder().url(url)
            .method(method, body?.toRequestBody(JSON) ?: if (method == "POST") "".toRequestBody(JSON) else null)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
        headers.forEach { (k, v) -> request.header(k, v) }
        val response = http.newCall(request.build()).execute()
        val text = response.use { it.body?.string().orEmpty() }
        if (!response.isSuccessful) {
            Log.w("Xbox session ${url.substringAfterLast("/sessions/home")}: HTTP ${response.code}")
            throw XhomeException("HTTP ${response.code}", if (response.code == 409) XhomeException.Reason.BUSY else XhomeException.Reason.FAILED)
        }
        return if (text.isBlank()) JsonObject(emptyMap()) else (json.parseToJsonElement(text) as? JsonObject) ?: JsonObject(emptyMap())
    }

    private fun deviceInfo(osName: String): String = buildJsonObject {
        put("appInfo", buildJsonObject {
            put("env", buildJsonObject {
                put("clientAppId", "www.xbox.com")
                put("clientAppType", "browser")
                put("clientAppVersion", "29.9.35")
                put("clientSdkVersion", "10.6.8")
                put("httpEnvironment", "prod")
                put("sdkInstallId", "")
            })
        })
        put("dev", buildJsonObject {
            put("hw", buildJsonObject { put("make", "Microsoft"); put("model", "unknown"); put("platformType", "desktop"); put("sdktype", "web") })
            put("os", buildJsonObject { put("name", osName); put("ver", "22631.2715"); put("platform", "desktop") })
            put("displayInfo", buildJsonObject {
                put("dimensions", buildJsonObject { put("widthInPixels", 1920); put("heightInPixels", 1080) })
                put("pixelDensity", buildJsonObject { put("dpiX", 1); put("dpiY", 1) })
            })
            put("browser", buildJsonObject { put("browserName", "edge"); put("browserVersion", "140.0.3485.66") })
        })
    }.toString()

    companion object {
        private val JSON = "application/json".toMediaType()
        private const val STATE_POLLS = 90
        private const val EXCHANGE_POLLS = 60

        /** Data channel versions the player speaks (as the Xbox web client offers them). */
        private val SDP_CONFIG = buildJsonObject {
            put("chatConfiguration", buildJsonObject {
                put("bytesPerSample", 2)
                put("expectedClipDurationMs", 20)
                put("format", buildJsonObject { put("codec", "opus"); put("container", "webm") })
                put("numChannels", 1)
                put("sampleFrequencyHz", 24000)
            })
            put("chat", buildJsonObject { put("minVersion", 1); put("maxVersion", 1) })
            put("control", buildJsonObject { put("minVersion", 1); put("maxVersion", 3) })
            put("input", buildJsonObject { put("minVersion", 1); put("maxVersion", 8) })
            put("message", buildJsonObject { put("minVersion", 1); put("maxVersion", 1) })
        }
    }
}

/**
 * The console announces its address outside the home network as a Teredo IPv6 address. Clients
 * without Teredo reach it through the IPv4 addresses inside it: these are added as extra host
 * candidates (as the Xbox web client does), with the console's game port 3074 as fallback.
 */
object Teredo {
    private const val GAME_PORT = 3074

    fun expand(candidates: JsonArray): JsonArray = buildJsonArray {
        candidates.forEachIndexed { index, element ->
            add(element)
            val c = element as? JsonObject ?: return@forEachIndexed
            val line = c.str("candidate") ?: return@forEachIndexed
            val tokens = line.trim().split(Regex("\\s+"))
            val ipIndex = tokens.indexOfFirst { it.contains(':') && parseTeredo(it) != null }
            if (ipIndex < 0) return@forEachIndexed
            val teredo = parseTeredo(tokens[ipIndex]) ?: return@forEachIndexed
            val port = tokens.getOrNull(ipIndex + 1)?.toIntOrNull() ?: 9002
            val extra = buildList {
                add(teredo.client to teredo.clientPort)
                add(teredo.server to port)
                add(teredo.client to port)
                if (port != GAME_PORT) {
                    add(teredo.server to GAME_PORT)
                    add(teredo.client to GAME_PORT)
                }
            }
            extra.forEachIndexed { i, (ip, p) ->
                add(buildJsonObject {
                    put("candidate", "a=candidate:${index + 2 + i} 1 UDP 100 $ip $p typ host ")
                    c["messageType"]?.let { put("messageType", it) }
                    c["sdpMLineIndex"]?.let { put("sdpMLineIndex", it) }
                    c["sdpMid"]?.let { put("sdpMid", it) }
                })
            }
        }
    }

    data class Address(val server: String, val client: String, val clientPort: Int)

    /** The IPv4 parts of a Teredo address (prefix 2001:0000::/32), null for other addresses. */
    fun parseTeredo(ip: String): Address? {
        if (!ip.matches(Regex("^[0-9a-fA-F:.]+$"))) return null
        val bytes = runCatching { InetAddress.getByName(ip) }.getOrNull()?.takeIf { it is Inet6Address }?.address ?: return null
        if (bytes[0] != 0x20.toByte() || bytes[1] != 0x01.toByte() || bytes[2] != 0.toByte() || bytes[3] != 0.toByte()) return null
        val server = (4..7).joinToString(".") { (bytes[it].toInt() and 0xff).toString() }
        val clientPort = (((bytes[10].toInt() and 0xff) shl 8) or (bytes[11].toInt() and 0xff)) xor 0xffff
        val client = (12..15).joinToString(".") { ((bytes[it].toInt() and 0xff) xor 0xff).toString() }
        return Address(server, client, clientPort)
    }
}
