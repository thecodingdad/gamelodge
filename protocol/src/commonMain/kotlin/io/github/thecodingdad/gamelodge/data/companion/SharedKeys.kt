package io.github.thecodingdad.gamelodge.data.companion

import kotlinx.serialization.Serializable

/**
 * The SteamGridDB / IGDB keys a GameLodge host shares with the devices allowed to (GET/PUT /v1/keys).
 * Per provider the newest change wins; a key without values was removed.
 */
@Serializable
data class SharedKeys(
    /** [SharedKey.values]: the API key. */
    val sgdb: SharedKey? = null,
    /** [SharedKey.values]: client id, client secret. */
    val igdb: SharedKey? = null,
) {
    val isEmpty: Boolean get() = sgdb == null && igdb == null

    /** Sensible values only: the right number of short printable values, no time in the future (+5 min). */
    fun isValid(now: Long): Boolean {
        fun ok(key: SharedKey?, count: Int) = key == null || (
            key.changedAt in 1..(now + 5 * 60_000) && (key.values.isEmpty() || key.values.size == count) &&
                key.values.all { v -> v.length in 1..MAX_LENGTH && v.all { it.code in 0x21..0x7E } }
            )
        return ok(sgdb, 1) && ok(igdb, 2)
    }

    companion object {
        const val MAX_LENGTH = 256
    }
}

@Serializable
data class SharedKey(
    /** Unix milliseconds of the change (1: set before keys were shared, loses against every change). */
    val changedAt: Long,
    /** Empty: removed. */
    val values: List<String> = emptyList(),
)
