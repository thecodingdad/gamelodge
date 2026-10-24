package io.github.thecodingdad.gamelodge.stream

import android.content.Context
import android.content.res.XmlResourceParser
import org.xmlpull.v1.XmlPullParser

/**
 * Parses Artemis' `res/xml/preferences.xml` at runtime, so every stream option can be shown
 * in the launcher's own UI (and new upstream options appear without code changes).
 */
object StreamSettingsSchema {
    private const val ANDROID = "http://schemas.android.com/apk/res/android"
    private const val SEEKBAR = "http://schemas.moonlight-stream.com/apk/res/seekbar"

    /** Categories that configure Artemis' own UI, irrelevant inside the launcher. */
    private val skippedCategories = setOf("category_ui_settings")
    private val skippedKeys = setOf(
        "list_languages", "checkbox_small_icon_mode",
        // Artemis' AI 3D render mode isn't part of GameLodge (see moonlight-core's Stereo3DRenderer).
        RENDER_MODE_KEY, "parallax_depth", "convergence_ratio", "balance_shift",
    )

    /** Artemis' render mode; anything but 2D ("0") would ask for the 3D renderer. */
    const val RENDER_MODE_KEY = "render_mode_list"

    @Volatile private var cached: List<StreamOptionCategory>? = null

    fun load(context: Context): List<StreamOptionCategory> = (cached ?: parse(context).also { cached = it }).let { schema ->
        // The screen's own resolution and refresh rate, as Artemis' settings offer them.
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager).getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val mode = display?.mode
        schema.withNativeDisplay(
            mode?.physicalWidth ?: 0, mode?.physicalHeight ?: 0, mode?.refreshRate ?: 0f,
            context.getString(com.limelight.R.string.resolution_prefix_native), context.getString(com.limelight.R.string.fps_suffix_fps),
        )
    }

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
