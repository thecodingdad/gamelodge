package io.github.thecodingdad.gamelodge.data.db

import android.content.Context
import androidx.room.Room

/** The database on Android, with the migrations of earlier app versions (the desktop starts at the current version). */
object AndroidDatabase {
    fun create(context: Context): LauncherDatabase =
        Room.databaseBuilder(context, LauncherDatabase::class.java, "launcher.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
            .build()

    /** v12: VPN tunnel per host. */
    val MIGRATION_11_12 = object : androidx.room.migration.Migration(11, 12) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            SchemaChanges.V11_TO_V12.forEach(db::execSQL)
        }
    }

    /** v11: wait time for a woken host. */
    val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            SchemaChanges.V10_TO_V11.forEach(db::execSQL)
        }
    }

    /** v10: square tile. */
    val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            SchemaChanges.V9_TO_V10.forEach(db::execSQL)
        }
    }

    /** v9: time to beat. */
    val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            SchemaChanges.V8_TO_V9.forEach(db::execSQL)
        }
    }

    /** v4: host API type/user (companion data from Vibepollo, Apollo or Sunshine), not installed targets. */
    /** v8: the host web API (Vibepollo/Apollo/Sunshine logins) is gone; the companion delivers its data. */
    val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `host_config_new` (`hostUuid` TEXT NOT NULL, `wakeMethod` TEXT NOT NULL, `haWakeEntity` TEXT, " +
                    "`haShutdownEntity` TEXT, `haRestartEntity` TEXT, `haSleepEntity` TEXT, `autoSleepMinutes` INTEGER, PRIMARY KEY(`hostUuid`))",
            )
            db.execSQL(
                "INSERT INTO host_config_new (hostUuid, wakeMethod, haWakeEntity, haShutdownEntity, haRestartEntity, haSleepEntity, autoSleepMinutes) " +
                    "SELECT hostUuid, wakeMethod, haWakeEntity, haShutdownEntity, haRestartEntity, haSleepEntity, autoSleepMinutes FROM host_config",
            )
            db.execSQL("DROP TABLE host_config")
            db.execSQL("ALTER TABLE host_config_new RENAME TO host_config")
        }
    }

    /** v7: ROM targets (emulators). */
    val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE launch_targets ADD COLUMN romUri TEXT")
            db.execSQL("ALTER TABLE launch_targets ADD COLUMN romPath TEXT")
            db.execSQL("ALTER TABLE launch_targets ADD COLUMN romPlatform TEXT")
            db.execSQL("ALTER TABLE launch_targets ADD COLUMN emulatorKey TEXT")
        }
    }

    /** v6: manual order of group members. */
    val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE item_collections ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** v5: landscape tiles for apps on the start tab. */
    val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE library_items ADD COLUMN tileUrl TEXT")
        }
    }

    val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE host_config ADD COLUMN apiType TEXT")
            db.execSQL("ALTER TABLE host_config ADD COLUMN apiUser TEXT")
            db.execSQL("ALTER TABLE launch_targets ADD COLUMN installed INTEGER NOT NULL DEFAULT 1")
        }
    }

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
