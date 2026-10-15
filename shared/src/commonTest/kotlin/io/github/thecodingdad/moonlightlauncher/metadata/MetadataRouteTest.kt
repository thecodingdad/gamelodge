package io.github.thecodingdad.moonlightlauncher.metadata

import io.github.thecodingdad.moonlightlauncher.data.companion.ProxyTarget
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** A proxy over plain HTTP to a MockWebServer (the real one uses mutual TLS). */
private class FakeProxy(private val server: MockWebServer?) : ProxyTarget {
    private val http = OkHttpClient()
    override fun rawGet(pathAndQuery: String): Response =
        http.newCall(Request.Builder().url(url(pathAndQuery)).build()).execute()
    override fun rawPost(path: String, body: String, mediaType: String): Response =
        http.newCall(Request.Builder().url(url(path)).post(body.toRequestBody(mediaType.toMediaType())).build()).execute()
    private fun url(path: String) = server?.url(path) ?: throw IOException("offline")
}

class MetadataRouteTest {
    private val proxy = MockWebServer().apply { start() }
    private val own = MockWebServer().apply { start() }

    @After fun tearDown() {
        proxy.shutdown()
        own.shutdown()
    }

    private fun sgdb(routes: List<MetadataRoute>, key: String? = "own-key") =
        SteamGridDbClient(OkHttpClient(), { key }, own.url("/api/v2/"), { routes })

    @Test fun proxyIsUsedWithSamePathAndQuery() {
        proxy.enqueue(MockResponse().setBody("""{"success":true,"data":[{"id":7,"name":"Portal"}]}"""))
        val result = sgdb(listOf(MetadataRoute.Via(FakeProxy(proxy)), MetadataRoute.Own)).search("Portal 2")
        assertEquals(7L, result.single().id)
        val request = proxy.takeRequest()
        assertEquals("/v1/sgdb/search/autocomplete/Portal%202", request.path)
        assertEquals(null, request.getHeader("Authorization")) // the proxy uses its own key
        assertEquals(0, own.requestCount)
    }

    @Test fun offlineOrUnconfiguredProxyFallsBackToOwnKey() {
        proxy.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"not_configured"}"""))
        own.enqueue(MockResponse().setBody("""{"success":true,"data":[{"id":1,"name":"A"}]}"""))
        own.enqueue(MockResponse().setBody("""{"success":true,"data":[{"id":2,"name":"B"}]}"""))
        val client = sgdb(listOf(MetadataRoute.Via(FakeProxy(proxy)), MetadataRoute.Own))
        assertEquals(1L, client.search("A").single().id)
        assertEquals("Bearer own-key", own.takeRequest().getHeader("Authorization"))
        val offline = sgdb(listOf(MetadataRoute.Via(FakeProxy(null)), MetadataRoute.Own))
        assertEquals(2L, offline.search("B").single().id)
    }

    @Test fun ownKeyMissingMeansOnlyServers() {
        assertFalse(sgdb(listOf(MetadataRoute.Own), key = null).isConfigured)
        assertTrue(sgdb(listOf(MetadataRoute.Via(FakeProxy(proxy))), key = null).isConfigured)
    }

    @Test fun assetQueryIsForwarded() {
        proxy.enqueue(MockResponse().setBody("""{"success":true,"data":[]}"""))
        sgdb(listOf(MetadataRoute.Via(FakeProxy(proxy)))).assets(42, SgdbAssetType.GRID)
        val path = proxy.takeRequest().path!!
        assertTrue(path, path.startsWith("/v1/sgdb/grids/game/42?dimensions=600x900"))
        assertTrue(path, path.contains("types=static") && path.contains("nsfw=false"))
    }

    @Test fun igdbGoesThroughProxy() {
        proxy.enqueue(MockResponse().setBody("""[{"id":5,"name":"Portal"}]"""))
        val igdb = IgdbClient(OkHttpClient(), object : IgdbCredentials {
            override val clientId: String? = null
            override val clientSecret: String? = null
            override var token: String? = null
            override var tokenExpiresAt: Long = 0
        }, routes = { listOf(MetadataRoute.Via(FakeProxy(proxy)), MetadataRoute.Own) })
        assertTrue(igdb.isConfigured)
        assertEquals(5L, igdb.search("Portal").single().id)
        val request = proxy.takeRequest()
        assertEquals("/v1/igdb/games", request.path)
        assertTrue(request.body.readUtf8().startsWith("search \"Portal\""))
    }
}
