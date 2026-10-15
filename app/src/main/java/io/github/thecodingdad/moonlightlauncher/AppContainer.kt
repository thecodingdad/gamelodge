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
class AppContainer(context: Context) : LauncherContainer {
    val appContext: Context = context.applicationContext
    override val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val platform = io.github.thecodingdad.moonlightlauncher.platform.AndroidPlatformInfo(appContext)
    override val database: LauncherDatabase by lazy { io.github.thecodingdad.moonlightlauncher.data.db.AndroidDatabase.create(appContext) }
    override val settings: SettingsRepository by lazy { io.github.thecodingdad.moonlightlauncher.platform.AndroidStores.settings(appContext) }
    override val secrets: SecretStore by lazy { io.github.thecodingdad.moonlightlauncher.platform.AndroidStores.secrets(appContext) }

    val localApps: LocalAppScanner by lazy { LocalAppScanner(appContext) }
    override val hosts: ArtemisHostRepository by lazy { ArtemisHostRepository(appContext, scope) }

    override val companionFeed: io.github.thecodingdad.moonlightlauncher.data.companion.CompanionFeed by lazy {
        io.github.thecodingdad.moonlightlauncher.data.companion.CompanionFeed(appContext.filesDir, servers) {
            metadata.isEnabled(io.github.thecodingdad.moonlightlauncher.metadata.MetadataSources.idOf(it))
        }
    }
    override val library: LibraryRepository by lazy {
        LibraryRepository(database, LibrarySync(database, emulatorPackages = ::emulatorPackages), localApps, hosts, scope, companionFeed)
    }
    override val playtime: PlaytimeTracker by lazy { PlaytimeTracker(database) }
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
    override val filters: io.github.thecodingdad.moonlightlauncher.data.library.FilterRepository by lazy {
        io.github.thecodingdad.moonlightlauncher.data.library.FilterRepository(database, settings)
    }
    val controllerSettings: io.github.thecodingdad.moonlightlauncher.stream.ControllerOverrideStore by lazy {
        io.github.thecodingdad.moonlightlauncher.stream.ControllerOverrideStore(appContext)
    }
    override val vpn: VpnManager by lazy { VpnManager(appContext, secrets, settings, scope) }
    override val profiles: io.github.thecodingdad.moonlightlauncher.stream.StreamProfiles = io.github.thecodingdad.moonlightlauncher.stream.ArtemisStreamProfiles
    override val launcher: LaunchCoordinator by lazy {
        LaunchCoordinator(
            hosts, hostActions, { hostPower }, { autoSleep }, profiles, playtime, settings, { ha }, scope,
            io.github.thecodingdad.moonlightlauncher.stream.AndroidLaunchPlatform(this),
        ).also { it.vpn = vpn }
    }
    override val hostActions: ArtemisHostActions by lazy { ArtemisHostActions(hosts, library) }

    override val artwork: io.github.thecodingdad.moonlightlauncher.data.artwork.ArtworkStore by lazy {
        io.github.thecodingdad.moonlightlauncher.data.artwork.ArtworkStore(io.github.thecodingdad.moonlightlauncher.platform.AndroidNetworkStatus(appContext), http, database.libraryDao(), settings, scope)
    }

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    override val servers: io.github.thecodingdad.moonlightlauncher.data.companion.ServerRepository by lazy {
        io.github.thecodingdad.moonlightlauncher.data.companion.ServerRepository(platform, http, scope) { io.github.thecodingdad.moonlightlauncher.data.companion.KeystoreClientIdentity() }
    }
    override val metadata: MetadataService by lazy {
        MetadataService(database, secrets, settings, http, scope, servers).also {
            it.onCorrected = { id, reset -> library.pushCorrection(id, reset) }
            it.companionGame = { id -> library.companionGame(id) }
            it.onFullRefresh = { library.resyncHosts() }
        }
    }

    override val haAuth: HaAuth by lazy { HaAuth(io.github.thecodingdad.moonlightlauncher.platform.CustomTabOpener(appContext), http, secrets) }
    override val ha: HaRepository by lazy {
        val wsHttp = http.newBuilder().pingInterval(30, TimeUnit.SECONDS).build()
        HaRepository(HaClient(wsHttp, haAuth, { haAuth.baseUrl }, scope), haAuth, database)
    }
    override val hostPower: HostPower by lazy { HostPower(database.hostConfigDao(), hosts, hostActions, ha) }
    val autoSleep: AutoSleep by lazy { io.github.thecodingdad.moonlightlauncher.stream.WorkManagerAutoSleep(appContext, database.hostConfigDao()) }
    val backup: BackupManager by lazy { BackupManager(appContext, database, settings, secrets) }

    override val emulators get() = emulation.options

    override fun externalDisplay() = io.github.thecodingdad.moonlightlauncher.system.ExternalDisplay.observe(appContext)
}
