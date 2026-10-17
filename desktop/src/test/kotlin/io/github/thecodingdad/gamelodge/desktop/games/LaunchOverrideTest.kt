package io.github.thecodingdad.gamelodge.desktop.games

import org.junit.Assert.assertEquals
import org.junit.Test

class LaunchOverrideTest {
    private val scanned = GameEntry(
        id = "steam:1", name = "Game", source = "steam", installed = true, launch = "steam://rungameid/1",
        installDir = "C:\\Games\\Game", processNames = listOf("game.exe"),
    )

    @Test
    fun onlySetFieldsReplaceTheScannedOnes() {
        val e = LaunchOverride(arguments = "-dx11", processNames = listOf("game_dx11.exe")).applyTo(scanned)
        assertEquals("steam://rungameid/1", e.launch)
        assertEquals("-dx11", e.launchArgs)
        assertEquals(listOf("game_dx11.exe"), e.processNames)
    }

    @Test
    fun emptyOverridesAreNotStored() {
        val map = mapOf("a" to LaunchOverride(), "b" to LaunchOverride(arguments = "-x"))
        assertEquals(setOf("b"), LaunchOverride.decode(LaunchOverride.encode(map)).keys)
    }
}
