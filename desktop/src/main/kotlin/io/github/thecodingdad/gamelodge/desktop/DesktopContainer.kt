package io.github.thecodingdad.gamelodge.desktop

import io.github.thecodingdad.gamelodge.ui.screens.settings.layoutFor
import kotlinx.coroutines.flow.first
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.thecodingdad.gamelodge.LauncherContainer
import io.github.thecodingdad.gamelodge.data.artwork.ArtworkStore
import io.github.thecodingdad.gamelodge.data.companion.ClientIdentity
import io.github.thecodingdad.gamelodge.data.companion.CompanionFeed
import io.github.thecodingdad.gamelodge.data.companion.ServerRepository
import io.github.thecodingdad.gamelodge.data.db.LauncherDatabase
import io.github.thecodingdad.gamelodge.data.library.FilterRepository
import io.github.thecodingdad.gamelodge.data.library.LibraryRepository
import io.github.thecodingdad.gamelodge.data.library.LibrarySync
import io.github.thecodingdad.gamelodge.data.library.PlaytimeTracker
import io.github.thecodingdad.gamelodge.data.settings.SecretStore
import io.github.thecodingdad.gamelodge.data.settings.SettingsRepository
import io.github.thecodingdad.gamelodge.ha.HaAuth
import io.github.thecodingdad.gamelodge.ha.HaClient
import io.github.thecodingdad.gamelodge.ha.HaRepository
import io.github.thecodingdad.gamelodge.metadata.MetadataService
import io.github.thecodingdad.gamelodge.metadata.MetadataSources
import io.github.thecodingdad.gamelodge.stream.HostPower
import io.github.thecodingdad.gamelodge.stream.LaunchCoordinator
import io.github.thecodingdad.gamelodge.system.ExternalDisplayInfo
import io.github.thecodingdad.gamelodge.vpn.NoVpn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import okhttp3.OkHttpClient
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date
import java.util.concurrent.TimeUnit

/** Everything the desktop launcher runs on; one per process. */
class DesktopContainer(dataDir: File) : LauncherContainer {
    override val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override val platform = DesktopPlatformInfo(dataDir)

    override val database: LauncherDatabase by lazy {
        Room.databaseBuilder<LauncherDatabase>(File(dataDir, "launcher.db").absolutePath)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .addMigrations(MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14)
            .build()
    }
    override val settings: SettingsRepository by lazy {
        SettingsRepository(PreferenceDataStoreFactory.create(produceFile = { File(dataDir, "launcher_settings.preferences_pb") }))
    }
    override val secrets: SecretStore by lazy { SecretStore(DesktopSecretBackend(dataDir)) }
    override val keySync by lazy { io.github.thecodingdad.gamelodge.data.sync.KeySync(secrets).also { it.onChanged = { metadata.refreshPending() } } }

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** This launcher's GameStream identity (shared with the bundled Moonlight-Qt). */
    val streamIdentity: io.github.thecodingdad.gamelodge.desktop.stream.StreamIdentity by lazy {
        io.github.thecodingdad.gamelodge.desktop.stream.StreamIdentity.loadOrCreate(secrets)
    }
    val desktopHosts: io.github.thecodingdad.gamelodge.desktop.stream.DesktopHosts by lazy {
        io.github.thecodingdad.gamelodge.desktop.stream.DesktopHosts(dataDir, { streamIdentity }, http, { platform.deviceName }, scope)
    }
    override val hosts: io.github.thecodingdad.gamelodge.data.remote.HostRepository get() = desktopHosts
    override val hostActions: io.github.thecodingdad.gamelodge.data.remote.HostActions get() = desktopHosts
    override val profiles: io.github.thecodingdad.gamelodge.desktop.stream.DesktopProfiles by lazy {
        io.github.thecodingdad.gamelodge.desktop.stream.DesktopProfiles(dataDir)
    }

    override val emulators = NoEmulators
    override val vpn = NoVpn

    /** The PC's own games from the installed stores. */
    val pcLibrary: io.github.thecodingdad.gamelodge.desktop.games.PcLibrary by lazy {
        io.github.thecodingdad.gamelodge.desktop.games.PcLibrary(
            http, io.github.thecodingdad.gamelodge.desktop.games.SteamAccount(secrets), { disabledSources },
            listOf(
                io.github.thecodingdad.gamelodge.desktop.emulation.RomScanner { pcEmulation },
                io.github.thecodingdad.gamelodge.desktop.games.CustomGameScanner { customGames },
            ),
            { launchOverrides },
        )
    }

