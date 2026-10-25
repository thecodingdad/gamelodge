package io.github.thecodingdad.gamelodge

import android.content.Context
import io.github.thecodingdad.gamelodge.data.backup.BackupManager
import io.github.thecodingdad.gamelodge.data.db.LauncherDatabase
import io.github.thecodingdad.gamelodge.data.library.LibraryRepository
import io.github.thecodingdad.gamelodge.data.library.LibrarySync
import io.github.thecodingdad.gamelodge.data.library.PlaytimeTracker
import io.github.thecodingdad.gamelodge.data.local.LocalAppScanner
import io.github.thecodingdad.gamelodge.data.remote.ArtemisHostActions
import io.github.thecodingdad.gamelodge.data.remote.ArtemisHostRepository
import io.github.thecodingdad.gamelodge.data.settings.SecretStore
import io.github.thecodingdad.gamelodge.data.settings.SettingsRepository
import io.github.thecodingdad.gamelodge.stream.AutoSleep
import io.github.thecodingdad.gamelodge.stream.HostPower
import io.github.thecodingdad.gamelodge.stream.LaunchCoordinator
import io.github.thecodingdad.gamelodge.ha.HaAuth
import io.github.thecodingdad.gamelodge.ha.HaClient
import io.github.thecodingdad.gamelodge.ha.HaRepository
import io.github.thecodingdad.gamelodge.metadata.MetadataService
import io.github.thecodingdad.gamelodge.vpn.VpnManager
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

/** Manual dependency container; one instance per process, owned by [LauncherApplication]. */
class AppContainer(context: Context) : LauncherContainer {
    val appContext: Context = context.applicationContext
    override val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val platform = io.github.thecodingdad.gamelodge.platform.AndroidPlatformInfo(appContext)
    override val database: LauncherDatabase by lazy { io.github.thecodingdad.gamelodge.data.db.AndroidDatabase.create(appContext) }
    override val settings: SettingsRepository by lazy { io.github.thecodingdad.gamelodge.platform.AndroidStores.settings(appContext) }
    override val secrets: SecretStore by lazy { io.github.thecodingdad.gamelodge.platform.AndroidStores.secrets(appContext) }

    val localApps: LocalAppScanner by lazy { LocalAppScanner(appContext) }
    override val hosts: ArtemisHostRepository by lazy { ArtemisHostRepository(appContext, scope) }

    override val companionFeed: io.github.thecodingdad.gamelodge.data.companion.CompanionFeed by lazy {
        io.github.thecodingdad.gamelodge.data.companion.CompanionFeed(appContext.filesDir, servers) {
            metadata.isEnabled(io.github.thecodingdad.gamelodge.metadata.MetadataSources.idOf(it))
        }
    }
    override val library: LibraryRepository by lazy {
        LibraryRepository(database, LibrarySync(database, emulatorPackages = ::emulatorPackages), localApps, hosts, scope, companionFeed, settings)
    }
    override val playtime: PlaytimeTracker by lazy { PlaytimeTracker(database) }
    /** Packages of every known emulator (catalog and own ones); some real games share a package prefix (miHoYo). */
    private fun emulatorPackages(): Set<String> =
        (emulation.catalog.catalog.emulators.values.flatten().map { it.substringBefore('/') } + emulation.config.value.custom.map { it.packageName })
            .filterNot { it.startsWith("com.miHoYo.") }.toSet()

