package io.github.thecodingdad.moonlightlauncher.data.library

import io.github.thecodingdad.moonlightlauncher.data.db.ItemKind

/** Facts the classifier can use; every field is optional because sources differ. */
data class ClassificationInput(
    val title: String,
    val isRemote: Boolean,
    val remoteAppUuid: String? = null,
    /** Android: ApplicationInfo.category == CATEGORY_GAME or FLAG_IS_GAME. */
    val androidGameFlag: Boolean = false,
    val packageName: String? = null,
    /** Vibepollo: entry was synced from Playnite. */
    val hasPlayniteId: Boolean = false,
    /** Metadata lookup found a confident game match. */
    val confidentGameMatch: Boolean = false,
)

/**
 * Decides whether an entry is a game or an app. Manual overrides are handled by the caller
 * (they always win); this class only implements the automatic rules, in priority order.
 */
object Classifier {
    /** Moonlight's virtual "Remote Input" app. */
    const val REMOTE_INPUT_UUID = "8CB5C136-DA67-4F99-B4A1-F9CD35005CF4"

    /** Host entries that are tools, never games (Sunshine/Apollo/Vibepollo defaults). */
    private val knownRemoteApps = listOf(
        "desktop", "steam big picture", "steam", "playnite", "playnite fullscreen",
        "terminate", "resume", "remote monitor", "remote input", "virtual display",
        "virtual desktop", "explorer", "file explorer", "browser", "chrome", "firefox",
        "edge", "terminal", "powershell", "cmd", "task manager", "settings",
        "mstsc", "remote desktop", "sleep", "shutdown", "restart", "lock",
    )

    /** Streaming clients and launchers flag themselves as games but are tools. */
    private val nonGamePackagePrefixes = listOf(
        "com.limelight", "com.moonlight", "io.github.thecodingdad", "com.valvesoftware.steamlink",
        "com.retroarch", "org.es_de", "com.teslacoilsw.launcher", "com.odin.",
    )

    /** True when the entry is a known tool regardless of any other signal. */
    fun isKnownApp(input: ClassificationInput): Boolean {
        if (input.hasPlayniteId) return false
        if (input.packageName != null && nonGamePackagePrefixes.any { input.packageName.startsWith(it) }) return true
        if (!input.isRemote) return false
        return input.remoteAppUuid.equals(REMOTE_INPUT_UUID, ignoreCase = true) ||
            TitleNormalizer.normalize(input.title) in knownRemoteApps
    }

    fun classify(input: ClassificationInput): ItemKind {
        if (input.hasPlayniteId) return ItemKind.GAME
        if (isKnownApp(input)) return ItemKind.APP

        if (input.androidGameFlag) return ItemKind.GAME
        if (input.confidentGameMatch) return ItemKind.GAME

        // Remote entries are added to a streaming host deliberately and are usually games;
        // local apps without the game flag are usually regular apps.
        return if (input.isRemote) ItemKind.GAME else ItemKind.APP
    }
}
