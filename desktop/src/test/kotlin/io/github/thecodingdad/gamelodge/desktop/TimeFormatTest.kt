package io.github.thecodingdad.gamelodge.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormatTest {
    @Test
    fun windowsPatternsBecomeJavaPatterns() {
        assertEquals("HH:mm", DesktopPlatformUi.windowsTimePattern("HH:mm"))
        assertEquals("h:mm a", DesktopPlatformUi.windowsTimePattern("h:mm tt"))
        assertEquals("a h:mm", DesktopPlatformUi.windowsTimePattern("tt h:mm"))
    }
}
