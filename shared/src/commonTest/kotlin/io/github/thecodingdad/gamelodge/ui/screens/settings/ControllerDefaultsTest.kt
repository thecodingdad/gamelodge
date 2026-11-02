package io.github.thecodingdad.gamelodge.ui.screens.settings

import io.github.thecodingdad.gamelodge.data.settings.ButtonLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class ControllerDefaultsTest {
    @Test
    fun `unknown controllers get the standard defaults`() {
        assertEquals(ControllerDefaults(), controllerDefaults(0x1234, 0x0001))
    }

    @Test
    fun `layout follows the vendor`() {
        assertEquals(ButtonLayout.XBOX, controllerDefaults(0x045E, 0x0B13).layout)
        assertEquals(ButtonLayout.PLAYSTATION, controllerDefaults(0x054C, 0x0CE6).layout)
        assertEquals(ButtonLayout.NINTENDO, controllerDefaults(0x057E, 0x2009).layout)
    }

    @Test
    fun `handheld controls fall back to the device's motors and sensors`() {
        val d = controllerDefaults(0x2020, 0x0112)
        assertEquals(VibrationMode.CONTROLLER_ELSE_DEVICE, d.vibration)
        assertEquals(MotionMode.CONTROLLER_ELSE_DEVICE, d.motion)
    }

    @Test
    fun `joy-cons don't report their battery`() {
        assertEquals(false, controllerDefaults(0x057E, 0x2006).batteryReport)
        assertEquals(false, controllerDefaults(0x057E, 0x2007).batteryReport)
        assertEquals(true, controllerDefaults(0x057E, 0x2009).batteryReport)
    }
}
