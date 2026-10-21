package io.github.thecodingdad.gamelodge.desktop.host

import io.github.thecodingdad.gamelodge.host.*

import io.github.thecodingdad.gamelodge.data.companion.HostGameCorrection
import io.github.thecodingdad.gamelodge.data.companion.JPakeExchange
import io.github.thecodingdad.gamelodge.data.companion.PairingFailed
import io.github.thecodingdad.gamelodge.data.companion.ServerClient
import io.github.thecodingdad.gamelodge.data.companion.ServerException
import io.github.thecodingdad.gamelodge.desktop.StoredClientIdentity
import io.github.thecodingdad.gamelodge.data.companion.ClientIdentity
import io.github.thecodingdad.gamelodge.data.settings.SecretStore
import io.github.thecodingdad.gamelodge.platform.SecretBackend
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/** The launcher's own client (as on Android) against the desktop's server: pairing and access rules over real TLS. */
class PairingServerTest {
    private val dir = Files.createTempDirectory("pairing").toFile()
    private val identity = ServerIdentity.create("Test PC")
    private val devices = DeviceRegistry(dir.resolve("devices.json"))
    private val pairing = PairingManager(devices, { identity.fingerprint }, setOf(Permissions.METADATA, Permissions.HOST_GAMES))
    private val notices = java.util.concurrent.CopyOnWriteArrayList<io.github.thecodingdad.gamelodge.data.companion.StreamNotice>()
    private val server = ApiServer(identity, devices, pairing, { "Test PC" }, streamNotice = { _, n -> notices += n })
    private val http = OkHttpClient()
    private lateinit var base: String

    @Before
    fun start() {
        server.start(0)
        base = "https://127.0.0.1:${server.port}"
    }

    @After
    fun stop() {
        server.stop()
        dir.deleteRecursively()
    }

    private fun newIdentity(): ClientIdentity = StoredClientIdentity(SecretStore(object : SecretBackend {
        private val values = HashMap<String, String>()
        override fun get(key: String) = values[key]
        override fun put(key: String, value: String?) { if (value.isNullOrEmpty()) values -= key else values[key] = value }
        override fun keys() = values.keys.toSet()
    }))

    private fun client(identity: ClientIdentity = newIdentity(), pinned: String? = null) = ServerClient(http, identity, base, pinned)

    /** Pairs [c] with [pin] typed on the server as [typed]. */
    private fun pair(c: ServerClient, pin: String, typed: String = pin) = runBlocking {
        pairing.open()
        withTimeout(20_000) {
            val result = async { runCatching { c.pair("Odin", pin) } }
            while (pairing.status.value.requests.isEmpty()) delay(50)
            assertTrue(pairing.enterPin(pairing.status.value.requests.single().id, typed))
            result.await()
        }
    }

    @Test
    fun pairsAndThenUsesTheApiWithThePinnedCertificate() {
        val me = newIdentity()
        val paired = pair(client(me), "123456").getOrThrow()
        assertEquals(identity.fingerprint, paired.fingerprint)
        assertEquals("companion", paired.kind)
        assertEquals(1, devices.devices.value.size)
        val info = client(me, paired.fingerprint).info()
        assertEquals("Odin", info.deviceName)
        assertTrue("hostgames" in info.permissions)
        // One device per opened window.
        assertFalse(pairing.isOpen)
    }

    @Test
    fun wrongPinFailsAndThreeFailuresLockPairing() {
        repeat(3) {
            val r = pair(client(), "111111", typed = "222222")
            assertTrue(r.exceptionOrNull() is PairingFailed || r.exceptionOrNull() is ServerException)
        }
        assertTrue(devices.devices.value.isEmpty())
        assertFalse(pairing.open())
    }

    @Test
    fun unpairedCertificatesOnlyReachPairing() {
        try {
            client().info()
            fail("unpaired client got in")
        } catch (e: ServerException) {
            assertEquals(401, e.status)
        }
        // Pairing endpoints answer, but refuse to start outside the window.
        assertFalse(client().hello().pairingOpen)
        val closed = runBlocking { runCatching { client().pair("Odin", JPakeExchange.newPin()) } }
        assertEquals(403, (closed.exceptionOrNull() as ServerException).status)
    }

    @Test
    fun onlyPairedDevicesReportStreams() {
        val body = "{\"active\":true,\"resume\":true}"
        client().rawPost("/v1/stream", body, "application/json").use { assertEquals(401, it.code) }
        assertTrue(notices.isEmpty())
        val me = newIdentity()
        val paired = pair(client(me), "654321").getOrThrow()
        client(me, paired.fingerprint).rawPost("/v1/stream", body, "application/json").use { assertEquals(204, it.code) }
        assertEquals(listOf(io.github.thecodingdad.gamelodge.data.companion.StreamNotice(active = true, resume = true)), notices.toList())
        client(me, paired.fingerprint).rawPost("/v1/stream", "nonsense", "application/json").use { assertEquals(400, it.code) }
    }

    @Test
    fun revokedDeviceIsRejectedAtOnce() {
        val me = newIdentity()
        val paired = pair(client(me), "654321").getOrThrow()
        devices.remove(devices.devices.value.single().id)
        try {
            client(me, paired.fingerprint).info()
            fail("revoked device got in")
        } catch (e: ServerException) {
            assertEquals(401, e.status)
        }
    }

    @Test
    fun correctionsAreCheckedStrictly() {
        assertTrue(ApiServer.valid(HostGameCorrection(cover = "https://cdn2.steamgriddb.com/grid/x.png", genres = listOf("RPG"))))
        assertFalse(ApiServer.valid(HostGameCorrection(cover = "https://evil.example/x.png")))
        assertFalse(ApiServer.valid(HostGameCorrection(cover = "http://cdn2.steamgriddb.com/x.png")))
        assertFalse(ApiServer.valid(HostGameCorrection(cover = "https://steamgriddb.com:8443/x.png")))
        assertFalse(ApiServer.valid(HostGameCorrection(name = "bad\u0000name")))
        assertFalse(ApiServer.valid(HostGameCorrection(reset = listOf("password"))))
        assertFalse(ApiServer.valid(HostGameCorrection(kind = "tool")))
    }
}
