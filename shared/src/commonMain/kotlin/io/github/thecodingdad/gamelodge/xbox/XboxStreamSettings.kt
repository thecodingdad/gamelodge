package io.github.thecodingdad.gamelodge.xbox

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Resolution asked of the console (Xbox One consoles always send 720p). */
enum class XboxResolution(
    /** The client platform the console's streaming service sizes the stream for. */
    val osName: String,
) {
    /** 1080p on Series consoles, 720p on Xbox One. */
    AUTO(""),
    P720("android"),
    P1080("windows"),
    /** 1080p with a higher bitrate (from about 15 Mbit/s). */
    P1080_HQ("tizen"),
}

/** How the picture fills the screen. */
enum class XboxFit(val player: String, val css: String) {
    FIT("", "contain"),
    STRETCH("Stretch", "fill"),
    ZOOM("Zoom", "cover"),
}

/** Stream settings of one console (Settings → Hosts → its settings → Stream). */
@Serializable
data class XboxStreamSettings(
    val resolution: XboxResolution = XboxResolution.AUTO,
    /** Upper limit for the video bitrate in Mbit/s; 0: the console decides. */
    val maxBitrateMbps: Int = 0,
    /** Sharpening of the picture (FSR) 0 = off, 1–10. */
    val sharpness: Int = 0,
    val fit: XboxFit = XboxFit.FIT,
    val vibration: Boolean = true,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(text: String?): Map<String, XboxStreamSettings> = text?.let {
            runCatching { json.decodeFromString<Map<String, XboxStreamSettings>>(it) }.getOrNull()
        } ?: emptyMap()

        fun encode(map: Map<String, XboxStreamSettings>): String = json.encodeToString(map)
    }
}
