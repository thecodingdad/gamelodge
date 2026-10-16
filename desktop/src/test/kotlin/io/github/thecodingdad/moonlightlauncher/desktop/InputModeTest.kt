package io.github.thecodingdad.moonlightlauncher.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import org.junit.Assert.assertEquals
import org.junit.Test

/** The desktop lets the launcher switch between touch and controller mode (focus ring) like Android. */
class InputModeTest {
    @Test
    fun touchAndKeyboardModeCanBeRequested() {
        var manager: InputModeManager? = null
        javax.swing.SwingUtilities.invokeAndWait {
            val scene = ImageComposeScene(100, 100) { manager = LocalInputModeManager.current }
            scene.render(0)
            manager!!.requestInputMode(InputMode.Touch)
            assertEquals(InputMode.Touch, manager!!.inputMode)
            manager!!.requestInputMode(InputMode.Keyboard)
            assertEquals(InputMode.Keyboard, manager!!.inputMode)
            scene.close()
        }
    }
}
