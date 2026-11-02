package io.github.thecodingdad.gamelodge.input

import android.view.InputDevice
import android.view.KeyEvent

/**
 * The controller that starts a stream becomes player 1 (setting, unless a controller is set as
 * player 1): the launcher notes which controller pressed a button last; touch, mouse and keyboard
 * clear it, so a stream started that way keeps the usual order.
 */
object StarterPad {
    @Volatile private var last: String? = null

    /** Descriptor of the controller used last in the launcher (null: touch or mouse since). */
    val lastPad: String? get() = last

    /** Descriptor of the controller that started the current stream (null: none, or the setting is off). */
    @Volatile var starter: String? = null
        private set

    fun onKey(event: KeyEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        last = event.device?.takeIf { isPad(it) }?.descriptor
    }

    /** A tap or mouse click in the launcher. */
    fun onPointer() {
        last = null
    }

    /** A stream starts now: the controller used last becomes its starter ([enabled]: the setting). */
    fun capture(enabled: Boolean) {
        starter = if (enabled) last else null
    }

    private fun isPad(device: InputDevice): Boolean =
        device.supportsSource(InputDevice.SOURCE_GAMEPAD) || device.supportsSource(InputDevice.SOURCE_JOYSTICK)
}
