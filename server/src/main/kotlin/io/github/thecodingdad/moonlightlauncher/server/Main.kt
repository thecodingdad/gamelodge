package io.github.thecodingdad.moonlightlauncher.server

import io.github.thecodingdad.moonlightlauncher.data.companion.Fingerprint
import io.github.thecodingdad.moonlightlauncher.data.settings.SecretStore
import io.github.thecodingdad.moonlightlauncher.host.AdminAuth
import io.github.thecodingdad.moonlightlauncher.host.ApiServer
import io.github.thecodingdad.moonlightlauncher.host.DeviceRegistry
import io.github.thecodingdad.moonlightlauncher.host.FileSecretBackend
import io.github.thecodingdad.moonlightlauncher.host.MetadataProxy
import io.github.thecodingdad.moonlightlauncher.host.PairingManager
import io.github.thecodingdad.moonlightlauncher.host.Permissions
import io.github.thecodingdad.moonlightlauncher.host.ResponseCache
import io.github.thecodingdad.moonlightlauncher.host.ServerIdentity
import io.github.thecodingdad.moonlightlauncher.host.WebAdmin
import io.github.thecodingdad.moonlightlauncher.util.Log
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/*
 * Moonlight Metadata Server: SteamGridDB/IGDB for paired launchers.
 *   MLSERVER_DATA         data folder (default /data, else ./data)
 *   MLSERVER_API_PORT     client API, mutual TLS (default 47900)
 *   MLSERVER_ADMIN_PORT   web UI (default 47901)
 *   MLSERVER_ADMIN_PUBLIC "true": web UI also from public addresses (default: private ranges / VPN only)
 * "mlserver reset-admin" clears the admin account and prints a new setup code.
 */
fun main(args: Array<String>) {
    val dataDir = System.getenv("MLSERVER_DATA")?.takeIf { it.isNotBlank() }?.let(::File)
        ?: File("/data").takeIf { it.isDirectory } ?: File("data").absoluteFile
    dataDir.mkdirs()
    val apiPort = port("MLSERVER_API_PORT", 47900)
    val adminPort = port("MLSERVER_ADMIN_PORT", 47901)
    val adminPublic = System.getenv("MLSERVER_ADMIN_PUBLIC").equals("true", ignoreCase = true)

    val secrets = SecretStore(FileSecretBackend(dataDir))
    val auth = AdminAuth(File(dataDir, "admin.json"), secrets)
    if ("reset-admin" in args) {
        println("Admin account cleared. Setup code: ${auth.reset()} (valid 24 h)")
        return
    }

    val settings = ServerSettings(File(dataDir, "settings.json"), secrets)
    val identity = ServerIdentity.loadOrCreate(secrets, ServerSettings.DEFAULT_NAME)
    val devices = DeviceRegistry(File(dataDir, "devices.json"))
    val pairing = PairingManager(devices, { identity.fingerprint }, setOf(Permissions.METADATA))
    val version = ServerSettings::class.java.`package`?.implementationVersion ?: "dev"
    val http = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", "MoonlightMetadataServer/$version").build()) }
        .build()
    val proxy = MetadataProxy(http, settings, ResponseCache(File(dataDir, "cache")))

    ApiServer(identity, devices, pairing, { settings.name }, proxy = { proxy }, kind = ApiServer.KIND_METADATA).start(apiPort)
    WebAdmin(
        identity, auth, devices, pairing,
        permissionChoices = listOf(Permissions.METADATA), product = ServerSettings.DEFAULT_NAME,
        serverSettings = settings, allowPublic = adminPublic,
    ).start(adminPort)

    Log.i("Moonlight Metadata Server $version, data in ${dataDir.path}")
    Log.i("Client API on port $apiPort (mutual TLS), web UI on https://<this host>:$adminPort" + if (adminPublic) "" else " (private addresses only)")
    Log.i("Server certificate fingerprint: ${Fingerprint.pretty(identity.fingerprint)}")
    if (auth.needsSetup) Log.w("Not set up yet. Setup code: ${auth.issueSetupCode()} (valid 24 h; open the web UI to set the admin password)")
    Thread.currentThread().join()
}

private fun port(name: String, fallback: Int): Int = System.getenv(name)?.toIntOrNull()?.takeIf { it in 1..65535 } ?: fallback
