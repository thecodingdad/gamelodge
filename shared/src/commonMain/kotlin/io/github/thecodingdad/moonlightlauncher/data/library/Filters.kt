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
enum class GroupBy {
    PLATFORM, GENRE, CATEGORY, SOURCE, HOST,
    LIBRARY, DEVELOPER, PUBLISHER, SERIES, FEATURE, AGE_RATING, RELEASE_YEAR,
}

/** How a condition's values are chosen. */
enum class FieldType {
    /** Values present in the library (chips). */
    LIST,
    /** Yes / no. */
    BOOLEAN,
    /** Free text the name contains. */
    TEXT,
    /** Fixed ranges (play time, periods, rating). */
    BUCKET,
}

/** Sections of the field picker. */
enum class FieldSection { GAME, METADATA, ACTIVITY, STATUS }

/** Item property a filter condition looks at (Playnite's filter scope). */
enum class FilterField(val type: FieldType, val section: FieldSection, val multiValue: Boolean = false) {
    NAME(FieldType.TEXT, FieldSection.GAME),
    PLATFORM(FieldType.LIST, FieldSection.GAME),
    SOURCE(FieldType.LIST, FieldSection.GAME),
    LIBRARY(FieldType.LIST, FieldSection.GAME),
    CATEGORY(FieldType.LIST, FieldSection.GAME, multiValue = true),
    GROUP(FieldType.LIST, FieldSection.GAME, multiValue = true),
    INSTALLED(FieldType.BOOLEAN, FieldSection.GAME),
    /** Owned on a host but not installed there (companion); playing it starts the installation. */
    NOT_INSTALLED(FieldType.BOOLEAN, FieldSection.GAME),
    GENRE(FieldType.LIST, FieldSection.METADATA, multiValue = true),
    DEVELOPER(FieldType.LIST, FieldSection.METADATA),
    PUBLISHER(FieldType.LIST, FieldSection.METADATA),
    SERIES(FieldType.LIST, FieldSection.METADATA),
    FEATURE(FieldType.LIST, FieldSection.METADATA, multiValue = true),
    AGE_RATING(FieldType.LIST, FieldSection.METADATA),
    RELEASE_YEAR(FieldType.LIST, FieldSection.METADATA),
    RATING(FieldType.BUCKET, FieldSection.METADATA),
    PLAYED(FieldType.BOOLEAN, FieldSection.ACTIVITY),
    LAST_PLAYED(FieldType.BUCKET, FieldSection.ACTIVITY),
    PLAYTIME(FieldType.BUCKET, FieldSection.ACTIVITY),
    ADDED(FieldType.BUCKET, FieldSection.ACTIVITY),
    FAVORITE(FieldType.BOOLEAN, FieldSection.STATUS),
    /** Hidden items only show in filters with a condition on this field. */
    HIDDEN(FieldType.BOOLEAN, FieldSection.STATUS),
    REVIEW(FieldType.BOOLEAN, FieldSection.STATUS),
    ;

    val boolean: Boolean get() = type == FieldType.BOOLEAN

    /** Fixed options of a BUCKET field. */
    val buckets: List<String>
        get() = when (this) {
            PLAYTIME -> listOf("none", "lt1", "1to10", "10to100", "100to500", "gt500")
            LAST_PLAYED -> listOf("today", "yesterday", "week", "month", "year", "older", "never")
            ADDED -> listOf("today", "week", "month", "year", "older")
            RATING -> listOf("90", "80", "70", "60", "lt60", "none")
            else -> emptyList()
        }
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
    /** Multi-value fields (genres …): every chosen value must be present instead of one of them. */
    val matchAll: Boolean = false,
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
        /** Source value of games from the ROM folders. */
        const val SOURCE_EMULATOR = "emulator"
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
    const val NOT_INSTALLED = "not_installed"
    const val REVIEW = "review"
    const val HIDDEN = "hidden"
    const val EMULATION = "emulation"