    /** The PC as host for other launchers: their API, pairing, paired devices, web UI. */
    val hostRole: io.github.thecodingdad.gamelodge.desktop.host.HostRole by lazy {
        io.github.thecodingdad.gamelodge.desktop.host.HostRole(dataDir, secrets) { platform.deviceName }.also { role ->
            // Paired devices may look up metadata with this launcher's own keys (if switched on).
            val keys = object : io.github.thecodingdad.gamelodge.host.MetadataKeys {
                override val steamGridDbKey get() = secrets.get(SecretStore.Keys.SGDB_API_KEY)
                override val igdbClientId get() = secrets.get(SecretStore.Keys.IGDB_CLIENT_ID)
                override val igdbClientSecret get() = secrets.get(SecretStore.Keys.IGDB_CLIENT_SECRET)
            }
            role.proxy = io.github.thecodingdad.gamelodge.host.MetadataProxy(
                http, keys, io.github.thecodingdad.gamelodge.host.ResponseCache(File(dataDir, "cache/proxy")),
            )
        }
    }

    /** Play data of the PC's games, shared with its paired launchers (docs/PLAYDATA_SYNC_PLAN.md). */
    val playDataHub: io.github.thecodingdad.gamelodge.data.sync.PlayDataHub by lazy {
        io.github.thecodingdad.gamelodge.data.sync.PlayDataHub(database, { hostGameIds() })
    }

    private suspend fun hostGameIds(): Set<String> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { hostGames.games().games.map { it.id }.toSet() }

    /** This PC as a device of its own play data store (while it is a host for other launchers). */
    private suspend fun ownPlayDataHost(): io.github.thecodingdad.gamelodge.data.sync.SyncHost? {
        if (!hostRole.settings.value.serverEnabled) return null
        val ids = hostGameIds()
        val targets = database.libraryDao().getAllTargets()
            .filter { it.type == io.github.thecodingdad.gamelodge.data.db.TargetType.LOCAL && it.packageName in ids }
        val hub = playDataHub
        return io.github.thecodingdad.gamelodge.data.sync.SyncHost(
            OWN_HOST_KEY,
            object : io.github.thecodingdad.gamelodge.data.sync.PlayDataTransport {
                override suspend fun pull(after: Long) = hub.changesAfter(after)
                override suspend fun push(changes: io.github.thecodingdad.gamelodge.data.companion.PlayDataChanges) =
                    hub.apply(OWN_DEVICE_ID, platform.deviceName, changes)
            },
            targets.associate { it.packageName!! to it.itemId },
            targets.associate { it.id to it.packageName!! },
        )
    }

    /** The PC's games for the streaming host and paired launchers. */
    val hostGames: io.github.thecodingdad.gamelodge.desktop.host.LibraryHostGames by lazy {
        io.github.thecodingdad.gamelodge.desktop.host.LibraryHostGames(database, { id -> pcLibrary.entryFor(id, null) }, { metadata }, { hostRole.settings.value }, { hostSync }, http, pcLibrary::actions)
    }

    /** Puts the PC's games on the streaming host (Vibepollo …) with the launcher as runner. */
    val hostSync: io.github.thecodingdad.gamelodge.desktop.host.sync.HostSync by lazy {
        io.github.thecodingdad.gamelodge.desktop.host.sync.HostSync(
            dataDir, http,
            connection = {
                val s = hostRole.settings.value
                io.github.thecodingdad.gamelodge.desktop.host.sync.HostConnection(s.hostUrl, s.hostType, s.hostUser, secrets.get(HOST_SECRET))
            },
            wanted = { hostGames.syncEntries().map { it.first } },
            incompleteSources = { pcLibrary.incompleteSources },
            runnerCommand = { Runner.command() },
        )
    }

    /** Kept current from the settings (the scan runs off the UI). */
    @Volatile var disabledSources: Set<String> = emptySet()
    /** Emulators and ROM folders, games added by hand (from the settings, kept current by main). */
    @Volatile var pcEmulation = io.github.thecodingdad.gamelodge.desktop.emulation.PcEmulation()
    @Volatile var customGames: List<io.github.thecodingdad.gamelodge.desktop.games.CustomGame> = emptyList()
    @Volatile var launchOverrides: Map<String, io.github.thecodingdad.gamelodge.desktop.games.LaunchOverride> = emptyMap()

