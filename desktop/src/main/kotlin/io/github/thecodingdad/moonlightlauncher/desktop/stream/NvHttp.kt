package io.github.thecodingdad.moonlightlauncher.desktop.stream

import io.github.thecodingdad.moonlightlauncher.data.settings.SecretStore
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.IOException
import java.math.BigInteger
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import javax.xml.parsers.DocumentBuilderFactory

/**
 * This launcher's identity towards streaming hosts (the GameStream protocol): an RSA-2048
 * self-signed certificate and a unique id, the same that Moonlight-Qt gets in its settings, so
 * one pairing serves both. Kept in the encrypted secret store.
 */
class StreamIdentity(val certificate: X509Certificate, val privateKey: PrivateKey, val uniqueId: String) {
    val certificatePem: String get() = pem("CERTIFICATE", certificate.encoded)
    val privateKeyPem: String get() = pem("PRIVATE KEY", privateKey.encoded)

    companion object {
        private const val CERT = "stream_identity_cert"
        private const val KEY = "stream_identity_key"
        private const val ID = "stream_identity_uniqueid"

        fun loadOrCreate(secrets: SecretStore): StreamIdentity {
            val cert = secrets.get(CERT)
            val key = secrets.get(KEY)
            val id = secrets.get(ID)
            if (cert != null && key != null && id != null) {
                runCatching {
                    val c = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(cert))) as X509Certificate
                    val k = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(key)))
                    return StreamIdentity(c, k, id)
                }
            }
            val created = create()
            secrets.put(CERT, Base64.getEncoder().encodeToString(created.certificate.encoded))
            secrets.put(KEY, Base64.getEncoder().encodeToString(created.privateKey.encoded))
            secrets.put(ID, created.uniqueId)
            return created
        }

        fun create(): StreamIdentity {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()
            val now = System.currentTimeMillis()
            val name = X500Name("CN=NVIDIA GameStream Client")
            val holder = JcaX509v3CertificateBuilder(
                name, BigInteger(64, SecureRandom()).abs(), Date(now - 86_400_000L), Date(now + 20L * 365 * 86_400_000L), name, pair.public,
            ).build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private))
            val id = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            return StreamIdentity(JcaX509CertificateConverter().getCertificate(holder), pair.private, id)
        }

        fun pem(type: String, der: ByteArray) =
            "-----BEGIN $type-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END $type-----\n"

        fun parseCertificate(pem: String): X509Certificate =
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(pem.toByteArray())) as X509Certificate
    }
}

/**
 * Hosts (Sunshine and its forks, OpenSSL with client certificates) answer a resumed TLS session
 * with "internal_error": every connection does a full TLS 1.2 handshake (1.3 resumes through
 * tickets that arrive after the handshake), the session is dropped right after it.
 */
private class NoResumptionSocketFactory(private val base: javax.net.ssl.SSLSocketFactory) : javax.net.ssl.SSLSocketFactory() {
    private fun prepare(s: Socket): Socket = (s as javax.net.ssl.SSLSocket).apply {
        enabledProtocols = arrayOf("TLSv1.2")
        addHandshakeCompletedListener { it.session.invalidate() }
    }

    override fun getDefaultCipherSuites(): Array<String> = base.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = base.supportedCipherSuites
    override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean) = prepare(base.createSocket(s, host, port, autoClose))
    override fun createSocket(host: String?, port: Int) = prepare(base.createSocket(host, port))
    override fun createSocket(host: String?, port: Int, local: java.net.InetAddress?, localPort: Int) = prepare(base.createSocket(host, port, local, localPort))
    override fun createSocket(host: java.net.InetAddress?, port: Int) = prepare(base.createSocket(host, port))
    override fun createSocket(address: java.net.InetAddress?, port: Int, local: java.net.InetAddress?, localPort: Int) = prepare(base.createSocket(address, port, local, localPort))
    override fun createSocket(): Socket = prepare(base.createSocket())
}

/** What a host's serverinfo tells. */
data class ServerInfo(
    val name: String,
    val uuid: String,
    val mac: String?,
    val httpsPort: Int,
    val paired: Boolean,
    val runningAppId: Int,
    val appVersion: String,
    val localIp: String?,
    val permission: Int,
    val virtualDisplay: Boolean,
    val serverCommands: List<String>,
)

data class NvApp(val id: Int, val name: String, val uuid: String?, val hdr: Boolean)

class NvHttpException(val status: Int, message: String) : IOException(message)

/**
 * GameStream HTTP(S) API of a host (Sunshine, Apollo, Vibepollo): plain HTTP for serverinfo and
 * pairing, HTTPS with this launcher's client certificate for everything else. HTTPS trusts only
 * the host certificate pinned at pairing.
 */
