package io.github.thecodingdad.moonlightlauncher.desktop.games

import io.github.thecodingdad.moonlightlauncher.desktop.games.playnite.LiteDb
import io.github.thecodingdad.moonlightlauncher.desktop.games.playnite.PlayniteImporter
import io.github.thecodingdad.moonlightlauncher.desktop.games.playnite.PlayniteLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Fixtures written by LiteDB 4.1.4 itself (the version Playnite uses), shaped like Playnite's library. */
class PlayniteTest {
    private val dir = File(javaClass.getResource("/playnite/games.db")!!.toURI()).parentFile

    @Test
    fun readsEveryDocumentButNotDeletedOnes() {
        val docs = LiteDb.readAll(File(dir, "games.db"))
        // 300 + 2 inserted, 10 deleted.
        assertEquals(292, docs.size)
        val game = docs.first { it["Name"] == "Game 3" }
        assertEquals(UUID.fromString("00000000-0000-0000-0000-000000000003"), game["_id"])
        assertEquals(UUID.fromString("cb91dfc9-b977-43bf-8e70-55f46e410fab"), game["PluginId"])
        assertEquals(false, game["IsInstalled"])
        assertEquals(180L, game["Playtime"])
        assertTrue(docs.none { it["Name"] == "Game 105" })
    }

    @Test
    fun readsDocumentsSpreadOverExtendPages() {
        val own = LiteDb.readAll(File(dir, "games.db")).first { it["Name"] == "Own Game" }
        assertEquals(1500 * 28, (own["Description"] as String).length)
        assertEquals(4.5, own["Score"])
        assertTrue(own.containsKey("Nothing") && own["Nothing"] == null)
    }

    @Test
    fun importsEmulatedAndOwnGamesButNotStoreGames() {
        val library = PlayniteLibrary.read(dir)
        val games = PlayniteImporter(library, dir, null, exists = { true }).games()
        assertEquals(listOf("Super Mario World", "Own Game"), games.map { it.name })

        val rom = games[0]
        assertEquals("rom:super-nintendo-entertainment-system:super-mario-world", rom.id)
        assertEquals("emulator", rom.source)
        assertEquals("SNES", rom.platform)
        assertEquals("C:\\Emus\\snes9x\\snes9x-x64.exe", rom.launch)
        assertEquals("\"D:\\Roms\\SNES\\smw.sfc\" -fullscreen", rom.launchArgs)
        assertEquals(listOf("snes9x-x64.exe"), rom.processNames)

        val own = games[1]
        assertEquals("playnite:99999999-0000-0000-0000-000000000002", own.id)
        assertEquals("C:\\Games\\Own\\own.exe", own.launch)
        assertEquals("-fullscreen", own.launchArgs)
        assertEquals("C:\\Games\\Own", own.workingDir)
    }

    @Test
    fun builtInEmulatorDefinitions() {
        val yaml = """
            Id: snes9x
            Name: Snes9x
            Website: 'https://www.snes9x.com/'
            Profiles:
              - Name: Default
                Platforms: [nintendo_super_nes]
                ImageExtensions: [sfc, smc]
                StartupArguments: '"{ImagePath}" -fullscreen'
                StartupExecutable: ^snes9x(-x64)?\.exe${'$'}
              - Name: Windowed
                StartupArguments: '"{ImagePath}"'
        """.trimIndent()
        val profiles = PlayniteImporter.parseEmulatorYaml(yaml)
        assertEquals("\"{ImagePath}\" -fullscreen" to "^snes9x(-x64)?\\.exe$", profiles["snes9x" to "Default"])
        assertNotNull(profiles["snes9x" to "Windowed"])
        assertNull(profiles["snes9x" to "Missing"])
    }
}