    /** Called when a game started from the launcher has ended (the window comes back to the front). */
    @Volatile var onGameEnded: () -> Unit = {}

    override val servers: ServerRepository by lazy { ServerRepository(platform, http, scope) { StoredClientIdentity(secrets) } }
    override val companionFeed: CompanionFeed by lazy {
        CompanionFeed(dataDir, servers) { metadata.isEnabled(MetadataSources.idOf(it)) }
    }
    override val library: LibraryRepository by lazy {
        LibraryRepository(database, LibrarySync(database), pcLibrary, hosts, scope, companionFeed, settings).also {
            it.playData = io.github.thecodingdad.gamelodge.data.sync.PlayDataSync.create(database, secrets, { platform.deviceName }) { filters }
            it.keySync = keySync
            it.ownPlayDataHost = ::ownPlayDataHost
        }
    }
    override val filters: FilterRepository by lazy { FilterRepository(database, settings) }
    override val playtime: PlaytimeTracker by lazy { PlaytimeTracker(database) }
    override val metadata: MetadataService by lazy {
        MetadataService(database, secrets, settings, http, scope, servers).also {
            it.onCorrected = { id, reset -> library.pushCorrection(id, reset) }
            it.companionGame = { id -> library.companionGame(id) }
            it.onFullRefresh = { library.resyncHosts() }
        }
    }
    override val artwork: ArtworkStore by lazy { ArtworkStore(DesktopNetworkStatus, http, database.libraryDao(), settings, scope) }

    override val haAuth: HaAuth by lazy { HaAuth(BrowserOpener, http, secrets) }
    override val ha: HaRepository by lazy {
        val wsHttp = http.newBuilder().pingInterval(30, TimeUnit.SECONDS).build()
        HaRepository(HaClient(wsHttp, haAuth, { haAuth.baseUrl }, scope), haAuth, database)
    }
    override val hostPower: HostPower by lazy { HostPower(database.hostConfigDao(), hosts, hostActions, ha) }
    override val startActions: io.github.thecodingdad.gamelodge.stream.StartActionSource by lazy {
        io.github.thecodingdad.gamelodge.stream.StartActionSource({ companionFeed }) { _, target ->
            target.packageName?.let(pcLibrary::actions).orEmpty()
        }
    }

    override val streamControls: io.github.thecodingdad.gamelodge.stream.StreamControls by lazy {
        io.github.thecodingdad.gamelodge.desktop.stream.DesktopStreamControls(desktopHosts, desktopHosts)
    }

    override val mqtt by lazy { io.github.thecodingdad.gamelodge.ha.mqtt.MqttBridge(this) }

    override val launcher: LaunchCoordinator by lazy {
        LaunchCoordinator(hosts, hostActions, { hostPower }, { NoAutoSleep }, profiles, playtime, settings, { ha }, scope, DesktopLaunches(
            pcLibrary, playtime, scope, { launcher.ended(); onGameEnded(); scope.launch { library.syncPlayData() } }, desktopHosts,
            identity = { streamIdentity },
            streamOptions = { entry ->
                // Face buttons follow the launcher's layout (the game's own, else the one of the pad
                // that started it: Moonlight-Qt swaps them for every pad alike).
                val s = settings.settings.first()
                val pad = s.gamePads[entry.item.id]
                // A game that wants the mouse emulation on (or off) starts with it (one state with the launcher's).
                pad?.mouse?.let { io.github.thecodingdad.gamelodge.desktop.WindowsPointer.set(it) }
                val starter = io.github.thecodingdad.gamelodge.ui.screens.settings.ActivePad.key
                val nintendo = (pad?.layout ?: s.layoutFor(starter, starter?.let { io.github.thecodingdad.gamelodge.desktop.modelLayout(it) })) ==
                    io.github.thecodingdad.gamelodge.data.settings.ButtonLayout.NINTENDO
                profiles.activeOptions() + if (nintendo) "--swap-gamepad-buttons" else "--no-swap-gamepad-buttons"
            },
            onStreamEnded = {
                launcher.streamEnded()
                launcher.restoreProfile()
                launcher.ended()
                scope.launch {
                    playtime.finishOpenSession()
                    library.syncPlayData()
                }
            },
        )).also { it.streamNotices = io.github.thecodingdad.gamelodge.data.companion.StreamNotices(servers, scope) }
    }

