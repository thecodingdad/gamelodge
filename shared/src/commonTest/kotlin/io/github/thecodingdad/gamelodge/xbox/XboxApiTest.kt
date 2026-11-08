package io.github.thecodingdad.gamelodge.xbox

import io.github.thecodingdad.gamelodge.testing.memorySecrets
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class XboxApiTest {
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private var tokenPolls = 0
    private var licensed = setOf("OWNED1")
    private var sessionStatePolls = 0

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.path.orEmpty()
                val body = request.body.readUtf8().also { request.body.clear() }
                return when {
                    path.startsWith("/devicecode") -> ok("""{"device_code":"dc","user_code":"ABCD1234","verification_uri":"https://www.microsoft.com/link","expires_in":900,"interval":1}""")
                    path.startsWith("/token") && "device_code" in body ->
                        if (tokenPolls++ == 0) MockResponse().setResponseCode(400).setBody("""{"error":"authorization_pending"}""")
                        else ok("""{"access_token":"ms-access","refresh_token":"ms-refresh","expires_in":3600}""")
                    path.startsWith("/token") && "refresh_token" in body ->
                        if ("bad-refresh" in body) MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}""")
                        else ok("""{"access_token":"ms-access2","refresh_token":"ms-refresh2","expires_in":3600}""")
                    path.startsWith("/user") -> ok("""{"Token":"user-token","NotAfter":"2099-01-01T00:00:00Z"}""")
                    path.startsWith("/xsts") -> {
                        val rp = Regex("\"RelyingParty\":\"([^\"]+)\"").find(body)!!.groupValues[1]
                        ok("""{"Token":"xsts-${rp.length}","NotAfter":"2099-01-01T00:00:00Z","DisplayClaims":{"xui":[{"uhs":"uhs1","xid":"2533","gtg":"Lodger"}]}}""")
                    }
                    path.startsWith("/xhome") -> ok("""{"gsToken":"gs","durationInSeconds":14400,"offeringSettings":{"regions":[{"name":"A","baseUri":"${server.url("/region").toString().trimEnd('/')}","isDefault":true}]}}""")
                    path.startsWith("/xccs/lists/devices") -> ok("""{"result":[{"id":"FD01","name":"Living room","consoleType":"XboxOneS","powerState":"ConnectedStandby","remoteManagementEnabled":"True","consoleStreamingEnabled":"True"}]}""")
                    path.startsWith("/xccs/lists/installedApps") -> ok("""{"result":[
                        {"oneStoreProductId":"OWNED1","titleId":702634063,"name":"The Swapper","contentType":"Game","sizeInBytes":1.0E9},
                        {"oneStoreProductId":"PASS1","titleId":2067761315,"name":"Sports Game™","contentType":"Game"},
                        {"oneStoreProductId":"DLC1","name":"Extra","contentType":"Dlc"}]}""")
                    path.startsWith("/xccs/commands") -> ok("""{"result":null,"status":{"errorCode":"OK"}}""")
                    path.startsWith("/xccs/consoles/") -> ok("""{"id":"FD01","powerState":"On"}""")
                    path.startsWith("/presence") -> ok("""{"devices":[{"type":"XboxOne","titles":[{"id":"702634063","name":"The Swapper","placement":"Full","state":"Active"},{"id":"750323071","name":"Home","placement":"Background","state":"Active"}]}]}""")
                    path.startsWith("/collections") -> {
                        val id = Regex("\"productId\":\"([^\"]+)\"").find(body)!!.groupValues[1]
                        if (id in licensed) ok("""{"items":[{"productId":"$id","acquisitionType":"Single","status":"Active"}]}""") else ok("""{"items":[]}""")
                    }
                    path.startsWith("/region/v5/sessions/home/play") -> MockResponse().setResponseCode(202).setBody("""{"sessionPath":"v5/sessions/home/S1","state":"Provisioning"}""")
                    path.startsWith("/region/v5/sessions/home/S1/state") ->
                        ok(if (sessionStatePolls++ < 1) """{"state":"Provisioning"}""" else """{"state":"Provisioned"}""")
                    path == "/region/v5/sessions/home/S1/sdp" && request.method == "POST" -> MockResponse().setResponseCode(202)
                    path == "/region/v5/sessions/home/S1/sdp" -> ok("""{"exchangeResponse":"{\"sdp\":\"v=0 answer\",\"status\":\"success\"}"}""")
                    path == "/region/v5/sessions/home/S1/ice" && request.method == "POST" -> MockResponse().setResponseCode(202)
                    path == "/region/v5/sessions/home/S1/ice" -> ok("""{"exchangeResponse":"[{\"candidate\":\"candidate:1 1 udp 2122063615 2001:0:d5c7:a2ca:10c1:2fb9:3f57:fefe 9002 typ host\",\"sdpMid\":\"0\",\"sdpMLineIndex\":0}]"}""")
                    path.startsWith("/region/v5/sessions/home/S1") && request.method == "DELETE" -> MockResponse().setResponseCode(202)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun endpoints() = XboxEndpoints(
        deviceCode = server.url("/devicecode").toString(),
        token = server.url("/token").toString(),
        userAuth = server.url("/user").toString(),
        xsts = server.url("/xsts").toString(),
        xhomeLogin = server.url("/xhome").toString(),
        xccs = server.url("/xccs").toString().trimEnd('/'),
        presence = server.url("/presence").toString().trimEnd('/'),
        collections = server.url("/collections").toString().trimEnd('/'),
        catalog = server.url("/catalog").toString().trimEnd('/'),
    )

    private val http = OkHttpClient()

    @Test
    fun deviceCodeSignInStoresOnlyTheRefreshTokenAndLearnsTheGamertag() = runBlocking {
        val secrets = memorySecrets()
        val auth = XboxAuth(http, secrets, endpoints = endpoints())
        val code = auth.startSignIn()
        assertEquals("ABCD1234", code.userCode)
        assertEquals("https://www.microsoft.com/link?otc=ABCD1234", code.directUri)
        assertTrue(auth.awaitSignIn(code))
        assertTrue(auth.isSignedIn)
        assertEquals("Lodger", auth.gamertag)
        val stored = secrets.get(XboxAuth.KEY)!!
        assertTrue("ms-refresh" in stored)
        assertFalse("ms-access" in stored)
        // Kept out of backups like pairings.
        assertTrue(XboxAuth.KEY.contains("_identity"))
    }

    @Test
    fun xblHeaderAndStreamingToken() = runBlocking {
        val auth = signedIn()
        assertEquals("XBL3.0 x=uhs1;xsts-19", auth.web().header)
        val xhome = auth.xhome()
        assertEquals("gs", xhome.gsToken)
        assertTrue(xhome.baseUri.endsWith("/region"))
    }

    @Test
    fun revokedRefreshTokenSignsOut() = runBlocking {
        val secrets = memorySecrets()
        secrets.put(XboxAuth.KEY, """{"refreshToken":"bad-refresh"}""")
        val auth = XboxAuth(http, secrets, endpoints = endpoints())
        val e = runCatching { auth.web() }.exceptionOrNull()
        assertTrue(e is XboxAuthException && e.signedOut)
        assertFalse(auth.isSignedIn)
    }

    private suspend fun signedIn(): XboxAuth {
        val auth = XboxAuth(http, memorySecrets(), endpoints = endpoints())
        assertTrue(auth.awaitSignIn(auth.startSignIn()))
        return auth
    }

    @Test
    fun consolesAppsCommandsPresenceAndLicenses() = runBlocking {
        val client = XboxClient(http, signedIn(), endpoints())
        val console = client.consoles().single()
        assertEquals("Living room", console.name)
        assertEquals(XboxPower.STANDBY, console.power)
        assertTrue(console.streaming)
        assertFalse(console.isSeries)

        val apps = client.installedApps("FD01")
        assertEquals(listOf("OWNED1", "PASS1"), apps.filter { it.isGame }.map { it.productId })
        assertEquals(2067761315L, apps[1].titleId)

        assertTrue(client.launch("FD01", "OWNED1"))
        val launch = requests.last { it.path!!.startsWith("/xccs/commands") }
        assertEquals("4", launch.getHeader("x-xbl-contract-version"))
        assertEquals(XboxPower.ON, client.power("FD01"))

        assertEquals(702634063L, client.activeTitle())
        assertEquals(setOf("OWNED1"), client.ownedProducts(listOf("OWNED1", "PASS1")))
    }

    @Test
    fun sessionSignalingAndTeredo() = runBlocking {
        val session = XhomeSession(http, signedIn(), "FD01", XboxResolution.P720.osName)
        session.start()
        assertTrue(session.started)
        val play = requests.first { it.path!!.endsWith("/home/play") }
        assertTrue(play.getHeader("X-MS-Device-Info")!!.contains("\"android\""))
        assertEquals("Bearer gs", play.getHeader("Authorization"))
        assertEquals("v=0 answer", session.exchangeSdp("v=0 offer"))
        val remote = session.exchangeIce(buildJsonArray { add(buildJsonObject { put("candidate", "a=candidate:1 1 udp 1 192.168.1.2 5000 typ host") }) })
        // The Teredo candidate plus five IPv4 candidates spelled out of it.
        assertEquals(6, remote.size)
        val lines = remote.map { (it.jsonObject["candidate"] as JsonPrimitive).content }
        assertTrue(lines.any { "213.199.162.202 9002" in it || "192.0.2.45" in it })
        session.stop()
        assertFalse(session.started)
    }

    @Test
    fun teredoAddressParts() {
        // RFC 4380 example: server 65.54.227.120, client 192.0.2.45 port 40000.
        val t = Teredo.parseTeredo("2001:0000:4136:e378:8000:63bf:3fff:fdd2")!!
        assertEquals("65.54.227.120", t.server)
        assertEquals("192.0.2.45", t.client)
        assertEquals(40000, t.clientPort)
        assertNull(Teredo.parseTeredo("2a02:810d:1:2::5"))
        assertNull(Teredo.parseTeredo("192.168.0.1"))
    }

    @Test
    fun streamControllerAnswersThePage() = runBlocking {
        val session = XhomeSession(http, signedIn(), "FD01", XboxResolution.P1080.osName)
        session.start()
        val sent = CopyOnWriteArrayList<String>()
        val controller = XboxStreamController(session, this, XboxStreamController.Config(maxBitrateKbps = 12000))
        controller.send = { sent += it }
        controller.onMessage("""{"t":"ready"}""")
        assertTrue(sent.last().contains("\"maxBitrateKbps\":12000"))
        controller.onMessage("""{"t":"offer","sdp":"v=0 offer"}""")
        kotlinx.coroutines.delay(300)
        assertTrue(sent.any { it.contains("\"t\":\"answer\"") && it.contains("v=0 answer") })
        controller.pads(listOf(XboxPadState(0, a = true)))
        assertFalse(sent.any { it.contains("\"pads\"") })
        controller.onMessage("""{"t":"state","state":"connected"}""")
        assertEquals(XboxStreamState.Connected, controller.state.value)
        controller.pads(listOf(XboxPadState(0, a = true, lx = 0.5f)))
        val pads = sent.last { it.contains("\"pads\"") }
        assertTrue(pads.contains("\"A\":1") && pads.contains("\"LeftThumbXAxis\":0.5"))
        controller.end()
        kotlinx.coroutines.delay(300)
        assertEquals(XboxStreamState.Ended, controller.state.value)
        assertTrue(requests.any { it.method == "DELETE" })
    }
}
