package io.github.thecodingdad.gamelodge.desktop.stream

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsParameters
import com.sun.net.httpserver.HttpsServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** The launcher's GameStream pairing against a host that does it like Sunshine. */
class NvPairingTest {
    private val hostId = StreamIdentity.create() // the host's key and certificate
    private val pin = "1234"
    private var clientCert: X509Certificate? = null
    private var aesKey = ByteArray(0)
    private var serverChallenge = ByteArray(0)
    private var serverSecret = ByteArray(0)
    private var clientHash = ByteArray(0)
    private var pairedClient: X509Certificate? = null

    private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex -> handle(ex) }
        start()
    }
    private val https = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null); setKeyEntry("s", hostId.privateKey, CharArray(0), arrayOf(hostId.certificate)) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, CharArray(0)) }
        val any = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, arrayOf<TrustManager>(any), SecureRandom()) }
        httpsConfigurator = object : HttpsConfigurator(ssl) {
            override fun configure(params: HttpsParameters) { params.setSSLParameters(ssl.defaultSSLParameters.apply { needClientAuth = true }) }
        }
        createContext("/") { ex ->
            val peer = (ex as com.sun.net.httpserver.HttpsExchange).sslSession.peerCertificates[0] as X509Certificate
            val ok = pairedClient != null && peer.encoded.contentEquals(pairedClient!!.encoded)
            reply(ex, if (ok) "<paired>1</paired>" else "<paired>0</paired>")
        }
        start()
    }

    @After
    fun stop() {
        http.stop(0)
        https.stop(0)
    }

    private fun aes(data: ByteArray, encrypt: Boolean) = Cipher.getInstance("AES/ECB/NoPadding").run {
        init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"))
        doFinal(data.copyOf((data.size + 15) / 16 * 16))
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun rnd() = ByteArray(16).also { SecureRandom().nextBytes(it) }

    private fun handle(ex: HttpExchange) {
        val q = ex.requestURI.rawQuery.split('&').associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        val path = ex.requestURI.path
        if (path == "/serverinfo") return reply(ex, "<hostname>Test</hostname><uniqueid>HOST-1</uniqueid><appversion>7.1.431.-1</appversion><HttpsPort>${https.address.port}</HttpsPort><state>SUNSHINE_SERVER_FREE</state><PairStatus>0</PairStatus>")
        when {
            q["phrase"] == "getservercert" -> {
                clientCert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(NvPairing.unhex(q["clientcert"]!!))) as X509Certificate
                aesKey = sha(NvPairing.unhex(q["salt"]!!) + pin.toByteArray()).copyOf(16)
                reply(ex, "<paired>1</paired><plaincert>${NvPairing.hex(hostId.certificatePem.toByteArray())}</plaincert>")
            }
            q.containsKey("clientchallenge") -> {
                val challenge = aes(NvPairing.unhex(q["clientchallenge"]!!), false).copyOf(16)
                serverChallenge = rnd()
                serverSecret = rnd()
                val response = sha(challenge + hostId.certificate.signature + serverSecret) + serverChallenge
                reply(ex, "<paired>1</paired><challengeresponse>${NvPairing.hex(aes(response, true))}</challengeresponse>")
            }
            q.containsKey("serverchallengeresp") -> {
                clientHash = aes(NvPairing.unhex(q["serverchallengeresp"]!!), false).copyOf(32)
                val sig = Signature.getInstance("SHA256withRSA").apply { initSign(hostId.privateKey); update(serverSecret) }.sign()
                reply(ex, "<paired>1</paired><pairingsecret>${NvPairing.hex(serverSecret + sig)}</pairingsecret>")
            }
            q.containsKey("clientpairingsecret") -> {
                val data = NvPairing.unhex(q["clientpairingsecret"]!!)
                val secret = data.copyOf(16)
                val sigOk = Signature.getInstance("SHA256withRSA").apply { initVerify(clientCert!!.publicKey); update(secret) }.verify(data.copyOfRange(16, data.size))
                val hashOk = sha(serverChallenge + clientCert!!.signature + secret).contentEquals(clientHash)
                if (sigOk && hashOk) pairedClient = clientCert
                reply(ex, "<paired>${if (sigOk && hashOk) 1 else 0}</paired>")
            }
            path == "/unpair" -> reply(ex, "")
            else -> reply(ex, "<paired>0</paired>")
        }
    }

    private fun reply(ex: HttpExchange, body: String) {
        val b = "<?xml version=\"1.0\" encoding=\"utf-8\"?><root status_code=\"200\">$body</root>".toByteArray()
        ex.sendResponseHeaders(200, b.size.toLong())
        ex.responseBody.use { it.write(b) }
    }

    private fun client(me: StreamIdentity) = NvHttp("127.0.0.1", http.address.port, me, null, OkHttpClient(), "test").also { it.httpsPort = https.address.port }

    @Test
    fun pairsAndPinsTheHostCertificate() {
        val me = StreamIdentity.create()
        val c = client(me)
        assertEquals("HOST-1", c.serverInfo().uuid)
        val (result, cert) = NvPairing.pair(c, me, pin, 7)
        assertEquals(NvPairing.Result.PAIRED, result)
        assertEquals(hostId.certificate, cert)
        assertNotNull(pairedClient)
    }

    @Test
    fun wrongPinIsDetected() {
        val me = StreamIdentity.create()
        val (result, cert) = NvPairing.pair(client(me), me, "9999", 7)
        assertEquals(NvPairing.Result.PIN_WRONG, result)
        assertNull(cert)
        assertNull(pairedClient)
    }
}
