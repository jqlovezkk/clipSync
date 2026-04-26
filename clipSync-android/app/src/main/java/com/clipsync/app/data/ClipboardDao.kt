package com.clipsync.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.clipsync.app.data.entities.ClipboardEntity
import com.clipsync.app.data.entities.ClipboardHistoryItem
import kotlinx.coroutines.flow.Flow

/**
 * DAO for clipboard history operations.
 */
@Dao
interface ClipboardDao {

    @Query(
        """
        SELECT 
            id,
            CASE
                WHEN contentType = 'image' THEN ''
                ELSE substr(content, 1, 2048)
            END AS previewContent,
            contentType,
            checksum,
            sourceDeviceId,
            sourceDeviceName,
            createdAt,
            length(content) AS contentSize
        FROM clipboard_history
        ORDER BY createdAt DESC
        LIMIT :limit
        """
    )
    fun getHistorySummaries(limit: Int = 50): Flow<List<ClipboardHistoryItem>>

    @Query("SELECT * FROM clipboard_history ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<ClipboardEntity>

    @Query("SELECT * FROM clipboard_history WHERE id = :id LIMIT 1")
    suspend fun getById(id: Int): ClipboardEntity?

    @Query("SELECT * FROM clipboard_history WHERE checksum = :checksum LIMIT 1")
    suspend fun getByChecksum(checksum: String): ClipboardEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ClipboardEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<ClipboardEntity>)

    @Query("DELETE FROM clipboard_history WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("DELETE FROM clipboard_history")
    suspend fun deleteAll()

    /**
     * Keep only the most recent N items, delete the rest.
     */
    @Query("""
        DELETE FROM clipboard_history 
        WHERE id NOT IN (
            SELECT id FROM clipboard_history 
            ORDER BY createdAt DESC 
            LIMIT :keepCount
        )
    """)
    suspend fun deleteOldItems(keepCount: Int)
}
