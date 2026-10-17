package io.github.thecodingdad.moonlightlauncher.desktop

import io.github.thecodingdad.moonlightlauncher.data.storage.StorageArea
import io.github.thecodingdad.moonlightlauncher.data.storage.StorageKind
import io.github.thecodingdad.moonlightlauncher.platform.NetworkStatus
import io.github.thecodingdad.moonlightlauncher.platform.PlatformInfo
import io.github.thecodingdad.moonlightlauncher.platform.SecretBackend
import io.github.thecodingdad.moonlightlauncher.platform.UrlOpener
import io.github.thecodingdad.moonlightlauncher.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import java.awt.Desktop
import java.io.File
import java.net.InetAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

/** The launcher's data folder: %LOCALAPPDATA%\MoonlightLauncher on Windows, ~/.local/share elsewhere. */
fun dataDirectory(): File {
    val base = if (isWindows) {
        System.getenv("LOCALAPPDATA")?.let(::File) ?: File(System.getProperty("user.home"), "AppData/Local")
    } else {
        System.getenv("XDG_DATA_HOME")?.let(::File) ?: File(System.getProperty("user.home"), ".local/share")
    }
    return File(base, if (isWindows) "MoonlightLauncher" else "moonlight-launcher").apply { mkdirs() }
}

class DesktopPlatformInfo(override val filesDir: File) : PlatformInfo {
    override val deviceName: String
        get() = System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() }
            ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
            ?: "PC"

    override val versionName: String
        get() = DesktopPlatformInfo::class.java.`package`?.implementationVersion ?: "dev"

    override fun storageAreas(): List<StorageArea> {
        val db = File(filesDir, "launcher.db")
        val dbFiles = listOf(db, File(db.path + "-wal"), File(db.path + "-shm"))
        return listOf(
            StorageArea(StorageKind.DATABASE, dbFiles),
            // Settings, hosts, profiles, credentials: the files at the top of the data folder.
            StorageArea(StorageKind.SETTINGS, filesDir.listFiles { f -> f.isFile && f !in dbFiles }.orEmpty().toList()),
            StorageArea(StorageKind.CACHE, listOf(File(filesDir, "cache/proxy"))),
            StorageArea(StorageKind.LOGS, listOf(File(filesDir, "logs"))),
            StorageArea(StorageKind.STREAMING, listOf(io.github.thecodingdad.moonlightlauncher.desktop.stream.MoonlightQt.locate().dir)),
            StorageArea(StorageKind.HOST_ROLE, listOf(File(filesDir, "covers"), File(filesDir, "backups"))),
            // Covers of own games (taken over from Playnite); referenced from the settings, not cleaned up.
            StorageArea(StorageKind.OWN_IMAGES, listOf(File(filesDir, "custom-covers"))),
        )
    }
}

/** Web pages in the default browser. */
object BrowserOpener : UrlOpener {
    override fun open(url: String) {
        runCatching { Desktop.getDesktop().browse(URI(url)) }.onFailure { Log.w("Can't open $url", it) }
    }
}

/** A PC is on a fixed line or Wi-Fi; metered connections are not reported by the JVM. */
object DesktopNetworkStatus : NetworkStatus {
    override fun metered(): Flow<Boolean> = flowOf(false)
}

/**
 * Credentials in a JSON file in the data folder, each value encrypted: with Windows DPAPI (bound
 * to the user account) on Windows, else with AES-GCM and a key file only the user can read.
 */
class DesktopSecretBackend(dir: File) : SecretBackend {
    private val file = File(dir, "secrets.json")
    private val json = Json { prettyPrint = true }
    private val cipher: ValueCipher = if (isWindows) DpapiCipher else KeyFileCipher(File(dir, "secrets.key"))
    private val values: MutableMap<String, String> = load()

    @Synchronized
    override fun get(key: String): String? {
        val stored = values[key] ?: return null
        return runCatching { String(cipher.decrypt(Base64.getDecoder().decode(stored)), Charsets.UTF_8) }
            .onFailure { Log.w("Secret $key unreadable", it) }.getOrNull()
    }

    @Synchronized
    override fun put(key: String, value: String?) {
        if (value.isNullOrEmpty()) values.remove(key)
        else values[key] = Base64.getEncoder().encodeToString(cipher.encrypt(value.toByteArray(Charsets.UTF_8)))
        save()
    }

    @Synchronized
    override fun keys(): Set<String> = values.keys.toSet()

    private fun load(): MutableMap<String, String> =
        runCatching { json.decodeFromString<Map<String, String>>(file.readText()).toMutableMap() }.getOrDefault(mutableMapOf())

    private fun save() {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(values.toMap()))
        restrictToOwner(tmp)
        Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    private interface ValueCipher {
        fun encrypt(plain: ByteArray): ByteArray
        fun decrypt(data: ByteArray): ByteArray
    }

    private object DpapiCipher : ValueCipher {
        override fun encrypt(plain: ByteArray): ByteArray = com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(plain)
        override fun decrypt(data: ByteArray): ByteArray = com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(data)
    }

    private class KeyFileCipher(private val keyFile: File) : ValueCipher {
        private val key by lazy {
            if (!keyFile.exists()) {
                keyFile.writeBytes(ByteArray(32).also { SecureRandom().nextBytes(it) })
                restrictToOwner(keyFile)
            }
            SecretKeySpec(keyFile.readBytes(), "AES")
        }

        override fun encrypt(plain: ByteArray): ByteArray {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
            return iv + c.doFinal(plain)
        }

        override fun decrypt(data: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 0, 12)) }
            return c.doFinal(data, 12, data.size - 12)
        }
    }
}

/** Only the owner may read the file (no-op where the file system has no POSIX permissions). */
fun restrictToOwner(file: File) = io.github.thecodingdad.moonlightlauncher.host.restrictToOwner(file)
