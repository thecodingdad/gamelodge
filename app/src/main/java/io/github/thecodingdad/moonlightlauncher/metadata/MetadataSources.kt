package io.github.thecodingdad.moonlightlauncher.metadata

import io.github.thecodingdad.moonlightlauncher.data.companion.PairedServer

/**
 * The configured metadata sources in the user's order. Stored as ids in the settings
 * ("sgdb", "igdb", "server:<id>", "companion:<id>"; a leading "!" means switched off). Only
 * sources that are still configured count; configured ones missing from the stored order (an
 * older launcher, or added elsewhere) are appended.
 */
object MetadataSources {
    const val SGDB = "sgdb"
    const val IGDB = "igdb"
    private const val SERVER = "server:"
    private const val COMPANION = "companion:"

    data class Entry(val id: String, val enabled: Boolean, val server: PairedServer? = null)

    fun idOf(server: PairedServer): String = (if (server.isCompanion) COMPANION else SERVER) + server.id

    fun resolve(stored: List<String>, servers: List<PairedServer>, hasSgdb: Boolean, hasIgdb: Boolean): List<Entry> {
        val byId = servers.associateBy(::idOf)
        val result = LinkedHashMap<String, Entry>()
        fun add(id: String, enabled: Boolean) {
            if (id in result) return
            when {
                id == SGDB -> if (hasSgdb) result[id] = Entry(id, enabled)
                id == IGDB -> if (hasIgdb) result[id] = Entry(id, enabled)
                else -> byId[id]?.let { result[id] = Entry(id, enabled, it) }
            }
        }
        for (raw in stored) {
            val enabled = !raw.startsWith("!")
            when (val id = raw.removePrefix("!")) {
                // Ids of the first version: one entry per kind.
                "own" -> { add(SGDB, enabled); add(IGDB, enabled) }
                "server" -> servers.filter { !it.isCompanion }.forEach { add(idOf(it), enabled) }
                "companion" -> servers.filter { it.isCompanion }.forEach { add(idOf(it), enabled) }
                else -> add(id, enabled)
            }
        }
        servers.filter { !it.isCompanion }.forEach { add(idOf(it), true) }
        servers.filter { it.isCompanion }.forEach { add(idOf(it), true) }
        add(SGDB, true)
        add(IGDB, true)
        return result.values.toList()
    }

    /** What to store for [entries]. */
    fun store(entries: List<Entry>): List<String> = entries.map { if (it.enabled) it.id else "!${it.id}" }
}
