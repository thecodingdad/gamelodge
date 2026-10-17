package io.github.thecodingdad.gamelodge.desktop

import io.github.thecodingdad.gamelodge.data.storage.StorageArea
import io.github.thecodingdad.gamelodge.data.storage.StorageKind
import io.github.thecodingdad.gamelodge.platform.NetworkStatus
import io.github.thecodingdad.gamelodge.platform.PlatformInfo
import io.github.thecodingdad.gamelodge.platform.SecretBackend
import io.github.thecodingdad.gamelodge.platform.UrlOpener
import io.github.thecodingdad.gamelodge.util.Log
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

/** The launcher's data folder: %LOCALAPPDATA%\GameLodge on Windows, ~/.local/share elsewhere. */
fun dataDirectory(): File {
    val base = if (isWindows) {
        System.getenv("LOCALAPPDATA")?.let(::File) ?: File(System.getProperty("user.home"), "AppData/Local")
    } else {
        System.getenv("XDG_DATA_HOME")?.let(::File) ?: File(System.getProperty("user.home"), ".local/share")
    }
    val dir = File(base, if (isWindows) "GameLodge" else "gamelodge")
    // Named "Moonlight Launcher" before: its data moves over once.
    val old = File(base, if (isWindows) "MoonlightLauncher" else "moonlight-launcher")
    if (!dir.exists() && old.isDirectory) old.renameTo(dir)
    return dir.apply { mkdirs() }
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
            StorageArea(StorageKind.STREAMING, listOf(io.github.thecodingdad.gamelodge.desktop.stream.MoonlightQt.locate().dir)),
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

/** Secrets of the PC: Windows DPAPI (bound to the user account), a key file elsewhere. */
fun DesktopSecretBackend(dir: File): SecretBackend = io.github.thecodingdad.gamelodge.host.FileSecretBackend(
    dir, if (isWindows) DpapiCipher else io.github.thecodingdad.gamelodge.host.KeyFileCipher(File(dir, "secrets.key")),
)

private object DpapiCipher : io.github.thecodingdad.gamelodge.host.ValueCipher {
    override fun encrypt(plain: ByteArray): ByteArray = com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(plain)
    override fun decrypt(data: ByteArray): ByteArray = com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(data)
}

/** Only the owner may read the file (no-op where the file system has no POSIX permissions). */
fun restrictToOwner(file: File) = io.github.thecodingdad.gamelodge.host.restrictToOwner(file)
