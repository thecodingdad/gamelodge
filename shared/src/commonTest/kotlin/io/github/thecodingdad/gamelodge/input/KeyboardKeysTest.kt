package io.github.thecodingdad.gamelodge.input

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyboardKeysTest {
    @Test
    fun `shortcuts need a modifier and a known key`() {
        assertEquals(KeyShortcut(ctrl = true, alt = true, shift = false, key = "M"), KeyShortcut.parse("Ctrl+Alt+M"))
        assertEquals("Ctrl+Alt+M", KeyShortcut.parse("Ctrl+Alt+M").toString())
        assertNull(KeyShortcut.parse("M"))
        assertNull(KeyShortcut.parse("Ctrl+Enter"))
        assertNull(KeyShortcut.parse(""))
    }

    @Test
    fun `assigned keys override the defaults, empty means none`() {
        KeyboardKeys.mapping = emptyMap()
        assertEquals(KeyAction.Y, KeyboardKeys.actionFor(Key.F1))
        KeyboardKeys.mapping = mapOf(KeyAction.Y.name to "", KeyAction.X.name to "F1")
        assertEquals(KeyAction.X, KeyboardKeys.actionFor(Key.F1))
        assertNull(KeyboardKeys.keyOf(KeyAction.Y))
        KeyboardKeys.mapping = emptyMap()
    }
}
