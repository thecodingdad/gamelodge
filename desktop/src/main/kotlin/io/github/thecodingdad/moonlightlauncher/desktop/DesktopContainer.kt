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

    override val hosts = NoHosts
    override val hostActions = NoHostActions
    override val profiles = BaseProfileOnly
    override val emulators = NoEmulators
    override val vpn = NoVpn

    /** The PC's own games from the installed stores. */
    val pcLibrary: io.github.thecodingdad.moonlightlauncher.desktop.games.PcLibrary by lazy { io.github.thecodingdad.moonlightlauncher.desktop.games.PcLibrary(http) }

    /** Called when a game started from the launcher has ended (the window comes back to the front). */
    @Volatile var onGameEnded: () -> Unit = {}

    override val servers: ServerRepository by lazy { ServerRepository(platform, http, scope) { StoredClientIdentity(secrets) } }
    override val companionFeed: CompanionFeed by lazy {
        CompanionFeed(dataDir, servers) { metadata.isEnabled(MetadataSources.idOf(it)) }
    }
    override val library: LibraryRepository by lazy {
        LibraryRepository(database, LibrarySync(database), pcLibrary, hosts, scope, companionFeed)
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
        LaunchCoordinator(hosts, hostActions, { hostPower }, { NoAutoSleep }, profiles, playtime, settings, { ha }, scope, DesktopLaunches(pcLibrary, playtime, scope) { onGameEnded() })
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
