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
    ],
    version = 1,
    exportSchema = true,
)
abstract class LauncherDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun sessionDao(): SessionDao
    abstract fun collectionDao(): CollectionDao
    abstract fun hostConfigDao(): HostConfigDao
    abstract fun haTileDao(): HaTileDao

    companion object {
        fun create(context: Context): LauncherDatabase =
            Room.databaseBuilder(context, LauncherDatabase::class.java, "launcher.db").build()
    }
}