class NvHttp(
    private val address: String,
    private val httpPort: Int,
    private val identity: StreamIdentity,
    /** Pinned at pairing; null before. */
    var serverCert: X509Certificate?,
    http: OkHttpClient,
    private val deviceName: String,
) {
    var httpsPort: Int = 47984

    private val host = if (address.contains(':') && !address.startsWith("[")) "[$address]" else address
    private val plain: OkHttpClient = http.newBuilder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(7, TimeUnit.SECONDS).build()

    private val secure: OkHttpClient by lazy {
        val keyManager = object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf("client")
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = "client"
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = null
            override fun getCertificateChain(alias: String?) = arrayOf(identity.certificate)
            override fun getPrivateKey(alias: String?) = identity.privateKey
            override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: javax.net.ssl.SSLEngine?) = "client"
        }
        // Only the pinned host certificate (hosts are self-signed; names play no role).
        val pinned = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException()
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val expected = serverCert ?: throw CertificateException("No pinned host certificate")
                if (chain.isNullOrEmpty() || !chain[0].encoded.contentEquals(expected.encoded)) throw CertificateException("Host certificate changed")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(arrayOf(keyManager), arrayOf(pinned), SecureRandom()) }
        http.newBuilder()
            .sslSocketFactory(NoResumptionSocketFactory(ssl.socketFactory), pinned)
            .connectionSpecs(listOf(okhttp3.ConnectionSpec.Builder(okhttp3.ConnectionSpec.MODERN_TLS).tlsVersions(okhttp3.TlsVersion.TLS_1_2).build()))
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val httpBase: HttpUrl get() = "http://$host:$httpPort/".toHttpUrl()
    private val httpsBase: HttpUrl get() = "https://$host:$httpsPort/".toHttpUrl()

    /** Over HTTPS once paired (only then the pair state is right), else HTTP. */
    fun serverInfo(): ServerInfo {
        if (serverCert != null) {
            try {
                return parseServerInfo(get(httpsBase, "serverinfo")).also { httpsPort = it.httpsPort }
            } catch (e: NvHttpException) {
                // Unpaired by the host: it refuses our certificate (HTTP 401 or status_code 401); HTTP tells the rest.
                if (e.status != 401) throw e
            } catch (e: javax.net.ssl.SSLException) {
                // Some hosts refuse an unknown client certificate in the handshake already.
            }
        }
        return parseServerInfo(get(httpBase, "serverinfo")).also { httpsPort = it.httpsPort }
    }

    fun appList(): List<NvApp> {
        val root = parseRoot(get(httpsBase, "applist"))
        val apps = root.getElementsByTagName("App")
        return (0 until apps.length).mapNotNull { i ->
            val e = apps.item(i) as Element
            val id = e.text("ID")?.toIntOrNull() ?: return@mapNotNull null
            NvApp(id, e.text("AppTitle").orEmpty(), e.text("UUID"), e.text("IsHdrSupported") == "1")
        }
    }

    fun boxArt(appId: Int): ByteArray? = runCatching {
        request(secure, httpsBase, "appasset", "appid=$appId&AssetType=2&AssetIdx=0").takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** Ends the running app; true when the host did. */
    fun quit(): Boolean = parseRoot(get(httpsBase, "cancel", timeoutLong = true)).text("cancel") != "0"

    fun unpair() {
        runCatching { get(httpBase, "unpair") }
    }

    /** One pairing step; [secure]: over HTTPS (the last step). The PIN step waits for the user. */
    fun pairCommand(arguments: String, secure: Boolean = false, waitForUser: Boolean = false): String {
        val client = if (secure) this.secure else if (waitForUser) plain.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build() else plain
        return String(request(client, if (secure) httpsBase else httpBase, "pair", "updateState=1&$arguments"))
    }

    private fun get(base: HttpUrl, path: String, query: String? = null, timeoutLong: Boolean = false): String {
        val client = if (base.isHttps) secure else plain
        return String(request(if (timeoutLong) client.newBuilder().readTimeout(60, TimeUnit.SECONDS).build() else client, base, path, query))
    }

    private fun request(client: OkHttpClient, base: HttpUrl, path: String, query: String?): ByteArray {
        val url = base.newBuilder().addPathSegments(path).query(query)
            .addQueryParameter("devicename", deviceName)
            .addQueryParameter("uniqueid", identity.uniqueId)
            .addQueryParameter("uuid", UUID.randomUUID().toString())
            .build()
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw NvHttpException(r.code, "$path: HTTP ${r.code}")
            return r.body?.bytes() ?: ByteArray(0)
        }
    }

    companion object {
        fun parse(xml: String) = DocumentBuilderFactory.newInstance().apply {
            // Hosts' answers only; no external entities.
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isExpandEntityReferences = false
        }.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))

        /** Root element after checking the host's status code. */
        fun parseRoot(xml: String): Element {
            val root = parse(xml).documentElement
            val status = root.getAttribute("status_code").toIntOrNull() ?: 200
            if (status != 200) throw NvHttpException(status, root.getAttribute("status_message"))
            return root
        }

        fun Element.text(tag: String): String? = getElementsByTagName(tag).takeIf { it.length > 0 }?.item(0)?.textContent?.trim()

        fun parseServerInfo(xml: String): ServerInfo {
            val r = parseRoot(xml)
            val state = r.text("state").orEmpty()
            val commands = r.getElementsByTagName("ServerCommand").let { list -> (0 until list.length).map { list.item(it).textContent.trim() } }
            return ServerInfo(
                name = r.text("hostname")?.ifBlank { null } ?: "UNKNOWN",
                uuid = r.text("uniqueid") ?: throw IOException("serverinfo without uniqueid"),
                mac = r.text("mac")?.takeIf { it.isNotBlank() && it != "00:00:00:00:00:00" },
                httpsPort = r.text("HttpsPort")?.toIntOrNull() ?: 47984,
                paired = r.text("PairStatus") == "1",
                runningAppId = if (state.endsWith("_SERVER_BUSY")) r.text("currentgame")?.toIntOrNull() ?: 0 else 0,
                appVersion = r.text("appversion").orEmpty(),
                localIp = r.text("LocalIP"),
                permission = r.text("Permission")?.toIntOrNull() ?: -1,
                virtualDisplay = r.text("VirtualDisplayCapable").let { it == "1" || it == "true" },
                serverCommands = commands,
            )
        }
    }
}
