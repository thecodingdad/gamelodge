package io.github.thecodingdad.gamelodge.data.companion

import kotlinx.serialization.Serializable

/**
 * Play data of a host's games, shared by every GameLodge paired with it (GET/POST /v1/playdata):
 * sessions (playtime, last played), flags (own rating, favourite, hidden everywhere) and
 * collection memberships. The host fills in [deviceId]/[deviceName] from the sending device's
 * certificate; what a device sends there is ignored.
 */
@Serializable
data class PlayDataChanges(
    /** Sequence number of the newest change in this answer (ask with after=seq next time). */
    val seq: Long = 0,
    /** More changes than fit into one answer: ask again right away. */
    val more: Boolean = false,
    val sessions: List<PlaySessionData> = emptyList(),
    val flags: List<PlayFlagData> = emptyList(),
    val memberships: List<CollectionMemberData> = emptyList(),
) {
    val isEmpty: Boolean get() = sessions.isEmpty() && flags.isEmpty() && memberships.isEmpty()
    val size: Int get() = sessions.size + flags.size + memberships.size
}

/** One play session of a host game ([gameId] = [HostGame.id]); [id] is unique (UUID). */
@Serializable
data class PlaySessionData(
    val id: String,
    val gameId: String,
    /** Unix milliseconds. */
    val startedAt: Long,
    val seconds: Long,
    /** [KIND_PLAY], or [KIND_EARLIER]: a device's playtime from before syncing, once per game. */
    val kind: String = KIND_PLAY,
    val deviceId: String? = null,
    val deviceName: String? = null,
) {
    companion object {
        const val KIND_PLAY = "play"
        const val KIND_EARLIER = "earlier"
    }
}

/** A flag of a host game; the newest [changedAt] (Unix milliseconds) wins. */
@Serializable
data class PlayFlagData(
    val gameId: String,
    /** [FIELD_RATING] ("1"–"5", null = none), [FIELD_FAVORITE] / [FIELD_HIDDEN] ("1" / "0"). */
    val field: String,
    val value: String?,
    val changedAt: Long,
    val deviceId: String? = null,
) {
    companion object {
        const val FIELD_RATING = "rating"
        const val FIELD_FAVORITE = "favorite"
        const val FIELD_HIDDEN = "hidden"
        val fields = setOf(FIELD_RATING, FIELD_FAVORITE, FIELD_HIDDEN)
    }
}

/** Whether a host game is in the collection named [collection]; the newest [changedAt] wins. */
@Serializable
data class CollectionMemberData(
    val collection: String,
    val gameId: String,
    val member: Boolean,
    val changedAt: Long,
    val deviceId: String? = null,
)
