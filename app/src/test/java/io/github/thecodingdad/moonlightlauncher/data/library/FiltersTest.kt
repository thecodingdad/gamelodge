package io.github.thecodingdad.moonlightlauncher.data.library

import io.github.thecodingdad.moonlightlauncher.data.db.FilterEntity
import io.github.thecodingdad.moonlightlauncher.data.db.FilterKind
import io.github.thecodingdad.moonlightlauncher.data.db.FilterScope
import io.github.thecodingdad.moonlightlauncher.data.db.ItemKind
import io.github.thecodingdad.moonlightlauncher.data.db.LaunchTargetEntity
import io.github.thecodingdad.moonlightlauncher.data.db.LibraryEntry
import io.github.thecodingdad.moonlightlauncher.data.db.LibraryItemEntity
import io.github.thecodingdad.moonlightlauncher.data.db.PlatformSource
import io.github.thecodingdad.moonlightlauncher.data.db.TargetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FiltersTest {
    private fun entry(id: String, platform: String?, genres: String? = null, remote: Boolean = true, favorite: Boolean = false, hidden: Boolean = false) =
        LibraryEntry(
            LibraryItemEntity(
                id = id, title = id, normalizedTitle = id, kind = ItemKind.GAME, addedAt = 0, favorite = favorite, hidden = hidden,
                platform = platform, genres = genres,
            ),
            listOf(
                if (remote) LaunchTargetEntity("r:$id", id, TargetType.REMOTE, id, hostUuid = "H1", hostName = "PC", appId = 1, lastSeenAt = 0)
                else LaunchTargetEntity("l:$id", id, TargetType.LOCAL, id, packageName = id, lastSeenAt = 0),
            ),
        )

    private val entries = listOf(
        entry("a", "PC", "Shooter, Indie"),
        entry("b", "Nintendo Switch", "Platform", favorite = true),
        entry("c", "Android", remote = false),
        entry("d", "PC", hidden = true),
    )

    private fun builtin(key: String, order: Int) =
        FilterEntity(key, "", FilterKind.BUILTIN, builtin = key, scope = FilterScope.GAMES, sortOrder = order)

    @Test
    fun `platform builtin splits into one chip per platform`() {
        val r = FilterEngine.resolve(listOf(builtin(BuiltinFilter.PLATFORMS, 0)), FilterPlace.LIBRARY, entries, emptyMap(), emptyMap())
        assertEquals(listOf("Android", "Nintendo Switch", "PC"), r.map { it.value })
        assertEquals(listOf("a"), r.last().entries.map { it.item.id }) // hidden "d" excluded
    }

    @Test
    fun `smart filter combines rules and hides empty results`() {
        val smart = FilterEntity("s", "Indie PC", FilterKind.SMART, rules = FilterRules.encode(FilterRules(platforms = setOf("PC"), genres = setOf("Indie"))))
        val r = FilterEngine.resolve(listOf(smart, builtin(BuiltinFilter.FAVORITES, 1).copy(showInLibrary = false)), FilterPlace.LIBRARY, entries, emptyMap(), emptyMap())
        assertEquals(1, r.size)
        assertEquals(listOf("a"), r.single().entries.map { it.item.id })
    }

    @Test
    fun `groups use their members and hidden builtin shows hidden items`() {
        val group = FilterEntity("g", "Couch", FilterKind.GROUP)
        val r = FilterEngine.resolve(listOf(group, builtin(BuiltinFilter.HIDDEN, 1)), FilterPlace.LIBRARY, entries, emptyMap(), mapOf("g" to setOf("b", "c")))
        assertEquals(listOf("b", "c"), r[0].entries.map { it.item.id })
        assertEquals(listOf("d"), r[1].entries.map { it.item.id })
    }

    @Test
    fun `free conditions combine with AND, negation and legacy rules`() {
        val switchFavorites = FilterRules(
            conditions = listOf(
                FilterCondition(FilterField.PLATFORM, setOf("Nintendo Switch")),
                FilterCondition(FilterField.FAVORITE, setOf("true")),
            ),
        )
        assertEquals(listOf("b"), entries.filter { FilterEngine.matches(it, switchFavorites) }.map { it.item.id })
        val notPc = FilterRules(conditions = listOf(FilterCondition(FilterField.PLATFORM, setOf("PC"), negate = true)))
        assertEquals(listOf("b", "c"), entries.filter { FilterEngine.matches(it, notPc) }.map { it.item.id })
        val inGroup = FilterRules(conditions = listOf(FilterCondition(FilterField.GROUP, setOf("g"))))
        assertEquals(listOf("c"), entries.filter { FilterEngine.matches(it, inGroup, mapOf("g" to setOf("c"))) }.map { it.item.id })
        // Old stored format still works and normalizes into conditions.
        val legacy = FilterRules.decode("""{"platforms":["PC"],"favorite":false}""")
        assertEquals(2, legacy.normalized().conditions.size)
        assertEquals(listOf("a", "d"), entries.filter { FilterEngine.matches(it, legacy) }.map { it.item.id })
    }

    @Test
    fun `game and app filters are separate sets`() {
        val app = LibraryEntry(entries[2].item.copy(id = "app", kind = ItemKind.APP), entries[2].targets)
        val all = entries + app
        val gameAll = builtin(BuiltinFilter.ALL, 0)
        val appAll = FilterEntity(
            "apps_all", "", FilterKind.BUILTIN, builtin = BuiltinFilter.ALL, scope = FilterScope.APPS,
            showInLibrary = false, showInApps = true, sortOrder = 1,
        )
        val library = FilterEngine.resolve(listOf(gameAll, appAll), FilterPlace.LIBRARY, all, emptyMap(), emptyMap())
        val apps = FilterEngine.resolve(listOf(gameAll, appAll), FilterPlace.APPS, all, emptyMap(), emptyMap())
        assertEquals(listOf("all"), library.map { it.filterId })
        assertEquals(listOf("apps_all"), apps.map { it.filterId })
        assertEquals(listOf("app"), apps.single().entries.map { it.item.id })
    }

    @Test
    fun `platform sources rank manual over playnite over igdb`() {
        val item = entries[0].item.copy(platformSource = PlatformSource.IGDB)
        assertTrue(PlatformResolver.mayReplace(item, PlatformSource.PLAYNITE, "Nintendo Switch"))
        assertFalse(PlatformResolver.mayReplace(item.copy(platformSource = PlatformSource.MANUAL), PlatformSource.PLAYNITE, "Wii"))
        assertFalse(PlatformResolver.mayReplace(item.copy(platformSource = PlatformSource.PLAYNITE), PlatformSource.IGDB, "Wii"))
        assertTrue(PlatformResolver.mayReplace(item.copy(platform = "PC", platformSource = PlatformSource.SUGGESTED), PlatformSource.PLAYNITE, "PC"))
        assertFalse(PlatformResolver.mayReplace(item.copy(platform = "PC", platformSource = PlatformSource.PLAYNITE), PlatformSource.PLAYNITE, "PC"))
        assertEquals("PC", PlatformResolver.fromPlaynitePlugin("Steam"))
        assertEquals(null, PlatformResolver.fromPlaynitePlugin(""))
        assertEquals("Nintendo Switch", PlatformResolver.fromIgdb(listOf("Wii U", "Nintendo Switch", "Nintendo 3DS"), playsOnPc = true, playsOnAndroid = false))
        assertEquals("PC", PlatformResolver.fromIgdb(listOf("Nintendo Switch", "PC (Microsoft Windows)"), playsOnPc = true, playsOnAndroid = false))
    }

    private fun converted(key: String, order: Int) = FilterEntity(
        key, "", FilterKind.SMART, builtin = key, rules = FilterRules.encode(BuiltinFilter.rulesFor(key)), scope = FilterScope.GAMES, sortOrder = order,
    )

    @Test
    fun `default filters as free filters match their old built-in behaviour`() {
        val keys = BuiltinFilter.gameDefaults.map { it.key }
        keys.forEach { key ->
            val old = FilterEngine.resolve(listOf(builtin(key, 0).copy(showInLibrary = true)), FilterPlace.LIBRARY, entries, mapOf("H1" to "PC"), emptyMap())
            val new = FilterEngine.resolve(listOf(converted(key, 0)), FilterPlace.LIBRARY, entries, mapOf("H1" to "PC"), emptyMap())
            assertEquals(key, old.map { it.key to it.entries.map { e -> e.item.id } }, new.map { it.key to it.entries.map { e -> e.item.id } })
        }
        val hosts = FilterEngine.resolve(listOf(converted(BuiltinFilter.HOSTS, 0)), FilterPlace.LIBRARY, entries, mapOf("H1" to "PC"), emptyMap())
        assertEquals(listOf("H1"), hosts.map { it.value }) // only hosts, no "local" part
        assertEquals("PC", hosts.single().valueLabel)
    }

    @Test
    fun `grouped filter keeps separate name options for chip and row`() {
        val rules = FilterRules(groupBy = GroupBy.PLATFORM, chipShowName = false, rowShowName = true)
        val f = FilterEntity("s", "Couch", FilterKind.SMART, rules = FilterRules.encode(rules))
        val parts = FilterEngine.resolve(listOf(f), FilterPlace.LIBRARY, entries, emptyMap(), emptyMap())
        assertTrue(parts.all { it.name == "Couch" && !it.chipShowName && it.rowShowName })
        assertEquals(listOf("Android", "Nintendo Switch", "PC"), parts.map { it.valueLabel })
    }

    @Test
    fun `sort and limit apply before grouping`() {
        val rules = FilterRules(sort = LibrarySort.TITLE, limit = 2)
        val r = FilterEngine.apply(entries, rules)
        assertEquals(listOf("a", "b"), r.map { it.item.id })
    }

    @Test
    fun `name, multi-value and bucket conditions`() {
        val e = entry("Super Mario Odyssey", "Nintendo Switch", "Platform, Adventure", favorite = true)
        assertTrue(FilterEngine.matches(e, FilterCondition(FilterField.NAME, setOf("mario"))))
        assertFalse(FilterEngine.matches(e, FilterCondition(FilterField.NAME, setOf("zelda"))))
        val genres = FilterCondition(FilterField.GENRE, setOf("Platform", "Shooter"))
        assertTrue(FilterEngine.matches(e, genres))
        assertFalse(FilterEngine.matches(e, genres.copy(matchAll = true)))
        assertTrue(FilterEngine.matches(e, FilterCondition(FilterField.PLAYTIME, setOf("none"))))
        assertTrue(FilterEngine.matches(e, FilterCondition(FilterField.LAST_PLAYED, setOf("never"))))
        assertTrue(FilterEngine.matches(e, FilterCondition(FilterField.RATING, setOf("none"))))
        assertFalse(FilterEngine.matches(e, FilterCondition(FilterField.INSTALLED, setOf("true"))))
    }
}
