package io.github.thecodingdad.gamelodge.data.sync

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.thecodingdad.gamelodge.data.companion.PlayDataChanges
import io.github.thecodingdad.gamelodge.data.companion.PlayFlagData
import io.github.thecodingdad.gamelodge.data.companion.PlaySessionData
import io.github.thecodingdad.gamelodge.data.db.CollectionEntity
import io.github.thecodingdad.gamelodge.data.db.ItemCollectionCrossRef
import io.github.thecodingdad.gamelodge.data.db.ItemKind
import io.github.thecodingdad.gamelodge.data.db.LauncherDatabase
import io.github.thecodingdad.gamelodge.data.db.LibraryItemEntity
import io.github.thecodingdad.gamelodge.data.db.PlaySessionEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A host and two devices (Odin, PC) with one shared game "g1". */
class PlayDataSyncTest {
    private var now = 1_000_000_000L
    private lateinit var hostDb: LauncherDatabase
    private lateinit var odinDb: LauncherDatabase
    private lateinit var pcDb: LauncherDatabase
    private lateinit var hub: PlayDataHub
    private lateinit var odin: PlayDataSync
    private lateinit var pc: PlayDataSync

    private fun db() = Room.inMemoryDatabaseBuilder<LauncherDatabase>().setDriver(BundledSQLiteDriver()).build()

    private fun device(db: LauncherDatabase, id: String, name: String = id.uppercase()) = PlayDataSync(
        db, installId = { id },
        createCollection = { name -> "c-$name".also { db.collectionDao().upsert(CollectionEntity(it, name, 0)) } },
        deviceName = { name },
        clock = { now },
    )

    private fun host(deviceId: String, item: String, name: String = deviceId.uppercase()) = SyncHost(
        "host",
        object : PlayDataTransport {
            override suspend fun pull(after: Long) = hub.changesAfter(after)
            override suspend fun push(changes: PlayDataChanges) = hub.apply(deviceId, name, changes)
        },
        games = mapOf("g1" to item),
        gameOfTarget = mapOf("t-$item" to "g1"),
    )

    private suspend fun item(db: LauncherDatabase, id: String, playtime: Long = 0, favorite: Boolean = false) =
        db.libraryDao().upsertItem(LibraryItemEntity(id, "Game", "game", ItemKind.GAME, addedAt = 0, playtimeSeconds = playtime, favorite = favorite, lastPlayedAt = if (playtime > 0) 500L else null))

    private suspend fun get(db: LauncherDatabase, id: String) = db.libraryDao().getItem(id)!!

    @Before
    fun setUp() {
        hostDb = db(); odinDb = db(); pcDb = db()
        hub = PlayDataHub(hostDb, { setOf("g1") }, { now })
        odin = device(odinDb, "odin")
        pc = device(pcDb, "pc")
    }

    @After
    fun tearDown() {
        hostDb.close(); odinDb.close(); pcDb.close()
    }

    @Test
    fun `earlier playtime and new sessions add up on every device, once`() = runTest {
        item(odinDb, "o1", playtime = 3600)
        item(pcDb, "p1", playtime = 1800)
        assertTrue(odin.sync(host("odin", "o1")))
        assertTrue(pc.sync(host("pc", "p1")))
        assertTrue(odin.sync(host("odin", "o1")))
        assertEquals(5400, get(odinDb, "o1").playtimeSeconds)
        assertEquals(5400, get(pcDb, "p1").playtimeSeconds)

        // A stream of half an hour on the Odin, synced twice: counted once.
        now += 10_000
        odinDb.sessionDao().insert(PlaySessionEntity(itemId = "o1", targetId = "t-o1", startedAt = now, endedAt = now + 1_800_000))
        assertTrue(odin.sync(host("odin", "o1")))
        assertTrue(odin.sync(host("odin", "o1")))
        assertTrue(pc.sync(host("pc", "p1")))
        assertEquals(7200, get(pcDb, "p1").playtimeSeconds)
        assertEquals(7200, get(odinDb, "o1").playtimeSeconds)
        assertEquals(now, get(pcDb, "p1").lastPlayedAt)
    }

    @Test
    fun `a device installed anew with the restored total doesn't count it again`() = runTest {
        item(odinDb, "o1", playtime = 3600)
        item(pcDb, "p1", playtime = 1800)
        assertTrue(odin.sync(host("odin", "o1")))
        assertTrue(pc.sync(host("pc", "p1")))
        assertTrue(odin.sync(host("odin", "o1")))
        assertEquals(5400, get(odinDb, "o1").playtimeSeconds)

        // The Odin installed anew (new install id, paired again: a new device on the host, same name)
        // with a backup of the total.
        val newDb = db()
        try {
            item(newDb, "n1", playtime = 5400)
            assertTrue(device(newDb, "odin-new", "ODIN").sync(host("odin-new", "n1", "ODIN")))
            assertEquals(5400, get(newDb, "n1").playtimeSeconds)
            assertTrue(pc.sync(host("pc", "p1")))
            assertEquals(5400, get(pcDb, "p1").playtimeSeconds)
        } finally {
            newDb.close()
        }
    }

