package io.github.thecodingdad.gamelodge.host

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

/** Only allowlisted upstream requests leave the PC; answers are cached. */
class MetadataProxyTest {
    private val requested = mutableListOf<String>()
    private val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val url = chain.request().url.toString()
        requested += url
        val body = if (url.startsWith("https://id.twitch.tv/")) """{"access_token":"t","expires_in":3600}""" else """{"success":true}"""
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
    }).build()
    private val keys = object : MetadataKeys {
        override val steamGridDbKey = "key"
        override val igdbClientId = "id"
        override val igdbClientSecret = "secret"
    }
    private val proxy = MetadataProxy(http, keys, ResponseCache(Files.createTempDirectory("proxy").toFile()))

    @Test
    fun allowlistedPathsOnly() {
        assertEquals(ProxyResult.Status.OK, proxy.steamGridDb("grids/game/123", listOf("dimensions" to "600x900")).status)
        assertEquals("https://www.steamgriddb.com/api/v2/grids/game/123?dimensions=600x900", requested.single())
        for (bad in listOf("../../evil", "grids/game/abc", "users/me", "search/autocomplete/a/b", "grids%2F..%2F..%2Fx")) {
            assertEquals(bad, ProxyResult.Status.NOT_ALLOWED, proxy.steamGridDb(bad, emptyList()).status)
        }
        assertEquals(ProxyResult.Status.NOT_ALLOWED, proxy.steamGridDb("grids/game/1", listOf("callback" to "x")).status)
        assertEquals(ProxyResult.Status.NOT_ALLOWED, proxy.steamGridDb("grids/game/1", listOf("styles" to "a&b=c")).status)
        assertEquals(ProxyResult.Status.NOT_ALLOWED, proxy.igdb("private/users", "fields *;").status)
        assertEquals(1, requested.size)
    }

    @Test
    fun answersAreCached() {
        proxy.steamGridDb("search/autocomplete/portal", emptyList())
        proxy.steamGridDb("search/autocomplete/portal", emptyList())
        assertEquals(1, requested.size)
        assertEquals(ProxyResult.Status.OK, proxy.igdb("games", "fields name; where id = 1;").status)
        proxy.igdb("games", "fields name; where id = 1;")
        // Token once, query once.
        assertEquals(3, requested.size)
    }
}
