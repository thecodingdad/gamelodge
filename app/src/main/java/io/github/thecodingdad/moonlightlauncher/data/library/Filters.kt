package io.github.thecodingdad.moonlightlauncher.data.library

import io.github.thecodingdad.moonlightlauncher.data.db.FilterEntity
import io.github.thecodingdad.moonlightlauncher.data.db.FilterKind
import io.github.thecodingdad.moonlightlauncher.data.db.FilterScope
import io.github.thecodingdad.moonlightlauncher.data.db.ItemKind
import io.github.thecodingdad.moonlightlauncher.data.db.LibraryEntry
import io.github.thecodingdad.moonlightlauncher.data.db.MetadataState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Dimension a filter can be split by: one chip/row per distinct value. */
enum class GroupBy { PLATFORM, GENRE, CATEGORY, SOURCE }

/** Rules of a smart filter. Empty sets match everything; values within a set are OR-ed, sets AND-ed. */
@Serializable
data class FilterRules(
    val platforms: Set<String> = emptySet(),
    val genres: Set<String> = emptySet(),
    val categories: Set<String> = emptySet(),
    /** [SOURCE_LOCAL] or host UUIDs. */
    val sources: Set<String> = emptySet(),
    val favorite: Boolean? = null,
    val played: Boolean? = null,
    val groupBy: GroupBy? = null,
) {
    companion object {
        const val SOURCE_LOCAL = "local"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        fun decode(raw: String?): FilterRules = raw?.let { runCatching { json.decodeFromString<FilterRules>(it) }.getOrNull() } ?: FilterRules()
        fun encode(rules: FilterRules): String = json.encodeToString(serializer(), rules)
    }
}

/** Keys of the default filters. */
object BuiltinFilter {
    const val ALL = "all"
    const val CONTINUE = "continue"
    const val RECENT = "recent"
    const val LOCAL = "local"
    const val HOSTS = "hosts"
    const val PLATFORMS = "platforms"
    const val FAVORITES = "favorites"
    const val NEVER_PLAYED = "never_played"
    const val REVIEW = "review"
    const val HIDDEN = "hidden"

    /** Default game filters in display order. */
    val gameDefaults = listOf(
        Default(CONTINUE, library = false, home = true),
        Default(ALL, library = true, home = false),
        Default(LOCAL, library = true, home = false),
        Default(HOSTS, library = true, home = false),
        Default(PLATFORMS, library = true, home = false),
        Default(FAVORITES, library = true, home = true),
        Default(RECENT, library = false, home = true),
        Default(NEVER_PLAYED, library = true, home = false),
        Default(REVIEW, library = true, home = false),
        Default(HIDDEN, library = true, home = false),
    )

    /** Default app filters (chips in the Apps tab). */
    val appDefaults = listOf(
        Default(ALL, library = true, home = false),
        Default(LOCAL, library = true, home = false),
        Default(HOSTS, library = true, home = false),
        Default(FAVORITES, library = true, home = false),
        Default(HIDDEN, library = true, home = false),
    )

    /** Row id of a default filter; app defaults get their own ids. */
    fun id(key: String, scope: FilterScope) = if (scope == FilterScope.APPS) "apps_$key" else key

    /** [library] = shown as chip in its tab (library for games, Apps tab for apps). */
    data class Default(val key: String, val library: Boolean, val home: Boolean)
}

enum class LibrarySort { TITLE, LAST_PLAYED, ADDED, PLAYTIME }

/** Where a filter is being shown; decides which items it sees. */
enum class FilterPlace { LIBRARY, HOME, APPS }

/** A filter ready for display: one chip or home row. Split filters produce one per value. */
data class ResolvedFilter(
    /** Stable key for selection/focus, e.g. `all`, `<id>`, `<id>:Nintendo Switch`. */
    val key: String,
    val filterId: String,
    /** Display name; null = use the builtin's localized name ([builtin]) or [value]. */
    val name: String?,
    val builtin: String?,
    /** For split filters: the value of this part (platform, genre, host name …). */
    val value: String?,
    val entries: List<LibraryEntry>,
    /** Fixed order for home rows (continue/recent); null = title order. */
    val rowSort: LibrarySort?,
)

object FilterEngine {
    /** Host UUID → name, for source splits and labels. */
    fun resolve(
        filters: List<FilterEntity>,
        place: FilterPlace,
        entries: List<LibraryEntry>,
        hostNames: Map<String, String>,
        groupMembers: Map<String, Set<String>>,
    ): List<ResolvedFilter> {
        // Game and app filters are separate sets; legacy BOTH rows count as game filters.
        val visible = filters.filter {
            when (place) {
                FilterPlace.LIBRARY -> it.scope != FilterScope.APPS && it.showInLibrary
                FilterPlace.HOME -> it.showOnHome
                FilterPlace.APPS -> it.scope == FilterScope.APPS && it.showInApps
            }
        }.sortedBy { it.sortOrder }
        return visible.flatMap { filter ->
            val pool = entries.filter { e ->
                when (place) {
                    FilterPlace.LIBRARY -> e.item.kind == ItemKind.GAME
                    FilterPlace.APPS -> e.item.kind == ItemKind.APP
                    FilterPlace.HOME -> when (filter.scope) {
                        FilterScope.GAMES -> e.item.kind == ItemKind.GAME
                        FilterScope.APPS -> e.item.kind == ItemKind.APP
                        FilterScope.BOTH -> true
                    }
                }
            }
            resolveOne(filter, pool, hostNames, groupMembers)
        }.filter { it.entries.isNotEmpty() || (it.builtin == BuiltinFilter.ALL && place != FilterPlace.HOME) }
    }

