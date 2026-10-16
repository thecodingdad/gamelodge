package io.github.thecodingdad.moonlightlauncher.desktop.games

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import java.io.File

/**
 * Reads another program's SQLite database. The program may hold the file open (Galaxy, Amazon
 * Games), so a copy is read and deleted afterwards.
 */
fun <T> readSqliteCopy(db: File, block: (SQLiteConnection) -> T): T {
    val copy = File.createTempFile("launcher-db-", ".sqlite")
    try {
        db.copyTo(copy, overwrite = true)
        val connection = BundledSQLiteDriver().open(copy.absolutePath, SQLITE_OPEN_READONLY)
        try {
            return block(connection)
        } finally {
            connection.close()
        }
    } finally {
        copy.delete()
    }
}

/** Rows of a query as lists of nullable text columns. */
fun SQLiteConnection.rows(sql: String): List<List<String?>> = prepare(sql).use { st ->
    buildList {
        while (st.step()) add((0 until st.getColumnCount()).map { if (st.isNull(it)) null else st.getText(it) })
    }
}
