package io.github.thecodingdad.gamelodge.desktop

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** Arrow keys (the pad's D-pad and left stick arrive as arrows) move the focus like the D-pad on Android. */
@OptIn(androidx.compose.ui.InternalComposeUiApi::class)
class FocusKeysTest {
    @Test
    fun arrowsMoveFocus() {
        javax.swing.SwingUtilities.invokeAndWait {
            var focused = -1
            val first = FocusRequester()
            val scene = ImageComposeScene(200, 200) {
                val focusManager = LocalFocusManager.current
                Column(Modifier.onKeyEvent { arrowFocus(it, focusManager) }) {
                    repeat(3) { i ->
                        Box(
                            Modifier.size(40.dp).then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                                .onFocusChanged { if (it.isFocused) focused = i }.focusable(),
                        )
                    }
                }
            }
            scene.render(0)
            first.requestFocus()
            scene.render(1)
            fun press(key: Key) {
                scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown))
                scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp))
                scene.render(2)
            }
            press(Key.DirectionDown)
            assertEquals(1, focused)
            press(Key.DirectionDown)
            assertEquals(2, focused)
            press(Key.DirectionUp)
            assertEquals(1, focused)
            scene.close()
        }
    }
}
