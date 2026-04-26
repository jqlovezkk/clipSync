package com.clipsync.app.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for clipboard history items.
 */
@Entity(tableName = "clipboard_history")
data class ClipboardEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val content: String,
    val contentType: String = "text",
    val checksum: String = "",
    val sourceDeviceId: String = "",
    val sourceDeviceName: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