    private fun resolveOne(
        filter: FilterEntity,
        pool: List<LibraryEntry>,
        hostNames: Map<String, String>,
        groupMembers: Map<String, Set<String>>,
    ): List<ResolvedFilter> {
        val shown = pool.filter { !it.item.hidden }
        fun single(entries: List<LibraryEntry>, sort: LibrarySort? = null) =
            listOf(ResolvedFilter(filter.id, filter.id, filter.name.ifBlank { null }, filter.builtin, null, entries, sort))
        return when (filter.kind) {
            FilterKind.BUILTIN -> when (filter.builtin) {
                BuiltinFilter.ALL -> single(shown)
                BuiltinFilter.CONTINUE -> single(
                    shown.filter { it.item.lastPlayedAt != null }.sortedByDescending { it.item.lastPlayedAt }.take(20),
                    LibrarySort.LAST_PLAYED,
                )
                BuiltinFilter.RECENT -> single(shown.sortedByDescending { it.item.addedAt }.take(20), LibrarySort.ADDED)
                BuiltinFilter.LOCAL -> single(shown.filter { it.localTarget != null })
                BuiltinFilter.HOSTS -> split(filter, shown, GroupBy.SOURCE, hostNames, onlyHosts = true)
                BuiltinFilter.PLATFORMS -> split(filter, shown, GroupBy.PLATFORM, hostNames)
                BuiltinFilter.FAVORITES -> single(shown.filter { it.item.favorite })
                BuiltinFilter.NEVER_PLAYED -> single(shown.filter { it.item.lastPlayedAt == null })
                BuiltinFilter.REVIEW -> single(shown.filter { it.item.metadataState == MetadataState.REVIEW })
                BuiltinFilter.HIDDEN -> single(pool.filter { it.item.hidden })
                else -> emptyList()
            }
            FilterKind.GROUP -> {
                val members = groupMembers[filter.id].orEmpty()
                single(shown.filter { it.item.id in members })
            }
            FilterKind.SMART -> {
                val rules = FilterRules.decode(filter.rules)
                val matching = shown.filter { matches(it, rules) }
                rules.groupBy?.let { split(filter, matching, it, hostNames) } ?: single(matching)
            }
        }
    }

    private fun split(
        filter: FilterEntity,
        entries: List<LibraryEntry>,
        by: GroupBy,
        hostNames: Map<String, String>,
        onlyHosts: Boolean = false,
    ): List<ResolvedFilter> {
        val byValue = LinkedHashMap<String, MutableList<LibraryEntry>>()
        entries.forEach { e ->
            values(e, by).filter { !onlyHosts || it != FilterRules.SOURCE_LOCAL }.forEach { byValue.getOrPut(it) { mutableListOf() }.add(e) }
        }
        return byValue.entries
            .sortedBy { (value, _) -> label(value, by, hostNames).lowercase() }
            .map { (value, list) ->
                val label = label(value, by, hostNames)
                // A split filter with a custom name keeps it as prefix ("Couch: Nintendo Switch").
                val name = if (filter.kind == FilterKind.SMART && filter.name.isNotBlank()) "${filter.name}: $label" else label
                ResolvedFilter("${filter.id}:$value", filter.id, name, filter.builtin, value, list, null)
            }
    }

    private fun label(value: String, by: GroupBy, hostNames: Map<String, String>) =
        if (by == GroupBy.SOURCE) hostNames[value] ?: value else value

    fun values(entry: LibraryEntry, by: GroupBy): List<String> = when (by) {
        GroupBy.PLATFORM -> listOfNotNull(entry.item.platform)
        GroupBy.GENRE -> splitList(entry.item.genres)
        GroupBy.CATEGORY -> splitList(entry.item.playniteCategories)
        GroupBy.SOURCE -> buildList {
            if (entry.localTarget != null) add(FilterRules.SOURCE_LOCAL)
            entry.remoteTargets.mapNotNull { it.hostUuid }.distinct().forEach(::add)
        }
    }

    fun matches(entry: LibraryEntry, rules: FilterRules): Boolean {
        fun anyOf(set: Set<String>, by: GroupBy) = set.isEmpty() || values(entry, by).any { it in set }
        return anyOf(rules.platforms, GroupBy.PLATFORM) &&
            anyOf(rules.genres, GroupBy.GENRE) &&
            anyOf(rules.categories, GroupBy.CATEGORY) &&
            anyOf(rules.sources, GroupBy.SOURCE) &&
            (rules.favorite == null || entry.item.favorite == rules.favorite) &&
            (rules.played == null || (entry.item.lastPlayedAt != null) == rules.played)
    }

    private fun splitList(raw: String?): List<String> =
        raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    /** All distinct values of a dimension, for the rule editor. */
    fun allValues(entries: List<LibraryEntry>, by: GroupBy): List<String> =
        entries.flatMap { values(it, by) }.distinct().sortedBy { it.lowercase() }

    /** Search + sort on top of a filter's entries (library grid). */
    fun query(entries: List<LibraryEntry>, sort: LibrarySort, search: String): List<LibraryEntry> {
        val q = TitleNormalizer.normalize(search)
        return entries.asSequence()
            .filter { q.isEmpty() || it.item.normalizedTitle.contains(q) }
            .let { seq ->
                when (sort) {
                    LibrarySort.TITLE -> seq.sortedBy { TitleNormalizer.sortKey(it.item.title) }
                    LibrarySort.LAST_PLAYED -> seq.sortedByDescending { it.item.lastPlayedAt ?: 0L }
                    LibrarySort.ADDED -> seq.sortedByDescending { it.item.addedAt }
                    LibrarySort.PLAYTIME -> seq.sortedByDescending { it.item.playtimeSeconds }
                }
            }
            .toList()
    }
}
