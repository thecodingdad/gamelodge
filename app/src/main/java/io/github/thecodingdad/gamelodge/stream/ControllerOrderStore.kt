package io.github.thecodingdad.gamelodge.stream

import android.content.Context
import android.view.InputDevice

/**
 * Remembers the relative order of controllers (by stable descriptor) so they keep their player
 * numbers across streams. The order is a plain list; a controller seen for the first time is
 * appended. The player slots handed to the host are derived from it per stream: the controller
 * that started the stream is player 1, the rest follow this remembered order, compacted so there
 * are never gaps (which would leave the host without a player 1 and break many games).
 */
class ControllerOrderStore(context: Context) {
    private val prefs = context.getSharedPreferences("controller_order", Context.MODE_PRIVATE)

    private fun order(): List<String> =
        prefs.getString(KEY, "").orEmpty().split('\n').filter { it.isNotEmpty() }

    /** Every controller seen in a stream so far (descriptors). */
    fun known(): List<String> = order()

    private fun remember(descriptors: List<String>) {
        val known = order()
        val added = descriptors.filter { it !in known }
        if (added.isEmpty()) return
        prefs.edit().putString(KEY, (known + added).joinToString("\n")).apply()
    }

    /**
     * Player slot per descriptor for the controllers connected right now, and which of them should
     * auto-attach. [starter] (the descriptor of the pad that started the stream) becomes player 1.
     * [autoAttach] gives a controller's manual choice: false excludes it from auto-attach (e.g. a
     * built-in pad you don't stream with); null/true include it.
     */
    fun assign(starter: String?, autoAttach: (String) -> Boolean? = { null }): Assignment {
        val connected = LinkedHashSet<String>()
        for (id in InputDevice.getDeviceIds()) {
            val dev = InputDevice.getDevice(id) ?: continue
            if (dev.supportsSource(InputDevice.SOURCE_GAMEPAD) || dev.supportsSource(InputDevice.SOURCE_JOYSTICK)) {
                connected.add(dev.descriptor)
            }
        }
        val eligible: List<String> = connected.filter { autoAttach(it) != false }
        remember(eligible)
        val rank: Map<String, Int> = order().withIndex().associate { it.value to it.index }
        val ordered: List<String> = eligible.sortedWith(
            compareByDescending<String> { it == starter }.thenBy { rank[it] ?: Int.MAX_VALUE },
        )
        val slots: Map<String, Int> = ordered.withIndex().associate { it.value to it.index }
        return Assignment(slots, eligible.toSet())
    }

    /** [slots]: 0-based player number per descriptor. [eligible]: who auto-attaches at stream start. */
    data class Assignment(val slots: Map<String, Int>, val eligible: Set<String>)

    companion object {
        private const val KEY = "order"
    }
}
