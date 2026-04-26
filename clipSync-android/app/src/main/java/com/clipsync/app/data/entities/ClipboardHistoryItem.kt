package com.clipsync.app.data.entities

import androidx.room.ColumnInfo

/**
 * 供历史列表展示使用的轻量数据模型。
 * 这里只查询摘要字段，避免大图片 Base64 在列表查询时撑爆 CursorWindow。
 */
data class ClipboardHistoryItem(
    val id: Int,
    @ColumnInfo(name = "previewContent")
    val previewContent: String,
    val contentType: String,
    val checksum: String = "",
    val sourceDeviceId: String = "",
    val sourceDeviceName: String = "",
    val createdAt: Long,
    @ColumnInfo(name = "contentSize")
    val contentSize: Int = 0
)
