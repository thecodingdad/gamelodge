package io.github.thecodingdad.gamelodge.ha

import io.github.thecodingdad.gamelodge.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** One Home Assistant entity as the launcher needs it. */
data class HaEntity(
    val id: String,
    val state: String,
    val attributes: JsonObject,
) {
    val domain: String get() = id.substringBefore('.')
    val name: String get() = attributes["friendly_name"]?.jsonPrimitive?.contentOrNull ?: id
    val unit: String? get() = attributes["unit_of_measurement"]?.jsonPrimitive?.contentOrNull
    val isOn: Boolean get() = state == "on" || state == "open" || state == "playing" || state == "heat" || state == "cool"
    val isUnavailable: Boolean get() = state == "unavailable" || state == "unknown"
    /** Light brightness 0..100 (null when off or unsupported). */
    val brightnessPercent: Int? get() = attributes["brightness"]?.jsonPrimitive?.doubleOrNull?.let { (it / 255.0 * 100).toInt() }
    val coverPosition: Int? get() = attributes["current_position"]?.jsonPrimitive?.doubleOrNull?.toInt()
    val targetTemperature: Double? get() = attributes["temperature"]?.jsonPrimitive?.doubleOrNull
    val currentTemperature: Double? get() = attributes["current_temperature"]?.jsonPrimitive?.doubleOrNull
}

enum class HaConnection { NOT_CONFIGURED, DISCONNECTED, CONNECTING, CONNECTED, AUTH_FAILED }

/**
 * Home Assistant WebSocket API client: authenticates, keeps entity states via
 * `subscribe_entities` and runs commands. Reconnects with backoff while [start]ed.
 */
