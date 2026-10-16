package io.github.thecodingdad.moonlightlauncher.platform

import kotlinx.coroutines.flow.Flow
import java.io.File

/** Facts about the device the launcher runs on. */
interface PlatformInfo {
    /** Private data folder of the launcher. */
    val filesDir: File
    /** Name shown to paired servers (e.g. "Odin 2 Portal", the PC name). */
    val deviceName: String

    /** Version of the launcher ("0.1.0"). */
    val versionName: String

    /** Data folders only this platform has (database, settings, logs …), for Settings › Data. */
    fun storageAreas(): List<io.github.thecodingdad.moonlightlauncher.data.storage.StorageArea> = emptyList()
}

/** Encrypted storage for credentials; the platform keeps the key (Android Keystore, Windows DPAPI). */
interface SecretBackend {
    fun get(key: String): String?
    /** Null or empty removes the entry. */
    fun put(key: String, value: String?)
    fun keys(): Set<String>
}

/** Opens a web page outside the launcher (browser, custom tab). */
fun interface UrlOpener {
    fun open(url: String)
}

/** State of the default network. */
interface NetworkStatus {
    /** True while the network is metered (mobile data, hotspot); emits on changes. */
    fun metered(): Flow<Boolean>
}
