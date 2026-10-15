package io.github.thecodingdad.moonlightlauncher.metadata

import java.util.Locale

/** Age ratings of several systems, stored as "PEGI:12;USK:12;ESRB:T"; shown in one per language. */
object AgeRatings {
    val SYSTEMS = listOf("USK", "PEGI", "ESRB")

    /** Common values per system, for the editor. */
    val VALUES = mapOf(
        "USK" to listOf("0", "6", "12", "16", "18"),
        "PEGI" to listOf("3", "7", "12", "16", "18"),
        "ESRB" to listOf("EC", "E", "E10+", "T", "M", "AO", "RP"),
    )

    private val WORDS = mapOf(
        "THREE" to "3", "SEVEN" to "7", "TWELVE" to "12", "SIXTEEN" to "16", "EIGHTEEN" to "18",
        "USK_0" to "0", "USK_6" to "6", "USK_12" to "12", "USK_16" to "16", "USK_18" to "18", "E10" to "E10+",
    )

    fun normalizeValue(raw: String): String {
        val v = raw.trim().uppercase()
        return WORDS[v] ?: v.removePrefix("PEGI").removePrefix("USK").removePrefix("ESRB").trim().ifEmpty { v }
    }

    fun decode(raw: String?): Map<String, String> = raw.orEmpty().split(';').mapNotNull { part ->
        val (system, value) = part.split(':', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
        system.trim() to value.trim()
    }.filter { it.first.isNotEmpty() && it.second.isNotEmpty() }.toMap()

    fun encode(ratings: Map<String, String>): String? =
        ratings.entries.joinToString(";") { "${it.key}:${it.value}" }.ifEmpty { null }

    /** Systems in preference order for the current language: German USK, English ESRB, PEGI as fallback. */
    fun preferredSystems(locale: Locale = Locale.getDefault()): List<String> = when (locale.language) {
        "de" -> listOf("USK", "PEGI", "ESRB")
        "en" -> listOf("ESRB", "PEGI", "USK")
        else -> listOf("PEGI", "USK", "ESRB")
    }

    /** The system the editor offers for the current language. */
    fun primarySystem(locale: Locale = Locale.getDefault()): String = preferredSystems(locale).first()

    /** "USK 12", "PEGI 16", "ESRB T" for display and filtering, or null. */
    fun display(raw: String?, locale: Locale = Locale.getDefault()): String? {
        val all = decode(raw)
        val system = preferredSystems(locale).firstOrNull { it in all } ?: return null
        return "$system ${all.getValue(system)}"
    }
}
