package io.github.thecodingdad.gamelodge.ha

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class HaActionTest {
    @Test
    fun plainAndSwitchActions() {
        assertEquals("script.wake", HaAction.encode("script.wake", null))
        assertEquals("switch.pc#off", HaAction.encode("switch.pc", false))
        assertNull(HaAction.on("script.wake"))
        assertEquals(false, HaAction.on("switch.pc#off"))
        assertNull(HaAction.brightness("switch.pc#on"))
    }

    @Test
    fun lightBrightness() {
        val action = HaAction.encode("light.desk", true, 40)
        assertEquals("light.desk#on@40", action)
        assertEquals("light.desk", HaAction.entity(action))
        assertEquals(true, HaAction.on(action))
        assertEquals(40, HaAction.brightness(action))
        assertEquals("light.desk#off", HaAction.encode("light.desk", false, 40))
        assertNull(HaAction.brightness("light.desk#on"))
    }
}
