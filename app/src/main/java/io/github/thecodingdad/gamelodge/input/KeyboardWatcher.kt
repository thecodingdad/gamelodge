package io.github.thecodingdad.gamelodge.input

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice

/**
 * Keeps [KeyboardKeys.connected] up to date: an external keyboard with letters (USB, Bluetooth).
 * Built-in keys (a handheld's gamepad reports itself as one) and virtual keyboards don't count.
 */
object KeyboardWatcher {
    fun start(context: Context) {
        val input = context.getSystemService(InputManager::class.java) ?: return
        fun check() {
            KeyboardKeys.connected = input.inputDeviceIds.any { id ->
                val d = input.getInputDevice(id) ?: return@any false
                !d.isVirtual && d.isExternal && d.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC &&
                    d.supportsSource(InputDevice.SOURCE_KEYBOARD) &&
                    // Game controllers announce letter keys too.
                    !d.supportsSource(InputDevice.SOURCE_GAMEPAD) && !d.supportsSource(InputDevice.SOURCE_JOYSTICK)
            }
        }
        input.registerInputDeviceListener(object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) = check()
            override fun onInputDeviceRemoved(deviceId: Int) = check()
            override fun onInputDeviceChanged(deviceId: Int) = check()
        }, Handler(Looper.getMainLooper()))
        check()
    }
}
