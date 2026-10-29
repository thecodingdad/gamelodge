package io.github.thecodingdad.gamelodge.ha.mqtt

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/** TLS of the MQTT client: only a trusted certificate issued for the broker's name is accepted. */
class MqttTlsTest {
    private class Cert(val keys: KeyPair, val cert: X509Certificate)

    private fun cert(name: String): Cert {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            X500Name("CN=$name"), BigInteger.valueOf(now), Date(now - 60_000), Date(now + 86_400_000), X500Name("CN=$name"), keys.public,
        ).addExtension(Extension.subjectAlternativeName, false, GeneralNames(GeneralName(GeneralName.dNSName, name)))
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(JcaContentSignerBuilder("SHA256withRSA").build(keys.private)))
        return Cert(keys, cert)
    }

    /** A TLS broker on localhost that accepts any login. */
    private fun broker(cert: Cert): SSLServerSocket {
        val store = KeyStore.getInstance("PKCS12").apply { load(null); setKeyEntry("k", cert.keys.private, CharArray(0), arrayOf(cert.cert)) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, CharArray(0)) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val server = ctx.serverSocketFactory.createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { s ->
                    MqttPackets.read(s.getInputStream())
                    s.getOutputStream().apply { write(byteArrayOf(0x20, 2, 0, 0)); flush() }
                    Thread.sleep(200)
                }
            }
        }
        return server
    }

    /** Trust as if [cert] came from an authority the system trusts. */
    private fun trusting(cert: Cert): X509TrustManager {
        val store = KeyStore.getInstance("PKCS12").apply { load(null); setCertificateEntry("c", cert.cert) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private fun options(port: Int, selfSigned: Boolean = false, pin: String? = null) =
        MqttOptions("localhost", port, tls = true, username = "u", password = "p", clientId = "test", allowSelfSigned = selfSigned, pinnedCertificate = pin)

    private inline fun <reified T : Throwable> refused(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("expected ${T::class.simpleName}, got $e", e)
        }
        fail("connected although it should be refused")
        error("unreachable")
    }

    @Test
    fun trustedCertificateForTheHostConnects() {
        val c = cert("localhost")
        broker(c).use { server -> MqttConnection.open(options(server.localPort), trusting(c)).close() }
    }

    @Test
    fun certificateForAnotherNameIsRefused() {
        val c = cert("other.example")
        broker(c).use { server -> refused<javax.net.ssl.SSLException> { MqttConnection.open(options(server.localPort), trusting(c)).close() } }
        // Allowing self-signed certificates doesn't drop the name check of trusted ones.
        broker(c).use { server -> refused<javax.net.ssl.SSLException> { MqttConnection.open(options(server.localPort, selfSigned = true), trusting(c)).close() } }
    }

    @Test
    fun untrustedCertificateIsRefused() {
        val c = cert("localhost")
        broker(c).use { server -> refused<javax.net.ssl.SSLException> { MqttConnection.open(options(server.localPort)).close() } }
    }

    @Test
    fun selfSignedAsksToConfirmItsFingerprint() {
        val c = cert("localhost")
        val e = broker(c).use { server -> refused<MqttCertificateException> { MqttConnection.open(options(server.localPort, selfSigned = true)).close() } }
        assertEquals(MqttConnection.fingerprint(c.cert), e.fingerprint)
        assertFalse(e.changed)
    }

    @Test
    fun pinnedSelfSignedConnects() {
        // Self-signed certificates often name no host: the pin is what counts.
        val c = cert("mosquitto")
        broker(c).use { server -> MqttConnection.open(options(server.localPort, selfSigned = true, pin = MqttConnection.fingerprint(c.cert))).close() }
    }

    @Test
    fun otherCertificateThanThePinnedIsRefused() {
        val pinned = cert("localhost")
        val other = cert("localhost")
        val e = broker(other).use { server ->
            refused<MqttCertificateException> { MqttConnection.open(options(server.localPort, selfSigned = true, pin = MqttConnection.fingerprint(pinned.cert))).close() }
        }
        assertEquals(MqttConnection.fingerprint(other.cert), e.fingerprint)
        assertTrue(e.changed)
    }
}
