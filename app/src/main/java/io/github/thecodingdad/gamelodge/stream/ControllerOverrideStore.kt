package io.github.thecodingdad.gamelodge.stream

import android.content.Context
import com.limelight.binding.input.ControllerOverrides
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Settings of one controller that differ from the global gamepad settings (null = global). */
@Serializable
data class ControllerSettings(
    val flipFaceButtons: Boolean? = null,
    val deadzonePercent: Int? = null,
    val rumblePercent: Int? = null,
    val touchpadAsMouse: Boolean? = null,
    val motionSensors: Boolean? = null,
    /** 0-based player slot on the host. */
    val playerSlot: Int? = null,
    /** Button combinations (key codes) of this controller: null = global, empty = off. */
    val comboHome: List<Int>? = null,
    val comboQuickMenu: List<Int>? = null,
) {
    val isEmpty: Boolean get() = this == ControllerSettings()
}

/**
 * Per-controller settings keyed by the device descriptor (stable across reconnects), stored in
 * plain SharedPreferences because Artemis reads them synchronously when a controller appears.
 */
class ControllerOverrideStore(context: Context) {
    private val prefs = context.getSharedPreferences("controller_overrides", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val _all = MutableStateFlow(load())
    val all: StateFlow<Map<String, ControllerSettings>> = _all.asStateFlow()

    private fun load(): Map<String, ControllerSettings> = prefs.all.mapNotNull { (key, value) ->
        (value as? String)?.let { runCatching { key to json.decodeFromString(ControllerSettings.serializer(), it) }.getOrNull() }
    }.toMap()

    fun get(descriptor: String): ControllerSettings = _all.value[descriptor] ?: ControllerSettings()

    fun set(descriptor: String, settings: ControllerSettings) {
        prefs.edit().apply {
            if (settings.isEmpty) remove(descriptor) else putString(descriptor, json.encodeToString(ControllerSettings.serializer(), settings))
        }.apply()
        _all.value = load()
    }

    /** Hands the settings to Artemis' controller handling (applied when a stream starts). */
    fun install() {
        ControllerOverrides.setProvider { descriptor ->
            val s = _all.value[descriptor] ?: return@setProvider null
            ControllerOverrides.Override().apply {
                flipFaceButtons = s.flipFaceButtons
                deadzonePercent = s.deadzonePercent
                rumblePercent = s.rumblePercent
                touchpadAsMouse = s.touchpadAsMouse
                motionSensors = s.motionSensors
                playerSlot = s.playerSlot
            }
        }
    }
}
