package io.github.thecodingdad.gamelodge.system

import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import android.view.Window

/**
 * Tells whether a window draws smoothly: over each run of [SAMPLE] drawn frames, [onResult] gets
 * true when more than half of them took longer than one and a half refresh intervals.
 */
class FrameWatch(private val onResult: (slow: Boolean) -> Unit) {
    private var window: Window? = null
    private var listener: Window.OnFrameMetricsAvailableListener? = null
    private var frames = 0
    private var late = 0

    fun start(window: Window, refreshRate: Float) {
        if (this.window === window && listener != null) return
        stop()
        val budgetNs = (1_000_000_000L / refreshRate * 1.5f).toLong()
        val main = Handler(Looper.getMainLooper())
        val l = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            frames++
            if (metrics.getMetric(FrameMetrics.TOTAL_DURATION) > budgetNs) late++
            if (frames >= SAMPLE) {
                val slow = late * 2 > frames
                frames = 0
                late = 0
                main.post { onResult(slow) }
            }
        }
        window.addOnFrameMetricsAvailableListener(l, Handler(Looper.getMainLooper()))
        this.window = window
        listener = l
    }

    fun stop() {
        val w = window
        val l = listener
        if (w != null && l != null) runCatching { w.removeOnFrameMetricsAvailableListener(l) }
        window = null
        listener = null
        frames = 0
        late = 0
    }

    private companion object {
        /** Frames per verdict (a few seconds of moving through the launcher). */
        const val SAMPLE = 180
    }
}
