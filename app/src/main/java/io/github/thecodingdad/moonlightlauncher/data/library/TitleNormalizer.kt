package io.github.thecodingdad.moonlightlauncher.data.library

import java.text.Normalizer

/**
 * Normalises titles so the same game from different sources (Android package label,
 * host app list, metadata databases) compares equal.
 */
object TitleNormalizer {
    private val trademarks = Regex("[™®©]")
    private val diacritics = Regex("\\p{Mn}+")
    private val nonAlphaNum = Regex("[^a-z0-9]+")
    private val editionSuffix = Regex(
        "\\b(goty|game of the year|definitive|deluxe|complete|ultimate|gold|remastered|enhanced|anniversary)( edition)?$",
    )
    private val romanNumerals = mapOf(
        "ii" to "2", "iii" to "3", "iv" to "4", "v" to "5",
        "vi" to "6", "vii" to "7", "viii" to "8", "ix" to "9", "x" to "10",
    )

    /** Title as a search term: store names carry ™/® that IGDB and SteamGridDB don't match. */
    fun searchTerm(title: String): String = trademarks.replace(title, "").replace(Regex("\\s+"), " ").trim()

    fun normalize(title: String): String {
        // Strip ™/®/© first: NFKD would decompose "™" into the letters "TM".
        var s = trademarks.replace(title, "")
        s = Normalizer.normalize(s, Normalizer.Form.NFKD)
        s = diacritics.replace(s, "")
        s = s.lowercase()
        s = s.replace("&", " and ")
        s = nonAlphaNum.replace(s, " ").trim()
        s = editionSuffix.replace(s, "").trim()
        return s.split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { romanNumerals[it] ?: it }
    }

    /** Title used for sorting: leading articles are ignored ("The Witcher 3" sorts under W). */
    fun sortKey(title: String): String =
        normalize(title).removePrefix("the ").removePrefix("a ")
}
