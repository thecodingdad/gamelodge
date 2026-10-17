package io.github.thecodingdad.moonlightlauncher.desktop.emulation

import io.github.thecodingdad.moonlightlauncher.data.library.PlatformResolver

/**
 * A console the PC emulates: its name (IGDB's, as the companion writes it; part of game ids),
 * folder names that mean it in a ROM collection ("snes", "ps2" …, as ES-DE names them) and the
 * file types that count as games, preferred first.
 */
data class PcPlatform(val name: String, val folders: List<String>, val extensions: List<String>) {
    /** The launcher's short name (shown and filtered on). */
    val displayName: String get() = PlatformResolver.normalizeIgdb(name)
}

object PcPlatforms {
    val all: List<PcPlatform> = listOf(
        PcPlatform("Nintendo Switch", listOf("switch", "nx"), listOf(".nsp", ".xci", ".nca", ".nro")),
        PcPlatform("Wii U", listOf("wiiu"), listOf(".wua", ".wud", ".wux", ".rpx")),
        PcPlatform("Wii", listOf("wii"), listOf(".rvz", ".wbfs", ".iso", ".wad")),
        PcPlatform("Nintendo GameCube", listOf("gc", "gamecube", "ngc"), listOf(".rvz", ".iso", ".gcm", ".gcz", ".ciso")),
        PcPlatform("Nintendo 3DS", listOf("n3ds", "3ds"), listOf(".3ds", ".cci", ".cxi", ".3dsx", ".app")),
        PcPlatform("Nintendo DS", listOf("nds", "ds"), listOf(".nds", ".dsi")),
        PcPlatform("Nintendo 64", listOf("n64"), listOf(".z64", ".n64", ".v64")),
        PcPlatform("Super Nintendo Entertainment System", listOf("snes", "sfc", "superfamicom"), listOf(".sfc", ".smc")),
        PcPlatform("Nintendo Entertainment System", listOf("nes", "famicom", "fds"), listOf(".nes", ".fds", ".unf")),
        PcPlatform("Game Boy Advance", listOf("gba"), listOf(".gba")),
        PcPlatform("Game Boy Color", listOf("gbc"), listOf(".gbc")),
        PcPlatform("Game Boy", listOf("gb"), listOf(".gb")),
        PcPlatform("PlayStation 3", listOf("ps3"), listOf(".iso", ".pkg")),
        PcPlatform("PlayStation 2", listOf("ps2"), listOf(".chd", ".iso", ".cso", ".gz", ".bin")),
        PcPlatform("PlayStation", listOf("psx", "ps1", "playstation"), listOf(".m3u", ".chd", ".cue", ".pbp", ".ecm", ".mds")),
        PcPlatform("PlayStation Portable", listOf("psp"), listOf(".iso", ".cso", ".chd", ".pbp")),
        PcPlatform("PlayStation Vita", listOf("psvita", "vita"), listOf(".vpk")),
        PcPlatform("Xbox 360", listOf("xbox360", "x360"), listOf(".iso", ".xex", ".zar")),
        PcPlatform("Xbox", listOf("xbox"), listOf(".iso", ".xiso")),
        PcPlatform("Dreamcast", listOf("dreamcast", "dc"), listOf(".gdi", ".cdi", ".chd")),
        PcPlatform("Sega Saturn", listOf("saturn"), listOf(".m3u", ".chd", ".cue")),
        PcPlatform("Sega Mega Drive/Genesis", listOf("megadrive", "genesis", "md"), listOf(".md", ".gen", ".smd", ".bin")),
        PcPlatform("Sega Master System", listOf("mastersystem", "sms"), listOf(".sms")),
        PcPlatform("Arcade", listOf("arcade", "mame", "fbneo"), listOf(".zip", ".7z")),
    )

    fun byName(name: String?): PcPlatform? = all.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** The platform a folder named like a system stands for ("snes" → SNES). */
    fun forFolder(folderName: String): PcPlatform? = folderName.lowercase().let { n -> all.firstOrNull { n in it.folders } }
}
