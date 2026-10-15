package io.github.thecodingdad.moonlightlauncher

import android.content.Context
import io.github.thecodingdad.moonlightlauncher.data.backup.BackupManager
import io.github.thecodingdad.moonlightlauncher.data.db.LauncherDatabase
import io.github.thecodingdad.moonlightlauncher.data.library.LibraryRepository
import io.github.thecodingdad.moonlightlauncher.data.library.LibrarySync
import io.github.thecodingdad.moonlightlauncher.data.library.PlaytimeTracker
import io.github.thecodingdad.moonlightlauncher.data.local.LocalAppScanner
import io.github.thecodingdad.moonlightlauncher.data.remote.ArtemisHostActions
import io.github.thecodingdad.moonlightlauncher.data.remote.ArtemisHostRepository
import io.github.thecodingdad.moonlightlauncher.data.settings.SecretStore
import io.github.thecodingdad.moonlightlauncher.data.settings.SettingsRepository
import io.github.thecodingdad.moonlightlauncher.stream.AutoSleep
import io.github.thecodingdad.moonlightlauncher.stream.HostPower
import io.github.thecodingdad.moonlightlauncher.stream.LaunchCoordinator
import io.github.thecodingdad.moonlightlauncher.ha.HaAuth
import io.github.thecodingdad.moonlightlauncher.ha.HaClient
import io.github.thecodingdad.moonlightlauncher.ha.HaRepository
import io.github.thecodingdad.moonlightlauncher.metadata.MetadataService
import io.github.thecodingdad.moonlightlauncher.vpn.VpnManager
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

/** Manual dependency container; one instance per process, owned by [LauncherApplication]. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val platform = io.github.thecodingdad.moonlightlauncher.platform.AndroidPlatformInfo(appContext)
    val database: LauncherDatabase by lazy { io.github.thecodingdad.moonlightlauncher.data.db.AndroidDatabase.create(appContext) }
    val settings: SettingsRepository by lazy { io.github.thecodingdad.moonlightlauncher.platform.AndroidStores.settings(appContext) }
    val secrets: SecretStore by lazy { io.github.thecodingdad.moonlightlauncher.platform.AndroidStores.secrets(appContext) }

    val localApps: LocalAppScanner by lazy { LocalAppScanner(appContext) }
    val hosts: ArtemisHostRepository by lazy { ArtemisHostRepository(appContext, scope) }

    val companionFeed: io.github.thecodingdad.moonlightlauncher.data.companion.CompanionFeed by lazy {
        io.github.thecodingdad.moonlightlauncher.data.companion.CompanionFeed(appContext.filesDir, servers) {
            metadata.isEnabled(io.github.thecodingdad.moonlightlauncher.metadata.MetadataSources.idOf(it))
        }
    }
    val library: LibraryRepository by lazy {
        LibraryRepository(database, LibrarySync(database, emulatorPackages = ::emulatorPackages), localApps, hosts, scope, companionFeed)
    }
    val playtime: PlaytimeTracker by lazy { PlaytimeTracker(database) }
    /** Packages of every known emulator (catalog and own ones); some real games share a package prefix (miHoYo). */
    private fun emulatorPackages(): Set<String> =
        (emulation.catalog.catalog.emulators.values.flatten().map { it.substringBefore('/') } + emulation.config.value.custom.map { it.packageName })
            .filterNot { it.startsWith("com.miHoYo.") }.toSet()

    val emulation: io.github.thecodingdad.moonlightlauncher.data.emulation.EmulationRepository by lazy {
        io.github.thecodingdad.moonlightlauncher.data.emulation.EmulationRepository(appContext, settings, library, scope)
    }
    val usage: io.github.thecodingdad.moonlightlauncher.data.library.UsageSync by lazy {
        io.github.thecodingdad.moonlightlauncher.data.library.UsageSync(appContext, database)
    }
    val filters: io.github.thecodingdad.moonlightlauncher.data.library.FilterRepository by lazy {
        io.github.thecodingdad.moonlightlauncher.data.library.FilterRepository(database, settings)
    }
    val controllerSettings: io.github.thecodingdad.moonlightlauncher.stream.ControllerOverrideStore by lazy {
        io.github.thecodingdad.moonlightlauncher.stream.ControllerOverrideStore(appContext)
    }
    val vpn: VpnManager by lazy { VpnManager(appContext, secrets, settings, scope) }
    val profiles: io.github.thecodingdad.moonlightlauncher.stream.StreamProfiles = io.github.thecodingdad.moonlightlauncher.stream.ArtemisStreamProfiles
    val launcher: LaunchCoordinator by lazy {
        LaunchCoordinator(
            hosts, hostActions, { hostPower }, { autoSleep }, profiles, playtime, settings, { ha }, scope,
            io.github.thecodingdad.moonlightlauncher.stream.AndroidLaunchPlatform(this),
        ).also { it.vpn = vpn }
    }
    val hostActions: ArtemisHostActions by lazy { ArtemisHostActions(hosts, library) }

    val artwork: io.github.thecodingdad.moonlightlauncher.data.artwork.ArtworkStore by lazy {
        io.github.thecodingdad.moonlightlauncher.data.artwork.ArtworkStore(io.github.thecodingdad.moonlightlauncher.platform.AndroidNetworkStatus(appContext), http, database.libraryDao(), settings, scope)
    }

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    val servers: io.github.thecodingdad.moonlightlauncher.data.companion.ServerRepository by lazy {
        io.github.thecodingdad.moonlightlauncher.data.companion.ServerRepository(platform, http, scope) { io.github.thecodingdad.moonlightlauncher.data.companion.KeystoreClientIdentity() }
    }
    val metadata: MetadataService by lazy {
        MetadataService(database, secrets, settings, http, scope, servers).also {
            it.onCorrected = { id, reset -> library.pushCorrection(id, reset) }
            it.companionGame = { id -> library.companionGame(id) }
            it.onFullRefresh = { library.resyncHosts() }
        }
    }

    val haAuth: HaAuth by lazy { HaAuth(io.github.thecodingdad.moonlightlauncher.platform.CustomTabOpener(appContext), http, secrets) }
    val ha: HaRepository by lazy {
        val wsHttp = http.newBuilder().pingInterval(30, TimeUnit.SECONDS).build()
        HaRepository(HaClient(wsHttp, haAuth, { haAuth.baseUrl }, scope), haAuth, database)
    }
    val hostPower: HostPower by lazy { HostPower(database.hostConfigDao(), hosts, hostActions, ha) }
    val autoSleep: AutoSleep by lazy { io.github.thecodingdad.moonlightlauncher.stream.WorkManagerAutoSleep(appContext, database.hostConfigDao()) }
    val backup: BackupManager by lazy { BackupManager(appContext, database, settings, secrets) }
}
