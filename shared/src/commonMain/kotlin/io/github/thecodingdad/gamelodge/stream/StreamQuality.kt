package io.github.thecodingdad.gamelodge.stream

import io.github.thecodingdad.gamelodge.data.settings.ConnectionWarnings
import io.github.thecodingdad.gamelodge.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** One second of a stream's connection as the streaming client sees it. */
data class ConnectionSample(
    val rttMs: Int,
    val rttVarianceMs: Int,
    /** The client's own verdict from lost frames (Moonlight's "poor connection"). */
    val frameLoss: Boolean,
    /** Audio packets dropped since the last sample (they came too late or bunched up). */
    val audioDrops: Int,
    /** The performance overlay shows. */
    val perfOverlay: Boolean,
)

/** What is wrong, most severe first. */
enum class ConnectionIssue { FRAME_LOSS, AUDIO_DROPS, JITTER, LATENCY }

data class ConnectionWarning(val issue: ConnectionIssue, val rttMs: Int)

/**
 * Decides from the samples whether the connection is poor: an issue counts once it shows over a
 * few seconds, and the warning stays until the connection was fine for [CLEAR_AFTER] seconds, so
 * it doesn't flicker. The first seconds of a stream (connecting) don't count.
 */
class ConnectionQualityMonitor {
    private val recent = ArrayDeque<ConnectionSample>()
    private var count = 0
    private var good = 0
    private var current: ConnectionIssue? = null

    fun reset() {
        recent.clear()
        count = 0
        good = 0
        current = null
    }

    /** Adds the next second; returns the issue to show (null: none). */
    fun add(s: ConnectionSample): ConnectionIssue? {
        count++
        recent.addLast(s)
        while (recent.size > WINDOW) recent.removeFirst()
        if (count <= WARMUP) return null
        val issue = issueNow()
        if (issue != null) {
            good = 0
            current = issue
        } else if (current != null && ++good >= CLEAR_AFTER) {
            current = null
        }
        return current
    }

    private fun issueNow(): ConnectionIssue? {
        val last3 = recent.toList().takeLast(3)
        return when {
            recent.last().frameLoss -> ConnectionIssue.FRAME_LOSS
            last3.sumOf { it.audioDrops } >= AUDIO_DROPS -> ConnectionIssue.AUDIO_DROPS
            last3.size == 3 && last3.count { it.rttVarianceMs >= JITTER_MS } >= 2 -> ConnectionIssue.JITTER
            recent.size == WINDOW && recent.all { it.rttMs >= HIGH_RTT_MS } -> ConnectionIssue.LATENCY
            else -> null
        }
    }

    companion object {
        const val WINDOW = 5
        const val WARMUP = 5
        const val CLEAR_AFTER = 5
        /** Dropped audio packets within 3 s (one packet is 5 ms of sound). */
        const val AUDIO_DROPS = 5
        /** Round trip variation that shows as stutter. */
        const val JITTER_MS = 10
        /** Round trip that is felt (5 s in a row): far above a home network's few ms. */
        const val HIGH_RTT_MS = 60
    }
}

/**
 * The connection warning of the running stream: the streaming client reports [sample] once a
 * second, the stream window shows [warning]. [mode] is the stream profile's setting, set at the
 * stream's start ([modeOf]).
 */
object StreamQuality {
    private val monitor = ConnectionQualityMonitor()
    private val _warning = MutableStateFlow<ConnectionWarning?>(null)
    val warning: StateFlow<ConnectionWarning?> = _warning.asStateFlow()

    @Volatile var mode: ConnectionWarnings = ConnectionWarnings.ALWAYS

    /** Key of the setting in a stream profile's values. */
    const val PROFILE_KEY = "gamelodge_connection_warnings"

    /** A profile's setting; profiles without one take the base settings' ([base]), else [ConnectionWarnings.ALWAYS]. */
    fun modeOf(values: ProfileValues, base: ProfileValues?): ConnectionWarnings {
        val name = values.getString(PROFILE_KEY, null) ?: base?.getString(PROFILE_KEY, null)
        return ConnectionWarnings.entries.firstOrNull { it.name == name } ?: ConnectionWarnings.ALWAYS
    }

    @Synchronized
    fun sample(s: ConnectionSample) {
        val issue = monitor.add(s)
        val shown = when (mode) {
            ConnectionWarnings.OFF -> false
            ConnectionWarnings.PERF_OVERLAY -> s.perfOverlay
            ConnectionWarnings.ALWAYS -> true
        }
        _warning.value = if (issue != null && shown) ConnectionWarning(issue, s.rttMs) else null
    }

    /** A stream starts or ended: nothing carried over. */
    @Synchronized
    fun reset() {
        monitor.reset()
        _warning.value = null
    }
}
