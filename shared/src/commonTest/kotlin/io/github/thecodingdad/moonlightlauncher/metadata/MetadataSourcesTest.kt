package io.github.thecodingdad.moonlightlauncher.metadata

import io.github.thecodingdad.moonlightlauncher.data.companion.PairedServer
import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataSourcesTest {
    private val server = PairedServer("s1", PairedServer.KIND_METADATA, "NAS", "https://nas:47900", "AA")
    private val companion = PairedServer("c1", PairedServer.KIND_COMPANION, "PC", "https://pc:47910", "BB", hostId = "H")

    private fun ids(stored: List<String>, sgdb: Boolean = true, igdb: Boolean = true) =
        MetadataSources.resolve(stored, listOf(companion, server), sgdb, igdb).map { if (it.enabled) it.id else "!${it.id}" }

    @Test
    fun `own keys first, then servers, then companions`() {
        assertEquals(listOf("sgdb", "igdb", "server:s1", "companion:c1"), ids(emptyList()))
        assertEquals(listOf("server:s1", "companion:c1"), ids(listOf("igdb", "sgdb"), sgdb = false, igdb = false))
    }

    @Test
    fun `switched off sources are remembered, also from the first version`() {
        assertEquals(listOf("sgdb", "igdb", "server:s1", "!companion:c1"), ids(listOf("igdb", "!companion:c1", "sgdb")))
        assertEquals(listOf("!sgdb", "!igdb", "server:s1", "companion:c1"), ids(listOf("!own", "companion", "server")))
    }

    @Test
    fun `switching keeps only the off markers`() {
        assertEquals(listOf("!server:s1"), MetadataSources.set(listOf("sgdb", "server:s1", "own"), "server:s1", false))
        assertEquals(emptyList<String>(), MetadataSources.set(listOf("!server:s1"), "server:s1", true))
    }
}
