package io.github.thecodingdad.gamelodge.desktop

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.thecodingdad.gamelodge.data.db.LauncherDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DatabaseMigrationTest {
    @Test
    fun `a v8 desktop database opens as v9 and keeps its games`() = runBlocking {
        val file = File(Files.createTempDirectory("db").toFile(), "launcher.db")
        // The v8 database as the first desktop versions created it (from the exported schema).
        val schema = Json.parseToJsonElement(File("../shared/schemas/io.github.thecodingdad.gamelodge.data.db.LauncherDatabase/8.json").readText()).jsonObject["database"]!!.jsonObject
        val connection = BundledSQLiteDriver().open(file.absolutePath)
        fun exec(sql: String) = connection.prepare(sql).use { it.step() }
        schema["entities"]!!.jsonArray.forEach { e ->
            val table = e.jsonObject["tableName"]!!.jsonPrimitive.content
            exec(e.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            e.jsonObject["indices"]?.jsonArray?.forEach { i -> exec(i.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
        }
        schema["setupQueries"]!!.jsonArray.forEach { exec(it.jsonPrimitive.content) }
        exec("INSERT INTO library_items (id, title, normalizedTitle, kind, kindSource, favorite, hidden, addedAt, playtimeSeconds, metadataState, matchConfidence, metadataVersion) VALUES ('g1', 'Hades', 'hades', 'GAME', 'AUTO', 1, 0, 0, 3600, 'NONE', 0, 0)")
        exec("PRAGMA user_version = 8")
        connection.close()

        val db = Room.databaseBuilder<LauncherDatabase>(file.absolutePath).setDriver(BundledSQLiteDriver()).addMigrations(MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13).build()
        val item = db.libraryDao().getItem("g1")!!
        assertEquals("Hades", item.title)
        assertEquals(3600L, item.playtimeSeconds)
        assertNull(item.timeToBeatMain)
        assertNull(item.squareUrl)
        db.close()
    }
}
