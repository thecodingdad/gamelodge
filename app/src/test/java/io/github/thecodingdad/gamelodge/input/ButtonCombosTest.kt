package io.github.thecodingdad.gamelodge.input

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class ButtonCombosTest {
    @After
    fun reset() {
        ButtonCombos.combos = emptyMap()
        ButtonCombos.overrides = emptyMap()
    }

    @Test
    fun controllerWithoutOverrideUsesGlobal() {
        ButtonCombos.combos = mapOf(ComboAction.HOME to setOf(1, 2))
        assertEquals(setOf(1, 2), ButtonCombos.combosFor("pad")[ComboAction.HOME])
        assertEquals(setOf(1, 2), ButtonCombos.combosFor(null)[ComboAction.HOME])
    }

    @Test
    fun overrideReplacesOnlyItsAction() {
        ButtonCombos.combos = mapOf(ComboAction.HOME to setOf(1, 2), ComboAction.QUICK_MENU to setOf(3))
        ButtonCombos.overrides = mapOf("xbox" to mapOf(ComboAction.HOME to setOf(9)))
        val xbox = ButtonCombos.combosFor("xbox")
        assertEquals(setOf(9), xbox[ComboAction.HOME])
        assertEquals(setOf(3), xbox[ComboAction.QUICK_MENU])
        assertEquals(setOf(1, 2), ButtonCombos.combosFor("other")[ComboAction.HOME])
    }

    @Test
    fun emptyOverrideSwitchesActionOff() {
        ButtonCombos.combos = mapOf(ComboAction.HOME to setOf(1, 2))
        ButtonCombos.overrides = mapOf("odin" to mapOf(ComboAction.HOME to emptySet()))
        assertEquals(emptySet<Int>(), ButtonCombos.combosFor("odin")[ComboAction.HOME])
    }
}
