package io.github.thecodingdad.gamelodge.ui.screens.settings

import io.github.thecodingdad.gamelodge.data.settings.ButtonLayout

/** Where a controller's rumble goes. */
enum class VibrationMode {
    OFF,
    /** The controller's own motors. */
    CONTROLLER,
    /** The controller's motors; the device vibrates instead if the controller has none. */
    CONTROLLER_ELSE_DEVICE,
    /** Always the device (e.g. the handheld itself) instead of the controller. */
    DEVICE,
}

/** Where the host gets gyro / accelerometer data for a controller. */
enum class MotionMode {
    OFF,
    /** The controller's own sensors. */
    CONTROLLER,
    /** The controller's sensors; the device's if the controller has none. */
    CONTROLLER_ELSE_DEVICE,
}

/**
 * What a controller starts with before the user changes anything. Every controller setting is
 * per controller; these defaults depend on the kind of controller (detected from its USB ids).
 */
data class ControllerDefaults(
    val layout: ButtonLayout = ButtonLayout.XBOX,
    val deadzonePercent: Int = 5,
    val vibration: VibrationMode = VibrationMode.CONTROLLER,
    val vibrationStrength: Int = 100,
    val motion: MotionMode = MotionMode.CONTROLLER,
    val touchpadAsMouse: Boolean = false,
    val batteryReport: Boolean = true,
)

/** The defaults for a controller with these USB ids. */
fun controllerDefaults(vendorId: Int, productId: Int): ControllerDefaults {
    val base = ControllerDefaults(layout = buttonLayoutForVendor(vendorId) ?: ButtonLayout.XBOX)
    return when (controllerIconAuto(vendorId, productId)) {
        // Built-in controls of a handheld: no gyro or motors of their own, but the device has them.
        ControllerIconKind.HANDHELD -> base.copy(vibration = VibrationMode.CONTROLLER_ELSE_DEVICE, motion = MotionMode.CONTROLLER_ELSE_DEVICE)
        // Joy-Cons: reporting their battery causes occasional stutter of touch/mouse input on Android.
        ControllerIconKind.JOYCON_L, ControllerIconKind.JOYCON_R -> base.copy(batteryReport = false)
        else -> base
    }
}
