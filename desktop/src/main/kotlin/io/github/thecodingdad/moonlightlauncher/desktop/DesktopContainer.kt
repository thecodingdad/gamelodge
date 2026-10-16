package io.github.thecodingdad.moonlightlauncher.desktop

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.thecodingdad.moonlightlauncher.LauncherContainer
import io.github.thecodingdad.moonlightlauncher.data.artwork.ArtworkStore
import io.github.thecodingdad.moonlightlauncher.data.companion.ClientIdentity
import io.github.thecodingdad.moonlightlauncher.data.companion.CompanionFeed
import io.github.thecodingdad.moonlightlauncher.data.companion.ServerRepository
import io.github.thecodingdad.moonlightlauncher.data.db.LauncherDatabase
import io.github.thecodingdad.moonlightlauncher.data.library.FilterRepository
import io.github.thecodingdad.moonlightlauncher.data.library.LibraryRepository
import io.github.thecodingdad.moonlightlauncher.data.library.LibrarySync
import io.github.thecodingdad.moonlightlauncher.data.library.PlaytimeTracker
import io.github.thecodingdad.moonlightlauncher.data.settings.SecretStore
import io.github.thecodingdad.moonlightlauncher.data.settings.SettingsRepository
import io.github.thecodingdad.moonlightlauncher.ha.HaAuth
import io.github.thecodingdad.moonlightlauncher.ha.HaClient
import io.github.thecodingdad.moonlightlauncher.ha.HaRepository
import io.github.thecodingdad.moonlightlauncher.metadata.MetadataService
import io.github.thecodingdad.moonlightlauncher.metadata.MetadataSources
import io.github.thecodingdad.moonlightlauncher.stream.HostPower
import io.github.thecodingdad.moonlightlauncher.stream.LaunchCoordinator
import io.github.thecodingdad.moonlightlauncher.system.ExternalDisplayInfo
import io.github.thecodingdad.moonlightlauncher.vpn.NoVpn
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
            .build()
    }
    override val settings: SettingsRepository by lazy {
        SettingsRepository(PreferenceDataStoreFactory.create(produceFile = { File(dataDir, "launcher_settings.preferences_pb") }))
    }
    override val secrets: SecretStore by lazy { SecretStore(DesktopSecretBackend(dataDir)) }

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** This launcher's GameStream identity (shared with the bundled Moonlight-Qt). */
    val streamIdentity: io.github.thecodingdad.moonlightlauncher.desktop.stream.StreamIdentity by lazy {
        io.github.thecodingdad.moonlightlauncher.desktop.stream.StreamIdentity.loadOrCreate(secrets)
    }
    val desktopHosts: io.github.thecodingdad.moonlightlauncher.desktop.stream.DesktopHosts by lazy {
        io.github.thecodingdad.moonlightlauncher.desktop.stream.DesktopHosts(dataDir, { streamIdentity }, http, { platform.deviceName }, scope)
    }
    override val hosts: io.github.thecodingdad.moonlightlauncher.data.remote.HostRepository get() = desktopHosts
    override val hostActions: io.github.thecodingdad.moonlightlauncher.data.remote.HostActions get() = desktopHosts
    override val profiles: io.github.thecodingdad.moonlightlauncher.desktop.stream.DesktopProfiles by lazy {
        io.github.thecodingdad.moonlightlauncher.desktop.stream.DesktopProfiles(dataDir)
    }

    override val emulators = NoEmulators
    override val vpn = NoVpn

    /** The PC's own games from the installed stores. */
    val pcLibrary: io.github.thecodingdad.moonlightlauncher.desktop.games.PcLibrary by lazy {
        io.github.thecodingdad.moonlightlauncher.desktop.games.PcLibrary(http, io.github.thecodingdad.moonlightlauncher.desktop.games.SteamAccount(secrets)) {
            disabledSources
        }
    }

    /** The PC as host for other launchers: their API, pairing, paired devices, web UI. */
    val hostRole: io.github.thecodingdad.moonlightlauncher.desktop.host.HostRole by lazy {
        io.github.thecodingdad.moonlightlauncher.desktop.host.HostRole(dataDir, secrets) { platform.deviceName }.also { role ->
            // Paired devices may look up metadata with this launcher's own keys (if switched on).
            val keys = object : io.github.thecodingdad.moonlightlauncher.desktop.host.MetadataKeys {
                override val steamGridDbKey get() = secrets.get(SecretStore.Keys.SGDB_API_KEY)
                override val igdbClientId get() = secrets.get(SecretStore.Keys.IGDB_CLIENT_ID)
                override val igdbClientSecret get() = secrets.get(SecretStore.Keys.IGDB_CLIENT_SECRET)
            }
            role.proxy = io.github.thecodingdad.moonlightlauncher.desktop.host.MetadataProxy(
                http, keys, io.github.thecodingdad.moonlightlauncher.desktop.host.ResponseCache(File(dataDir, "cache/proxy")),
            )
        }
    }

    /** The PC's games for the streaming host and paired launchers. */
    val hostGames: io.github.thecodingdad.moonlightlauncher.desktop.host.LibraryHostGames by lazy {
        io.github.thecodingdad.moonlightlauncher.desktop.host.LibraryHostGames(database, pcLibrary::entry, { metadata }, { hostRole.settings.value }, { hostSync }, http)
    }

    /** Puts the PC's games on the streaming host (Vibepollo …) with the launcher as runner. */
    val hostSync: io.github.thecodingdad.moonlightlauncher.desktop.host.sync.HostSync by lazy {
        io.github.thecodingdad.moonlightlauncher.desktop.host.sync.HostSync(
            dataDir, http,
            connection = {
                val s = hostRole.settings.value
                io.github.thecodingdad.moonlightlauncher.desktop.host.sync.HostConnection(s.hostUrl, s.hostType, s.hostUser, secrets.get(HOST_SECRET))
            },
            wanted = { hostGames.syncEntries().map { it.first } },
            incompleteSources = { pcLibrary.incompleteSources },
            runnerCommand = { Runner.command() },
        )
    }

    /** Kept current from the settings (the scan runs off the UI). */
    @Volatile var disabledSources: Set<String> = emptySet()

    /** Called when a game started from the launcher has ended (the window comes back to the front). */
    @Volatile var onGameEnded: () -> Unit = {}

    override val servers: ServerRepository by lazy { ServerRepository(platform, http, scope) { StoredClientIdentity(secrets) } }
    override val companionFeed: CompanionFeed by lazy {
        CompanionFeed(dataDir, servers) { metadata.isEnabled(MetadataSources.idOf(it)) }
    }
    override val library: LibraryRepository by lazy {
        LibraryRepository(database, LibrarySync(database), pcLibrary, hosts, scope, companionFeed, settings)
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
    override val launcher: LaunchCoordinator by lazy {
        LaunchCoordinator(hosts, hostActions, { hostPower }, { NoAutoSleep }, profiles, playtime, settings, { ha }, scope, DesktopLaunches(
            pcLibrary, playtime, scope, { onGameEnded() }, desktopHosts,
            identity = { streamIdentity },
            streamOptions = { profiles.activeOptions() },
            onStreamEnded = {
                launcher.restoreProfile()
                scope.launch { playtime.finishOpenSession() }
            },
        ))
    }

    companion object {
        /** Vibepollo API token or Apollo / Sunshine password of the sync. */
        const val HOST_SECRET = "host_sync_secret"
    }

    /** A PC drives its monitors itself; there is no handheld screen to mirror. */
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
            val name = X500Name("CN=Moonlight Launcher")
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
