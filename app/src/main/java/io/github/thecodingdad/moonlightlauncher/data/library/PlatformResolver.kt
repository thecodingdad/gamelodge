package io.github.thecodingdad.moonlightlauncher.data.library

import io.github.thecodingdad.moonlightlauncher.data.db.LibraryEntry
import io.github.thecodingdad.moonlightlauncher.data.db.LibraryItemEntity
import io.github.thecodingdad.moonlightlauncher.data.db.PlatformSource

/**
 * Decides which platform an item belongs to. Sources in order of trust:
 * manual choice > Playnite library plugin > IGDB > suggestion from the launch targets.
 */
object PlatformResolver {
    const val PC = "PC"
    const val ANDROID = "Android"

    /** Playnite library plugins whose games are all PC games. */
    private val pcPlugins = listOf(
        "steam", "epic", "gog", "xbox", "ea app", "origin", "ubisoft", "uplay", "humble", "itch",
        "battle.net", "amazon", "rockstar", "bethesda", "legacy games", "indiegala", "game pass",
    )

    /** Console names, newest first: used when IGDB lists several platforms and none is preferred. */
    private val consoleRank = listOf(
        "Nintendo Switch 2", "PlayStation 5", "Xbox Series X|S", "Nintendo Switch", "PlayStation 4", "Xbox One",
        "Wii U", "Nintendo 3DS", "PlayStation Vita", "PlayStation 3", "Xbox 360", "Wii", "Nintendo DS",
        "PlayStation Portable", "PlayStation 2", "Xbox", "Nintendo GameCube", "Game Boy Advance", "Dreamcast",
        "PlayStation", "Nintendo 64", "Game Boy Color", "Game Boy", "Sega Saturn", "Super Nintendo Entertainment System",
        "Sega Mega Drive/Genesis", "Nintendo Entertainment System",
    )

    fun fromPlaynitePlugin(pluginName: String?): String? {
        val name = pluginName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return if (pcPlugins.any { name.contains(it) }) PC else null
    }

    /** Short display name for an IGDB platform name. */
    fun normalizeIgdb(name: String): String = when {
        // Windows, Linux and Mac versions are one platform here: the game runs on the PC host.
        name.startsWith("PC (") || name == "PC" || name == "Linux" || name == "Mac" -> PC
        name.equals("Android", ignoreCase = true) -> ANDROID
        name.startsWith("Super Nintendo") -> "SNES"
        name == "Nintendo Entertainment System" -> "NES"
        name.startsWith("Sega Mega Drive") -> "Mega Drive"
        else -> name
    }

    /**
     * IGDB's platform only when it is unambiguous: the game exists on exactly one platform, or
     * it is installed here and IGDB lists Android. A game on several platforms stays without one
     * (a streamed game may as well run in an emulator) until the user or Playnite says otherwise.
     */
    fun fromIgdb(platforms: List<String>, playsOnAndroid: Boolean): String? {
        val normalized = platforms.map(::normalizeIgdb).distinct()
        if (playsOnAndroid && ANDROID in normalized) return ANDROID
        return normalized.singleOrNull()
    }

    /** Fallback when nothing better is known: only an installed Android game is certain. */
    fun suggest(entry: LibraryEntry): String? = if (entry.remoteTargets.isEmpty() && entry.localTarget != null) ANDROID else null

    /** True when [candidate] from [source] may replace the item's current platform. */
    fun mayReplace(item: LibraryItemEntity, source: PlatformSource, candidate: String?): Boolean {
        if (candidate.isNullOrBlank()) return false
        val current = item.platformSource ?: return true
        if (current == PlatformSource.MANUAL) return false
        // Same value from a more trusted source: only the source is upgraded.
        return if (candidate == item.platform) source.ordinal > current.ordinal else source.ordinal >= current.ordinal
    }
}
