package io.github.thecodingdad.moonlightlauncher.data.emulation

import android.content.ComponentName
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class EmulatorLauncherTest {
    private val catalog = Json { ignoreUnknownKeys = true }.decodeFromString(
        Catalog.serializer(), File("src/main/assets/emulators.json").readText(),
    )
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val uri = "content://com.android.externalstorage.documents/tree/primary%3AROMs/document/primary%3AROMs%2Fgc%2FGame.rvz"

    private fun command(platform: String, label: String) = catalog.platforms.first { it.id == platform }.commands.first { it.label == label }

    @Test
    fun `catalog covers the companion's systems`() {
        val ids = catalog.platforms.map { it.id }.toSet()
        assertTrue(ids.containsAll(listOf("gc", "wii", "switch", "psx", "ps2", "psp", "n3ds", "nds", "dreamcast", "snes", "gba", "arcade")))
        assertTrue(catalog.platforms.first { it.id == "ps3" }.commands.all { it.experimental })
    }

    @Test
    fun `dolphin gets the document uri as extra with a read grant`() {
        val choice = EmulatorChoice(command("gc", "Dolphin"), ComponentName("org.dolphinemu.dolphinemu", "org.dolphinemu.dolphinemu.ui.main.TvMainActivity"))
        val intent = (EmulatorLauncher.intent(context, choice, uri, null) as EmulatorLauncher.Result.Ok).intent
        assertEquals(uri, intent.getStringExtra("AutoStartFile"))
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri.toString())
    }

    @Test
    fun `eden uses its nfc action with the uri as data`() {
        val choice = EmulatorChoice(command("switch", "Eden"), ComponentName("dev.eden.eden_emulator", "org.yuzu.yuzu_emu.activities.EmulationActivity"))
        val intent = (EmulatorLauncher.intent(context, choice, uri, null) as EmulatorLauncher.Result.Ok).intent
        assertEquals("android.nfc.action.TECH_DISCOVERED", intent.action)
        assertEquals(uri, intent.dataString)
    }

    @Test
    fun `retroarch needs the real path`() {
        val choice = EmulatorChoice(command("snes", "Snes9x - Current (RetroArch)"), ComponentName("com.retroarch", "com.retroarch.browser.retroactivity.RetroActivityFuture"))
        assertEquals(RomStartProblem.NEEDS_PATH, (EmulatorLauncher.intent(context, choice, uri, null) as EmulatorLauncher.Result.Problem).problem)
        val intent = (EmulatorLauncher.intent(context, choice, uri, "/storage/emulated/0/ROMs/snes/Game.sfc") as EmulatorLauncher.Result.Ok).intent
        assertEquals("/storage/emulated/0/ROMs/snes/Game.sfc", intent.getStringExtra("ROM"))
        assertEquals("/data/data/com.retroarch/cores/snes9x_libretro_android.so", intent.getStringExtra("LIBRETRO"))
    }

    @Test
    fun `paths of external storage documents`() {
        assertTrue(RomScanner.pathOf("primary:ROMs/snes/Game.sfc")!!.endsWith("/ROMs/snes/Game.sfc"))
        assertEquals("/storage/1234-ABCD/ROMs", RomScanner.pathOf("1234-ABCD:ROMs"))
        assertNull(RomScanner.pathOf("msf:42"))
    }
}
