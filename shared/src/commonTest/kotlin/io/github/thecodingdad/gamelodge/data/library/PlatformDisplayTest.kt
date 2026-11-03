package io.github.thecodingdad.gamelodge.data.library

import org.junit.Assert.assertEquals
import org.junit.Test

class PlatformDisplayTest {
    @Test
    fun shortNamesOnlyForDisplay() {
        assertEquals("Switch", PlatformResolver.displayName("Nintendo Switch"))
        assertEquals("N64", PlatformResolver.displayName("Nintendo 64"))
        assertEquals("Saturn", PlatformResolver.displayName("Sega Saturn"))
        assertEquals("Wii U", PlatformResolver.displayName("Wii U"))
        assertEquals("PlayStation 2", PlatformResolver.displayName("PlayStation 2"))
        // Already short names and unknown ones stay as they are.
        assertEquals("SNES", PlatformResolver.displayName("SNES"))
        assertEquals("Atari 2600", PlatformResolver.displayName("Atari 2600"))
    }
}
