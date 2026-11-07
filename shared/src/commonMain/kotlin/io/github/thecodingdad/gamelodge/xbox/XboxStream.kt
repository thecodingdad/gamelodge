package io.github.thecodingdad.gamelodge.xbox

import io.github.thecodingdad.gamelodge.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** One controller's state in the player's terms (buttons 0/1, sticks -1..1 with down positive as Android, SDL and browsers report them, triggers 0..1). */
data class XboxPadState(
    val index: Int,
    val a: Boolean = false, val b: Boolean = false, val x: Boolean = false, val y: Boolean = false,
    val lb: Boolean = false, val rb: Boolean = false,
    val view: Boolean = false, val menu: Boolean = false, val nexus: Boolean = false,
    val leftThumb: Boolean = false, val rightThumb: Boolean = false,
    val up: Boolean = false, val down: Boolean = false, val left: Boolean = false, val right: Boolean = false,
    val lx: Float = 0f, val ly: Float = 0f, val rx: Float = 0f, val ry: Float = 0f,
    val lt: Float = 0f, val rt: Float = 0f,
) {
    fun toJson(): JsonObject = buildJsonObject {
        fun b(v: Boolean) = if (v) 1 else 0
        put("GamepadIndex", index)
        put("A", b(a)); put("B", b(b)); put("X", b(x)); put("Y", b(y))
        put("LeftShoulder", b(lb)); put("RightShoulder", b(rb))
        put("LeftTrigger", lt); put("RightTrigger", rt)
        put("View", b(view)); put("Menu", b(menu)); put("Nexus", b(nexus))
        put("LeftThumb", b(leftThumb)); put("RightThumb", b(rightThumb))
        put("DPadUp", b(up)); put("DPadDown", b(down)); put("DPadLeft", b(left)); put("DPadRight", b(right))
        put("LeftThumbXAxis", lx); put("LeftThumbYAxis", ly)
        put("RightThumbXAxis", rx); put("RightThumbYAxis", ry)
    }
}

/** What the stream window shows. */
sealed interface XboxStreamState {
    data object Connecting : XboxStreamState
    data object Connected : XboxStreamState
    /** Ended by the user or the console. */
    data object Ended : XboxStreamState
    data class Failed(val message: String) : XboxStreamState
}

/**
 * Runs one stream between the player page and the console: answers the page's messages (SDP and
 * ICE exchange through [XhomeSession]), keeps the session alive and ends it. The platform only
 * carries text messages between this and the page ([send] towards the page, [onMessage] from it)
 * and sends the controllers ([pads]).
 *
 * Messages, all JSON with a type "t":
 * - page → here: `ready`, `offer {sdp}`, `chatOffer {sdp}`, `ice {candidates}`, `state {state}`,
 *   `rumble {index, strong, weak, leftTrigger, rightTrigger, durationMs}`, `stats {…}`, `log {message}`
 * - here → page: `config {…}`, `answer {sdp}`, `chatAnswer {sdp}`, `remoteIce {candidates}`, `pads {pads}`, `stop`
 */
