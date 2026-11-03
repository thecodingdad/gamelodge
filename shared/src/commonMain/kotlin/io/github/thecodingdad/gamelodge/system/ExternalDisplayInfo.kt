package io.github.thecodingdad.gamelodge.system

/** A TV or monitor attached via USB-C (DisplayPort) or HDMI. */
data class ExternalDisplayInfo(
    val name: String,
    val width: Int,
    val height: Int,
    /** Platform display id; -1 = none. */
    val id: Int = -1,
    /** Modes the TV offers (for the resolution setting). */
    val modes: List<TvMode> = emptyList(),
    /** Refresh rate of the mode it runs in now. */
    val refreshRate: Float = 0f,
)

/** A display mode; [id] is only valid while connected (Android numbers them anew each time). */
data class TvMode(val id: Int, val width: Int, val height: Int, val refreshRate: Float) {
    /** Stable name of the mode, as stored in the settings: "1920x1080@60". */
    val key: String get() = "${width}x$height@${kotlin.math.round(refreshRate).toInt()}"
}

object TvModes {
    /** Above this the launcher's UI doesn't draw smoothly on a handheld (and the TV scales up well). */
    private const val SMOOTH_PIXELS = 1920 * 1080

    /**
     * "Automatic": the largest resolution up to 1080p, at its highest refresh rate (smooth); a TV
     * without such a mode keeps its smallest one at the highest rate.
     */
    fun auto(modes: List<TvMode>): TvMode? =
        modes.filter { it.width * it.height <= SMOOTH_PIXELS }.maxWithOrNull(compareBy<TvMode>({ it.width * it.height }, { it.refreshRate }))
            ?: modes.minWithOrNull(compareBy<TvMode>({ it.width * it.height }, { -it.refreshRate }))

    /** The mode for the setting ([key] "" = automatic); an unknown one falls back to automatic. */
    fun resolve(key: String, modes: List<TvMode>): TvMode? =
        key.takeIf { it.isNotEmpty() }?.let { k -> modes.firstOrNull { it.key == k } } ?: auto(modes)
}
