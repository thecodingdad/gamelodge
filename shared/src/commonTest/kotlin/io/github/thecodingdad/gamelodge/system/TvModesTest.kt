package io.github.thecodingdad.gamelodge.system

import org.junit.Assert.assertEquals
import org.junit.Test

class TvModesTest {
    private val modes = listOf(
        TvMode(1, 3840, 2160, 30f), TvMode(2, 1920, 1080, 120f), TvMode(3, 1920, 1080, 60f), TvMode(4, 1280, 720, 60f),
    )

    @Test
    fun automaticIsSmoothUpTo1080p() {
        assertEquals(2, TvModes.auto(modes)?.id)
        // Only bigger modes: the smallest one at its highest rate.
        assertEquals(5, TvModes.auto(listOf(TvMode(5, 2560, 1440, 60f), TvMode(6, 3840, 2160, 60f)))?.id)
    }

    @Test
    fun chosenModeByNameNotId() {
        // The ids change on every connect; the name stays.
        assertEquals(3, TvModes.resolve("1920x1080@60", modes)?.id)
        assertEquals(2, TvModes.resolve("", modes)?.id)
        assertEquals(2, TvModes.resolve("2560x1440@60", modes)?.id)
    }
}
