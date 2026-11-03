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
    // Artemis' UI settings, and its misc page (updates, debug info, key imports): GameLodge's own.
    private val skippedCategories = setOf("category_ui_settings", "category_settings_misc")
    private val skippedKeys = setOf(
        "list_languages", "checkbox_small_icon_mode",
        // Artemis' AI 3D render mode isn't part of GameLodge (see moonlight-core's Stereo3DRenderer).
        RENDER_MODE_KEY, "parallax_depth", "convergence_ratio", "balance_shift",
        // The quick menu replaces the overlay's own quick options; logging and its dashboard are
        // developer tools; Home and the quick menu come from GameLodge's button combinations.
        "checkbox_enable_perf_overlay_lite_dialog", "checkbox_enable_perf_logging", "share_performance_logs",
        "option_view_shared_pref_logs", "checkbox_back_as_guide", "checkbox_back_as_meta",
        // GameLodge's mouse emulation (combination, speed) and its button layout (Xbox / Nintendo,
        // per controller and per game) replace these.
        "checkbox_mouse_emulation", "analog_scrolling", "checkbox_flip_face_buttons",
        // Pinned (see ControllerPrefsMigration): they'd break the player order, bypass the
        // per-controller settings, or are niche (Joy-Con remap, always device sensors).
        "checkbox_multi_controller", "checkbox_usb_bind_all", "checkbox_enable_joyconfix",
        "checkbox_force_device_motion", "checkbox_enable_rumble",
        // Per controller now (with defaults for the kind of controller): dead zone, vibration,
        // motion sensors, touchpad, battery report.
        "seekbar_deadzone", "checkbox_gamepad_touchpad_as_mouse", "checkbox_gamepad_motion_sensors",
        "checkbox_gamepad_motion_fallback", "checkbox_gamepad_enable_battery_report", "checkbox_vibrate_fallback",
        "checkbox_enable_device_rumble", "seekbar_vibrate_fallback_strength",
        // Samsung-only drag & drop tweaks and a workaround for old Android versions.
        "checkbox_trackpad_drag_drop_vibration", "seekbar_trackpad_drag_drop_threshold", "checkbox_ignore_synth_events",
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
        "checkbox_use_virtual_display", "frame_pacing", "list_audio_config", "checkbox_enable_perf_overlay",
    )

    /**
     * The controller options: they depend on the device and its controllers, not on the stream's
     * quality, so they live in the controller settings for every profile (the base settings).
     */
    const val GAMEPAD_CATEGORY = "category_gamepad_settings"

    /** Touch, mouse and keyboard options (Artemis calls them input settings). */
    const val INPUT_CATEGORY = "category_input_settings"

    /**
     * Input options that depend on this device (its screen and keyboard), not on how a stream is
     * used: the same for every profile (the base settings).
     */
    val deviceInputKeys = setOf(
        "seekbar_trackpad_sensitivity_x", "seekbar_trackpad_sensitivity_y", "checkbox_trackpad_swap_axis",
        "checkbox_force_qwerty", "checkbox_right_alt_as_meta",
    )

    /**
     * Profile editor: everything but the controller options and the device's input options; the
     * input category is titled [inputTitle] (Artemis' title says "controller" in some languages).
     */
    fun profileSchema(context: Context, inputTitle: String): List<StreamOptionCategory> = load(context)
        .filter { it.key != GAMEPAD_CATEGORY }
        .map { c -> if (c.key == INPUT_CATEGORY) c.copy(title = inputTitle, options = c.options.filter { it.key !in deviceInputKeys }) else c }

    /** The keyboard ones among [deviceInputKeys] (the rest are touch / trackpad options). */
    val deviceKeyboardKeys = setOf("checkbox_force_qwerty", "checkbox_right_alt_as_meta")

    /** The device's input options alone (Settings › Touch, mouse & keyboard, for every profile). */
    fun deviceInputOptions(context: Context): List<StreamOption> =
        load(context).firstOrNull { it.key == INPUT_CATEGORY }?.options.orEmpty().filter { it.key in deviceInputKeys }

    /** Controller settings: the controller options alone. */
    fun gamepadOptions(context: Context): List<StreamOption> = load(context).firstOrNull { it.key == GAMEPAD_CATEGORY }?.options.orEmpty()
}
