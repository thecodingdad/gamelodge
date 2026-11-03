package io.github.thecodingdad.gamelodge.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchRulesTest {
    private fun game(id: Long, name: String, type: String? = "Main Game", platforms: List<String> = listOf("PC (Microsoft Windows)"), year: Long? = null) =
        IgdbGame(
            id = id, name = name, game_type = type?.let { IgdbGameType(it) }, platforms = platforms.map { IgdbNamed(it) },
            first_release_date = year?.let { (it - 1970) * 31_557_600L },
        )

    private fun decide(query: String, vararg games: Pair<IgdbGame, Float>, platform: String? = "PC", year: Int? = null) =
        MatchRules.decide(MatchRules.Context(query, platform, year), games.toList())

    @Test
    fun `an exact unique title on the right platform is sure`() {
        val d = decide("Celeste", game(1, "Celeste") to 1f, game(2, "Celeste Classic") to 0.8f)!!
        assertEquals(1L, d.game.id)
        assertTrue(d.sure)
    }

    @Test
    fun `an original and its remake of the same name go to review`() {
        val d = decide("Resident Evil 4", game(1, "Resident Evil 4") to 1f, game(2, "Resident Evil 4", type = "Remake") to 1f)!!
        assertFalse(d.sure)
    }

    @Test
    fun `a remake or remaster only when the title says so`() {
        assertFalse(decide("Dark Souls", game(1, "Dark Souls: Remastered", type = "Remaster") to 0.95f)!!.sure)
        assertTrue(decide("Dark Souls Remastered", game(1, "Dark Souls Remastered", type = "Remaster") to 1f)!!.sure)
        // The title names a remaster, the hit is the original.
        assertFalse(decide("Dark Souls Remastered", game(1, "Dark Souls Remastered", type = "Main Game") to 1f)!!.sure)
    }

    @Test
    fun `wrong platform, extras and only similar titles are not sure`() {
        assertFalse(decide("Celeste", game(1, "Celeste", platforms = listOf("Nintendo Switch")) to 1f)!!.sure)
        assertFalse(decide("Celeste", game(1, "Celeste", type = "DLC") to 1f)!!.sure)
        assertFalse(decide("Witcher 3", game(1, "The Witcher 3: Wild Hunt") to 0.8f)!!.sure)
        assertNull(decide("Celeste", game(1, "Something else") to 0.3f))
    }

    @Test
    fun `a known year that doesn't fit is not sure`() {
        assertFalse(decide("Doom", game(1, "Doom", year = 1993) to 1f, year = 2016)!!.sure)
        assertTrue(decide("Doom", game(1, "Doom", year = 2016) to 1f, year = 2016)!!.sure)
    }

    @Test
    fun `store ids map to IGDB sources`() {
        assertEquals(MatchRules.STEAM to "2215200", MatchRules.storeId("steam:2215200"))
        assertEquals(MatchRules.GOG to "123", MatchRules.storeId("gog:123"))
        assertNull(MatchRules.storeId("epic:abc"))
    }
}
