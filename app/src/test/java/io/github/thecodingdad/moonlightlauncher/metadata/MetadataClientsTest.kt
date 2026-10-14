package io.github.thecodingdad.moonlightlauncher.metadata

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MatchScorerTest {
    @Test
    fun `exact match after normalisation is accepted`() {
        assertTrue(MatchScorer.score("ELDEN RING™", "Elden Ring") >= MatchScorer.AUTO_ACCEPT)
    }

    @Test
    fun `subtitle variants land in review, not auto accept`() {
        val s = MatchScorer.score("The Witcher 3", "The Witcher 3: Wild Hunt")
        assertTrue("score $s", s >= MatchScorer.REVIEW && s < MatchScorer.AUTO_ACCEPT)
    }

    @Test
    fun `unrelated titles are rejected`() {
        assertTrue(MatchScorer.score("Celeste", "Hollow Knight") < MatchScorer.REVIEW)
    }
}

class MetadataClientsTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `steamgriddb search and assets are parsed, nsfw filtered`() {
        server.enqueue(MockResponse().setBody("""{"success":true,"data":[{"id":42,"name":"Hades","verified":true}]}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"success":true,"data":[
                  {"id":1,"url":"https://x/a.png","mime":"image/png","score":3,"width":600,"height":900},
                  {"id":2,"url":"https://x/b.png","mime":"image/png","nsfw":true,"score":9,"width":600,"height":900}]}""",
            ),
        )
        val client = SteamGridDbClient(http, { "key" }, server.url("/api/v2/"))

        assertEquals(42L, client.search("Hades").single().id)
        val grids = client.assets(42, SgdbAssetType.GRID)
        assertEquals(listOf(1L), grids.map { it.id })
        assertEquals("Bearer key", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `igdb fetches a token once and parses games`() {
        server.enqueue(MockResponse().setBody("""{"access_token":"tok","expires_in":3600,"token_type":"bearer"}"""))
        server.enqueue(
            MockResponse().setBody(
                """[{"id":7,"name":"Hades","summary":"Roguelike","genres":[{"id":1,"name":"Action"}],
                   "first_release_date":1600300800,"cover":{"id":1,"image_id":"co1"},
                   "involved_companies":[{"id":1,"company":{"id":1,"name":"Supergiant"},"developer":true}]}]""",
            ),
        )
        val creds = object : IgdbCredentials {
            override val clientId = "id"
            override val clientSecret = "secret"
            override var token: String? = null
            override var tokenExpiresAt = 0L
        }
        val client = IgdbClient(http, creds, server.url("/v4/"), server.url("/oauth2/token"))

        val game = client.search("Hades").single()
        assertEquals("Hades", game.name)
        assertEquals(2020, game.releaseYear)
        assertEquals("Supergiant", game.developer)
        assertEquals("https://images.igdb.com/igdb/image/upload/t_cover_big_2x/co1.jpg", game.coverUrl)
        assertEquals("tok", creds.token)

        server.takeRequest() // token
        val query = server.takeRequest()
        assertEquals("Bearer tok", query.getHeader("Authorization"))
        assertEquals("id", query.getHeader("Client-ID"))
    }

    @Test
    fun `other titles are searched via alternative names and localizations`() {
        server.enqueue(MockResponse().setBody("""{"access_token":"tok","expires_in":3600,"token_type":"bearer"}"""))
        server.enqueue(MockResponse().setBody("""[{"id":1,"game":42}]""")) // alternative_names
        server.enqueue(MockResponse().setBody("""[{"id":2,"game":42},{"id":3,"game":43}]""")) // game_localizations
        server.enqueue(
            MockResponse().setBody(
                """[{"id":42,"name":"LEGO Batman: Legacy of the Dark Knight",
                   "game_localizations":[{"id":2,"name":"LEGO Batman: Das Vermächtnis des Dunklen Ritters"}]}]""",
            ),
        )
        val creds = object : IgdbCredentials {
            override val clientId = "id"
            override val clientSecret = "secret"
            override var token: String? = null
            override var tokenExpiresAt = 0L
        }
        val client = IgdbClient(http, creds, server.url("/v4/"), server.url("/oauth2/token"))

        val game = client.searchOtherTitles("LEGO Batman: Das Vermächtnis des Dunklen Ritters").single()
        assertEquals(42L, game.id)
        assertEquals(
            listOf("LEGO Batman: Legacy of the Dark Knight", "LEGO Batman: Das Vermächtnis des Dunklen Ritters"),
            game.allNames,
        )
        server.takeRequest() // token
        assertEquals("/v4/alternative_names", server.takeRequest().path)
        assertEquals("/v4/game_localizations", server.takeRequest().path)
        val details = server.takeRequest()
        assertEquals("/v4/games", details.path)
        assert(details.body.readUtf8().contains("where id = (42,43)"))
    }
}

class CompanionFeedParseTest {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `host games of the companion`() {
        val list = json.decodeFromString<io.github.thecodingdad.moonlightlauncher.data.companion.HostGamesList>(
            """{"hostId":"ABC","games":[{"id":"steam:1145360","appUuid":"bbb","name":"Hades","kind":"game","source":"steam","platform":"PC","installed":false,"collections":["Roguelike"],"cover":"image:steam_1145360","genres":["Action"],"releaseDate":1600300800,"newField":1},
               {"id":"x:1","name":"Desktop","kind":"app"},{"id":"rom:1","name":"Zelda","source":"emulator","emulator":"Dolphin","platform":"Wii"}]}""",
        )
        assertEquals("ABC", list.hostId)
        val hades = list.games[0]
        assertEquals(true, hades.isGame)
        assertEquals(false, hades.installed)
        assertEquals("Steam", hades.sourceLabel)
        assertEquals(listOf("Roguelike"), hades.collections)
        assertEquals(false, list.games[1].isGame)
        assertEquals(true, list.games[1].installed)
        assertEquals("Dolphin", list.games[2].sourceLabel)
    }
}
