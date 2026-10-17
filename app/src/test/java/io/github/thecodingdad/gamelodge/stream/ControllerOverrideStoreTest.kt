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
        store.set("pad-1", ControllerSettings(flipFaceButtons = true, playerSlot = 0))
        assertEquals(ControllerSettings(flipFaceButtons = true, playerSlot = 0), ControllerOverrideStore(context).get("pad-1"))
        assertEquals(ControllerSettings(), store.get("pad-2"))
        store.set("pad-1", ControllerSettings())
        assertTrue(ControllerOverrideStore(context).all.value.isEmpty())
    }
}
