package io.github.thecodingdad.gamelodge.xbox

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import io.github.thecodingdad.gamelodge.data.db.LaunchTargetEntity
import io.github.thecodingdad.gamelodge.data.db.LibraryEntry

/** A game running on an Xbox console, matched to its library entry (null when not in the library). */
data class XboxRunningGame(
    val consoleId: String,
    val consoleName: String,
    val titleId: Long,
    val entry: LibraryEntry?,
    val target: LaunchTargetEntity?,
    /** This launcher streams it right now. */
    val streamed: Boolean,
    /** The console's dashboard streamed from here (no game): ending it only ends the stream. */
    val dashboard: Boolean = false,
)

/** The games running on the added consoles right now. */
@Composable
fun rememberXboxRunning(): List<XboxRunningGame> {
    val c = io.github.thecodingdad.gamelodge.LocalContainer.current
    val running by c.xbox.running.collectAsState()
    val hosts by c.xbox.hosts.collectAsState()
    val entries by c.library.entries.collectAsState()
    val active by c.launcher.activeStream.collectAsState()
    return remember(running, hosts, entries, active) {
        running.mapNotNull { (consoleId, titleId) ->
            val host = hosts[consoleId] ?: return@mapNotNull null
            val hostUuid = host.hostUuid
            var target: LaunchTargetEntity? = null
            val entry = entries.firstOrNull { e ->
                e.xboxTargets.firstOrNull { it.hostUuid == hostUuid && it.appId?.toUInt()?.toLong() == titleId }?.also { target = it } != null
            }
            val streamed = active?.hostUuid == hostUuid && XboxStreams.current != null
            XboxRunningGame(consoleId, host.console.name, titleId, entry, target, streamed)
        } + listOfNotNull(
            // The dashboard streamed from here: shown like a running game, so it can be resumed and ended.
            active?.takeIf { XboxStreams.current?.guideToConsole == true }?.let { a ->
                val target = a.entry.xboxTargets.firstOrNull { XboxRepository.isDashboard(it) } ?: return@let null
                val host = hosts.values.firstOrNull { it.hostUuid == a.hostUuid } ?: return@let null
                XboxRunningGame(host.console.id, host.console.name, 0, a.entry, target, streamed = true, dashboard = true)
            },
        )
    }
}
