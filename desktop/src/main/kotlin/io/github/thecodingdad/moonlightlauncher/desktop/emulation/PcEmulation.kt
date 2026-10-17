package io.github.thecodingdad.moonlightlauncher.desktop.emulation

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A console an emulator runs here; empty [extensions] = the platform's usual ones. */
@Serializable
data class PcEmulatorPlatform(val platform: String, val extensions: List<String> = emptyList(), val core: String? = null) {
    fun effectiveExtensions(): List<String> =
        extensions.map { e -> e.lowercase().let { if (it.startsWith(".")) it else ".$it" } }.ifEmpty { PcPlatforms.byName(platform)?.extensions.orEmpty() }
}

/**
 * An emulator set up on this PC: program, how it starts a ROM (placeholders of
 * [RomScanner.expand]) and the consoles it runs. [presetId]: the known emulator it was made from.
 */
@Serializable
data class PcEmulator(
    val id: String,
    val name: String,
    val executable: String,
    val arguments: String,
    val platforms: List<PcEmulatorPlatform>,
    val presetId: String? = null,
    val workingDir: String? = null,
    /** Games are folders with this file inside (RPCS3). */
    val folderGameFile: String? = null,
) {
    fun supports(platform: String) = platforms.any { it.platform == platform }
}

/**
 * A ROM folder: one console ([platform]) or, when null, one subfolder per console named like
 * it ("snes", "ps2" …). [emulator]: started with this emulator instead of the console's default.
 */
@Serializable
data class PcRomFolder(val path: String, val platform: String? = null, val emulator: String? = null)

/** Everything set up for emulation on the PC (stored in the settings, so in backups too). */
@Serializable
data class PcEmulation(
    val folders: List<PcRomFolder> = emptyList(),
    val emulators: List<PcEmulator> = emptyList(),
    /** Console → id of the emulator its games start with. */
    val defaults: Map<String, String> = emptyMap(),
) {
    /** The emulator a console's games start with: chosen default, else the first that runs it. */
    fun emulatorFor(platform: String, folderChoice: String? = null): PcEmulator? =
        folderChoice?.let { id -> emulators.firstOrNull { it.id == id && it.supports(platform) } }
            ?: defaults[platform]?.let { id -> emulators.firstOrNull { it.id == id && it.supports(platform) } }
            ?: emulators.firstOrNull { it.supports(platform) }

    /** Consoles some emulator runs. */
    fun platforms(): List<String> = emulators.flatMap { e -> e.platforms.map { it.platform } }.distinct()

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        fun decode(raw: String?): PcEmulation =
            raw?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() } ?: PcEmulation()
    }
}
