package io.github.thecodingdad.moonlightlauncher.stream

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
