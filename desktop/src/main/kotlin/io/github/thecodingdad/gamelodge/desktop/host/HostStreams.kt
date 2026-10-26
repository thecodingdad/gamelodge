package io.github.thecodingdad.gamelodge.desktop.host

import io.github.thecodingdad.gamelodge.data.companion.StreamNotice
import io.github.thecodingdad.gamelodge.util.Log
import java.util.concurrent.ConcurrentHashMap

/** Paired launchers that stream from this PC right now (their notices expire without repetition). */
object HostStreams {
    private const val EXPIRES_MS = 90_000L
    private const val ACTION_VALID_MS = 120_000L
    private val until = ConcurrentHashMap<String, Long>()

    /** A resumed stream: brings the running host game to the front (set by the launcher). */
    @Volatile var onResume: () -> Unit = {}

    /** Start actions paired launchers asked for, by app uuid: (action, valid until). */
    private val actions = ConcurrentHashMap<String, Pair<String, Long>>()

    /**
     * The start action a paired launcher asked for when it started streaming [appUuid] (once, and
     * only for a short while: a stream that never started must not change a later one).
     */
    fun takeAction(appUuid: String): String? {
        val (action, validUntil) = actions.remove(appUuid.lowercase()) ?: return null
        return action.takeIf { System.currentTimeMillis() < validUntil }
    }

    fun notice(deviceId: String, deviceName: String, notice: StreamNotice) {
        notice.appUuid?.lowercase()?.let { app ->
            val action = notice.action
            if (action != null) actions[app] = action to System.currentTimeMillis() + ACTION_VALID_MS else if (!notice.resume) actions.remove(app)
        }
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