class HaClient(
    private val http: OkHttpClient,
    private val auth: HaAuth,
    private val baseUrl: () -> String?,
    private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()

    private val _connection = MutableStateFlow(HaConnection.DISCONNECTED)
    val connection: StateFlow<HaConnection> = _connection.asStateFlow()

    private val _entities = MutableStateFlow<Map<String, HaEntity>>(emptyMap())
    val entities: StateFlow<Map<String, HaEntity>> = _entities.asStateFlow()

    private var socket: WebSocket? = null
    private var loop: Job? = null
    private var subscriptionId = -1

    fun start() {
        // A loop sitting in its reconnect backoff is restarted so the user doesn't wait a minute.
        if (loop?.isActive == true && _connection.value != HaConnection.DISCONNECTED) return
        loop?.cancel()
        loop = scope.launch {
            var backoff = 2_000L
            while (true) {
                val url = baseUrl()
                if (url.isNullOrBlank() || !auth.isLoggedIn) {
                    _connection.value = HaConnection.NOT_CONFIGURED
                    return@launch
                }
                _connection.value = HaConnection.CONNECTING
                val closed = CompletableDeferred<Unit>()
                runCatching { connect(url, closed) }.onFailure {
                    Log.w("HA connect failed", it)
                    if (it is HaAuthException) {
                        _connection.value = HaConnection.AUTH_FAILED
                        return@launch
                    }
                }
                closed.await()
                if (_connection.value == HaConnection.AUTH_FAILED) return@launch
                _connection.value = HaConnection.DISCONNECTED
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000)
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        socket?.close(1000, null)
        socket = null
        _connection.value = HaConnection.DISCONNECTED
    }

    fun restart() {
        stop()
        start()
    }

    private suspend fun connect(base: String, closed: CompletableDeferred<Unit>) {
        val token = auth.accessToken()
        val wsUrl = HaAuth.normalizeBaseUrl(base).replaceFirst("http", "ws") + "/api/websocket"
        socket = http.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = handle(webSocket, text, token)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Dropped connections (sleep, network change, HA restart) reconnect: one line, no stack trace.
                if (t is java.io.IOException) Log.w("HA websocket lost (${response?.code?.let { "HTTP $it, " } ?: ""}${t.javaClass.simpleName}: ${t.message}), reconnecting")
                else Log.w("HA websocket failure (HTTP ${response?.code})", t)
                failPending()
                closed.complete(Unit)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i("HA websocket closed: $code $reason")
                failPending()
                closed.complete(Unit)
            }
        })
    }

    private fun handle(ws: WebSocket, text: String, token: String) {
        val msg = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (msg["type"]?.jsonPrimitive?.contentOrNull) {
            "auth_required" -> ws.send(buildJsonObject { put("type", "auth"); put("access_token", token) }.toString())
            "auth_invalid" -> {
                _connection.value = HaConnection.AUTH_FAILED
                ws.close(1000, null)
            }
            "auth_ok" -> {
                _connection.value = HaConnection.CONNECTED
                subscriptionId = nextId.getAndIncrement()
                ws.send(buildJsonObject { put("id", subscriptionId); put("type", "subscribe_entities") }.toString())
            }
            "event" -> if (msg["id"]?.jsonPrimitive?.longOrNull?.toInt() == subscriptionId) {
                msg["event"]?.jsonObject?.let(::applyEntityEvent)
            }
            "result" -> {
                val id = msg["id"]?.jsonPrimitive?.longOrNull?.toInt() ?: return
                pending.remove(id)?.complete(msg)
            }
        }
    }

    /** Applies the compressed `subscribe_entities` format: a = added, c = changed, r = removed. */
    internal fun applyEntityEvent(event: JsonObject) {
        _entities.update { current ->
            val next = current.toMutableMap()
            event["a"]?.jsonObject?.forEach { (id, raw) ->
                val o = raw.jsonObject
                next[id] = HaEntity(id, o["s"]?.jsonPrimitive?.contentOrNull ?: "unknown", o["a"]?.jsonObject ?: JsonObject(emptyMap()))
            }
            event["c"]?.jsonObject?.forEach { (id, raw) ->
                val old = next[id] ?: return@forEach
                val diff = raw.jsonObject
                var state = old.state
                val attrs = old.attributes.toMutableMap()
                diff["+"]?.jsonObject?.let { plus ->
                    plus["s"]?.jsonPrimitive?.contentOrNull?.let { state = it }
                    plus["a"]?.jsonObject?.forEach { (k, v) -> attrs[k] = v }
                }
                diff["-"]?.jsonObject?.get("a")?.jsonArray?.forEach { attrs.remove(it.jsonPrimitive.content) }
                next[id] = HaEntity(id, state, JsonObject(attrs))
            }
            event["r"]?.jsonArray?.forEach { next.remove(it.jsonPrimitive.content) }
            next
        }
    }

    /** Sends a command and waits for its result (throws on error or timeout). */
    suspend fun command(type: String, fields: Map<String, JsonElement> = emptyMap()): JsonObject {
        if (_connection.value != HaConnection.CONNECTED) {
            start()
            // Connecting through a VPN that just came up may take a while.
            withTimeout(20_000) { connection.first { it == HaConnection.CONNECTED } }
        }
        val id = nextId.getAndIncrement()
        val payload = JsonObject(mapOf("id" to JsonPrimitive(id), "type" to JsonPrimitive(type)) + fields)
        val result = CompletableDeferred<JsonObject>()
        pending[id] = result
        socket?.send(payload.toString()) ?: throw IllegalStateException("Home Assistant not connected")
        val response = withTimeout(15_000) { result.await() }
        if (response["success"]?.jsonPrimitive?.booleanOrNull != true) {
            throw IllegalStateException(response["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull ?: "Home Assistant error")
        }
        return response
    }

    suspend fun callService(domain: String, service: String, entityId: String?, data: Map<String, JsonElement> = emptyMap()) {
        command(
            "call_service",
            buildMap {
                put("domain", JsonPrimitive(domain))
                put("service", JsonPrimitive(service))
                if (data.isNotEmpty()) put("service_data", JsonObject(data))
                if (entityId != null) put("target", JsonObject(mapOf("entity_id" to JsonPrimitive(entityId))))
            },
        )
    }

    suspend fun fireEvent(eventType: String, data: Map<String, JsonElement>) {
        command("fire_event", mapOf("event_type" to JsonPrimitive(eventType), "event_data" to JsonObject(data)))
    }

    private fun failPending() {
        pending.values.forEach { it.completeExceptionally(IllegalStateException("Connection closed")) }
        pending.clear()
    }
}
