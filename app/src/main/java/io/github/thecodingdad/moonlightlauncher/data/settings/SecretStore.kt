package io.github.thecodingdad.moonlightlauncher.data.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.thecodingdad.moonlightlauncher.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small encrypted key/value store for credentials (API keys, OAuth tokens, VPN keys).
 * Values are AES-GCM encrypted with a non-exportable Android Keystore key.
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("launcher_secrets", Context.MODE_PRIVATE)

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

    fun get(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
            String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8)
        }.onFailure { Log.w("Secret $key unreadable", it) }.getOrNull()
    }

    fun put(key: String, value: String?) {
        if (value.isNullOrEmpty()) {
            prefs.edit().remove(key).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(key, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    fun keys(): Set<String> = prefs.all.keys

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "launcher_secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
