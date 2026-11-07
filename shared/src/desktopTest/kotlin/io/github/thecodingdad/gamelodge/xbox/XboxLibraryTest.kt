package io.github.thecodingdad.gamelodge.xbox

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.thecodingdad.gamelodge.data.db.LauncherDatabase
import io.github.thecodingdad.gamelodge.data.db.TargetType
import io.github.thecodingdad.gamelodge.data.library.LibrarySync
import io.github.thecodingdad.gamelodge.data.library.SourceEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class XboxLibraryTest {
    private lateinit var db: LauncherDatabase
    private lateinit var sync: LibrarySync

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder<LauncherDatabase>().setDriver(BundledSQLiteDriver()).build()
        sync = LibrarySync(db) { 1000L }
    }

    @After
    fun tearDown() = db.close()

    private fun xbox(console: String, product: String, name: String, subscription: Boolean = false) = SourceEntry(
        targetId = SourceEntry.xboxId(console, product), type = TargetType.XBOX, name = name,
        hostUuid = SourceEntry.xboxHost(console), hostName = "Living room", appId = 702634063, appUuid = product,
        companionGame = true, subscription = subscription,
    )

    private suspend fun entries() = db.libraryDao().observeAll().first()

    @Test
    fun `xbox game joins the pc stream of the same game and keeps its flags`() = runTest {
        sync.syncHost("pc", listOf(SourceEntry(SourceEntry.remoteId("pc", 7), TargetType.REMOTE, "The Swapper", hostUuid = "pc", hostName = "PC", appId = 7)))
        sync.syncXbox("xbox:FD01", listOf(xbox("FD01", "BXSPZM4KZS5F", "The Swapper"), xbox("FD01", "PASS1", "Peggle 2", subscription = true)))

        val all = entries()
        assertEquals(2, all.size)
        val swapper = all.single { it.item.title == "The Swapper" }
        assertEquals(setOf(TargetType.REMOTE, TargetType.XBOX), swapper.targets.map { it.type }.toSet())
        assertFalse(swapper.needsSubscription)
        val peggle = all.single { it.item.title == "Peggle 2" }
        assertTrue(peggle.needsSubscription)
        assertEquals(702634063L, peggle.xboxTargets.single().appId!!.toUInt().toLong())
    }

    @Test
    fun `removing the console drops its games but not the other hosts'`() = runTest {
        sync.syncHost("pc", listOf(SourceEntry(SourceEntry.remoteId("pc", 7), TargetType.REMOTE, "The Swapper", hostUuid = "pc", hostName = "PC", appId = 7)))
        sync.syncXbox("xbox:FD01", listOf(xbox("FD01", "BXSPZM4KZS5F", "The Swapper"), xbox("FD01", "C1", "The Cave")))
        sync.removeHost("xbox:FD01")
        val all = entries()
        assertEquals(listOf("The Swapper"), all.map { it.item.title })
        assertEquals(TargetType.REMOTE, all.single().targets.single().type)
    }

    @Test
    fun `big title ids survive as unsigned`() {
        val big = 3_500_000_000L
        assertEquals(big, big.toInt().toUInt().toLong())
    }
}