    @Test
    fun `short starts don't count`() = runTest {
        item(odinDb, "o1")
        assertTrue(odin.sync(host("odin", "o1")))
        now += 10_000
        odinDb.sessionDao().insert(PlaySessionEntity(itemId = "o1", targetId = "t-o1", startedAt = now, endedAt = now + 30_000))
        assertTrue(odin.sync(host("odin", "o1")))
        assertEquals(null, hostDb.playDataDao().maxSeq())
    }

    @Test
    fun `the newest rating wins and the first sync doesn't overwrite another device`() = runTest {
        item(odinDb, "o1", favorite = true)
        item(pcDb, "p1")
        assertTrue(odin.sync(host("odin", "o1")))
        // The PC's first sync: its "not a favourite" must not undo the Odin's favourite.
        assertTrue(pc.sync(host("pc", "p1")))
        assertTrue(get(pcDb, "p1").favorite)

        now += 1000
        pcDb.libraryDao().setUserRating("p1", 4)
        assertTrue(pc.sync(host("pc", "p1")))
        assertTrue(odin.sync(host("odin", "o1")))
        assertEquals(4, get(odinDb, "o1").userRating)

        now += 1000
        odinDb.libraryDao().setFavorite("o1", false)
        assertTrue(odin.sync(host("odin", "o1")))
        assertTrue(pc.sync(host("pc", "p1")))
        assertFalse(get(pcDb, "p1").favorite)
    }

    @Test
    fun `hidden only here stays here, hidden everywhere reaches every device`() = runTest {
        item(odinDb, "o1"); item(pcDb, "p1")
        assertTrue(odin.sync(host("odin", "o1"))); assertTrue(pc.sync(host("pc", "p1")))

        now += 1000
        odinDb.libraryDao().setHiddenState("o1", hidden = true, here = true)
        assertTrue(odin.sync(host("odin", "o1"))); assertTrue(pc.sync(host("pc", "p1")))
        assertFalse(get(pcDb, "p1").hidden)
        assertTrue(get(odinDb, "o1").hidden)

        now += 1000
        pcDb.libraryDao().setHiddenState("p1", hidden = true, here = false)
        assertTrue(pc.sync(host("pc", "p1"))); assertTrue(odin.sync(host("odin", "o1")))
        assertTrue(get(odinDb, "o1").hidden)

        // Shown again on the PC: gone everywhere, the Odin's own "only here" too was ended by the user there only.
        now += 1000
        pcDb.libraryDao().setHiddenState("p1", hidden = false, here = false)
        assertTrue(pc.sync(host("pc", "p1"))); assertTrue(odin.sync(host("odin", "o1")))
        assertTrue("hidden here on the Odin stays", get(odinDb, "o1").hidden)
    }

    @Test
    fun `collections are matched by name and created where missing`() = runTest {
        item(odinDb, "o1"); item(pcDb, "p1")
        odinDb.collectionDao().upsert(CollectionEntity("x", "RPG", 0))
        odinDb.collectionDao().addItem(ItemCollectionCrossRef("o1", "x"))
        assertTrue(odin.sync(host("odin", "o1"))); assertTrue(pc.sync(host("pc", "p1")))
        val pcCollection = pcDb.collectionDao().getCollections().single { it.name == "RPG" }
        assertTrue(pcDb.collectionDao().getMemberships().any { it.itemId == "p1" && it.collectionId == pcCollection.id })

        now += 1000
        pcDb.collectionDao().removeItem(ItemCollectionCrossRef("p1", pcCollection.id))
        assertTrue(pc.sync(host("pc", "p1"))); assertTrue(odin.sync(host("odin", "o1")))
        assertTrue(odinDb.collectionDao().getMemberships().none { it.itemId == "o1" })
    }

    @Test
    fun `the host refuses unknown games, bad values and times in the future`() = runTest {
        fun session(game: String = "g1", started: Long = now, seconds: Long = 60) = PlayDataChanges(sessions = listOf(PlaySessionData("s1", game, started, seconds)))
        assertFalse(hub.apply("x", "X", session(game = "other")))
        assertFalse(hub.apply("x", "X", session(started = now + 3_600_000)))
        assertFalse(hub.apply("x", "X", session(seconds = 0)))
        assertFalse(hub.apply("x", "X", session(seconds = 2 * 24 * 3600)))
        assertFalse(hub.apply("x", "X", PlayDataChanges(flags = listOf(PlayFlagData("g1", PlayFlagData.FIELD_RATING, "9", now)))))
        assertFalse(hub.apply("x", "X", PlayDataChanges(flags = listOf(PlayFlagData("g1", "admin", "1", now)))))
        assertTrue(hub.apply("x", "X", session()))
        // Another device can't replace a session.
        assertTrue(hub.apply("y", "Y", PlayDataChanges(sessions = listOf(PlaySessionData("s1", "g1", now, 999)))))
        assertEquals(60, hostDb.playDataDao().hubSession("s1")!!.seconds)
    }
}
