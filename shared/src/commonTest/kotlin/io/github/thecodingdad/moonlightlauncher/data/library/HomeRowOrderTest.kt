package io.github.thecodingdad.moonlightlauncher.data.library

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeRowOrderTest {
    private fun row(id: String, value: String? = null) =
        ResolvedFilter(key = value?.let { "$id:$it" } ?: id, filterId = id, name = id, builtin = null, value = value, entries = emptyList(), rowSort = null)

    @Test
    fun `without a saved order rows keep the filter order and the smart home row comes third`() {
        val rows = listOf(row("a"), row("b"), row("c"))
        assertEquals(rows, HomeRowOrder.sort(rows, emptyList()))
        assertEquals(2, HomeRowOrder.haIndex(rows, emptyList()))
        assertEquals(1, HomeRowOrder.haIndex(rows.take(1), emptyList()))
        assertEquals(listOf("a", "b", "ha", "c"), HomeRowOrder.ids(listOf("a", "b", "c"), emptyList()))
    }

    @Test
    fun `saved order wins, split parts stay together, unknown filters follow`() {
        val rows = listOf(row("a"), row("p", "PC"), row("p", "Switch"), row("c"))
        val saved = listOf("c", "ha", "p")
        assertEquals(listOf("c", "p:PC", "p:Switch", "a"), HomeRowOrder.sort(rows, saved).map { it.key })
        assertEquals(1, HomeRowOrder.haIndex(HomeRowOrder.sort(rows, saved), saved))
        // Deleted filters drop out of the list, new ones are appended.
        assertEquals(listOf("c", "ha", "p", "a", "d"), HomeRowOrder.ids(listOf("a", "p", "c", "d"), saved + "gone"))
    }
}
