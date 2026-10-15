package io.github.thecodingdad.moonlightlauncher.data.library

/** A game found in a ROM folder. */
data class RomGame(
    val name: String,
    /** Platform id of the emulator catalog ("snes", "psx" …). */
    val platformId: String,
    /** Platform name as the companion writes it (merging with host entries). */
    val platformName: String,
    /** Platform address of the file (Android: document URI inside the granted folder). */
    val uri: String,
    /** Real path when it can be derived. */
    val path: String?,
    /** Image next to the ROM or from ES-DE's media folder, as address or path. */
    val image: String?,
)
