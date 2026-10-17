package io.github.thecodingdad.gamelodge.data.emulation

import android.content.ComponentName
import android.content.Context
import io.github.thecodingdad.gamelodge.data.library.PlatformResolver
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One intent extra of a start command; [value] may hold placeholders (%ROMSAF% …). */
@Serializable
data class CommandExtra(val type: String = "string", val name: String, val value: String)

/**
 * How to start a ROM with one emulator (from ES-DE's Android configuration, see
 * tools/emulators/generate_catalog.py). [emulator] names the package/activity list in
 * [Catalog.emulators]; custom emulators carry their component themselves.
 */
@Serializable
data class StartCommand(
    val emulator: String,
    val label: String,
    val action: String? = null,
    val category: String? = null,
    val data: String? = null,
    val extras: List<CommandExtra> = emptyList(),
    val flags: List<String> = emptyList(),
    val experimental: Boolean = false,
) {
    /** Stable id of this choice ("DOLPHIN|Dolphin"), stored as default and per game. */
    val key: String get() = "$emulator|$label"
}

@Serializable
data class CatalogPlatform(
    val id: String,
    /** Platform name as the companion writes it (merging with host entries). */
    val name: String,
    /** Folder names that mean this platform ("snes", "sfc" …). */
    val folders: List<String>,
    /** Files that count as games, in order of preference (".m3u" before ".cue" …). */
    val extensions: List<String>,
    val experimental: Boolean = false,
    val commands: List<StartCommand>,
) {
    /** The launcher's name for the platform (as shown and filtered on). */
    val displayName: String get() = PlatformResolver.normalizeIgdb(name)
}

@Serializable
data class Catalog(
    val notice: String = "",
    val platforms: List<CatalogPlatform>,
    /** Emulator name → "package/activity" candidates (first installed wins). */
    val emulators: Map<String, List<String>>,
)

/** An emulator choice that can actually start: its command and the installed component. */
data class EmulatorChoice(val command: StartCommand, val component: ComponentName) {
    val key: String get() = command.key
    val label: String get() = command.label
    val experimental: Boolean get() = command.experimental
}

/**
 * The emulator catalog (bundled asset) plus the user's own emulators, and which of them are
 * installed on this device.
 */
class EmulatorCatalog(private val context: Context) {
    val catalog: Catalog by lazy {
        context.assets.open("emulators.json").use { input ->
            json.decodeFromString(Catalog.serializer(), input.readBytes().decodeToString())
        }
    }

    val platforms: List<CatalogPlatform> get() = catalog.platforms

    fun platform(id: String?): CatalogPlatform? = platforms.firstOrNull { it.id == id }

    /** Platform for a folder name ("SNES", "psx" …), or null. */
    fun platformForFolder(folderName: String): CatalogPlatform? {
        val name = folderName.trim().lowercase()
        return platforms.firstOrNull { name in it.folders }
    }

    /** Installed component of a catalog emulator, or null. */
    fun component(emulator: String): ComponentName? =
        catalog.emulators[emulator].orEmpty().firstNotNullOfOrNull { entry ->
            val (pkg, activity) = entry.split('/', limit = 2).takeIf { it.size == 2 } ?: return@firstNotNullOfOrNull null
            if (!isInstalled(pkg)) return@firstNotNullOfOrNull null
            ComponentName(pkg, if (activity.startsWith(".")) pkg + activity else activity)
        }

    /** Installed emulators for a platform, in catalog order, then the user's own ones. */
    fun choices(platformId: String, config: EmulationConfig): List<EmulatorChoice> {
        val builtin = platform(platformId)?.commands.orEmpty().mapNotNull { cmd ->
            component(cmd.emulator)?.let { EmulatorChoice(cmd, it) }
        }
        val custom = config.custom.filter { platformId in it.platforms && isInstalled(it.packageName) }.mapNotNull { emu ->
            // Without an activity: the app's normal start activity.
            emu.choice() ?: context.packageManager.getLaunchIntentForPackage(emu.packageName)?.component?.let { EmulatorChoice(emu.command(), it) }
        }
        return builtin + custom
    }

    /** The emulator a game starts with: its own choice, else the platform default, else the first installed. */
    fun resolve(platformId: String, config: EmulationConfig, gameKey: String?): EmulatorChoice? {
        val all = choices(platformId, config)
        return all.firstOrNull { it.key == gameKey }
            ?: all.firstOrNull { it.key == config.defaults[platformId] }
            ?: all.firstOrNull()
    }

    @Suppress("DEPRECATION") // The flags overload needs Android 13.
    fun isInstalled(packageName: String): Boolean = runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
