package io.github.thecodingdad.gamelodge.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import io.github.thecodingdad.gamelodge.data.settings.SecretStore
import io.github.thecodingdad.gamelodge.data.settings.SettingsRepository
import io.github.thecodingdad.gamelodge.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.File
import io.github.thecodingdad.gamelodge.data.storage.StorageArea
import io.github.thecodingdad.gamelodge.data.storage.StorageKind
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidPlatformInfo(private val context: Context) : PlatformInfo {
    override val filesDir: File get() = context.filesDir

    override val deviceName: String
        get() = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    override val versionName: String get() = io.github.thecodingdad.gamelodge.BuildConfig.VERSION_NAME

    override fun storageAreas(): List<StorageArea> {
        val db = context.getDatabasePath("launcher.db")
        val dataDir = context.filesDir.parentFile ?: context.filesDir
        return listOf(
            StorageArea(StorageKind.DATABASE, listOf(db, File(db.path + "-wal"), File(db.path + "-shm"))),
            StorageArea(StorageKind.SETTINGS, listOf(File(context.filesDir, "datastore"), File(dataDir, "shared_prefs"))),
            // Image loader cache: measured and cleared through the loader itself.
        )
    }
}

private val Context.launcherSettings: DataStore<Preferences> by preferencesDataStore("launcher_settings")

/** Settings and secrets of the app, from the app's private storage. */
object AndroidStores {
    fun settings(context: Context) = SettingsRepository(context.applicationContext.launcherSettings)
    fun secrets(context: Context) = SecretStore(KeystoreSecretBackend(context))
}

/** HA login and similar pages in a custom tab. */
class CustomTabOpener(private val context: Context) : UrlOpener {
    override fun open(url: String) {
        CustomTabsIntent.Builder().setShowTitle(true).build().apply {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }.launchUrl(context, android.net.Uri.parse(url))
    }
}

class AndroidNetworkStatus(private val context: Context) : NetworkStatus {
    override fun metered(): Flow<Boolean> = callbackFlow {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        fun current() = cm.isActiveNetworkMetered
        trySend(current())
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            }
            override fun onLost(network: Network) { trySend(current()) }
        }
        cm.registerDefaultNetworkCallback(callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}

/** Values are AES-GCM encrypted with a non-exportable Android Keystore key. */
class KeystoreSecretBackend(context: Context) : SecretBackend {
    private val prefs = context.getSharedPreferences("launcher_secrets", Context.MODE_PRIVATE)

    override fun get(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
            String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8)
        }.onFailure { Log.w("Secret $key unreadable", it) }.getOrNull()
    }

    override fun put(key: String, value: String?) {
        if (value.isNullOrEmpty()) {
            prefs.edit().remove(key).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(key, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    override fun keys(): Set<String> = prefs.all.keys

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
