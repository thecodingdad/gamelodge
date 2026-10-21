package io.github.thecodingdad.gamelodge.desktop.host

import io.github.thecodingdad.gamelodge.data.companion.StreamNotice
import io.github.thecodingdad.gamelodge.util.Log
import java.util.concurrent.ConcurrentHashMap

/** Paired launchers that stream from this PC right now (their notices expire without repetition). */
object HostStreams {
    private const val EXPIRES_MS = 90_000L
    private val until = ConcurrentHashMap<String, Long>()

    /** A resumed stream: brings the running host game to the front (set by the launcher). */
    @Volatile var onResume: () -> Unit = {}

    fun notice(deviceId: String, deviceName: String, notice: StreamNotice) {
        if (notice.active) {
            if (until.put(deviceId, System.currentTimeMillis() + EXPIRES_MS) == null) Log.i("$deviceName streams from this PC")
            if (notice.resume) runCatching { onResume() }.onFailure { Log.w("Bringing the game to the front failed", it) }
        } else if (until.remove(deviceId) != null) {
            Log.i("$deviceName stopped streaming")
        }
    }

    /** A paired launcher streams: its controller buttons (Guide) are its own. */
    val active: Boolean
        get() {
            val now = System.currentTimeMillis()
            until.entries.removeIf { it.value < now }
            return until.isNotEmpty()
        }
}
