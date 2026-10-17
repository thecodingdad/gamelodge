package io.github.thecodingdad.gamelodge.desktop.host

import io.github.thecodingdad.gamelodge.host.*

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.thecodingdad.gamelodge.data.db.ItemKind
import io.github.thecodingdad.gamelodge.data.db.LaunchTargetEntity
import io.github.thecodingdad.gamelodge.data.db.LauncherDatabase
import io.github.thecodingdad.gamelodge.data.db.LibraryItemEntity
import io.github.thecodingdad.gamelodge.data.db.TargetType
import io.github.thecodingdad.gamelodge.desktop.games.GameEntry
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

/** Only this PC's own games go to the streaming host (never games of other hosts: no stream in a stream). */
class LibraryHostGamesTest {
    private val db = Room.inMemoryDatabaseBuilder<LauncherDatabase>().setDriver(BundledSQLiteDriver()).build()
    private val local = mapOf(
        "steam:1" to GameEntry("steam:1", "Local game", "steam", installed = true, launch = "steam://rungameid/1"),
        "epic:2" to GameEntry("epic:2", "Owned game", "epic", installed = false, install = "com.epicgames.launcher://store/2"),
    )
    private val games = LibraryHostGames(db, local::get, { error("no metadata") }, { HostSettings() }, { null }, OkHttpClient())

    private fun item(id: String, hidden: Boolean = false) =
        LibraryItemEntity(id = id, title = id, normalizedTitle = id, kind = ItemKind.GAME, hidden = hidden, addedAt = 0)

    private fun target(id: String, item: String, type: TargetType, pkg: String? = null) =
        LaunchTargetEntity(id = id, itemId = item, type = type, name = item, packageName = pkg, hostUuid = if (type == TargetType.REMOTE) "H" else null, appId = 1, lastSeenAt = 0)

    @Test
    fun onlyLocalGamesOfThisPc() = runBlocking {
        val dao = db.libraryDao()
        listOf(item("both"), item("remote"), item("hidden", hidden = true), item("owned")).forEach { dao.upsertItem(it) }
        dao.upsertTargets(
            listOf(
                // A game on this PC and on another host: synced once, as the local game.
                target("local:steam:1", "both", TargetType.LOCAL, "steam:1"),
                target("remote:H:1", "both", TargetType.REMOTE),
                // Only on another streaming host: never.
                target("remote:H:2", "remote", TargetType.REMOTE, "steam:1"),
                target("local:hidden", "hidden", TargetType.LOCAL, "steam:1"),
                // Owned, not installed: only when wanted.
                target("local:epic:2", "owned", TargetType.LOCAL, "epic:2"),
            ),
        )
        assertEquals(listOf("steam:1"), games.syncEntries().map { it.first.entry.id })
        val withOwned = LibraryHostGames(db, local::get, { error("no metadata") }, { HostSettings(syncNotInstalled = true) }, { null }, OkHttpClient())
        assertEquals(setOf("steam:1", "epic:2"), withOwned.syncEntries().map { it.first.entry.id }.toSet())
    }
}
