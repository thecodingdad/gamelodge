package io.github.thecodingdad.moonlightlauncher.system

/** A TV or monitor attached via USB-C (DisplayPort) or HDMI. */
data class ExternalDisplayInfo(
    val name: String,
    val width: Int,
    val height: Int,
    /** Platform display id; -1 = none. */
    val id: Int = -1,
    /** Modes the TV offers (for the resolution setting). */
    val modes: List<TvMode> = emptyList(),
)

data class TvMode(val id: Int, val width: Int, val height: Int, val refreshRate: Float)
