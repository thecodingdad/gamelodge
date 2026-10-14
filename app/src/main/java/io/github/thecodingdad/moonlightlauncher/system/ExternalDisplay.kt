package io.github.thecodingdad.moonlightlauncher.system

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** A TV or monitor attached via USB-C (DisplayPort) or HDMI. */
data class ExternalDisplayInfo(
    val name: String,
    val width: Int,
    val height: Int,
    val id: Int = Display.INVALID_DISPLAY,
    /** Modes the TV offers (for the resolution setting). */
    val modes: List<TvMode> = emptyList(),
)

data class TvMode(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

object ExternalDisplay {
    /** Emits the attached external display (or null), and again whenever that changes. */
    fun observe(context: Context): Flow<ExternalDisplayInfo?> = callbackFlow {
        val manager = context.getSystemService(DisplayManager::class.java)
        fun current(): ExternalDisplayInfo? = manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            // Private displays are virtual (screen recording, casting apps), not a real screen.
            .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY && it.flags and Display.FLAG_PRIVATE == 0 }
            ?.let { d ->
                val mode = d.mode
                ExternalDisplayInfo(
                    d.name, maxOf(mode.physicalWidth, mode.physicalHeight), minOf(mode.physicalWidth, mode.physicalHeight), d.displayId,
                    d.supportedModes.map { TvMode(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
                        .sortedWith(compareByDescending<TvMode> { it.width * it.height }.thenByDescending { it.refreshRate }),
                )
            }
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) { trySend(current()) }
            override fun onDisplayRemoved(displayId: Int) { trySend(current()) }
            override fun onDisplayChanged(displayId: Int) { trySend(current()) }
        }
        trySend(current())
        manager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        awaitClose { manager.unregisterDisplayListener(listener) }
    }.distinctUntilChanged()
}
