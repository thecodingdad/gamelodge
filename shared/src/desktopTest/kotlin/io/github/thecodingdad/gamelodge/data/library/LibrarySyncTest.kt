package io.github.thecodingdad.gamelodge.data.library

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.thecodingdad.gamelodge.data.db.ItemKind
import io.github.thecodingdad.gamelodge.data.db.LauncherDatabase
import io.github.thecodingdad.gamelodge.data.db.TargetType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LibrarySyncTest {
    private lateinit var db: LauncherDatabase
    private lateinit var sync: LibrarySync

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder<LauncherDatabase>()
            .setDriver(BundledSQLiteDriver())
            .build()
        sync = LibrarySync(db) { 1000L }
    }

    @After
    fun tearDown() = db.close()

    private fun local(pkg: String, label: String, game: Boolean = false) = SourceEntry(
        targetId = SourceEntry.localId(pkg), type = TargetType.LOCAL, name = label,
        packageName = pkg, androidGameFlag = game,
    )

    private fun remote(host: String, id: Int, name: String) = SourceEntry(
        targetId = SourceEntry.remoteId(host, id), type = TargetType.REMOTE, name = name,
        hostUuid = host, hostName = "Host", appId = id,
    )

    private suspend fun entries() = db.libraryDao().observeAll().first()

    @Test
    fun `same title from local and host becomes one item with two targets`() = runTest {
        sync.syncLocal(listOf(local("com.mihoyo.genshin", "Genshin Impact", game = true)))
        sync.syncHost("h1", listOf(remote("h1", 1, "Genshin Impact™")))

        val all = entries()
        assertEquals(1, all.size)
        assertEquals(2, all.single().targets.size)
        assertEquals(ItemKind.GAME, all.single().item.kind)
    }

    @Test
    fun `a renamed host app with a new id keeps its item via the app uuid`() = runTest {
        sync.syncHost("h1", listOf(remote("h1", 100, "Batman").copy(appUuid = "U-1")))
        val before = entries().single().item
        db.libraryDao().upsertItem(before.copy(playtimeSeconds = 3600, favorite = true, metadataState = io.github.thecodingdad.gamelodge.data.db.MetadataState.OK, coverUrl = "https://x/default-dan.png"))

        // The host derives a new numeric id from the new name; the UUID stays.
        sync.syncHost("h1", listOf(remote("h1", 200, "LEGO Batman").copy(appUuid = "u-1")))
        val after = entries().single()
        assertEquals(before.id, after.item.id)
        assertEquals(3600L, after.item.playtimeSeconds)
        assertTrue(after.item.favorite)
        assertEquals("LEGO Batman", after.item.title)
        // Metadata found for the old name is looked up again.
        assertEquals(io.github.thecodingdad.gamelodge.data.db.MetadataState.NONE, after.item.metadataState)
        assertEquals(listOf(SourceEntry.remoteId("h1", 200)), after.targets.map { it.id })
    }

    @Test
    fun `a game removed and found again gets its play sessions back`() = runTest {
        sync.syncHost("h1", listOf(remote("h1", 5, "Hades")))
        val old = entries().single().item
        db.sessionDao().insert(
            io.github.thecodingdad.gamelodge.data.db.PlaySessionEntity(itemId = old.id, targetId = SourceEntry.remoteId("h1", 5), startedAt = 10_000, endedAt = 3_610_000),
        )

        // A broken app list removes the game, the next one brings it back as a new item.
        sync.syncHost("h1", emptyList())
        assertTrue(entries().isEmpty())
        sync.syncHost("h1", listOf(remote("h1", 5, "Hades")))

        val again = entries().single().item
        assertEquals(3600L, again.playtimeSeconds)
        assertEquals(3_610_000L, again.lastPlayedAt)
        assertTrue(db.sessionDao().getOrphans().isEmpty())
    }

    @Test
    fun `local app without game flag is promoted when the host reports it as game`() = runTest {
        sync.syncLocal(listOf(local("com.example.stardew", "Stardew Valley")))
        assertEquals(ItemKind.APP, entries().single().item.kind)

        sync.syncHost("h1", listOf(remote("h1", 7, "Stardew Valley")))
        assertEquals(ItemKind.GAME, entries().single().item.kind)
    }

    @Test
    fun `removed targets disappear and orphaned items are deleted`() = runTest {
        sync.syncHost("h1", listOf(remote("h1", 1, "Elden Ring"), remote("h1", 2, "Desktop")))
        assertEquals(2, entries().size)

        sync.syncHost("h1", listOf(remote("h1", 2, "Desktop")))
        assertEquals(listOf("Desktop"), entries().map { it.item.title })
    }

    @Test
    fun `syncing one host leaves other sources untouched`() = runTest {
        sync.syncLocal(listOf(local("org.app", "Some App")))
        sync.syncHost("h1", listOf(remote("h1", 1, "Elden Ring")))
        sync.syncHost("h2", emptyList())

        assertEquals(2, entries().size)
    }

    @Test
    fun `manual kind survives later syncs`() = runTest {
        sync.syncHost("h1", listOf(remote("h1", 1, "Moonlight Tools")))
        val id = entries().single().item.id
        db.libraryDao().setKind(id, ItemKind.APP, io.github.thecodingdad.gamelodge.data.db.KindSource.MANUAL)

        sync.syncHost("h1", listOf(remote("h1", 1, "Moonlight Tools")))
        assertEquals(ItemKind.APP, entries().single().item.kind)
    }

    @Test
    fun `same-named entries of one host stay separate`() = runTest {
        sync.syncHost("h1", listOf(remote("h1", 1, "Desktop"), remote("h1", 2, "Desktop")))
        assertEquals(2, entries().size)
    }

    @Test
    fun `host tools are classified as apps`() = runTest {
        sync.syncHost("h1", listOf(remote("h1", 1, "Desktop"), remote("h1", 2, "Elden Ring")))
        val kinds = entries().associate { it.item.title to it.item.kind }
        assertEquals(ItemKind.APP, kinds["Desktop"])
        assertEquals(ItemKind.GAME, kinds["Elden Ring"])
        assertTrue(entries().all { it.targets.single().type == TargetType.REMOTE })
    }
}
