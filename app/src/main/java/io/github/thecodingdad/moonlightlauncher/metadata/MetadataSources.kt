package io.github.thecodingdad.moonlightlauncher.metadata

import io.github.thecodingdad.moonlightlauncher.data.companion.PairedServer

/**
 * The configured ways to SteamGridDB / IGDB in their fixed order: own keys first (shortest
 * way), then paired metadata servers, then companions. The settings only remember which are
 * switched off ("!sgdb", "!server:<id>", "!companion:<id>").
 */
object MetadataSources {
    const val SGDB = "sgdb"
    const val IGDB = "igdb"
    private const val SERVER = "server:"
    private const val COMPANION = "companion:"

    data class Entry(val id: String, val enabled: Boolean, val server: PairedServer? = null)

    fun idOf(server: PairedServer): String = (if (server.isCompanion) COMPANION else SERVER) + server.id

    fun resolve(stored: List<String>, servers: List<PairedServer>, hasSgdb: Boolean, hasIgdb: Boolean): List<Entry> {
        val off = stored.filter { it.startsWith("!") }.map { it.removePrefix("!") }.toSet()
        // Older versions stored an order; "!own" switched both own keys off.
        val ownOff = "own" in off
        fun entry(id: String, server: PairedServer? = null) = Entry(id, id !in off && !(server == null && ownOff), server)
        return buildList {
            if (hasSgdb) add(entry(SGDB))
            if (hasIgdb) add(entry(IGDB))
            servers.filter { !it.isCompanion }.forEach { add(entry(idOf(it), it)) }
            servers.filter { it.isCompanion }.forEach { add(entry(idOf(it), it)) }
        }
    }

    /** [stored] with [id] switched on or off, or forgotten (null: removed); only off markers are kept. */
    fun set(stored: List<String>, id: String, enabled: Boolean?): List<String> {
        val off = stored.filter { it.startsWith("!") }.map { it.removePrefix("!") }
            .flatMap { if (it == "own") listOf(SGDB, IGDB) else listOf(it) }
            .filter { it != id && it != "server" && it != "companion" }.distinct()
        return off.map { "!$it" } + listOfNotNull("!$id".takeIf { enabled == false })
    }
}