    /** Default game filters in display order. */
    val gameDefaults = listOf(
        Default(CONTINUE, library = false, home = true),
        Default(ALL, library = true, home = false),
        Default(LOCAL, library = true, home = false),
        Default(EMULATION, library = true, home = false),
        Default(HOSTS, library = true, home = false),
        Default(PLATFORMS, library = true, home = false),
        Default(FAVORITES, library = true, home = true),
        Default(RECENT, library = false, home = true),
        Default(NEVER_PLAYED, library = true, home = false),
        Default(NOT_INSTALLED, library = true, home = false),
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
        // ROMs, split by platform.
        EMULATION -> FilterRules(listOf(FilterCondition(FilterField.SOURCE, setOf(FilterRules.SOURCE_EMULATOR))), groupBy = GroupBy.PLATFORM)
        HOSTS -> FilterRules(groupBy = GroupBy.HOST, chipShowName = false, rowShowName = false)
        PLATFORMS -> FilterRules(groupBy = GroupBy.PLATFORM, chipShowName = false, rowShowName = false)
        FAVORITES -> FilterRules(listOf(FilterCondition(FilterField.FAVORITE, setOf("true"))))
        NEVER_PLAYED -> FilterRules(listOf(FilterCondition(FilterField.PLAYED, setOf("true"), negate = true)))
        NOT_INSTALLED -> FilterRules(listOf(FilterCondition(FilterField.NOT_INSTALLED, setOf("true"))))
        REVIEW -> FilterRules(listOf(FilterCondition(FilterField.REVIEW, setOf("true"))))
        HIDDEN -> FilterRules(listOf(FilterCondition(FilterField.HIDDEN, setOf("true"))))
        else -> FilterRules()
    }

    /** Row id of a default filter; app defaults get their own ids. */
    fun id(key: String, scope: FilterScope) = if (scope == FilterScope.APPS) "apps_$key" else key

    /** [library] = shown as chip in its tab (library for games, Apps tab for apps). */
    data class Default(val key: String, val library: Boolean, val home: Boolean)
}

