package io.github.thecodingdad.gamelodge.desktop.games

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Another way to start a game: a launcher or configurator, a DX11/VR variant, a mod manager …
 * [launch] is a program or link; [processNames] are the game's processes to follow (null: the
 * program's own, or the game's).
 */
@Serializable
data class CustomAction(
    val id: String,
    val name: String,
    val launch: String,
    val arguments: String? = null,
    val workingDir: String? = null,
    val processNames: List<String>? = null,
)

/**
 * The user's start settings of a scanned game: a changed start command ("play"; null fields keep
 * what the scan found), own start actions and which action a start uses.
 */
@Serializable
data class LaunchOverride(
    val launch: String? = null,
    val arguments: String? = null,
    val workingDir: String? = null,
    val processNames: List<String>? = null,
    val actions: List<CustomAction>? = null,
    /** Action a plain start uses; null = "play". */
    val defaultAction: String? = null,
) {
    val isEmpty: Boolean get() = launch == null && arguments == null && workingDir == null && processNames == null &&
        actions.isNullOrEmpty() && defaultAction == null

    /** The play command alone changed back to the scan's (the actions stay). */
    fun withoutCommand() = copy(launch = null, arguments = null, workingDir = null, processNames = null)

    /** [entry] with this override applied. */
    fun applyTo(entry: GameEntry): GameEntry = entry.copy(
        launch = launch ?: entry.launch,
        launchArgs = arguments ?: entry.launchArgs,
        workingDir = workingDir ?: entry.workingDir,
        processNames = processNames ?: entry.processNames,
        // An own program counts as installed when it is there (links always).
        installed = if (launch != null) entry.installed || !java.io.File(launch).isAbsolute || java.io.File(launch).exists() else entry.installed,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        private val mapSerializer = MapSerializer(String.serializer(), serializer())

        fun decode(raw: String?): Map<String, LaunchOverride> =
            raw?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(mapSerializer, it) }.getOrNull() }.orEmpty()

        fun encode(map: Map<String, LaunchOverride>): String = json.encodeToString(mapSerializer, map.filterValues { !it.isEmpty })
    }
}
