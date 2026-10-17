package io.github.thecodingdad.moonlightlauncher.data.settings

import io.github.thecodingdad.moonlightlauncher.platform.SecretBackend

/**
 * Small encrypted key/value store for credentials (API keys, OAuth tokens, VPN keys).
 * Encryption and storage come from the platform ([SecretBackend]).
 */
class SecretStore(private val backend: SecretBackend) {
    object Keys {
        const val SGDB_API_KEY = "sgdb_api_key"
        const val IGDB_CLIENT_ID = "igdb_client_id"
        const val IGDB_CLIENT_SECRET = "igdb_client_secret"
        const val IGDB_ACCESS_TOKEN = "igdb_access_token"
        const val HA_ACCESS_TOKEN = "ha_access_token"
        const val HA_REFRESH_TOKEN = "ha_refresh_token"
        const val HA_TOKEN_EXPIRY = "ha_token_expiry"
        /** Host web API logins of older versions (removed at start). */
        const val LEGACY_HOST_API_PREFIX = "vibepollo_token_"
        fun wireguardConfig(tunnel: String) = "wg_config_$tunnel"
    }

    fun get(key: String): String? = backend.get(key)

    fun put(key: String, value: String?) = backend.put(key, value)

    fun keys(): Set<String> = backend.keys()
}
