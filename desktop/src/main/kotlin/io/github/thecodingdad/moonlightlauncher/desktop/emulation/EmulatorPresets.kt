package io.github.thecodingdad.moonlightlauncher.desktop.emulation

import java.io.File

/** One console an emulator runs, with the ROM file types it accepts and (RetroArch) its core. */
data class PlatformSupport(val platform: String, val extensions: List<String>, val core: String? = null)

/**
 * A known emulator: its usual executables and folders, how it starts a ROM and which consoles it
 * covers. Arguments use the placeholders of [RomScanner.expand] ({rom} quoted by the template).
 */
data class EmulatorPreset(
    val id: String,
    val name: String,
    val executables: List<String>,
    val arguments: String,
    val platforms: List<PlatformSupport>,
    val searchPaths: List<String> = emptyList(),
    /** Games are folders (RPCS3: PS3_GAME\USRDIR\EBOOT.BIN) instead of single files. */
    val folderGameFile: String? = null,
) {
    /** First executable found in the usual install folders (null: not found / not Windows). */
    fun detect(): String? {
        for (dir in searchPaths.map(::expandEnv)) for (exe in executables) {
            val f = File(dir, exe)
            if (f.isFile) return f.path
        }
        return null
    }

    private fun expandEnv(path: String) = Regex("%([^%]+)%").replace(path) { System.getenv(it.groupValues[1]) ?: it.value }
}

/** The emulators the PC launcher knows (as the companion did); anything else is set up by hand. */
object EmulatorPresets {
    private fun p(name: String, vararg ext: String, core: String? = null) =
        PlatformSupport(name, ext.toList().ifEmpty { PcPlatforms.byName(name)?.extensions.orEmpty() }, core)

