package io.github.thecodingdad.gamelodge.stream

import io.github.thecodingdad.gamelodge.data.companion.CompanionFeed
import io.github.thecodingdad.gamelodge.data.db.LaunchTargetEntity
import io.github.thecodingdad.gamelodge.data.db.LibraryEntry
import io.github.thecodingdad.gamelodge.data.db.TargetType

/** One way to start a game: its own program ("play"), a launcher, a mod manager … */
data class StartAction(val id: String, val name: String, val isDefault: Boolean = false) {
    companion object {
        /** The game itself: always there, the default unless another action is chosen. */
        const val PLAY = "play"
    }
}

/**
 * The start actions of a target: local PC games from the platform's own settings, streamed games
 * from the host's GameLodge (paired). Empty when there is nothing to choose from.
 */
class StartActionSource(
    private val feed: () -> CompanionFeed?,
    private val local: suspend (LibraryEntry, LaunchTargetEntity) -> List<StartAction> = { _, _ -> emptyList() },
) {
    suspend fun actions(entry: LibraryEntry, target: LaunchTargetEntity): List<StartAction> = when (target.type) {
        TargetType.LOCAL -> local(entry, target)
        TargetType.REMOTE -> {
            val hostUuid = target.hostUuid
            val appUuid = target.appUuid
            if (hostUuid == null || appUuid == null) emptyList()
            else runCatching { feed()?.games(hostUuid)?.values?.firstOrNull { it.appUuid.equals(appUuid, ignoreCase = true) } }.getOrNull()
                ?.let { game -> game.actions.map { StartAction(it.id, it.name, isDefault = it.id == (game.defaultAction ?: StartAction.PLAY)) } }
                .orEmpty()
        }
        TargetType.ROM -> emptyList()
    }.takeIf { it.size > 1 }.orEmpty()
}
