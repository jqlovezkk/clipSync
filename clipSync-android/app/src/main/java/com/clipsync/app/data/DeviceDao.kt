package com.clipsync.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.clipsync.app.data.entities.DeviceEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for device management operations.
 */
@Dao
interface DeviceDao {

    @Query("SELECT * FROM devices ORDER BY isOnline DESC, deviceName ASC")
    fun getAll(): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices WHERE deviceId = :deviceId")
    suspend fun getById(deviceId: String): DeviceEntity?

    @Query("SELECT * FROM devices WHERE isOnline = 1")
    suspend fun getOnlineDevices(): List<DeviceEntity>

    @Query("SELECT * FROM devices WHERE isOnline = 1 ORDER BY deviceName ASC")
    fun getOnlineDevicesFlow(): Flow<List<DeviceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DeviceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<DeviceEntity>)

    @Update
    suspend fun update(entity: DeviceEntity)

    @Query("DELETE FROM devices WHERE deviceId = :deviceId")
    suspend fun deleteById(deviceId: String)

    @Query("DELETE FROM devices")
    suspend fun deleteAll()

    @Query("UPDATE devices SET isOnline = :isOnline, lastSeen = :lastSeen WHERE deviceId = :deviceId")
    suspend fun updateOnlineStatus(deviceId: String, isOnline: Boolean, lastSeen: Long)
}
