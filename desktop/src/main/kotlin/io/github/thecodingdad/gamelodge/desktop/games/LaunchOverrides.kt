package io.github.thecodingdad.gamelodge.desktop.games

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** A changed start command of a scanned game; null fields keep what the scan found. */
@Serializable
data class LaunchOverride(
    val launch: String? = null,
    val arguments: String? = null,
    val workingDir: String? = null,
    val processNames: List<String>? = null,
) {
    val isEmpty: Boolean get() = launch == null && arguments == null && workingDir == null && processNames == null

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
