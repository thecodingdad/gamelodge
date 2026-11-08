package io.github.thecodingdad.gamelodge.xbox

import io.github.thecodingdad.gamelodge.stream.ProfileValues

/** Resolution asked of the console; it sends at most what it can (Xbox One consoles often only 720p). */
enum class XboxResolution(
    /** The client platform the console's streaming service sizes the stream for. */
    val osName: String,
) {
    /** 1080p (the console sends less where it can't). */
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

/**
 * Xbox part of a stream profile. Kept in the profile's own values (keys [KEYS]) next to the PC
 * options, so new profiles, renames, deletion and backups carry it along.
 */
data class XboxStreamSettings(
    val resolution: XboxResolution = XboxResolution.AUTO,
    /** Upper limit for the video bitrate in Mbit/s; 0: the console decides. */
    val maxBitrateMbps: Int = 0,
    /** Sharpening of the picture (FSR) 0 = off, 1–10. */
    val sharpness: Int = 0,
    val fit: XboxFit = XboxFit.FIT,
    val vibration: Boolean = true,
) {
    /** Writes the changed values into a profile. */
    fun writeTo(values: ProfileValues, old: XboxStreamSettings) {
        if (resolution != old.resolution) values.set(RESOLUTION, resolution.name)
        if (maxBitrateMbps != old.maxBitrateMbps) values.set(BITRATE, maxBitrateMbps)
        if (sharpness != old.sharpness) values.set(SHARPNESS, sharpness)
        if (fit != old.fit) values.set(FIT, fit.name)
        if (vibration != old.vibration) values.set(VIBRATION, vibration)
    }

    companion object {
        const val RESOLUTION = "xbox_resolution"
        const val BITRATE = "xbox_max_bitrate_mbps"
        const val SHARPNESS = "xbox_sharpness"
        const val FIT = "xbox_fit"
        const val VIBRATION = "xbox_vibration"

        /** A profile's Xbox values; what the profile doesn't have comes from [base] (profiles older than the Xbox part). */
        fun of(values: ProfileValues, base: ProfileValues? = null): XboxStreamSettings {
            val d = XboxStreamSettings()
            val from = base?.let { of(it) } ?: d
            return XboxStreamSettings(
                resolution = values.getString(RESOLUTION, null)?.let { n -> XboxResolution.entries.firstOrNull { it.name == n } } ?: from.resolution,
                maxBitrateMbps = values.getInt(BITRATE, from.maxBitrateMbps),
                sharpness = values.getInt(SHARPNESS, from.sharpness),
                fit = values.getString(FIT, null)?.let { n -> XboxFit.entries.firstOrNull { it.name == n } } ?: from.fit,
                vibration = values.getBoolean(VIBRATION, from.vibration),
            )
        }
    }
}
