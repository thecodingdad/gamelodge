package io.github.thecodingdad.moonlightlauncher.metadata

import io.github.thecodingdad.moonlightlauncher.data.companion.PairedServer
import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataSourcesTest {
    private val server = PairedServer("s1", PairedServer.KIND_METADATA, "NAS", "https://nas:47900", "AA")
    private val companion = PairedServer("c1", PairedServer.KIND_COMPANION, "PC", "https://pc:47910", "BB", hostId = "H")

    private fun ids(stored: List<String>, sgdb: Boolean = true, igdb: Boolean = true) =
        MetadataSources.store(MetadataSources.resolve(stored, listOf(server, companion), sgdb, igdb))

    @Test
    fun `nothing stored lists every configured source`() {
        assertEquals(listOf("server:s1", "companion:c1", "sgdb", "igdb"), ids(emptyList()))
        assertEquals(listOf("server:s1", "companion:c1"), ids(emptyList(), sgdb = false, igdb = false))
    }

    @Test
    fun `stored order and switched off sources are kept`() {
        assertEquals(listOf("igdb", "!companion:c1", "sgdb", "server:s1"), ids(listOf("igdb", "!companion:c1", "sgdb", "server:s1")))
    }

    @Test
    fun `sources no longer configured drop out and new ones are appended`() {
        assertEquals(listOf("sgdb", "server:s1", "companion:c1"), ids(listOf("sgdb", "server:gone", "igdb"), igdb = false))
    }

    @Test
    fun `ids of the first version are expanded`() {
        assertEquals(listOf("!sgdb", "!igdb", "companion:c1", "server:s1"), ids(listOf("!own", "companion", "server")))
    }
}
