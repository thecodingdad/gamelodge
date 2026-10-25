package io.github.thecodingdad.gamelodge.stream

import android.content.Context
import com.limelight.binding.input.ControllerOverrides
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.github.thecodingdad.gamelodge.ui.screens.settings.layoutFor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Settings of one controller that differ from the global gamepad settings (null = global). */
@Serializable
data class ControllerSettings(
    val deadzonePercent: Int? = null,
    val rumblePercent: Int? = null,
    val touchpadAsMouse: Boolean? = null,
    val motionSensors: Boolean? = null,
    /** 0-based player slot on the host. */
    val playerSlot: Int? = null,
    /** Button combinations (key codes) of this controller: null = global, empty = off. */
    val comboHome: List<Int>? = null,
    val comboQuickMenu: List<Int>? = null,
    val comboMouse: List<Int>? = null,
    val comboStats: List<Int>? = null,
    /** The combinations' buttons reach the app as well: null = global. */
    val comboForward: Boolean? = null,
) {
    val isEmpty: Boolean get() = this == ControllerSettings()

    /** This controller's combination of [action]: null = the global one. */
    fun combo(action: io.github.thecodingdad.gamelodge.input.ComboAction): List<Int>? = when (action) {
        io.github.thecodingdad.gamelodge.input.ComboAction.HOME -> comboHome
        io.github.thecodingdad.gamelodge.input.ComboAction.QUICK_MENU -> comboQuickMenu
        io.github.thecodingdad.gamelodge.input.ComboAction.MOUSE -> comboMouse
        io.github.thecodingdad.gamelodge.input.ComboAction.STATS -> comboStats
    }

    fun withCombo(action: io.github.thecodingdad.gamelodge.input.ComboAction, keys: List<Int>?): ControllerSettings = when (action) {
        io.github.thecodingdad.gamelodge.input.ComboAction.HOME -> copy(comboHome = keys)
        io.github.thecodingdad.gamelodge.input.ComboAction.QUICK_MENU -> copy(comboQuickMenu = keys)
        io.github.thecodingdad.gamelodge.input.ComboAction.MOUSE -> copy(comboMouse = keys)
        io.github.thecodingdad.gamelodge.input.ComboAction.STATS -> copy(comboStats = keys)
    }
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

    /**
     * Hands the settings to Artemis' controller handling (applied when a stream starts). The face
     * buttons follow the launcher's layout: Nintendo swaps them for the host, the streamed game
     * ([game], its library id) can set its own ([launcher] gives the current settings).
     */
    fun install(launcher: () -> io.github.thecodingdad.gamelodge.data.settings.LauncherSettings, game: () -> String?) {
        ControllerOverrides.setProvider { descriptor ->
            val s = _all.value[descriptor] ?: ControllerSettings()
            val settings = launcher()
            // The streamed game's own settings win over the controller's.
            val pad = game()?.let { settings.gamePads[it] }
            val layout = pad?.layout ?: settings.layoutFor(descriptor)
            ControllerOverrides.Override().apply {
                flipFaceButtons = layout == io.github.thecodingdad.gamelodge.data.settings.ButtonLayout.NINTENDO
                deadzonePercent = s.deadzonePercent
                rumblePercent = when (pad?.rumble) {
                    false -> 0
                    true -> s.rumblePercent?.takeIf { it > 0 } ?: 100
                    null -> s.rumblePercent
                }
                touchpadAsMouse = pad?.touchpadMouse ?: s.touchpadAsMouse
                motionSensors = pad?.motion ?: s.motionSensors
                playerSlot = s.playerSlot
            }
        }
    }
}
