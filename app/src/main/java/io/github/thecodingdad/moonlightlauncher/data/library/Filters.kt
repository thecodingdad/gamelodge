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
enum class GroupBy { PLATFORM, GENRE, CATEGORY, SOURCE, HOST }

/** Item property a filter condition looks at. */
enum class FilterField(val boolean: Boolean = false) {
    PLATFORM, GENRE, CATEGORY, SOURCE, GROUP,
    FAVORITE(boolean = true), PLAYED(boolean = true), REVIEW(boolean = true),
    /** Hidden items only show in filters with a condition on this field. */
    HIDDEN(boolean = true),
}

/**
 * One condition: [field] is (or with [negate], is not) one of [values]. Boolean fields use the
 * value "true" ("is a favorite"; negated: "is not a favorite").
 */
@Serializable
data class FilterCondition(
    val field: FilterField,
    val values: Set<String> = emptySet(),
    val negate: Boolean = false,
)

/**
 * Rules of a free filter: every condition must match (AND). Older filters stored fixed fields
 * (platforms, favorite …); [effectiveConditions] turns those into conditions.
 */
@Serializable
data class FilterRules(
    val conditions: List<FilterCondition> = emptyList(),
    val groupBy: GroupBy? = null,
    /** Order and maximum number of items (e.g. "last played, 20"); null = title order, all. */
    val sort: LibrarySort? = null,
    val limit: Int? = null,
    /** With [groupBy]: prefix each part with the filter name ("Couch: Nintendo Switch") … */
    val chipShowName: Boolean = true,
    /** … separately for the row header on the home screen. */
    val rowShowName: Boolean = true,
    // --- legacy (schema before free conditions); read only ---
    val platforms: Set<String> = emptySet(),
    val genres: Set<String> = emptySet(),
    val categories: Set<String> = emptySet(),
    val sources: Set<String> = emptySet(),
    val favorite: Boolean? = null,
    val played: Boolean? = null,
) {
    val effectiveConditions: List<FilterCondition>
        get() = conditions + buildList {
            if (platforms.isNotEmpty()) add(FilterCondition(FilterField.PLATFORM, platforms))
            if (genres.isNotEmpty()) add(FilterCondition(FilterField.GENRE, genres))
            if (categories.isNotEmpty()) add(FilterCondition(FilterField.CATEGORY, categories))
            if (sources.isNotEmpty()) add(FilterCondition(FilterField.SOURCE, sources))
            favorite?.let { add(FilterCondition(FilterField.FAVORITE, setOf("true"), negate = !it)) }
            played?.let { add(FilterCondition(FilterField.PLAYED, setOf("true"), negate = !it)) }
        }

    /** Same rules in the current format (legacy fields folded into conditions). */
    fun normalized() = copy(
        conditions = effectiveConditions,
        platforms = emptySet(), genres = emptySet(), categories = emptySet(), sources = emptySet(), favorite = null, played = null,
    )

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

    /**
     * Rules of a default filter: defaults are ordinary free filters that keep their key only for
     * the localized name and for "restore defaults".
     */
    fun rulesFor(key: String?): FilterRules = when (key) {
        CONTINUE -> FilterRules(listOf(FilterCondition(FilterField.PLAYED, setOf("true"))), sort = LibrarySort.LAST_PLAYED, limit = 20)
        RECENT -> FilterRules(sort = LibrarySort.ADDED, limit = 20)
        LOCAL -> FilterRules(listOf(FilterCondition(FilterField.SOURCE, setOf(FilterRules.SOURCE_LOCAL))))
        HOSTS -> FilterRules(groupBy = GroupBy.HOST, chipShowName = false, rowShowName = false)
        PLATFORMS -> FilterRules(groupBy = GroupBy.PLATFORM, chipShowName = false, rowShowName = false)
        FAVORITES -> FilterRules(listOf(FilterCondition(FilterField.FAVORITE, setOf("true"))))
        NEVER_PLAYED -> FilterRules(listOf(FilterCondition(FilterField.PLAYED, setOf("true"), negate = true)))
        REVIEW -> FilterRules(listOf(FilterCondition(FilterField.REVIEW, setOf("true"))))
        HIDDEN -> FilterRules(listOf(FilterCondition(FilterField.HIDDEN, setOf("true"))))
        else -> FilterRules()
    }

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
    /** Custom filter name; null = the default's localized name ([builtin]). */
    val name: String?,
    val builtin: String?,
    /** For split filters: the value of this part (platform id, host uuid …) and its label. */
    val value: String?,
    val entries: List<LibraryEntry>,
    /** Fixed order (e.g. last played); null = title order. */
    val rowSort: LibrarySort?,
    val valueLabel: String? = null,
    val chipShowName: Boolean = true,
    val rowShowName: Boolean = true,
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
        val name = filter.name.ifBlank { null }
        if (filter.kind == FilterKind.GROUP) {
            val members = groupMembers[filter.id].orEmpty()
            return listOf(ResolvedFilter(filter.id, filter.id, name, filter.builtin, null, pool.filter { !it.item.hidden && it.item.id in members }, null))
        }
        val rules = effectiveRules(filter)
        val matching = apply(pool, rules, groupMembers)
        val by = rules.groupBy
            ?: return listOf(ResolvedFilter(filter.id, filter.id, name, filter.builtin, null, matching, rules.sort))
        val byValue = LinkedHashMap<String, MutableList<LibraryEntry>>()
        matching.forEach { e -> values(e, by).forEach { byValue.getOrPut(it) { mutableListOf() }.add(e) } }
        return byValue.entries
            .sortedBy { (value, _) -> label(value, by, hostNames).lowercase() }
            .map { (value, list) ->
                ResolvedFilter(
                    "${filter.id}:$value", filter.id, name, filter.builtin, value, list, rules.sort,
                    valueLabel = label(value, by, hostNames), chipShowName = rules.chipShowName, rowShowName = rules.rowShowName,
                )
            }
    }

    /** Rules of a filter; old default rows (BUILTIN, e.g. from a backup) use their default rules. */
    fun effectiveRules(filter: FilterEntity): FilterRules =
        if (filter.kind == FilterKind.BUILTIN) BuiltinFilter.rulesFor(filter.builtin) else FilterRules.decode(filter.rules).normalized()

    /**
     * Items of [pool] matching [rules], in the rules' order and limit. Hidden items only count
     * when a condition looks at "hidden".
     */
    fun apply(pool: List<LibraryEntry>, rules: FilterRules, groupMembers: Map<String, Set<String>> = emptyMap()): List<LibraryEntry> {
        val withHidden = rules.effectiveConditions.any { it.field == FilterField.HIDDEN }
        val matching = pool.filter { (withHidden || !it.item.hidden) && matches(it, rules, groupMembers) }
        val sorted = rules.sort?.let { query(matching, it, "") } ?: matching
        return rules.limit?.let { sorted.take(it) } ?: sorted
    }

    private fun label(value: String, by: GroupBy, hostNames: Map<String, String>) =
        if (by == GroupBy.SOURCE || by == GroupBy.HOST) hostNames[value] ?: value else value

    fun values(entry: LibraryEntry, by: GroupBy): List<String> = when (by) {
        GroupBy.PLATFORM -> listOfNotNull(entry.item.platform)
        GroupBy.GENRE -> splitList(entry.item.genres)
        GroupBy.CATEGORY -> splitList(entry.item.playniteCategories)
        GroupBy.SOURCE -> buildList {
            if (entry.localTarget != null) add(FilterRules.SOURCE_LOCAL)
            entry.remoteTargets.mapNotNull { it.hostUuid }.distinct().forEach(::add)
        }
        GroupBy.HOST -> entry.remoteTargets.mapNotNull { it.hostUuid }.distinct()
    }

    fun matches(entry: LibraryEntry, rules: FilterRules, groupMembers: Map<String, Set<String>> = emptyMap()): Boolean =
        rules.effectiveConditions.all { matches(entry, it, groupMembers) }

    fun matches(entry: LibraryEntry, condition: FilterCondition, groupMembers: Map<String, Set<String>> = emptyMap()): Boolean {
        val hit = when (condition.field) {
            FilterField.PLATFORM -> values(entry, GroupBy.PLATFORM).any { it in condition.values }
            FilterField.GENRE -> values(entry, GroupBy.GENRE).any { it in condition.values }
            FilterField.CATEGORY -> values(entry, GroupBy.CATEGORY).any { it in condition.values }
            FilterField.SOURCE -> values(entry, GroupBy.SOURCE).any { it in condition.values }
            FilterField.GROUP -> condition.values.any { entry.item.id in groupMembers[it].orEmpty() }
            FilterField.FAVORITE -> entry.item.favorite
            FilterField.PLAYED -> entry.item.lastPlayedAt != null
            FilterField.REVIEW -> entry.item.metadataState == MetadataState.REVIEW
            FilterField.HIDDEN -> entry.item.hidden
        }
        // A list condition without values doesn't restrict anything (yet).
        if (!condition.field.boolean && condition.values.isEmpty()) return true
        return hit != condition.negate
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
