package io.github.thecodingdad.gamelodge.stream

import android.content.Context
import androidx.preference.PreferenceManager
import com.limelight.profiles.ProfilesManager
import io.github.thecodingdad.gamelodge.ui.screens.settings.MotionMode
import io.github.thecodingdad.gamelodge.ui.screens.settings.VibrationMode

/**
 * Artemis' gamepad options, now that every controller setting is per controller:
 * - [enforce] pins the options GameLodge no longer offers (they would break the player order,
 *   bypass the per-controller settings or are covered by them) and keeps profiles from carrying
 *   their own copy of any gamepad option, so the base settings always apply;
 * - [migrate] turns the former global values into each known controller's own settings, once.
 */
object ControllerPrefsMigration {
    /** All of Artemis' gamepad keys (incl. the one its Joy-Con option is actually read from). */
    private val gamepadKeys = setOf(
        "seekbar_deadzone", "checkbox_multi_controller", "checkbox_usb_driver", "checkbox_usb_bind_all",
        "checkbox_mouse_emulation", "analog_scrolling", "checkbox_flip_face_buttons",
        "checkbox_gamepad_touchpad_as_mouse", "checkbox_gamepad_motion_sensors", "checkbox_gamepad_motion_fallback",
        "checkbox_force_device_motion", "checkbox_enable_joyconfix", "checkbox_joycon_fix",
        "checkbox_gamepad_enable_battery_report", "checkbox_enable_rumble", "checkbox_vibrate_fallback",
        "checkbox_enable_device_rumble", "seekbar_vibrate_fallback_strength",
    )

    /**
     * Fixed values: several controllers each as their own player (our player order relies on it),
     * Android's own controller support (so the per-controller settings apply), no Joy-Con remap, no
     * forced device sensors, and rumble passed on (each controller decides itself).
     */
    private val pinned = mapOf(
        "checkbox_multi_controller" to true,
        "checkbox_usb_bind_all" to false,
        "checkbox_enable_joyconfix" to false,
        "checkbox_joycon_fix" to false,
        "checkbox_force_device_motion" to false,
        "checkbox_enable_rumble" to true,
    )

    fun enforce(context: Context) {
        val base = PreferenceManager.getDefaultSharedPreferences(context)
        if (pinned.any { (k, v) -> base.all[k] != v }) {
            base.edit().apply { pinned.forEach { (k, v) -> putBoolean(k, v) } }.apply()
        }
        val manager = ProfilesManager.getInstance()
        manager.profiles.forEach { p ->
            val options = p.options ?: return@forEach
            if (options.keys.none { it in gamepadKeys }) return@forEach
            p.options = HashMap(options).apply { keys.removeAll(gamepadKeys) }
            p.modifiedUtc = System.currentTimeMillis()
            manager.update(p)
        }
    }

    fun migrate(context: Context, store: ControllerOverrideStore, order: ControllerOrderStore) {
        val flags = context.getSharedPreferences("gamelodge_migrations", Context.MODE_PRIVATE)
        if (flags.getBoolean(FLAG, false)) return
        val base = PreferenceManager.getDefaultSharedPreferences(context)
        val deadzone = base.getInt("seekbar_deadzone", 5)
        val touchpad = base.getBoolean("checkbox_gamepad_touchpad_as_mouse", false)
        val motionOn = base.getBoolean("checkbox_gamepad_motion_sensors", true)
        val motionFallback = base.getBoolean("checkbox_gamepad_motion_fallback", false)
        val battery = base.getBoolean("checkbox_gamepad_enable_battery_report", true)
        val rumbleOn = base.getBoolean("checkbox_enable_rumble", true)
        val vibrateFallback = base.getBoolean("checkbox_vibrate_fallback", false)
        val deviceRumble = base.getBoolean("checkbox_enable_device_rumble", false)
        val strength = base.getInt("seekbar_vibrate_fallback_strength", 100)

        (store.all.value.keys + order.known()).toSet().forEach { descriptor ->
            val s = store.get(descriptor)
            val motion = s.motion ?: when {
                s.motionSensors == false || !motionOn -> MotionMode.OFF
                motionFallback -> MotionMode.CONTROLLER_ELSE_DEVICE
                s.motionSensors == true -> MotionMode.CONTROLLER
                else -> null
            }
            val vibration = s.vibration ?: when {
                s.rumblePercent == 0 || !rumbleOn -> VibrationMode.OFF
                deviceRumble -> VibrationMode.DEVICE
                vibrateFallback -> VibrationMode.CONTROLLER_ELSE_DEVICE
                else -> null
            }
            val deviceVibration = vibration == VibrationMode.DEVICE || vibration == VibrationMode.CONTROLLER_ELSE_DEVICE
            val migrated = s.copy(
                motionSensors = null,
                motion = motion,
                vibration = vibration,
                rumblePercent = s.rumblePercent?.takeIf { it > 0 } ?: strength.takeIf { deviceVibration && it != 100 },
                deadzonePercent = s.deadzonePercent ?: deadzone.takeIf { it != 5 },
                touchpadAsMouse = s.touchpadAsMouse ?: true.takeIf { touchpad },
                batteryReport = s.batteryReport ?: false.takeIf { !battery },
            )
            if (migrated != s) store.set(descriptor, migrated)
        }
        flags.edit().putBoolean(FLAG, true).apply()
    }

    private const val FLAG = "controller_settings_per_controller"
}
