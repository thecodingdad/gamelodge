package io.github.thecodingdad.moonlightlauncher.desktop.games

import io.github.thecodingdad.moonlightlauncher.desktop.games.playnite.LiteDb
import io.github.thecodingdad.moonlightlauncher.desktop.games.playnite.PlayniteImport
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
    fun importsEmulatorsFoldersAndOwnGamesButNotStoreGames() {
        val covers = java.nio.file.Files.createTempDirectory("covers").toFile()
        val result = PlayniteImport(PlayniteLibrary.read(dir), dir, null, covers, exists = { true }).run()

        val emu = result.emulators.single()
        assertEquals("Snes9x", emu.name)
        assertEquals("C:\\Emus\\snes9x\\snes9x-x64.exe", emu.executable)
        assertEquals("\"{ImagePath}\" -fullscreen", emu.arguments)
        assertEquals(listOf("Super Nintendo Entertainment System"), emu.platforms.map { it.platform })
        assertEquals(listOf(".sfc", ".smc"), emu.platforms.single().effectiveExtensions())

        // The emulated game's folder, played with that emulator; its console's default.
        val folder = result.folders.single()
        assertEquals("D:\\Roms\\SNES", folder.path)
        assertEquals("Super Nintendo Entertainment System", folder.platform)
        assertEquals(emu.id, folder.emulator)
        assertEquals(mapOf("Super Nintendo Entertainment System" to emu.id), result.defaults)

        // Store games stay with the store scanners; the hand-added game is an own game.
        val own = result.games.single()
        assertEquals("Own Game", own.name)
        assertEquals("C:\\Games\\Own\\own.exe", own.path)
        assertEquals("-fullscreen", own.arguments)
        assertEquals("C:\\Games\\Own", own.workingDir)
    }

    @Test
    fun mergeKeepsWhatIsThereAndAddsTheRest() {
        val result = PlayniteImport(PlayniteLibrary.read(dir), dir, null, java.nio.file.Files.createTempDirectory("c").toFile(), exists = { true }).run()
        val (once, games) = PlayniteImport.merge(io.github.thecodingdad.moonlightlauncher.desktop.emulation.PcEmulation(), emptyList(), result)
        val (twice, gamesTwice) = PlayniteImport.merge(once, games, result)
        assertEquals(once, twice)
        assertEquals(games, gamesTwice)
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
        val profiles = PlayniteImport.parseEmulatorYaml(yaml)
        assertEquals("\"{ImagePath}\" -fullscreen" to "^snes9x(-x64)?\\.exe$", profiles["snes9x" to "Default"])
        assertNotNull(profiles["snes9x" to "Windowed"])
        assertNull(profiles["snes9x" to "Missing"])
    }
}
