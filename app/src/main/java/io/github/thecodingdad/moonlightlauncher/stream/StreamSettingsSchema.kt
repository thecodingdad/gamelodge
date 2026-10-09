package io.github.thecodingdad.moonlightlauncher.stream

import android.content.Context
import android.content.res.XmlResourceParser
import org.xmlpull.v1.XmlPullParser

/** One stream option, read from Artemis' preference definition. */
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

/**
 * Parses Artemis' `res/xml/preferences.xml` at runtime, so every stream option can be shown
 * in the launcher's own UI (and new upstream options appear without code changes).
 */
object StreamSettingsSchema {
    private const val ANDROID = "http://schemas.android.com/apk/res/android"
    private const val SEEKBAR = "http://schemas.moonlight-stream.com/apk/res/seekbar"

    /** Categories that configure Artemis' own UI, irrelevant inside the launcher. */
    private val skippedCategories = setOf("category_ui_settings")
    private val skippedKeys = setOf("list_languages", "checkbox_small_icon_mode")

    @Volatile private var cached: List<StreamOptionCategory>? = null

    fun load(context: Context): List<StreamOptionCategory> = cached ?: parse(context).also { cached = it }

    private fun parse(context: Context): List<StreamOptionCategory> {
        val res = context.resources
        val parser = res.getXml(com.limelight.R.xml.preferences)
        val categories = mutableListOf<StreamOptionCategory>()
        var currentKey: String? = null
        var currentTitle = ""
        var current = mutableListOf<StreamOption>()

        fun text(p: XmlResourceParser, attr: String, ns: String = ANDROID): String? {
            val id = p.getAttributeResourceValue(ns, attr, 0)
            return if (id != 0) runCatching { context.getString(id) }.getOrNull() else p.getAttributeValue(ns, attr)
        }
        fun array(p: XmlResourceParser, attr: String): List<String> {
            val id = p.getAttributeResourceValue(ANDROID, attr, 0)
            return if (id != 0) runCatching { res.getStringArray(id).toList() }.getOrDefault(emptyList()) else emptyList()
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val tag = parser.name
                val key = parser.getAttributeValue(ANDROID, "key")
                val title = text(parser, "title").orEmpty()
                val summary = text(parser, "summary")?.takeIf { !it.contains("%") }
                when {
                    tag == "PreferenceCategory" -> {
                        if (current.isNotEmpty() && currentKey !in skippedCategories) {
                            categories += StreamOptionCategory(currentKey, currentTitle, current)
                        }
                        currentKey = key
                        currentTitle = title
                        current = mutableListOf()
                    }
                    key == null || key in skippedKeys || title.isEmpty() -> Unit
                    tag == "CheckBoxPreference" || tag.endsWith("SwitchPreference") || tag.endsWith("SwitchPreferenceCompat") -> {
                        current += StreamOption.Toggle(key, title, summary, parser.getAttributeBooleanValue(ANDROID, "defaultValue", false))
                    }
                    tag == "ListPreference" -> {
                        val labels = array(parser, "entries")
                        val values = array(parser, "entryValues")
                        if (labels.isNotEmpty() && labels.size == values.size) {
                            current += StreamOption.Choice(key, title, summary, labels, values, parser.getAttributeValue(ANDROID, "defaultValue"))
                        }
                    }
                    tag.endsWith("SeekBarPreference") -> {
                        val hasDefault = parser.getAttributeValue(ANDROID, "defaultValue") != null
                        current += StreamOption.Slider(
                            key = key, title = title, summary = summary,
                            min = parser.getAttributeIntValue(SEEKBAR, "min", 1),
                            max = parser.getAttributeIntValue(ANDROID, "max", 100),
                            step = parser.getAttributeIntValue(SEEKBAR, "keyStep", 0).takeIf { it > 0 }
                                ?: parser.getAttributeIntValue(SEEKBAR, "step", 1),
                            divisor = parser.getAttributeIntValue(SEEKBAR, "divisor", 1),
                            suffix = text(parser, "text"),
                            default = if (hasDefault) parser.getAttributeIntValue(ANDROID, "defaultValue", 0) else null,
                        )
                    }
                }
            }
            event = parser.next()
        }
        if (current.isNotEmpty() && currentKey !in skippedCategories) categories += StreamOptionCategory(currentKey, currentTitle, current)
        parser.close()
        return categories
    }

    /** The options shown first in the profile editor ("the important ones"). */
    val essentialKeys = listOf(
        "list_resolution", "list_fps", "seekbar_bitrate_kbps", "video_format", "checkbox_enable_hdr",
        "checkbox_use_virtual_display", "frame_pacing", "list_audio_config",
        "checkbox_multi_controller", "checkbox_enable_rumble", "checkbox_enable_perf_overlay",
    )
}
