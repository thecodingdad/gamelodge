package io.github.thecodingdad.gamelodge.data.sync

import io.github.thecodingdad.gamelodge.data.companion.SharedKey
import io.github.thecodingdad.gamelodge.data.companion.SharedKeys
import io.github.thecodingdad.gamelodge.data.settings.SecretStore
import io.github.thecodingdad.gamelodge.data.settings.SecretStore.Keys

/**
 * This GameLodge's SteamGridDB / IGDB keys with the time of their last change, so they can be shared
 * through a host: per provider the newest change wins (ties keep what is here).
 */
class KeySync(private val secrets: SecretStore, private val clock: () -> Long = System::currentTimeMillis) {
    /** Keys of another device were taken over. */
    @Volatile var onChanged: (() -> Unit)? = null

    /** The user saved or removed the SteamGridDB key. */
    fun setSgdb(apiKey: String?) = synchronized(this) {
        secrets.put(Keys.SGDB_API_KEY, apiKey?.trim()?.takeIf { it.isNotEmpty() })
        secrets.put(Keys.SGDB_CHANGED_AT, clock().toString())
    }

    /** The user saved or removed the IGDB keys (null: removed). */
    fun setIgdb(clientId: String?, clientSecret: String?) = synchronized(this) {
        val complete = !clientId.isNullOrBlank() && !clientSecret.isNullOrBlank()
        secrets.put(Keys.IGDB_CLIENT_ID, clientId?.trim()?.takeIf { complete })
        secrets.put(Keys.IGDB_CLIENT_SECRET, clientSecret?.trim()?.takeIf { complete })
        secrets.put(Keys.IGDB_ACCESS_TOKEN, null)
        secrets.put(Keys.IGDB_CHANGED_AT, clock().toString())
    }

    /** What there is to share (a provider never set here is left out). */
    fun local(): SharedKeys = synchronized(this) {
        SharedKeys(
            sgdb = key(Keys.SGDB_CHANGED_AT, listOf(Keys.SGDB_API_KEY)),
            igdb = key(Keys.IGDB_CHANGED_AT, listOf(Keys.IGDB_CLIENT_ID, Keys.IGDB_CLIENT_SECRET)),
        )
    }

    /** Takes the newer keys of [remote]; true when something changed here. */
    fun merge(remote: SharedKeys): Boolean = synchronized(this) {
        var changed = false
        remote.sgdb?.let { r ->
            if (newer(r, Keys.SGDB_CHANGED_AT)) {
                secrets.put(Keys.SGDB_API_KEY, r.values.firstOrNull())
                secrets.put(Keys.SGDB_CHANGED_AT, r.changedAt.toString())
                changed = true
            }
        }
        remote.igdb?.let { r ->
            if (newer(r, Keys.IGDB_CHANGED_AT)) {
                secrets.put(Keys.IGDB_CLIENT_ID, r.values.getOrNull(0))
                secrets.put(Keys.IGDB_CLIENT_SECRET, r.values.getOrNull(1))
                secrets.put(Keys.IGDB_ACCESS_TOKEN, null)
                secrets.put(Keys.IGDB_CHANGED_AT, r.changedAt.toString())
                changed = true
            }
        }
        if (changed) onChanged?.invoke()
        changed
    }

    private fun key(stamp: String, names: List<String>): SharedKey? {
        val values = names.map { secrets.get(it)?.takeIf { v -> v.isNotBlank() } }
        val complete = values.all { it != null }
        val changedAt = secrets.get(stamp)?.toLongOrNull()
            // Set before keys were shared: older than any change.
            ?: if (complete) INITIAL else return null
        return SharedKey(changedAt, if (complete) values.map { it!! } else emptyList())
    }

    private fun newer(remote: SharedKey, stamp: String): Boolean {
        val here = secrets.get(stamp)?.toLongOrNull() ?: if (secrets.get(if (stamp == Keys.SGDB_CHANGED_AT) Keys.SGDB_API_KEY else Keys.IGDB_CLIENT_ID).isNullOrBlank()) 0 else INITIAL
        return remote.changedAt > here
    }

    companion object {
        const val INITIAL = 1L
    }
}
