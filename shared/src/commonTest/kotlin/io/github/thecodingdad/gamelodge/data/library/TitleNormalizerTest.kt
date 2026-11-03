package io.github.thecodingdad.gamelodge.data.library

import org.junit.Assert.assertEquals
import org.junit.Test

class TitleNormalizerTest {
    @Test
    fun `trademarks, case and punctuation are ignored`() {
        assertEquals("elden ring", TitleNormalizer.normalize("ELDEN RING™"))
        assertEquals("marvel s spider man", TitleNormalizer.normalize("Marvel's Spider-Man"))
    }

    @Test
    fun `diacritics are stripped`() {
        assertEquals("pokemon legends arceus", TitleNormalizer.normalize("Pokémon Legends: Arceus"))
    }

    @Test
    fun `roman numerals become digits`() {
        assertEquals(TitleNormalizer.normalize("Final Fantasy 7"), TitleNormalizer.normalize("FINAL FANTASY VII"))
    }

    @Test
    fun `edition suffixes are dropped`() {
        assertEquals("the witcher 3 wild hunt", TitleNormalizer.normalize("The Witcher 3: Wild Hunt - Game of the Year Edition"))
        assertEquals("skyrim", TitleNormalizer.normalize("Skyrim Deluxe Edition"))
    }

    @Test
    fun `editions that are often separate games stay`() {
        // Tomb Raider: Anniversary, Age of Empires II: Definitive Edition, Dark Souls Remastered …
        assertEquals("skyrim anniversary edition", TitleNormalizer.normalize("Skyrim Anniversary Edition"))
        assertEquals("dark souls remastered", TitleNormalizer.normalize("Dark Souls Remastered"))
    }

    @Test
    fun `ampersand equals and`() {
        assertEquals(TitleNormalizer.normalize("Ratchet and Clank"), TitleNormalizer.normalize("Ratchet & Clank"))
    }

    @Test
    fun `sort key ignores leading article`() {
        assertEquals("witcher 3", TitleNormalizer.sortKey("The Witcher 3"))
    }

    @Test
    fun `search terms drop trademark signs but keep the title readable`() {
        assertEquals("LEGO Batman: Legacy of the Dark Knight", TitleNormalizer.searchTerm("LEGO® Batman™: Legacy of the Dark Knight"))
        assertEquals("Hades II", TitleNormalizer.searchTerm("Hades  II"))
    }
}
