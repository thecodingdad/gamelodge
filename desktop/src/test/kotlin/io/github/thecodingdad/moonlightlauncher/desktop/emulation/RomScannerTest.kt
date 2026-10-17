package io.github.thecodingdad.moonlightlauncher.desktop.emulation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RomScannerTest {
    private val dolphin = PcEmulator("d", "Dolphin", "C:\\Emu\\Dolphin\\Dolphin.exe", "-b -e \"{rom}\"", listOf(PcEmulatorPlatform("Nintendo GameCube"), PcEmulatorPlatform("Wii")))
    private val snes = PcEmulator("s", "Snes9x", "C:\\Emu\\snes9x\\snes9x-x64.exe", "\"{rom}\" -fullscreen", listOf(PcEmulatorPlatform("Super Nintendo Entertainment System")))

    private fun tree(vararg files: String): File {
        val root = Files.createTempDirectory("roms").toFile()
        files.forEach { File(root, it).apply { parentFile.mkdirs(); writeText("x") } }
        return root
    }

    @Test
    fun subfoldersNamedLikeConsolesUseTheirDefaultEmulator() = runBlocking {
        val root = tree("gc/Super Mario Sunshine (USA) (Rev 1).rvz", "snes/Legend of Zelda, The - A Link to the Past (USA).sfc", "other/readme.txt")
        val config = PcEmulation(folders = listOf(PcRomFolder(root.path)), emulators = listOf(dolphin, snes))
        val games = RomScanner { config }.scan()
        assertEquals(listOf("Super Mario Sunshine", "The Legend of Zelda - A Link to the Past"), games.map { it.name })
        val mario = games[0]
        assertEquals("rom:nintendo-gamecube:super-mario-sunshine", mario.id)
        assertEquals("GameCube".let { mario.platform }, mario.platform)
        assertEquals("C:\\Emu\\Dolphin\\Dolphin.exe", mario.launch)
        assertEquals("-b -e \"${File(root, "gc/Super Mario Sunshine (USA) (Rev 1).rvz").path}\"", mario.launchArgs)
        assertEquals("C:\\Emu\\Dolphin", mario.workingDir)
    }

    @Test
    fun multiDiscSetsBecomeOneGameAndCueWinsOverBin() = runBlocking {
        val root = tree("Game (Disc 1).cue", "Game (Disc 1).bin", "Game (Disc 2).cue", "Game (Disc 2).bin")
        val psx = PcEmulator("p", "DuckStation", "C:\\duck.exe", "\"{rom}\"", listOf(PcEmulatorPlatform("PlayStation")))
        val games = RomScanner.scan(root.path, "PlayStation", psx.platforms.single(), psx)
        assertEquals(1, games.size)
        assertEquals("Game", games.single().name)
        assert(games.single().launchArgs!!.contains("Game (Disc 1).cue"))
    }

    @Test
    fun switchUpdatesAndDlcAreNoGames() = runBlocking {
        val root = tree("Mario Kart 8 Deluxe [0100152000022000][v0].nsp", "Mario Kart 8 Deluxe [0100152000022800][v655360].nsp", "Game [DLC].nsp")
        val eden = PcEmulator("e", "Eden", "C:\\eden.exe", "-f -g \"{rom}\"", listOf(PcEmulatorPlatform("Nintendo Switch")))
        assertEquals(listOf("Mario Kart 8 Deluxe"), RomScanner.scan(root.path, "Nintendo Switch", eden.platforms.single(), eden).map { it.name })
    }

    @Test
    fun aFoldersOwnChoiceBeatsTheDefault() {
        val other = dolphin.copy(id = "d2", name = "Dolphin Dev")
        val config = PcEmulation(emulators = listOf(dolphin, other), defaults = mapOf("Wii" to "d"))
        assertEquals("d", config.emulatorFor("Wii")?.id)
        assertEquals("d2", config.emulatorFor("Wii", "d2")?.id)
        assertEquals(null, config.emulatorFor("Xbox"))
    }
}
