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
 * order, or a fixed pattern the user chose ([ControllerSettings.ledPattern]).
 *
 * A [LightsManager] session is held open per device so the lights stay set while the launcher runs.
 * The host takes the LEDs over during a stream; [refresh] re-applies them on return. Only Android 12+
 * (LightsManager) and only devices that actually report player lights are touched.
 */
class ControllerLights(context: Context, private val store: ControllerOverrideStore) {
    private val order = ControllerOrderStore(context)
    private val sessions = HashMap<Int, LightsManager.LightsSession>()

    private val supported get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** The player LEDs of [dev] (named "player-N"), ordered by number, or empty if it has none. */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun playerLights(dev: InputDevice): List<Light> {
        val lm = dev.lightsManager ?: return emptyList()
        return lm.lights
            .mapNotNull { l -> playerNumber(l.name)?.let { it to l } }
            .sortedBy { it.first }
            .map { it.second }
    }

    private fun playerNumber(name: String?): Int? =
        Regex("player[-_ ]?(\\d+)").find(name.orEmpty().lowercase())?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..4 }

    /** Whether [deviceId] has player LEDs we can set (so the UI can show the LED controls). */
    fun hasPlayerLights(deviceId: Int): Boolean {
        if (!supported) return false
        val dev = InputDevice.getDevice(deviceId) ?: return false
        return playerLights(dev).isNotEmpty()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun apply(dev: InputDevice, pattern: Int) {
        val lights = playerLights(dev)
        if (lights.isEmpty()) return
        val lm = dev.lightsManager ?: return
        val session = sessions.getOrPut(dev.id) { lm.openSession() }
        val req = LightsRequest.Builder()
        lights.forEachIndexed { i, light ->
            val on = (pattern shr i) and 1 == 1
            req.addLight(light, LightState.Builder().setColor(if (on) -0x1 else 0).build())
        }
        session.requestLights(req.build())
    }

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
            if (playerLights(dev).isEmpty()) continue
            present.add(id)
            apply(dev, patternFor(dev.descriptor, slots))
        }
        sessions.keys.filter { it !in present }.toList().forEach { sessions.remove(it)?.close() }
    }

    fun releaseAll() {
        sessions.values.forEach { it.close() }
        sessions.clear()
    }
}
