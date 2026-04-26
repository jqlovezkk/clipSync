package com.clipsync.app.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for known devices.
 */
@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey
    val deviceId: String,
    val deviceName: String,
    val platform: String = "android",
    val lastSeen: Long = System.currentTimeMillis(),
    val isOnline: Boolean = false
)
