package io.github.thecodingdad.gamelodge.data.library

import io.github.thecodingdad.gamelodge.data.db.LibraryEntry
import io.github.thecodingdad.gamelodge.data.db.LibraryItemEntity
import io.github.thecodingdad.gamelodge.data.db.PlatformSource

/**
 * Decides which platform an item belongs to. Sources in order of trust:
 * manual choice > companion data of the host > IGDB > suggestion from the launch targets.
 */
object PlatformResolver {
    const val PC = "PC"
    const val ANDROID = "Android"

    /** Platform of games installed on this device: Android, or PC on the desktop (set at start). */
    @Volatile var localPlatform: String = ANDROID

    /** Console names, newest first: used when IGDB lists several platforms and none is preferred. */
    private val consoleRank = listOf(
        "Nintendo Switch 2", "PlayStation 5", "Xbox Series X|S", "Nintendo Switch", "PlayStation 4", "Xbox One",
        "Wii U", "Nintendo 3DS", "PlayStation Vita", "PlayStation 3", "Xbox 360", "Wii", "Nintendo DS",
        "PlayStation Portable", "PlayStation 2", "Xbox", "Nintendo GameCube", "Game Boy Advance", "Dreamcast",
        "PlayStation", "Nintendo 64", "Game Boy Color", "Game Boy", "Sega Saturn", "Super Nintendo Entertainment System",
        "Sega Mega Drive/Genesis", "Nintendo Entertainment System",
    )

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
     * it is installed here and IGDB lists this device's platform. A game on several platforms stays without one
     * (a streamed game may as well run in an emulator) until the user or Playnite says otherwise.
     */
    fun fromIgdb(platforms: List<String>, playsLocally: Boolean): String? {
        val normalized = platforms.map(::normalizeIgdb).distinct()
        if (playsLocally && localPlatform in normalized) return localPlatform
        return normalized.singleOrNull()
    }

    /** Fallback when nothing better is known: only a game installed on this device is certain. */
    fun suggest(entry: LibraryEntry): String? = if (entry.remoteTargets.isEmpty() && entry.localTarget != null) localPlatform else null

    /** True when [candidate] from [source] may replace the item's current platform. */
    fun mayReplace(item: LibraryItemEntity, source: PlatformSource, candidate: String?): Boolean {
        if (candidate.isNullOrBlank()) return false
        val current = item.platformSource ?: return true
        if (current == PlatformSource.MANUAL) return false
        // Same value from a more trusted source: only the source is upgraded.
        return if (candidate == item.platform) source.ordinal > current.ordinal else source.ordinal >= current.ordinal
    }

    /**
     * How a platform is shown: without the maker's name where the product name stands on its own
     * ("Nintendo Switch" → "Switch", "Sega Saturn" → "Saturn"). Only for display: the library,
     * filters, matching and sync keep IGDB's names.
     */
    fun displayName(name: String): String = DISPLAY[name] ?: when {
        name.startsWith("Sony ") -> name.removePrefix("Sony ")
        name.startsWith("Microsoft ") -> name.removePrefix("Microsoft ")
        else -> name
    }

    private val DISPLAY = mapOf(
        "Nintendo Switch" to "Switch",
        "Nintendo Switch 2" to "Switch 2",
        "Nintendo GameCube" to "GameCube",
        "Nintendo 64" to "N64",
        "Nintendo DS" to "DS",
        "Nintendo DSi" to "DSi",
        "Nintendo 3DS" to "3DS",
        "New Nintendo 3DS" to "New 3DS",
        "Nintendo 64DD" to "64DD",
        "Sega Saturn" to "Saturn",
        "Sega Dreamcast" to "Dreamcast",
        "Sega Master System" to "Master System",
        "Sega Master System/Mark III" to "Master System",
        "Sega Game Gear" to "Game Gear",
        "Sega Mega Drive/Genesis" to "Mega Drive",
        "Sega Pico" to "Pico",
        "Family Computer" to "Famicom",
        "Family Computer Disk System" to "Famicom Disk System",
        "Super Famicom" to "Super Famicom",
    )
}
