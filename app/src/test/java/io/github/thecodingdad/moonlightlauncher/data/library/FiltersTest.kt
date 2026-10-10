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
}
