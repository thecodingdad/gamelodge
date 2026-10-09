package io.github.thecodingdad.moonlightlauncher.metadata

import io.github.thecodingdad.moonlightlauncher.data.library.TitleNormalizer
import kotlin.math.max
import kotlin.math.min

/**
 * Scores how well a database title matches a library title (0..1).
 * Combines edit-distance similarity with token overlap so that "Witcher 3" vs
 * "The Witcher 3: Wild Hunt" scores lower than an exact match but still plausible.
 */
object MatchScorer {
    /** At or above: applied automatically. */
    const val AUTO_ACCEPT = 0.9f
    /** At or above (and below [AUTO_ACCEPT]): applied but flagged for review. */
    const val REVIEW = 0.6f

    fun score(query: String, candidate: String, releaseYear: Int? = null, expectedYear: Int? = null): Float {
        val a = TitleNormalizer.normalize(query)
        val b = TitleNormalizer.normalize(candidate)
        if (a.isEmpty() || b.isEmpty()) return 0f
        if (a == b) return yearAdjusted(1f, releaseYear, expectedYear)

        val edit = 1f - levenshtein(a, b).toFloat() / max(a.length, b.length)
        val ta = a.split(' ').toSet()
        val tb = b.split(' ').toSet()
        val jaccard = ta.intersect(tb).size.toFloat() / ta.union(tb).size
        // Candidate starts with the full query ("Celeste" vs "Celeste Classic") is common for
        // spin-offs, so it's plausible but must never auto-accept.
        val prefixBonus = if (b.startsWith("$a ") || a.startsWith("$b ")) 0.1f else 0f
        val combined = (0.55f * edit + 0.45f * jaccard + prefixBonus).coerceAtMost(0.89f)
        return yearAdjusted(combined, releaseYear, expectedYear)
    }

    private fun yearAdjusted(score: Float, year: Int?, expected: Int?): Float {
        if (year == null || expected == null) return score
        return if (year == expected) min(1f, score + 0.05f) else score - 0.05f
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
