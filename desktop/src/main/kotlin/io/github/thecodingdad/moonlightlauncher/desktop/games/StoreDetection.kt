package io.github.thecodingdad.moonlightlauncher.desktop.games

import io.github.thecodingdad.moonlightlauncher.desktop.isWindows
import java.io.File

/** Which stores are installed on this PC (their games can't be started without the client). */
object StoreDetection {
    /** True / false on Windows; null elsewhere (unknown). */
    fun installed(source: String): Boolean? {
        if (!isWindows) return null
        return runCatching {
            when (source) {
                "steam" -> SteamScanner(okhttp3.OkHttpClient()).findSteamPath() != null
                "epic" -> File(System.getenv("ProgramData") ?: "C:\\ProgramData", "Epic/EpicGamesLauncher").isDirectory || uninstall("Epic Games Launcher")
                // GOG games also install without Galaxy (offline installers): any of them counts.
                "gog" -> Reg.read("HKLM", "SOFTWARE\\WOW6432Node\\GOG.com\\GalaxyClient\\paths", "client") != null ||
                    Reg.subKeys("HKLM", "SOFTWARE\\WOW6432Node\\GOG.com\\Games").isNotEmpty()
                "xbox" -> Reg.subKeys("HKLM", "SOFTWARE\\Microsoft\\GamingServices").isNotEmpty() ||
                    Reg.read("HKLM", "SOFTWARE\\Microsoft\\GamingServices", "GameConfigStoreItemsVersion") != null
                "ea" -> Reg.read("HKLM", "SOFTWARE\\Electronic Arts\\EA Desktop", "InstallLocation") != null || uninstall("EA app")
                "ubisoft" -> Reg.read("HKLM", "SOFTWARE\\WOW6432Node\\Ubisoft\\Launcher", "InstallDir") != null || uninstall("Ubisoft Connect")
                "battlenet" -> uninstall("Battle.net") || File("C:\\Program Files (x86)\\Battle.net\\Battle.net.exe").exists()
                "amazon" -> File(System.getenv("LOCALAPPDATA") ?: "", "Amazon Games").isDirectory
                "playnite" -> io.github.thecodingdad.moonlightlauncher.desktop.games.playnite.PlayniteScanner.installed()
                else -> true
            }
        }.getOrNull()
    }

    private fun uninstall(displayName: String) = Reg.uninstallEntries().any { it.second("DisplayName").equals(displayName, ignoreCase = true) }
}