    val all: List<EmulatorPreset> = listOf(
        EmulatorPreset(
            "dolphin", "Dolphin", listOf("Dolphin.exe"), "-b -e \"{rom}\"",
            listOf(p("Nintendo GameCube"), p("Wii")),
            listOf("%ProgramFiles%\\Dolphin", "%ProgramFiles%\\Dolphin-x64", "%LOCALAPPDATA%\\Dolphin"),
        ),
        EmulatorPreset("cemu", "Cemu", listOf("Cemu.exe"), "-f -g \"{rom}\"", listOf(p("Wii U")), listOf("%ProgramFiles%\\Cemu", "%LOCALAPPDATA%\\Cemu")),
        EmulatorPreset(
            "ryujinx", "Ryujinx", listOf("Ryujinx.exe", "Ryujinx.Ava.exe"), "--fullscreen \"{rom}\"", listOf(p("Nintendo Switch")),
            listOf("%ProgramFiles%\\Ryujinx", "%LOCALAPPDATA%\\Ryujinx", "%APPDATA%\\Ryujinx"),
        ),
        // yuzu's successors share its command line.
        EmulatorPreset(
            "yuzu", "Eden / Citron / Sudachi (yuzu)", listOf("eden.exe", "citron.exe", "sudachi.exe", "suyu.exe", "yuzu.exe"), "-f -g \"{rom}\"",
            listOf(p("Nintendo Switch")), listOf("%LOCALAPPDATA%\\eden", "%LOCALAPPDATA%\\citron", "%LOCALAPPDATA%\\yuzu\\yuzu-windows-msvc"),
        ),
        EmulatorPreset(
            "pcsx2", "PCSX2", listOf("pcsx2-qt.exe", "pcsx2.exe"), "-batch -fullscreen -- \"{rom}\"", listOf(p("PlayStation 2")),
            listOf("%ProgramFiles%\\PCSX2", "%LOCALAPPDATA%\\PCSX2"),
        ),
        EmulatorPreset(
            "rpcs3", "RPCS3", listOf("rpcs3.exe"), "--no-gui \"{rom}\"", listOf(p("PlayStation 3")),
            listOf("%ProgramFiles%\\RPCS3", "%LOCALAPPDATA%\\RPCS3"), folderGameFile = "PS3_GAME\\USRDIR\\EBOOT.BIN",
        ),
        EmulatorPreset(
            "duckstation", "DuckStation", listOf("duckstation-qt-x64-ReleaseLTCG.exe", "duckstation-qt.exe"), "-batch -fullscreen \"{rom}\"",
            listOf(p("PlayStation")), listOf("%ProgramFiles%\\DuckStation", "%LOCALAPPDATA%\\DuckStation", "%LOCALAPPDATA%\\Programs\\DuckStation"),
        ),
        EmulatorPreset(
            "ppsspp", "PPSSPP", listOf("PPSSPPWindows64.exe", "PPSSPPWindows.exe"), "--fullscreen \"{rom}\"", listOf(p("PlayStation Portable")),
            listOf("%ProgramFiles%\\PPSSPP"),
        ),
        EmulatorPreset("vita3k", "Vita3K", listOf("Vita3K.exe"), "-F -r \"{rom}\"", listOf(p("PlayStation Vita")), listOf("%LOCALAPPDATA%\\Vita3K")),
        EmulatorPreset(
            "azahar", "Azahar / Lime3DS / Citra", listOf("azahar.exe", "lime3ds.exe", "citra-qt.exe"), "\"{rom}\"", listOf(p("Nintendo 3DS")),
            listOf("%ProgramFiles%\\Azahar", "%LOCALAPPDATA%\\Azahar", "%APPDATA%\\Lime3DS"),
        ),
        EmulatorPreset("melonds", "melonDS", listOf("melonDS.exe"), "-f \"{rom}\"", listOf(p("Nintendo DS")), listOf("%ProgramFiles%\\melonDS")),
        EmulatorPreset("xemu", "xemu", listOf("xemu.exe"), "-full-screen -dvd_path \"{rom}\"", listOf(p("Xbox")), listOf("%ProgramFiles%\\xemu")),
        EmulatorPreset("xenia", "Xenia Canary", listOf("xenia_canary.exe", "xenia.exe"), "--fullscreen \"{rom}\"", listOf(p("Xbox 360")), listOf("%ProgramFiles%\\Xenia")),
        EmulatorPreset("flycast", "Flycast", listOf("flycast.exe"), "\"{rom}\"", listOf(p("Dreamcast")), listOf("%ProgramFiles%\\Flycast")),
        EmulatorPreset(
            "retroarch", "RetroArch", listOf("retroarch.exe"), "-L \"{emuDir}\\cores\\{core}_libretro.dll\" \"{rom}\" -f",
            listOf(
                p("Nintendo Entertainment System", core = "mesen"),
                p("Super Nintendo Entertainment System", core = "snes9x"),
                p("Nintendo 64", core = "mupen64plus_next"),
                p("Game Boy", core = "gambatte"),
                p("Game Boy Color", core = "gambatte"),
                p("Game Boy Advance", core = "mgba"),
                p("Sega Mega Drive/Genesis", core = "genesis_plus_gx"),
                p("Sega Master System", core = "genesis_plus_gx"),
                p("Sega Saturn", core = "mednafen_saturn"),
                p("Arcade", core = "fbneo"),
            ),
            listOf("C:\\RetroArch-Win64", "%APPDATA%\\RetroArch", "%ProgramFiles%\\RetroArch"),
        ),
    )

    fun get(id: String?): EmulatorPreset? = all.firstOrNull { it.id.equals(id, ignoreCase = true) }

    /** Playnite's built-in emulator ids where they differ from the presets' ids. */
    fun forPlaynite(builtInId: String): EmulatorPreset? = get(
        when (val id = builtInId.lowercase()) {
            "citra", "lime3ds", "azahar" -> "azahar"
            "yuzu", "suyu", "citron", "sudachi", "eden" -> "yuzu"
            "xenia_canary", "xenia" -> "xenia"
            "pcsx2_qt", "pcsx2" -> "pcsx2"
            "ryujinx" -> "ryujinx"
            else -> id
        },
    )
}
