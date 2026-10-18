package io.github.thecodingdad.gamelodge.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchScoreTest {
    @Test
    fun titleBeatsKeywordsBeatsPageName() {
        val title = searchScore("ton", "tone vibration", emptyList(), "", isPage = true)
        val keyword = searchScore("ton", "darstellung", listOf("tonart"), "", isPage = true)
        val context = searchScore("ton", "lautstarke", emptyList(), "tone vibration", isPage = false)
        assertTrue("$title > $keyword", title > keyword)
        assertTrue("$keyword > $context", keyword > context)
        assertTrue(context > 0)
    }

    @Test
    fun exactThenPrefixThenWordThenInside() {
        val exact = searchScore("daten", "daten", emptyList(), "", false)
        val prefix = searchScore("daten", "datenquellen", emptyList(), "", false)
        val word = searchScore("daten", "meine daten", emptyList(), "", false)
        val inside = searchScore("daten", "metadaten", emptyList(), "", false)
        assertTrue(exact > prefix && prefix > word && word > inside)
    }

    @Test
    fun noMatchIsZero() {
        assertEquals(0, searchScore("xyz", "theme", listOf("dark"), "display", false))
        assertEquals(0, searchScore("", "theme", emptyList(), "", false))
    }
}
