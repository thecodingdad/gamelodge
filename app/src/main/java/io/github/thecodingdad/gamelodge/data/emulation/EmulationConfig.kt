package io.github.thecodingdad.gamelodge.data.emulation

import android.content.ComponentName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A ROM folder chosen by the user (storage access framework tree). [platform] null: the folder
 * holds one subfolder per system ("snes", "psx" …), recognised by name.
 */
@Serializable
data class RomFolder(val uri: String, val name: String, val platform: String? = null)

/** An emulator the catalog doesn't know, set up by hand (same placeholders as the catalog). */
@Serializable
data class CustomEmulator(
    val id: String,
    val name: String,
    val packageName: String,
    val activity: String? = null,
    val action: String? = null,
    val data: String? = null,
    val extras: List<CommandExtra> = emptyList(),
    val platforms: List<String> = emptyList(),
) {
    fun command() = StartCommand(
        emulator = "CUSTOM:$id", label = name, action = action, data = data, extras = extras,
        // Not verified by anyone: marked like the experimental catalog entries.
        experimental = true,
    )

    fun choice(): EmulatorChoice? {
        val activity = activity?.takeIf { it.isNotBlank() } ?: return null
        return EmulatorChoice(command(), ComponentName(packageName, if (activity.startsWith(".")) packageName + activity else activity))
    }
}

/** Everything the user set up for emulation; stored as JSON in the settings (and so in backups). */
@Serializable
data class EmulationConfig(
    val folders: List<RomFolder> = emptyList(),
    /** Platform id → [StartCommand.key] of its default emulator. */
    val defaults: Map<String, String> = emptyMap(),
    val custom: List<CustomEmulator> = emptyList(),
) {
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        fun decode(raw: String?): EmulationConfig =
            raw?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() } ?: EmulationConfig()
    }
}
