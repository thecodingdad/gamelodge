package io.github.thecodingdad.gamelodge.stream

import androidx.compose.runtime.Composable
import java.util.UUID

/** One stream option of a profile (Android: read from Artemis' preference definition). */
sealed interface StreamOption {
    val key: String
    val title: String
    val summary: String?

    data class Toggle(override val key: String, override val title: String, override val summary: String?, val default: Boolean) : StreamOption
    data class Choice(
        override val key: String,
        override val title: String,
        override val summary: String?,
        val labels: List<String>,
        val values: List<String>,
        val default: String?,
    ) : StreamOption
    data class Slider(
        override val key: String,
        override val title: String,
        override val summary: String?,
        val min: Int,
        val max: Int,
        val step: Int,
        val divisor: Int,
        val suffix: String?,
        val default: Int?,
    ) : StreamOption
}

data class StreamOptionCategory(val key: String?, val title: String, val options: List<StreamOption>)


/** Values of one profile ([ProfileEditing.values]); keys are the option keys of the schema. */
interface ProfileValues {
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getString(key: String, default: String?): String?
    fun getInt(key: String, default: Int): Int
    fun set(key: String, value: Any)
}

/** What the profile screens need from the platform's streaming client. */
interface ProfileEditing {
    /** The options in their categories, texts in the current language. */
    @Composable
    fun rememberSchema(): List<StreamOptionCategory>

    /** The options shown first in the editor ("the important ones"). */
    val essentialKeys: List<String>

    /** [uuid] null: the base settings. */
    fun values(uuid: UUID?): ProfileValues

    fun create(name: String): UUID
    fun rename(uuid: UUID, name: String)
    fun delete(uuid: UUID)

    /** Default of an option that depends on others (bitrate from resolution and frame rate), or null. */
    fun dynamicDefault(key: String, values: ProfileValues): Int?
}

/**
 * Adds this screen's own resolution and refresh rate to the resolution and frame rate choices,
 * as Artemis' settings do ("Native (2880x1920)"), unless the lists have them already.
 */
fun List<StreamOptionCategory>.withNativeDisplay(width: Int, height: Int, refreshRate: Float, nativeLabel: String, fpsSuffix: String): List<StreamOptionCategory> {
    if (width <= 0 || height <= 0) return this
    val w = maxOf(width, height)
    val h = minOf(width, height)
    val fps = kotlin.math.round(refreshRate).toInt()
    return map { category ->
        category.copy(options = category.options.map { option ->
            when {
                option is StreamOption.Choice && option.key == "list_resolution" && "${w}x$h" !in option.values ->
                    option.copy(labels = option.labels + "$nativeLabel (${w}x$h)", values = option.values + "${w}x$h")
                option is StreamOption.Choice && option.key == "list_fps" && fps > 0 && "$fps" !in option.values ->
                    option.copy(labels = option.labels + "$nativeLabel ($fps $fpsSuffix)", values = option.values + "$fps")
                else -> option
            }
        })
    }
}
