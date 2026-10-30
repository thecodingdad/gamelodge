package io.github.thecodingdad.gamelodge.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Test

/** Rows from the top: whole rows only, the list moves on row by row. */
class RowGeometryTest {
    // Rows 300 px high, 10 apart, room from 100 to 1060: three whole rows (920 px) fit.
    private fun geometry(startRows: Int? = null) = RowGeometry().apply {
        update(heights = List(6) { 300 }, top = 100, bottom = 1060, spacing = 10, startRows = startRows)
    }

    @Test
    fun `the start shows as many whole rows as fit, the lowest at the bottom`() {
        val g = geometry()
        // 960 room - 10 gap - 920 rows.
        assertEquals(30, g.spacer)
        assertEquals(140, g.topOf(0))
        assertEquals(0 to 0, g.scrollFor(0))
    }

    @Test
    fun `focus moves inside the window, below it the window moves by one row`() {
        val g = geometry()
        assertEquals(0, g.windowFor(2, 0))
        assertEquals(1, g.windowFor(3, 0))
        assertEquals(3, g.windowFor(5, 1))
        // Up above the window: that row becomes the first.
        assertEquals(2, g.windowFor(2, 3))
    }

    @Test
    fun `the window's first row lands where the start window's first row was`() {
        val g = geometry()
        // Row 1 starts at content 30 + 10 + 310 = 350; it has to show at 140: scrolled by 310 (row 0 + gap past the spacer).
        val (index, offset) = g.scrollFor(1)
        assertEquals(1, index) // row 0 (item 0 is the spacer)
        assertEquals(270, offset)
    }

    @Test
    fun `first row at the bottom keeps the old start`() {
        val g = geometry(startRows = 1)
        assertEquals(960 - 10 - 300, g.spacer)
    }
}
