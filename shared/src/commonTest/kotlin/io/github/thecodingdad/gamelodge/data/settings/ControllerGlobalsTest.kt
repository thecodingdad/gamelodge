package io.github.thecodingdad.gamelodge.data.settings

import io.github.thecodingdad.gamelodge.input.ComboAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ControllerGlobalsTest {
    @Test
    fun `built-in globals change nothing`() {
        val s = LauncherSettings()
        assertEquals(s, s.withControllerGlobalsMoved(setOf("pad")))
    }

    @Test
    fun `changed globals become the pads' own, own values win`() {
        val s = LauncherSettings(
            buttonLayout = ButtonLayout.NINTENDO,
            comboHome = setOf(1, 2),
            comboForward = true,
            controllerLayouts = mapOf("b" to ButtonLayout.XBOX),
            controllerCombos = mapOf("b" to PadCombos(mapOf(ComboAction.HOME.name to listOf(9)))),
        )
        val m = s.withControllerGlobalsMoved(setOf("a", "b"))
        assertEquals(ButtonLayout.NINTENDO, m.controllerLayouts["a"])
        assertEquals(ButtonLayout.XBOX, m.controllerLayouts["b"])
        assertEquals(listOf(1, 2), m.controllerCombos["a"]?.combos?.get(ComboAction.HOME.name))
        assertEquals(listOf(9), m.controllerCombos["b"]?.combos?.get(ComboAction.HOME.name))
        assertEquals(true, m.controllerCombos["a"]?.forward)
        assertFalse(m.hasControllerGlobals)
    }
}
