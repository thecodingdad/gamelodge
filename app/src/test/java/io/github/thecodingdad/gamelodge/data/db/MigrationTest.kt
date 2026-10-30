package io.github.thecodingdad.gamelodge.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LauncherDatabase::class.java)

    @Test
    fun `v1 to v2 keeps items and turns collections into groups`() {
        helper.createDatabase("m.db", 1).apply {
            execSQL(
                "INSERT INTO library_items (id, title, normalizedTitle, kind, kindSource, favorite, hidden, addedAt, playtimeSeconds, metadataState, matchConfidence) " +
                    "VALUES ('i1', 'Hades', 'hades', 'GAME', 'AUTO', 1, 0, 1, 0, 'NONE', 0)",
            )
            execSQL("INSERT INTO collections (id, name, sortOrder) VALUES ('c1', 'Couch', 0)")
            close()
        }
        val db = helper.runMigrationsAndValidate("m.db", 2, true, AndroidDatabase.MIGRATION_1_2)
        db.query("SELECT title, platform FROM library_items").use {
            it.moveToFirst()
            assertEquals("Hades", it.getString(0))
            assertEquals(true, it.isNull(1))
        }
        db.query("SELECT id, name, kind, showInLibrary, showOnHome FROM filters").use {
            it.moveToFirst()
            assertEquals("c1", it.getString(0))
            assertEquals("Couch", it.getString(1))
            assertEquals("GROUP", it.getString(2))
            assertEquals(1, it.getInt(3))
            assertEquals(1, it.getInt(4))
        }
    }

    @Test
    fun `v5 to v6 adds the member position`() {
        helper.createDatabase("m6.db", 5).close()
        val db = helper.runMigrationsAndValidate("m6.db", 6, true, AndroidDatabase.MIGRATION_5_6)
        db.query("SELECT position FROM item_collections").close()
    }

    @Test
    fun `v6 to v7 adds the ROM columns`() {
        helper.createDatabase("m7.db", 6).close()
        val db = helper.runMigrationsAndValidate("m7.db", 7, true, AndroidDatabase.MIGRATION_6_7)
        db.query("SELECT romUri, romPath, romPlatform, emulatorKey FROM launch_targets").close()
    }

    @Test
    fun `v7 to v8 drops the host API columns and keeps the host settings`() {
        helper.createDatabase("m8.db", 7).use { db ->
            db.execSQL("INSERT INTO host_config (hostUuid, wakeMethod, haWakeEntity, autoSleepMinutes, vibepolloUrl, apiType, apiUser) VALUES ('h1', 'WOL', 'script.wake', 15, 'https://x:47990', 'VIBEPOLLO', 'u')")
        }
        val db = helper.runMigrationsAndValidate("m8.db", 8, true, AndroidDatabase.MIGRATION_7_8)
        db.query("SELECT hostUuid, haWakeEntity, autoSleepMinutes FROM host_config").use { c ->
            org.junit.Assert.assertTrue(c.moveToFirst())
            org.junit.Assert.assertEquals("h1", c.getString(0))
            org.junit.Assert.assertEquals("script.wake", c.getString(1))
            org.junit.Assert.assertEquals(15, c.getInt(2))
        }
        db.query("PRAGMA table_info(host_config)").use { c ->
            val columns = generateSequence { if (c.moveToNext()) c.getString(1) else null }.toList()
            org.junit.Assert.assertFalse("vibepolloUrl" in columns)
        }
    }

    @Test
    fun `v13 to v14 adds the play data tables and keeps hidden items hidden`() {
        helper.createDatabase("m14.db", 13).use { db ->
            db.execSQL("INSERT INTO library_items (id, title, normalizedTitle, kind, kindSource, favorite, hidden, addedAt, playtimeSeconds, metadataState, matchConfidence, metadataVersion) VALUES ('i1', 'Game', 'game', 'GAME', 'AUTO', 0, 1, 0, 0, 'NONE', 0, 0)")
        }
        val db = helper.runMigrationsAndValidate("m14.db", 14, true, AndroidDatabase.MIGRATION_13_14)
        db.query("SELECT hidden, hiddenHere FROM library_items WHERE id = 'i1'").use { c ->
            org.junit.Assert.assertTrue(c.moveToFirst())
            org.junit.Assert.assertEquals(1, c.getInt(0))
            org.junit.Assert.assertEquals(0, c.getInt(1))
        }
    }
}
