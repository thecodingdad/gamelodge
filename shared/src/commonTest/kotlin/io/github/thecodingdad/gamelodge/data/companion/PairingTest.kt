package io.github.thecodingdad.gamelodge.data.companion

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date

/** Software key (tests only; the app uses the Android Keystore). */
class SoftwareIdentity : ClientIdentity {
    override val privateKey: PrivateKey
    override val certificate: X509Certificate

    init {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val name = X500Name("CN=test-launcher")
        val now = System.currentTimeMillis()
        val holder = JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now), Date(now - 86_400_000), Date(now + 86_400_000L * 365), name, pair.public)
            .build(JcaContentSignerBuilder("SHA256withECDSA").build(pair.private))
        certificate = JcaX509CertificateConverter().getCertificate(holder)
        privateKey = pair.private
    }
}

class PairingTest {
    private fun run(clientPin: String, serverPin: String): Pair<Boolean, Pair<JPakeExchange, JPakeExchange>> {
        val client = JPakeExchange(JPakeExchange.CLIENT_ID, clientPin)
        val server = JPakeExchange(JPakeExchange.SERVER_ID, serverPin)
        val c1 = client.createRound1(); val s1 = server.createRound1()
        server.receiveRound1(c1); client.receiveRound1(s1)
        val c2 = client.createRound2(); val s2 = server.createRound2()
        server.receiveRound2(c2); client.receiveRound2(s2)
        return try {
            val c3 = client.createRound3(); val s3 = server.createRound3()
            server.receiveRound3(c3); client.receiveRound3(s3)
            true to (client to server)
        } catch (e: PairingFailed) {
            false to (client to server)
        }
    }

    @Test fun samePinAgreesAndBindsCertificates() {
        repeat(10) {
            val (ok, ex) = run("004217", "004217")
            assertTrue(ok)
            val mac = ex.first.bindingMac(JPakeExchange.CLIENT_ID, "SFP", "CFP")
            assertTrue(ex.second.checkBindingMac(JPakeExchange.CLIENT_ID, "SFP", "CFP", mac))
            assertFalse(ex.second.checkBindingMac(JPakeExchange.CLIENT_ID, "MITM", "CFP", mac))
        }
    }

    @Test fun wrongPinFails() = assertFalse(run("111111", "111112").first)

    @Test fun addresses() {
        assertEquals("https://192.168.1.5:47900", PairedServer.normalizeAddress("192.168.1.5", 47900))
        assertEquals("https://nas.local:8443", PairedServer.normalizeAddress("https://nas.local:8443/", 47900))
        assertEquals("https://[fd00::5]:47910", PairedServer.normalizeAddress("[fd00::5]:47910", 47900))
        assertEquals("https://[fd00::5]:47900", PairedServer.normalizeAddress("fd00::5", 47900))
        assertNull(PairedServer.normalizeAddress("host:99999", 47900))
        assertNull(PairedServer.normalizeAddress("evil.com/path", 47900))
        assertNull(PairedServer.normalizeAddress("user@host", 47900))
        assertNull(PairedServer.normalizeAddress("", 47900))
    }

    /**
     * Against a real server (Companion.Protocol in C#), only when <tmp>/mlc-pair-test.txt exists:
     * line 1 server URL, line 2 file the PIN is written to (for whoever types it on the server).
     */
    @Test fun pairsWithServer() = runBlocking {
        val config = File(System.getProperty("java.io.tmpdir"), "mlc-pair-test.txt")
        assumeTrue(config.exists())
        val (url, pinFile) = config.readLines()
        val identity = SoftwareIdentity()
        val pin = JPakeExchange.newPin()
        val client = ServerClient(OkHttpClient(), identity, url, null)
        val server = client.pair("Kotlin-Test", pin) { File(pinFile).writeText(pin) }
        val paired = ServerClient(OkHttpClient(), identity, server.baseUrl, server.fingerprint)
        val info = paired.info()
        assertEquals("Kotlin-Test", info.deviceName)
        // Another certificate than the pinned one is refused.
        val wrongPin = ServerClient(OkHttpClient(), identity, server.baseUrl, "00".repeat(32))
        assertTrue(runCatching { wrongPin.info() }.isFailure)
        // Another client certificate is not paired.
        val stranger = ServerClient(OkHttpClient(), SoftwareIdentity(), server.baseUrl, server.fingerprint)
        assertEquals(401, (runCatching { stranger.info() }.exceptionOrNull() as ServerException).status)
    }
}