class XboxStreamController(
    private val session: XhomeSession,
    private val scope: CoroutineScope,
    private val config: Config,
) {
    data class Config(
        /** Upper limit for the video bitrate in kbit/s (0: the console decides). */
        val maxBitrateKbps: Int = 0,
        val vibration: Boolean = true,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow<XboxStreamState>(XboxStreamState.Connecting)
    val state: StateFlow<XboxStreamState> = _state.asStateFlow()

    private val _live = MutableStateFlow(false)
    /** The stream's first frame is on screen (the start screen can go). */
    val live: StateFlow<Boolean> = _live.asStateFlow()

    /** Statistics line of the player (resolution, fps, latency …) for the performance overlay. */
    private val _stats = MutableStateFlow<Map<String, String>>(emptyMap())
    val stats: StateFlow<Map<String, String>> = _stats.asStateFlow()

    /** Towards the page; set by the platform before the page loads. */
    @Volatile var send: (String) -> Unit = {}

    /** Rumble for controller index (strong, weak motor 0..1, duration in ms). */
    @Volatile var onRumble: (index: Int, strong: Float, weak: Float, durationMs: Int) -> Unit = { _, _, _, _ -> }

    private var keepalive: Job? = null

    fun onMessage(text: String) {
        val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (o.str("t")) {
            "ready" -> send(buildJsonObject {
                put("t", "config")
                put("maxBitrateKbps", config.maxBitrateKbps)
                put("vibration", config.vibration)
            }.toString())
            "offer", "chatOffer" -> {
                val chat = o.str("t") == "chatOffer"
                val sdp = o.str("sdp") ?: return
                scope.launch {
                    runCatching { session.exchangeSdp(sdp, chatRenegotiation = chat) }
                        .onSuccess { answer -> send(buildJsonObject { put("t", if (chat) "chatAnswer" else "answer"); put("sdp", answer) }.toString()) }
                        .onFailure { if (!chat) fail("SDP: ${it.message}") else Log.w("Xbox chat renegotiation failed: ${it.message}") }
                }
            }
            "ice" -> {
                val candidates = o["candidates"] as? JsonArray ?: return
                scope.launch {
                    runCatching { session.exchangeIce(candidates) }
                        .onSuccess { remote -> send(buildJsonObject { put("t", "remoteIce"); put("candidates", remote) }.toString()) }
                        .onFailure { fail("ICE: ${it.message}") }
                }
            }
            "state" -> when (o.str("state")) {
                "connected" -> connected()
                "failed" -> fail(o.str("detail") ?: "Connection failed")
                "closed", "disconnected" -> if (_state.value == XboxStreamState.Connected) end()
            }
            "rumble" -> onRumble(
                o.str("index")?.toIntOrNull() ?: 0,
                o.str("strong")?.toFloatOrNull() ?: 0f,
                o.str("weak")?.toFloatOrNull() ?: 0f,
                o.str("durationMs")?.toFloatOrNull()?.toInt() ?: 0,
            )
            "video" -> _live.value = true
            "stats" -> _stats.value = o.filterKeys { it != "t" }.mapValues { (_, v) -> (v as? kotlinx.serialization.json.JsonPrimitive)?.content ?: v.toString() }
            "log" -> Log.i("Xbox player: ${o.str("message")}")
        }
    }

    /** Sends the controllers to the console (call on every change and regularly). */
    fun pads(pads: List<XboxPadState>) {
        if (_state.value != XboxStreamState.Connected) return
        send(buildJsonObject {
            put("t", "pads")
            put("pads", buildJsonArray { pads.forEach { add(it.toJson()) } })
        }.toString())
    }

    private fun connected() {
        if (_state.value == XboxStreamState.Connected) return
        _state.value = XboxStreamState.Connected
        keepalive?.cancel()
        keepalive = scope.launch {
            while (isActive) {
                delay(KEEPALIVE_MS)
                session.keepalive()
            }
        }
    }

    private fun fail(message: String) {
        Log.w("Xbox stream failed: $message")
        if (_state.value is XboxStreamState.Ended) return
        _state.value = XboxStreamState.Failed(message)
        close()
    }

    /** Ends the stream (the window closes, the user left it): the console's session ends too. */
    fun end() {
        if (_state.value !is XboxStreamState.Failed) _state.value = XboxStreamState.Ended
        close()
    }

    private fun close() {
        keepalive?.cancel()
        keepalive = null
        runCatching { send("""{"t":"stop"}""") }
        scope.launch { session.stop() }
    }

    companion object {
        private const val KEEPALIVE_MS = 30_000L
    }
}

/** The Xbox stream that runs right now (the stream window finds it here; one at a time). */
object XboxStreams {
    @Volatile var current: XboxStreamController? = null
}