/** [MANUAL]: the filter's own order (a group's manual order); only offered for groups. */
enum class LibrarySort { TITLE, LAST_PLAYED, ADDED, PLAYTIME, MANUAL }

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
    /** Group: entries are in the user's own order. */
    val manualOrder: Boolean = false,
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
            // Members come in the group's manual order.
            val order = groupMembers[filter.id].orEmpty().withIndex().associate { it.value to it.index }
            val entries = pool.filter { !it.item.hidden && it.item.id in order }.sortedBy { order[it.item.id] }
            return listOf(ResolvedFilter(filter.id, filter.id, name, filter.builtin, null, entries, null, manualOrder = true))
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
        GroupBy.PLATFORM -> values(entry, FilterField.PLATFORM)
        GroupBy.GENRE -> values(entry, FilterField.GENRE)
        GroupBy.CATEGORY -> values(entry, FilterField.CATEGORY)
        GroupBy.SOURCE -> values(entry, FilterField.SOURCE)
        GroupBy.HOST -> entry.remoteTargets.mapNotNull { it.hostUuid }.distinct()
        GroupBy.LIBRARY -> values(entry, FilterField.LIBRARY)
        GroupBy.DEVELOPER -> values(entry, FilterField.DEVELOPER)
        GroupBy.PUBLISHER -> values(entry, FilterField.PUBLISHER)
        GroupBy.SERIES -> values(entry, FilterField.SERIES)
        GroupBy.FEATURE -> values(entry, FilterField.FEATURE)
        GroupBy.AGE_RATING -> values(entry, FilterField.AGE_RATING)
        GroupBy.RELEASE_YEAR -> values(entry, FilterField.RELEASE_YEAR)
    }

    /** Values of a LIST field for one item (what its chips offer and conditions compare). */
    fun values(entry: LibraryEntry, field: FilterField): List<String> {
        val item = entry.item
        return when (field) {
            FilterField.PLATFORM -> listOfNotNull(item.platform)
            FilterField.GENRE -> splitList(item.genres)
            FilterField.CATEGORY -> splitList(item.playniteCategories)
            FilterField.SOURCE -> buildList {
                if (entry.localTarget != null) add(FilterRules.SOURCE_LOCAL)
                if (entry.romTargets.isNotEmpty()) add(FilterRules.SOURCE_EMULATOR)
                entry.remoteTargets.mapNotNull { it.hostUuid }.distinct().forEach(::add)
            }
            FilterField.LIBRARY -> listOfNotNull(item.playnitePlugin?.takeIf { it.isNotBlank() })
            FilterField.DEVELOPER -> listOfNotNull(item.developer?.takeIf { it.isNotBlank() })
            FilterField.PUBLISHER -> listOfNotNull(item.publisher?.takeIf { it.isNotBlank() })
            FilterField.SERIES -> listOfNotNull(item.series?.takeIf { it.isNotBlank() })
            FilterField.FEATURE -> splitList(item.features)
            FilterField.AGE_RATING -> listOfNotNull(io.github.thecodingdad.moonlightlauncher.metadata.AgeRatings.display(item.ageRatings))
            FilterField.RELEASE_YEAR -> listOfNotNull(item.releaseYear?.toString())
            else -> emptyList()
        }
    }

    /** Which fixed range ([FilterField.buckets]) an item falls into; several for cumulative ranges. */
    fun buckets(entry: LibraryEntry, field: FilterField, now: Long): Set<String> {
        val item = entry.item
        val day = 24 * 60 * 60 * 1000L
        fun period(at: Long?, never: String?): Set<String> {
            at ?: return setOfNotNull(never)
            val age = now - at
            val startOfToday = java.util.Calendar.getInstance().apply {
                timeInMillis = now
                set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
            return buildSet {
                if (at >= startOfToday) add("today")
                if (at in (startOfToday - day) until startOfToday) add("yesterday")
                if (age <= 7 * day) add("week")
                if (age <= 30 * day) add("month")
                if (age <= 365 * day) add("year") else add("older")
            }
        }
        return when (field) {
            FilterField.LAST_PLAYED -> period(item.lastPlayedAt, "never")
            FilterField.ADDED -> period(item.addedAt, null)
            FilterField.PLAYTIME -> {
                val hours = item.playtimeSeconds / 3600.0
                setOf(
                    when {
                        item.playtimeSeconds <= 0 -> "none"
                        hours < 1 -> "lt1"
                        hours < 10 -> "1to10"
                        hours < 100 -> "10to100"
                        hours < 500 -> "100to500"
                        else -> "gt500"
                    },
                )
            }
            FilterField.RATING -> {
                val r = item.rating ?: return setOf("none")
                buildSet {
                    if (r >= 90) add("90")
                    if (r >= 80) add("80")
                    if (r >= 70) add("70")
                    if (r >= 60) add("60") else add("lt60")
                }
            }
            else -> emptySet()
        }
    }

    fun matches(entry: LibraryEntry, rules: FilterRules, groupMembers: Map<String, Set<String>> = emptyMap()): Boolean =
        rules.effectiveConditions.all { matches(entry, it, groupMembers) }

    fun matches(
        entry: LibraryEntry,
        condition: FilterCondition,
        groupMembers: Map<String, Set<String>> = emptyMap(),
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val field = condition.field
        // A condition without values doesn't restrict anything (yet).
        if (field.type != FieldType.BOOLEAN && condition.values.isEmpty()) return true
        val item = entry.item
        val hit = when (field.type) {
            FieldType.BOOLEAN -> when (field) {
                FilterField.INSTALLED -> entry.localTarget != null
                FilterField.NOT_INSTALLED -> entry.notInstalled
                FilterField.FAVORITE -> item.favorite
                FilterField.PLAYED -> item.lastPlayedAt != null
                FilterField.REVIEW -> item.metadataState == MetadataState.REVIEW
                FilterField.HIDDEN -> item.hidden
                else -> false
            }
            FieldType.TEXT -> {
                val title = TitleNormalizer.normalize(item.title)
                condition.values.map { TitleNormalizer.normalize(it) }.filter { it.isNotEmpty() }.let { terms ->
                    if (condition.matchAll) terms.all { it in title } else terms.any { it in title }
                }
            }
            FieldType.BUCKET -> buckets(entry, field, now).any { it in condition.values }
            FieldType.LIST -> {
                val have = if (field == FilterField.GROUP) condition.values.filter { item.id in groupMembers[it].orEmpty() }.toSet()
                else values(entry, field).toSet()
                if (condition.matchAll && field.multiValue) condition.values.all { it in have } else condition.values.any { it in have }
            }
        }
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
            // The source name (normalizedTitle) and a name edited by hand both count.
            .filter { q.isEmpty() || it.item.normalizedTitle.contains(q) || TitleNormalizer.normalize(it.item.title).contains(q) }
            .let { seq ->
                when (sort) {
                    LibrarySort.TITLE -> seq.sortedBy { TitleNormalizer.sortKey(it.item.title) }
                    LibrarySort.LAST_PLAYED -> seq.sortedByDescending { it.item.lastPlayedAt ?: 0L }
                    LibrarySort.ADDED -> seq.sortedByDescending { it.item.addedAt }
                    LibrarySort.PLAYTIME -> seq.sortedByDescending { it.item.playtimeSeconds }
                    LibrarySort.MANUAL -> seq
                }
            }
            .toList()
    }
}
