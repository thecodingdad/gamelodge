package io.github.thecodingdad.moonlightlauncher.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        LibraryItemEntity::class,
        LaunchTargetEntity::class,
        PlaySessionEntity::class,
        CollectionEntity::class,
        ItemCollectionCrossRef::class,
        HostConfigEntity::class,
        HaTileEntity::class,
        FilterEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class LauncherDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun sessionDao(): SessionDao
    abstract fun collectionDao(): CollectionDao
    abstract fun hostConfigDao(): HostConfigDao
    abstract fun haTileDao(): HaTileDao
    abstract fun filterDao(): FilterDao

    companion object {
        fun create(context: Context): LauncherDatabase =
            Room.databaseBuilder(context, LauncherDatabase::class.java, "launcher.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()

        /** v3: more metadata (publisher, series, features, age ratings, release date) and per-field edits. */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                listOf("publisher", "series", "features", "ageRatings", "manualFields").forEach {
                    db.execSQL("ALTER TABLE library_items ADD COLUMN $it TEXT")
                }
                db.execSQL("ALTER TABLE library_items ADD COLUMN releaseDate INTEGER")
                db.execSQL("ALTER TABLE library_items ADD COLUMN metadataVersion INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v2: platform + Playnite data per item, filters table (existing collections become groups). */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                listOf("platform", "platformSource", "playniteCategories", "playnitePlugin").forEach {
                    db.execSQL("ALTER TABLE library_items ADD COLUMN $it TEXT")
                }
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS filters (id TEXT NOT NULL, name TEXT NOT NULL, kind TEXT NOT NULL, " +
                        "builtin TEXT, rules TEXT, scope TEXT NOT NULL, showInLibrary INTEGER NOT NULL, " +
                        "showOnHome INTEGER NOT NULL, showInApps INTEGER NOT NULL, sortOrder INTEGER NOT NULL, PRIMARY KEY(id))",
                )
                // Collections from v1 are manual groups now; they used to appear as library chips and home rows.
                db.execSQL(
                    "INSERT OR IGNORE INTO filters (id, name, kind, builtin, rules, scope, showInLibrary, showOnHome, showInApps, sortOrder) " +
                        "SELECT id, name, 'GROUP', NULL, NULL, 'BOTH', 1, 1, 0, 100 + sortOrder FROM collections",
                )
            }
        }
    }
}
