package io.github.thecodingdad.moonlightlauncher.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Transaction
    @Query("SELECT * FROM library_items WHERE hidden = 0 ORDER BY title COLLATE NOCASE")
    fun observeVisible(): Flow<List<LibraryEntry>>

    @Transaction
    @Query("SELECT * FROM library_items ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<LibraryEntry>>

    @Transaction
    @Query("SELECT * FROM library_items WHERE id = :id")
    fun observeEntry(id: String): Flow<LibraryEntry?>

    @Transaction
    @Query("SELECT * FROM library_items WHERE id = :id")
    suspend fun getEntry(id: String): LibraryEntry?

    @Query("SELECT * FROM library_items")
    suspend fun getAllItems(): List<LibraryItemEntity>

    @Query("SELECT * FROM library_items WHERE id = :id")
    suspend fun getItem(id: String): LibraryItemEntity?

    @Query("SELECT * FROM library_items WHERE normalizedTitle = :normalized LIMIT 1")
    suspend fun findByNormalizedTitle(normalized: String): LibraryItemEntity?

    @Query("SELECT * FROM library_items WHERE metadataState IN (:states)")
    suspend fun getItemsWithMetadataState(states: List<MetadataState>): List<LibraryItemEntity>

    @Upsert
    suspend fun upsertItem(item: LibraryItemEntity)

    @Query("DELETE FROM library_items WHERE id = :id")
    suspend fun deleteItem(id: String)

    @Query("DELETE FROM library_items WHERE id NOT IN (SELECT DISTINCT itemId FROM launch_targets)")
    suspend fun deleteItemsWithoutTargets(): Int

    @Query("SELECT * FROM launch_targets")
    suspend fun getAllTargets(): List<LaunchTargetEntity>

    @Query("SELECT * FROM launch_targets WHERE id = :id")
    suspend fun getTarget(id: String): LaunchTargetEntity?

    @Upsert
    suspend fun upsertTargets(targets: List<LaunchTargetEntity>)

    @Query("DELETE FROM launch_targets WHERE id IN (:ids)")
    suspend fun deleteTargets(ids: List<String>)

    @Query("UPDATE library_items SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    @Query("UPDATE library_items SET hidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: String, hidden: Boolean)

    @Query("UPDATE library_items SET kind = :kind, kindSource = :source WHERE id = :id")
    suspend fun setKind(id: String, kind: ItemKind, source: KindSource)

    @Query("UPDATE library_items SET lastPlayedAt = :at WHERE id = :id")
    suspend fun setLastPlayed(id: String, at: Long)

    @Query("UPDATE library_items SET playtimeSeconds = playtimeSeconds + :seconds WHERE id = :id")
    suspend fun addPlaytime(id: String, seconds: Long)
}

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: PlaySessionEntity): Long

    @Query("SELECT * FROM play_sessions WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun getOpenSession(): PlaySessionEntity?

    @Query("UPDATE play_sessions SET endedAt = :endedAt WHERE id = :id")
    suspend fun close(id: Long, endedAt: Long)

    @Query("SELECT * FROM play_sessions WHERE itemId = :itemId ORDER BY startedAt DESC")
    fun observeForItem(itemId: String): Flow<List<PlaySessionEntity>>

    @Query("SELECT * FROM play_sessions WHERE endedAt IS NOT NULL ORDER BY startedAt DESC")
    fun observeFinished(): Flow<List<PlaySessionEntity>>
}

@Dao
interface CollectionDao {
    @Query("SELECT * FROM collections ORDER BY sortOrder")
    fun observeCollections(): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM item_collections")
    fun observeMemberships(): Flow<List<ItemCollectionCrossRef>>

    @Upsert
    suspend fun upsert(collection: CollectionEntity)

    @Delete
    suspend fun delete(collection: CollectionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addItem(ref: ItemCollectionCrossRef)

    @Delete
    suspend fun removeItem(ref: ItemCollectionCrossRef)
}

@Dao
interface HostConfigDao {
    @Query("SELECT * FROM host_config")
    fun observeAll(): Flow<List<HostConfigEntity>>

    @Query("SELECT * FROM host_config WHERE hostUuid = :uuid")
    suspend fun get(uuid: String): HostConfigEntity?

    @Upsert
    suspend fun upsert(config: HostConfigEntity)
}

@Dao
interface HaTileDao {
    @Query("SELECT * FROM ha_tiles ORDER BY sortOrder")
    fun observeAll(): Flow<List<HaTileEntity>>

    @Query("SELECT * FROM ha_tiles ORDER BY sortOrder")
    suspend fun getAll(): List<HaTileEntity>

    @Upsert
    suspend fun upsert(tiles: List<HaTileEntity>)

    @Query("DELETE FROM ha_tiles WHERE entityId = :entityId")
    suspend fun delete(entityId: String)
}
