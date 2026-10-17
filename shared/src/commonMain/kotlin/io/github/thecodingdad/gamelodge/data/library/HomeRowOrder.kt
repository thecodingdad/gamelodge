package io.github.thecodingdad.gamelodge.data.library

/**
 * Order of the start tab rows, kept apart from the filter order (library chips). The saved
 * list holds filter ids and [HA] for the smart home row; filters missing from it follow in
 * filter order.
 */
object HomeRowOrder {
    const val HA = "ha"

    /** Without a saved position the smart home row comes after the first two rows. */
    private const val HA_DEFAULT_INDEX = 2

    /** All rows in display order, for the settings list. */
    fun ids(filterIds: List<String>, saved: List<String>): List<String> {
        val known = saved.filter { it == HA || it in filterIds }.distinct()
        val list = (known + filterIds.filter { it !in known }).toMutableList()
        if (HA !in list) list.add(minOf(HA_DEFAULT_INDEX, list.size), HA)
        return list
    }

    /** Resolved start tab rows in the saved order; parts of a split filter stay together. */
    fun sort(rows: List<ResolvedFilter>, saved: List<String>): List<ResolvedFilter> {
        if (saved.isEmpty()) return rows
        val pos = position(saved)
        return rows.withIndex()
            .sortedWith(compareBy({ pos[it.value.filterId] ?: Int.MAX_VALUE }, { it.index }))
            .map { it.value }
    }

    /** Index in the sorted [rows] before which the smart home row is shown. */
    fun haIndex(rows: List<ResolvedFilter>, saved: List<String>): Int {
        val pos = position(saved)
        val ha = pos[HA] ?: return minOf(HA_DEFAULT_INDEX, rows.size)
        return rows.count { (pos[it.filterId] ?: Int.MAX_VALUE) < ha }
    }

    private fun position(saved: List<String>) = saved.withIndex().associate { it.value to it.index }
}
