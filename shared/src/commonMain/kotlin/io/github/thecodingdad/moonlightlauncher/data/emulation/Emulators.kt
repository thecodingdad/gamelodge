package io.github.thecodingdad.moonlightlauncher.data.emulation

/** An emulator that can start games of a platform. */
data class EmulatorOption(val key: String, val label: String, val experimental: Boolean = false)

/** Which emulators start which platform, as the start dialogs need it. */
interface Emulators {
    /** Every emulator that can start [platformId] here, preferred first. */
    fun choices(platformId: String): List<EmulatorOption>

    /** The emulator a game starts with ([gameKey]: the game's own choice; null = the platform's default). */
    fun resolve(platformId: String, gameKey: String?): EmulatorOption?

    /** Display name of a platform id ("snes" → "Super Nintendo"). */
    fun platformName(platformId: String): String?
}
