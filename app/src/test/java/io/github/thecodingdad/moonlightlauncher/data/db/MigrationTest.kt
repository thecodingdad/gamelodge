package io.github.thecodingdad.moonlightlauncher.data.db

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
        val db = helper.runMigrationsAndValidate("m.db", 2, true, LauncherDatabase.MIGRATION_1_2)
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
        val db = helper.runMigrationsAndValidate("m6.db", 6, true, LauncherDatabase.MIGRATION_5_6)
        db.query("SELECT position FROM item_collections").close()
    }
}