    companion object {
        /** This PC's own play data store in the local copy, and its device id there. */
        const val OWN_HOST_KEY = "self"
        const val OWN_DEVICE_ID = "host"
        /** Vibepollo API token or Apollo / Sunshine password of the sync. */
        const val HOST_SECRET = "host_sync_secret"
    }

    /** A PC drives its monitors itself; there is no handheld screen to mirror. */
    override val backup: io.github.thecodingdad.gamelodge.data.backup.BackupManager by lazy {
        io.github.thecodingdad.gamelodge.data.backup.BackupManager(database, settings, secrets, profiles, platform.filesDir)
    }
    override val backupStorage: DesktopBackupStorage by lazy { DesktopBackupStorage(scope, settings) { backup } }
    override val data: io.github.thecodingdad.gamelodge.data.storage.DataMaintenance by lazy {
        io.github.thecodingdad.gamelodge.data.storage.DataMaintenance(platform.filesDir, database.libraryDao(), library, platform::storageAreas)
    }

    override fun externalDisplay(): Flow<ExternalDisplayInfo?> = flowOf(null)
}

/**
 * This launcher's key for paired companions and metadata servers: an EC P-256 key with a
 * self-signed certificate, kept in the encrypted secret store (DPAPI on Windows).
 */
class StoredClientIdentity(private val secrets: SecretStore) : ClientIdentity {
    override val privateKey: PrivateKey
    override val certificate: X509Certificate

    init {
        val storedKey = secrets.get(KEY)
        val storedCert = secrets.get(CERT)
        if (storedKey != null && storedCert != null) {
            privateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(storedKey)))
            certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(Base64.getDecoder().decode(storedCert).inputStream()) as X509Certificate
        } else {
            val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            val now = System.currentTimeMillis()
            val name = X500Name("CN=GameLodge")
            val holder = JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(now), Date(now - 86_400_000L), Date(now + 30L * 365 * 86_400_000L), name, pair.public,
            ).build(JcaContentSignerBuilder("SHA256withECDSA").build(pair.private))
            privateKey = pair.private
            certificate = JcaX509CertificateConverter().getCertificate(holder)
            secrets.put(KEY, Base64.getEncoder().encodeToString(privateKey.encoded))
            secrets.put(CERT, Base64.getEncoder().encodeToString(certificate.encoded))
        }
    }

    private companion object {
        const val KEY = "client_identity_key"
        const val CERT = "client_identity_cert"
    }
}

/** Desktop databases start at v8 (the first desktop version). */
internal val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
    override fun migrate(connection: androidx.sqlite.SQLiteConnection) {
        io.github.thecodingdad.gamelodge.data.db.SchemaChanges.V8_TO_V9.forEach { sql ->
            connection.prepare(sql).use { it.step() }
        }
    }
}

internal val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
    override fun migrate(connection: androidx.sqlite.SQLiteConnection) {
        io.github.thecodingdad.gamelodge.data.db.SchemaChanges.V9_TO_V10.forEach { sql ->
            connection.prepare(sql).use { it.step() }
        }
    }
}

internal val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
    override fun migrate(connection: androidx.sqlite.SQLiteConnection) {
        io.github.thecodingdad.gamelodge.data.db.SchemaChanges.V10_TO_V11.forEach { sql ->
            connection.prepare(sql).use { it.step() }
        }
    }
}

internal val MIGRATION_11_12 = object : androidx.room.migration.Migration(11, 12) {
    override fun migrate(connection: androidx.sqlite.SQLiteConnection) {
        io.github.thecodingdad.gamelodge.data.db.SchemaChanges.V11_TO_V12.forEach { sql ->
            connection.prepare(sql).use { it.step() }
        }
    }
}

internal val MIGRATION_12_13 = object : androidx.room.migration.Migration(12, 13) {
    override fun migrate(connection: androidx.sqlite.SQLiteConnection) {
        io.github.thecodingdad.gamelodge.data.db.SchemaChanges.V12_TO_V13.forEach { sql ->
            connection.prepare(sql).use { it.step() }
        }
    }
}

internal val MIGRATION_13_14 = object : androidx.room.migration.Migration(13, 14) {
    override fun migrate(connection: androidx.sqlite.SQLiteConnection) {
        io.github.thecodingdad.gamelodge.data.db.SchemaChanges.V13_TO_V14.forEach { sql ->
            connection.prepare(sql).use { it.step() }
        }
    }
}
