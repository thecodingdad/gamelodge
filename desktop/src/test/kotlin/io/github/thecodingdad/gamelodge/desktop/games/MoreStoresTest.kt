package io.github.thecodingdad.gamelodge.desktop.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class MoreStoresTest {
    /** shortcuts.vdf as Steam writes it: one shortcut. */
    private fun shortcutsVdf(appId: Int, name: String, exe: String): ByteArray {
        val out = ByteArrayOutputStream()
        fun str(s: String) { out.write(s.toByteArray()); out.write(0) }
        fun int32(v: Int) { out.write(v and 0xFF); out.write(v shr 8 and 0xFF); out.write(v shr 16 and 0xFF); out.write(v shr 24 and 0xFF) }
        out.write(0); str("shortcuts")
        out.write(0); str("0")
        out.write(2); str("appid"); int32(appId)
        out.write(1); str("AppName"); str(name)
        out.write(1); str("Exe"); str("\"$exe\"")
        out.write(1); str("StartDir"); str("\"C:\\Games\\Ryujinx\\\"")
        out.write(0); str("tags"); out.write(8)
        out.write(8)
        out.write(8)
        out.write(8)
        return out.toByteArray()
    }

    @Test
    fun `non-Steam games start through Steam with their game id`() {
        val entries = SteamShortcutsScanner.parse(shortcutsVdf(-1_234_567_890, "Ryujinx", "C:\\Games\\Ryujinx\\Ryujinx.exe"), null)
        val e = entries.single()
        val unsigned = (-1_234_567_890).toLong() and 0xFFFFFFFFL
        assertEquals("Ryujinx", e.name)
        assertEquals("steamshortcut:$unsigned", e.id)
        assertEquals("steam://rungameid/${(unsigned shl 32) or 0x02000000L}", e.launch)
        assertEquals(listOf("Ryujinx.exe"), e.processNames)
    }

    @Test
    fun `humble games come with launch and install links`() {
        val json = """{"game-collection-4":[
            {"downloadMachineName":"celeste_windows","gameName":"Celeste","status":"available","imagePath":"https://x/celeste.png"},
            {"downloadMachineName":"hades_windows","gameName":"Hades","status":"downloaded","filePath":"${System.getProperty("java.io.tmpdir").replace("\\", "\\\\")}","executablePath":"Hades.exe"}
        ]}"""
        val entries = HumbleScanner.parse(json).associateBy { it.name }
        assertFalse(entries.getValue("Celeste").installed)
        assertEquals("humble://download/celeste_windows", entries.getValue("Celeste").install)
        assertTrue(entries.getValue("Hades").installed)
        assertEquals("humble://launch/hades_windows", entries.getValue("Hades").launch)
    }

    @Test
    fun `itch caves start through the itch app`() {
        val e = ItchScanner.create("42", "7", "Celeste Classic", "game", listOf("https://img/cover.png"), "C:\\itch\\celeste", """{"candidates":[{"path":"bin/Celeste.exe","flavor":"windows"}]}""")
        assertEquals("itch://caves/42/launch", e.launch)
        assertEquals(listOf("Celeste.exe"), e.processNames)
        assertFalse(ItchScanner.create("43", "8", "Some Tool", "tool", emptyList(), null, null).game)
    }

    @Test
    fun `rockstar and riot take their ids from the uninstall strings`() {
        assertEquals("gta5", RockstarScanner.titleId("\"C:\\Program Files\\Rockstar Games\\Launcher\\uninstall.exe\" -uninstall=gta5"))
        val riot = "\"C:\\Riot Games\\Riot Client\\RiotClientServices.exe\" --uninstall-product=valorant --uninstall-patchline=live"
        assertEquals("valorant", RiotScanner.argument(riot, "--uninstall-product"))
        assertEquals("live", RiotScanner.argument(riot, "--uninstall-patchline"))
        val e = RiotScanner.create("valorant", "live", "VALORANT", "C:\\Riot Games\\VALORANT", "C:\\Riot Games\\Riot Client\\RiotClientServices.exe")
        assertEquals("--launch-product=valorant --launch-patchline=live", e.launchArgs)
    }
}
