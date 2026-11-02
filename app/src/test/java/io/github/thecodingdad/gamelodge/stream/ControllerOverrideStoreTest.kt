package io.github.thecodingdad.gamelodge.stream

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class ControllerOverrideStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun `settings persist per descriptor and reset removes them`() {
        val store = ControllerOverrideStore(context)
        store.set("pad-1", ControllerSettings(rumblePercent = 50, autoAttach = false))
        assertEquals(ControllerSettings(rumblePercent = 50, autoAttach = false), ControllerOverrideStore(context).get("pad-1"))
        assertEquals(ControllerSettings(), store.get("pad-2"))
        store.set("pad-1", ControllerSettings())
        assertTrue(ControllerOverrideStore(context).all.value.isEmpty())
    }

    @Test
    fun `effective settings fill in the defaults for the kind of controller`() {
        val store = ControllerOverrideStore(context)
        // Not connected: the standard defaults, with the controller's own choices on top.
        val e = store.effective("gone", ControllerSettings(vibration = io.github.thecodingdad.gamelodge.ui.screens.settings.VibrationMode.OFF, deadzonePercent = 10))
        assertEquals(io.github.thecodingdad.gamelodge.ui.screens.settings.VibrationMode.OFF, e.vibration)
        assertEquals(10, e.deadzonePercent)
        assertEquals(io.github.thecodingdad.gamelodge.ui.screens.settings.MotionMode.CONTROLLER, e.motion)
        assertEquals(100, e.vibrationStrength)
        assertEquals(true, e.batteryReport)
    }
}
