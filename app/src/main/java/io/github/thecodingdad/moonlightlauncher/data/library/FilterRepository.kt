package io.github.thecodingdad.moonlightlauncher.data.library

import androidx.room.withTransaction
import io.github.thecodingdad.moonlightlauncher.data.db.CollectionEntity
import io.github.thecodingdad.moonlightlauncher.data.db.FilterEntity
import io.github.thecodingdad.moonlightlauncher.data.db.FilterKind
import io.github.thecodingdad.moonlightlauncher.data.db.FilterScope
import io.github.thecodingdad.moonlightlauncher.data.db.LauncherDatabase
import io.github.thecodingdad.moonlightlauncher.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** Library filters: default filters, smart filters and manual groups (backed by collections). */
class FilterRepository(
    private val db: LauncherDatabase,
    private val settings: SettingsRepository,
) {
    private val dao = db.filterDao()

    val filters: Flow<List<FilterEntity>> = dao.observeAll()

    /** First start (or first start after an update): create the default filters once. */
    suspend fun ensureDefaults() {
        val current = settings.current()
        if (!current.filtersSeeded) {
            restoreDefaults(FilterScope.GAMES)
        }
        if (!current.appFiltersSeeded) {
            // Until now filters were shared; existing ones become game filters.
            dao.upsert(
                dao.getAll().filter { it.scope == FilterScope.BOTH }
                    .map { it.copy(scope = FilterScope.GAMES, showInApps = false) },
            )
            restoreDefaults(FilterScope.APPS)
        }
        settings.update { it.copy(filtersSeeded = true, appFiltersSeeded = true) }
    }

    /** Re-adds default filters of one scope the user deleted; existing ones stay untouched. */
    suspend fun restoreDefaults(scope: FilterScope) {
        val existing = dao.getAll()
        val inScope = existing.filter { (it.scope == FilterScope.APPS) == (scope == FilterScope.APPS) }
        val present = inScope.mapNotNull { it.builtin }.toSet()
        val defaults = if (scope == FilterScope.APPS) BuiltinFilter.appDefaults else BuiltinFilter.gameDefaults
        val base = (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1
        val missing = defaults.withIndex().filter { it.value.key !in present }.map { (index, d) ->
            FilterEntity(
                id = BuiltinFilter.id(d.key, scope), name = "", kind = FilterKind.BUILTIN, builtin = d.key, scope = scope,
                showInLibrary = d.library && scope != FilterScope.APPS,
                showInApps = d.library && scope == FilterScope.APPS,
                showOnHome = d.home,
                sortOrder = if (inScope.isEmpty()) index else base + index,
            )
        }
        if (missing.isNotEmpty()) dao.upsert(missing)
    }

    suspend fun save(filter: FilterEntity) = dao.upsert(listOf(filter))

    suspend fun createSmart(name: String, rules: FilterRules, scope: FilterScope): FilterEntity {
        val apps = scope == FilterScope.APPS
        val filter = FilterEntity(
            id = UUID.randomUUID().toString(), name = name, kind = FilterKind.SMART,
            rules = FilterRules.encode(rules), scope = scope, sortOrder = nextOrder(),
            showInLibrary = !apps, showInApps = apps,
        )
        dao.upsert(listOf(filter))
        return filter
    }

    /** A manual group is a collection (members) plus its filter row (display options). */
    suspend fun createGroup(name: String, scope: FilterScope): FilterEntity = db.withTransaction {
        val id = UUID.randomUUID().toString()
        val order = nextOrder()
        val apps = scope == FilterScope.APPS
        db.collectionDao().upsert(CollectionEntity(id, name, order))
        FilterEntity(
            id = id, name = name, kind = FilterKind.GROUP, scope = scope, sortOrder = order,
            showInLibrary = !apps, showInApps = apps, showOnHome = !apps,
        )
            .also { dao.upsert(listOf(it)) }
    }

    suspend fun rename(filter: FilterEntity, name: String) {
        dao.upsert(listOf(filter.copy(name = name)))
        if (filter.kind == FilterKind.GROUP) db.collectionDao().upsert(CollectionEntity(filter.id, name, filter.sortOrder))
    }

    suspend fun delete(filter: FilterEntity) = db.withTransaction {
        dao.delete(filter.id)
        if (filter.kind == FilterKind.GROUP) db.collectionDao().delete(CollectionEntity(filter.id, filter.name, 0))
    }

    /** Moves a filter one place up (-1) or down (+1) and renumbers all. */
    suspend fun move(id: String, delta: Int) {
        val all = dao.getAll().sortedBy { it.sortOrder }
        val moving = all.firstOrNull { it.id == id } ?: return
        // Game and app filters are ordered independently: move within the own set only.
        val apps = moving.scope == FilterScope.APPS
        val list = all.filter { (it.scope == FilterScope.APPS) == apps }.toMutableList()
        val from = list.indexOf(moving)
        val to = (from + delta).coerceIn(0, list.lastIndex)
        if (from == to) return
        val slots = list.map { it.sortOrder }.sorted()
        list.add(to, list.removeAt(from))
        dao.upsert(list.mapIndexed { index, f -> f.copy(sortOrder = slots[index]) })
    }

    private suspend fun nextOrder() = (dao.getAll().maxOfOrNull { it.sortOrder } ?: -1) + 1
}