    val emulation: io.github.thecodingdad.gamelodge.data.emulation.EmulationRepository by lazy {
        io.github.thecodingdad.gamelodge.data.emulation.EmulationRepository(appContext, settings, library, scope)
    }
    val usage: io.github.thecodingdad.gamelodge.data.library.UsageSync by lazy {
        io.github.thecodingdad.gamelodge.data.library.UsageSync(appContext, database)
    }
    override val filters: io.github.thecodingdad.gamelodge.data.library.FilterRepository by lazy {
        io.github.thecodingdad.gamelodge.data.library.FilterRepository(database, settings)
    }
    val controllerSettings: io.github.thecodingdad.gamelodge.stream.ControllerOverrideStore by lazy {
        io.github.thecodingdad.gamelodge.stream.ControllerOverrideStore(appContext)
    }
    override val vpn: VpnManager by lazy {
        VpnManager(appContext, secrets, settings, scope).also { v ->
            v.hostChoice = { uuid -> hostPower.config(uuid).vpnTunnel }
            v.hostAddresses = { uuid ->
                hosts.details(uuid)?.let { d -> listOfNotNull(d.localAddress, d.manualAddress, d.remoteAddress).map { it.address } }.orEmpty()
            }
        }
    }
    override val profiles: io.github.thecodingdad.gamelodge.stream.StreamProfiles = io.github.thecodingdad.gamelodge.stream.ArtemisStreamProfiles(appContext)
    override val streamControls: io.github.thecodingdad.gamelodge.stream.StreamControls by lazy {
        io.github.thecodingdad.gamelodge.stream.AndroidStreamControls(launcher)
    }

    override val launcher: LaunchCoordinator by lazy {
        LaunchCoordinator(
            hosts, hostActions, { hostPower }, { autoSleep }, profiles, playtime, settings, { ha }, scope,
            io.github.thecodingdad.gamelodge.stream.AndroidLaunchPlatform(this),
        ).also {
            it.vpn = vpn
            it.gameActions.delayed = io.github.thecodingdad.gamelodge.stream.WorkManagerGameActions(appContext)
            it.streamNotices = io.github.thecodingdad.gamelodge.data.companion.StreamNotices(servers, scope)
        }
    }
    override val hostActions: ArtemisHostActions by lazy { ArtemisHostActions(hosts) }

    override val artwork: io.github.thecodingdad.gamelodge.data.artwork.ArtworkStore by lazy {
        io.github.thecodingdad.gamelodge.data.artwork.ArtworkStore(io.github.thecodingdad.gamelodge.platform.AndroidNetworkStatus(appContext), http, database.libraryDao(), settings, scope)
    }

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    override val servers: io.github.thecodingdad.gamelodge.data.companion.ServerRepository by lazy {
        io.github.thecodingdad.gamelodge.data.companion.ServerRepository(platform, http, scope) { io.github.thecodingdad.gamelodge.data.companion.KeystoreClientIdentity() }
    }
    override val metadata: MetadataService by lazy {
        MetadataService(database, secrets, settings, http, scope, servers).also {
            it.onCorrected = { id, reset -> library.pushCorrection(id, reset) }
            it.companionGame = { id -> library.companionGame(id) }
            it.onFullRefresh = { library.resyncHosts() }
        }
    }

    override val haAuth: HaAuth by lazy { HaAuth(io.github.thecodingdad.gamelodge.platform.CustomTabOpener(appContext), http, secrets) }
    override val ha: HaRepository by lazy {
        val wsHttp = http.newBuilder().pingInterval(30, TimeUnit.SECONDS).build()
        HaRepository(HaClient(wsHttp, haAuth, { haAuth.baseUrl }, scope), haAuth, database)
    }
    override val hostPower: HostPower by lazy { HostPower(database.hostConfigDao(), hosts, hostActions, ha) }
    val autoSleep: AutoSleep by lazy { io.github.thecodingdad.gamelodge.stream.WorkManagerAutoSleep(appContext, database.hostConfigDao()) }
    override val backup: BackupManager by lazy {
        BackupManager(database, settings, secrets, io.github.thecodingdad.gamelodge.data.backup.ArtemisStreamBackup(appContext))
    }
    override val backupStorage: io.github.thecodingdad.gamelodge.data.backup.BackupStorage by lazy {
        io.github.thecodingdad.gamelodge.data.backup.SafBackupStorage(appContext)
    }
    override val data: io.github.thecodingdad.gamelodge.data.storage.DataMaintenance by lazy {
        io.github.thecodingdad.gamelodge.data.storage.DataMaintenance(platform.filesDir, database.libraryDao(), library, platform::storageAreas)
    }

    override val emulators get() = emulation.options

    override fun externalDisplay() = io.github.thecodingdad.gamelodge.system.ExternalDisplay.observe(appContext)
}
