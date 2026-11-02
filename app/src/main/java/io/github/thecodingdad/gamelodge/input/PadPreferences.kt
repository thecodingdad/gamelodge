package io.github.thecodingdad.gamelodge.input

import android.view.InputDevice
import kotlin.math.abs
import kotlin.math.max

/**
 * The controller settings that also apply in GameLodge itself (not only in the stream), by device
 * descriptor; fed from the per-controller settings. Only values a controller has of its own are
 * here (the model defaults never turn anything off or widen the launcher's own dead zones).
 */
object PadPreferences {
    /** Stick dead zone (0..1) per controller. */
    @Volatile var deadzones: Map<String, Float> = emptyMap()

    /** Controllers whose vibration is off: GameLodge's UI haptics stay quiet while they are used. */
    @Volatile var vibrationOff: Set<String> = emptySet()

    /** [value] with the dead zone of [device]'s controller, at least [minimum]. */
    fun deadzone(device: InputDevice?, value: Float, minimum: Float = 0f): Float {
        val zone = max(minimum, device?.let { deadzones[it.descriptor] } ?: 0f)
        return if (abs(value) < zone) 0f else value
    }
}
