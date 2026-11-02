package io.github.thecodingdad.gamelodge.stream

import android.content.Context
import android.hardware.lights.Light
import android.hardware.lights.LightState
import android.hardware.lights.LightsManager
import android.hardware.lights.LightsRequest
import android.os.Build
import android.view.InputDevice
import androidx.annotation.RequiresApi

/**
 * Drives the player-indicator LEDs on controllers that expose them (e.g. Joy-Cons). Each controller
 * shows a pattern of its (up to four) player LEDs: by default the player number from the remembered
 * order, or a fixed pattern the user chose ([ControllerSettings.ledPattern]). Controllers with a
 * fifth light (the Right Joy-Con's Home-button ring) also get that set from [ControllerSettings.homeLed]
 * so it stops glowing on its own.
 *
 * A [LightsManager] session is held open per device so the lights stay set while the launcher runs.
 * The host/system owns the LEDs while a game is focused; [refresh] re-applies them on return. Only
 * Android 12+ (LightsManager) and only devices that actually report player lights are touched.
 */
class ControllerLights(context: Context, private val store: ControllerOverrideStore) {
    private val order = ControllerOrderStore(context)
    private val sessions = HashMap<Int, LightsManager.LightsSession>()
    // Last state written per device, so we don't re-send the same LEDs: writing to a Joy-Con over
    // Bluetooth competes with reading its input, so redundant writes cause input lag.
    private val lastApplied = HashMap<Int, Int>()

    private val supported get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** The device's player lights by number (1..4 = indicator LEDs, 5 = the Home-button ring). */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun lightsByNumber(dev: InputDevice): Map<Int, Light> {
        val lm = dev.lightsManager ?: return emptyMap()
        return lm.lights.mapNotNull { l -> playerNumber(l.name)?.let { it to l } }.toMap()
    }

    private fun playerNumber(name: String?): Int? =
        Regex("player[-_ ]?(\\d+)").find(name.orEmpty().lowercase())?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..5 }

    /** Whether [deviceId] has indicator LEDs we can set (so the UI can show the LED controls). */
    fun hasPlayerLights(deviceId: Int): Boolean = numbers(deviceId).any { it in 1..4 }

    /** Whether [deviceId] has a Home-button LED (the Right Joy-Con's ring). */
    fun hasHomeLight(deviceId: Int): Boolean = 5 in numbers(deviceId)

    private fun numbers(deviceId: Int): Set<Int> {
        if (!supported) return emptySet()
        val dev = InputDevice.getDevice(deviceId) ?: return emptySet()
        return lightsByNumber(dev).keys
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun apply(dev: InputDevice, pattern: Int, homeOn: Boolean) {
        val lights = lightsByNumber(dev)
        if (lights.isEmpty()) return
        val key = (pattern and 0xF) or (if (homeOn) 0x10 else 0)
        if (lastApplied[dev.id] == key) return
        val lm = dev.lightsManager ?: return
        val session = sessions.getOrPut(dev.id) { lm.openSession() }
        val req = LightsRequest.Builder()
        for ((number, light) in lights) {
            val on = if (number == 5) homeOn else (pattern shr (number - 1)) and 1 == 1
            req.addLight(light, LightState.Builder().setColor(if (on) -0x1 else 0).build())
        }
        session.requestLights(req.build())
        lastApplied[dev.id] = key
    }

    /** Forget what was last written (the host/system changed the LEDs), so the next refresh re-applies. */
    fun invalidate() = lastApplied.clear()

    /** The pattern a controller should show: its own choice, else the player number from the order. */
    private fun patternFor(descriptor: String, slots: Map<String, Int>): Int {
        store.get(descriptor).ledPattern?.let { return it }
        val slot = slots[descriptor] ?: 0
        return if (slot in 0..3) 1 shl slot else 0
    }

    /** Re-applies every connected controller's LEDs; drops sessions for ones no longer present. */
    fun refresh() {
        if (!supported) return
        // Player numbers follow the same order as a stream, minus the starter effect: a controller
        // the user never auto-attaches doesn't take a slot.
        val slots = order.assign(null) { store.get(it).autoAttach }.slots
        val present = HashSet<Int>()
        for (id in InputDevice.getDeviceIds()) {
            val dev = InputDevice.getDevice(id) ?: continue
            if (!(dev.supportsSource(InputDevice.SOURCE_GAMEPAD) || dev.supportsSource(InputDevice.SOURCE_JOYSTICK))) continue
            if (lightsByNumber(dev).isEmpty()) continue
            present.add(id)
            apply(dev, patternFor(dev.descriptor, slots), store.get(dev.descriptor).homeLed)
        }
        sessions.keys.filter { it !in present }.toList().forEach { sessions.remove(it)?.close(); lastApplied.remove(it) }
    }

    fun releaseAll() {
        sessions.values.forEach { it.close() }
        sessions.clear()
        lastApplied.clear()
    }
}
