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

    /** First start (or first start after the update): create the default filters once. */
    suspend fun ensureDefaults() {
        if (settings.current().filtersSeeded) return
        restoreDefaults()
        settings.update { it.copy(filtersSeeded = true) }
    }

    /** Re-adds default filters the user deleted; existing ones stay untouched. */
    suspend fun restoreDefaults() {
        val existing = dao.getAll()
        val present = existing.mapNotNull { it.builtin }.toSet()
        val missing = BuiltinFilter.defaults.withIndex().filter { it.value.key !in present }.map { (index, d) ->
            FilterEntity(
                id = d.key, name = "", kind = FilterKind.BUILTIN, builtin = d.key, scope = d.scope,
                showInLibrary = d.library, showOnHome = d.home, showInApps = d.apps, sortOrder = index,
            )
        }
        if (missing.isNotEmpty()) dao.upsert(missing)
    }

    suspend fun save(filter: FilterEntity) = dao.upsert(listOf(filter))

    suspend fun createSmart(name: String, rules: FilterRules): FilterEntity {
        val filter = FilterEntity(
            id = UUID.randomUUID().toString(), name = name, kind = FilterKind.SMART,
            rules = FilterRules.encode(rules), scope = FilterScope.GAMES, sortOrder = nextOrder(),
        )
        dao.upsert(listOf(filter))
        return filter
    }

    /** A manual group is a collection (members) plus its filter row (display options). */
    suspend fun createGroup(name: String): FilterEntity = db.withTransaction {
        val id = UUID.randomUUID().toString()
        val order = nextOrder()
        db.collectionDao().upsert(CollectionEntity(id, name, order))
        FilterEntity(id = id, name = name, kind = FilterKind.GROUP, scope = FilterScope.BOTH, showOnHome = true, sortOrder = order)
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
        val list = dao.getAll().sortedBy { it.sortOrder }.toMutableList()
        val from = list.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        val to = (from + delta).coerceIn(0, list.lastIndex)
        if (from == to) return
        list.add(to, list.removeAt(from))
        dao.upsert(list.mapIndexed { index, f -> f.copy(sortOrder = index) })
    }

    private suspend fun nextOrder() = (dao.getAll().maxOfOrNull { it.sortOrder } ?: -1) + 1
}
