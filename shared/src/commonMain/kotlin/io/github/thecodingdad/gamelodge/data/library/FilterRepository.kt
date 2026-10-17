package io.github.thecodingdad.gamelodge.data.library

import io.github.thecodingdad.gamelodge.data.db.withTransaction
import io.github.thecodingdad.gamelodge.data.db.CollectionEntity
import io.github.thecodingdad.gamelodge.data.db.FilterEntity
import io.github.thecodingdad.gamelodge.data.db.FilterKind
import io.github.thecodingdad.gamelodge.data.db.FilterScope
import io.github.thecodingdad.gamelodge.data.db.LauncherDatabase
import io.github.thecodingdad.gamelodge.data.settings.SettingsRepository
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
        } else if (!current.notInstalledFilterSeeded && dao.getAll().none { it.builtin == BuiltinFilter.NOT_INSTALLED }) {
            // Default added in a later version: append it once for existing installs.
            val d = BuiltinFilter.gameDefaults.first { it.key == BuiltinFilter.NOT_INSTALLED }
            dao.upsert(listOf(defaultEntity(d, FilterScope.GAMES, (dao.getAll().maxOfOrNull { it.sortOrder } ?: -1) + 1)))
        }
        if (current.filtersSeeded && !current.emulationFilterSeeded && dao.getAll().none { it.builtin == BuiltinFilter.EMULATION }) {
            // Default added with emulator support: appended once for existing installs.
            val d = BuiltinFilter.gameDefaults.first { it.key == BuiltinFilter.EMULATION }
            dao.upsert(listOf(defaultEntity(d, FilterScope.GAMES, (dao.getAll().maxOfOrNull { it.sortOrder } ?: -1) + 1)))
        }
        if (!current.appFiltersSeeded) {
            // Until now filters were shared; existing ones become game filters.
            dao.upsert(
                dao.getAll().filter { it.scope == FilterScope.BOTH }
                    .map { it.copy(scope = FilterScope.GAMES, showInApps = false) },
            )
            restoreDefaults(FilterScope.APPS)
        }
        settings.update { it.copy(filtersSeeded = true, appFiltersSeeded = true, notInstalledFilterSeeded = true, emulationFilterSeeded = true) }
        // Default filters used to be special rows; they are ordinary free filters now.
        val builtin = dao.getAll().filter { it.kind == FilterKind.BUILTIN }
        if (builtin.isNotEmpty()) {
            dao.upsert(builtin.map { it.copy(kind = FilterKind.SMART, rules = FilterRules.encode(BuiltinFilter.rulesFor(it.builtin))) })
        }
    }

    /** Re-adds default filters of one scope the user deleted; existing ones stay untouched. */
    suspend fun restoreDefaults(scope: FilterScope) {
        val existing = dao.getAll()
        val inScope = existing.filter { (it.scope == FilterScope.APPS) == (scope == FilterScope.APPS) }
        val present = inScope.mapNotNull { it.builtin }.toSet()
        val defaults = if (scope == FilterScope.APPS) BuiltinFilter.appDefaults else BuiltinFilter.gameDefaults
        val base = (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1
        val missing = defaults.withIndex().filter { it.value.key !in present }.map { (index, d) ->
            defaultEntity(d, scope, if (inScope.isEmpty()) index else base + index)
        }
        if (missing.isNotEmpty()) dao.upsert(missing)
    }

    /** Default filters of one scope back to their original rules, name and visibility (deleted ones re-added). */
    suspend fun resetDefaults(scope: FilterScope) {
        val apps = scope == FilterScope.APPS
        val defaults = if (apps) BuiltinFilter.appDefaults else BuiltinFilter.gameDefaults
        val existing = dao.getAll().filter { (it.scope == FilterScope.APPS) == apps && it.builtin != null }
        dao.upsert(
            existing.mapNotNull { f ->
                val d = defaults.firstOrNull { it.key == f.builtin } ?: return@mapNotNull null
                defaultEntity(d, scope, f.sortOrder).copy(id = f.id)
            },
        )
        restoreDefaults(scope)
    }

    /** Everything of one scope back to the start: own filters and groups deleted, defaults in their order. */
    suspend fun resetAll(scope: FilterScope) = db.withTransaction {
        val apps = scope == FilterScope.APPS
        dao.getAll().filter { (it.scope == FilterScope.APPS) == apps }.forEach { delete(it) }
        val defaults = if (apps) BuiltinFilter.appDefaults else BuiltinFilter.gameDefaults
        val base = (dao.getAll().maxOfOrNull { it.sortOrder } ?: -1) + 1
        dao.upsert(defaults.mapIndexed { index, d -> defaultEntity(d, scope, base + index) })
    }

    private fun defaultEntity(d: BuiltinFilter.Default, scope: FilterScope, order: Int) = FilterEntity(
        id = BuiltinFilter.id(d.key, scope), name = "", kind = FilterKind.SMART, builtin = d.key, scope = scope,
        rules = FilterRules.encode(BuiltinFilter.rulesFor(d.key)),
        showInLibrary = d.library && scope != FilterScope.APPS,
        showInApps = d.library && scope == FilterScope.APPS,
        showOnHome = d.home,
        sortOrder = order,
    )

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

    /** New order of one set (game or app filters) after drag and drop: [ids] take over their slots. */
    suspend fun reorder(ids: List<String>) {
        val all = dao.getAll().associateBy { it.id }
        val moving = ids.mapNotNull { all[it] }
        val slots = moving.map { it.sortOrder }.sorted()
        dao.upsert(moving.mapIndexed { index, f -> f.copy(sortOrder = slots[index]) })
    }

    private suspend fun nextOrder() = (dao.getAll().maxOfOrNull { it.sortOrder } ?: -1) + 1
}
