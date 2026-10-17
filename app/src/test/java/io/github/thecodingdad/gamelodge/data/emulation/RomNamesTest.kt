package io.github.thecodingdad.gamelodge.data.emulation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RomNamesTest {
    @Test
    fun `tags and underscores go, articles move to the front`() {
        assertEquals("Super Mario Sunshine", RomNames.clean("Super Mario Sunshine (USA) (Rev 1)"))
        assertEquals("The Legend of Zelda", RomNames.clean("Legend of Zelda, The (Europe)"))
        assertEquals("Final Fantasy VII", RomNames.clean("Final_Fantasy_VII [SCES-00867]"))
    }

    @Test
    fun `discs and generic names`() {
        assertEquals(2, RomNames.disc("Game (Europe) (Disc 2).chd"))
        assertNull(RomNames.disc("Game (Europe).chd"))
        assertTrue(RomNames.isGeneric("EBOOT"))
        assertEquals("Demon's Souls", RomNames.folderName(listOf("Demon's Souls", "PS3_GAME", "USRDIR")))
    }

    @Test
    fun `switch updates and dlc are no games`() {
        assertTrue(RomNames.isSwitchAddon("Zelda [UPD].nsp"))
        assertTrue(RomNames.isSwitchAddon("Zelda [01007EF00011E800].nsp"))
        assertFalse(RomNames.isSwitchAddon("Zelda [01007EF00011E000].nsp"))
    }
}
