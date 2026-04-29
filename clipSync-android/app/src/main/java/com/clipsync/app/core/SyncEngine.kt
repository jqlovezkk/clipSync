package com.clipsync.app.core

import com.clipsync.app.data.AppDatabase
import com.clipsync.app.data.entities.ClipboardEntity
import com.clipsync.app.network.WsMessage
import com.clipsync.app.network.WsMessageBuilder
import com.clipsync.app.network.WebSocketClient
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Orchestrates clipboard sync between local clipboard and remote server.
 * Handles push on local change, sync on remote change, and history management.
 */
class SyncEngine(
    private val webSocketClient: WebSocketClient,
    private val clipboardMonitor: ClipboardMonitor,
    private val settingsManager: SettingsManager,
    private val database: AppDatabase
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isDestroyed = false

    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private var lastSentChecksum: String? = null

    private val maxTextSizeBytes = 100 * 1024
    private val maxImageSizeBytes = 5 * 1024 * 1024

    /**
     * Initialize the sync engine.
     */
    fun initialize() {
        scope.launch {
            val syncEnabled = settingsManager.isSyncEnabled()
            if (syncEnabled) {
                startMonitoring()
            }
        }
    }

    /**
     * Start monitoring local clipboard changes and push to server.
     */
    fun startMonitoring() {
        scope.launch {
            settingsManager.syncEnabledFlow.collect { enabled ->
                if (enabled) {
                    _syncStatus.value = SyncStatus.Active
                    FileLogger.d(TAG, "Sync monitoring enabled")
                } else {
                    _syncStatus.value = SyncStatus.Paused
                    FileLogger.d(TAG, "Sync monitoring paused")
                }
            }
        }
    }

    /**
     * Push local clipboard content (text) to server.
     */
    fun pushToServer(content: String, force: Boolean = false) {
        scope.launch {
            if (isDestroyed) return@launch
            val syncEnabled = settingsManager.isSyncEnabled()
            if (!syncEnabled) {
                FileLogger.d(TAG, "Sync disabled, skipping push")
                return@launch
            }

            if (!webSocketClient.isConnected()) {
                FileLogger.w(TAG, "Not connected, cannot push")
                return@launch
            }

            // Check content size to prevent OOM
            val contentSizeBytes = content.toByteArray(Charsets.UTF_8).size
            if (contentSizeBytes > maxTextSizeBytes) {
                FileLogger.w(TAG, "Content too large (${contentSizeBytes} bytes), skipping push")
                return@launch
            }

            // Deduplication: skip if same content was just sent
            val checksum = EncryptionHelper.calculateChecksum(content)
            if (!force && checksum == lastSentChecksum) {
                FileLogger.d(TAG, "Duplicate content, skipping push")
                return@launch
            }
            lastSentChecksum = checksum
            FileLogger.d(TAG, "Push pipeline: text accepted for send, checksum=$checksum, force=$force")

            val encryptionEnabled = settingsManager.isEncryptionEnabled()
            val contentToSend = if (encryptionEnabled) {
                try {
                    EncryptionHelper.encryptWithSalt(content)
                } catch (e: Exception) {
                    FileLogger.e(TAG, "Encryption failed, skipping push", e)
                    return@launch
                }
            } else {
                content
            }

            // Use content length as size estimate to avoid creating another byte array copy
            val estimatedSize = contentToSend.length

            val message = WsMessageBuilder.clipboardPush(
                contentType = "text",
                content = contentToSend,
                checksum = checksum,
                size = estimatedSize
            )

            val deviceId = settingsManager.getDeviceId()
            val messageWithDevice = message.copy(deviceId = deviceId)

            val sent = webSocketClient.send(messageWithDevice)
            if (sent) {
                FileLogger.d(TAG, "Push pipeline: text sent to server (${content.length} chars)")
                // Save to local history
                saveToHistory(content, "text", checksum, deviceId)
            } else {
                FileLogger.w(TAG, "Push pipeline: failed to push text to server")
            }
        }
    }

    /**
     * Push local clipboard image to server.
     */
    fun pushImageToServer(
        imageBase64: String,
        format: String,
        size: Int,
        checksum: String,
        force: Boolean = false
    ) {
        scope.launch {
            if (isDestroyed) return@launch
            val syncEnabled = settingsManager.isSyncEnabled()
            if (!syncEnabled) {
                FileLogger.d(TAG, "Sync disabled, skipping image push")
                return@launch
            }

            if (!webSocketClient.isConnected()) {
                FileLogger.w(TAG, "Not connected, cannot push image")
                return@launch
            }

            // Check content size
            if (size > maxImageSizeBytes) {
                FileLogger.w(TAG, "Image too large (${size} bytes), skipping push")
                return@launch
            }

            // Deduplication
            if (!force && checksum == lastSentChecksum) {
                FileLogger.d(TAG, "Duplicate image, skipping push")
                return@launch
            }
            lastSentChecksum = checksum
            FileLogger.d(TAG, "Push pipeline: image accepted for send, checksum=$checksum, force=$force, size=$size")

            val encryptionEnabled = settingsManager.isEncryptionEnabled()
            val contentToSend = if (encryptionEnabled) {
                try {
                    EncryptionHelper.encryptWithSalt(imageBase64)
                } catch (e: Exception) {
                    FileLogger.e(TAG, "Image encryption failed, skipping push", e)
                    return@launch
                }
            } else {
                imageBase64
            }

            val message = WsMessageBuilder.clipboardPush(
                contentType = "image",
                content = contentToSend,
                checksum = checksum,
                size = size,
                format = format
            )

            val deviceId = settingsManager.getDeviceId()
            val messageWithDevice = message.copy(deviceId = deviceId)

            val sent = webSocketClient.send(messageWithDevice)
            if (sent) {
                FileLogger.d(TAG, "Push pipeline: image sent to server ($size bytes, format=$format)")
                saveToHistory(imageBase64, "image", checksum, deviceId)
            } else {
                FileLogger.w(TAG, "Push pipeline: failed to push image to server")
            }
        }
    }

    /**
     * Handle incoming clipboard sync from server.
     */
    fun handleIncomingSync(payload: JsonObject) {
        scope.launch {
            try {
                if (isDestroyed) return@launch

                val content = payload.safeString("content")
                if (content.isNullOrEmpty()) {
                    FileLogger.w(TAG, "Ignoring clipboard sync with empty content: $payload")
                    return@launch
                }

                val contentType = payload.safeString("content_type") ?: "text"
                val sourceDeviceId = payload.safeString("source_device_id").orEmpty()
                val sourceDeviceName = payload.safeString("source_device_name")
                    ?.takeIf { it.isNotBlank() }
                    ?: "Unknown device"
                val encrypted = payload.safeBoolean("encrypted") ?: false

                // Skip if this content originated from this device (avoid echo loop)
                val myDeviceId = settingsManager.getDeviceId()
                if (sourceDeviceId.isNotEmpty() && sourceDeviceId == myDeviceId) {
                    FileLogger.d(TAG, "Skipping own content (echo prevention)")
                    return@launch
                }

                val encryptionEnabled = settingsManager.isEncryptionEnabled()
                if (encrypted && !encryptionEnabled) {
                    FileLogger.w(TAG, "Encrypted clipboard sync received but encryption is disabled locally")
                    _syncStatus.value = SyncStatus.Error("无法解密远端剪贴板内容")
                    return@launch
                }

                val decryptedContent = if (encrypted) {
                    try {
                        EncryptionHelper.decryptWithSalt(content) ?: run {
                            FileLogger.e(TAG, "Failed to decrypt content")
                            _syncStatus.value = SyncStatus.Error("解密远端剪贴板内容失败")
                            return@launch
                        }
                    } catch (e: Exception) {
                        FileLogger.e(TAG, "Unexpected decryption error", e)
                        _syncStatus.value = SyncStatus.Error("解密远端剪贴板内容失败")
                        return@launch
                    }
                } else {
                    content
                }

                val checksum = payload.safeString("checksum")
                    ?.takeIf { it.isNotBlank() }
                    ?: EncryptionHelper.calculateChecksum(decryptedContent)

                when (contentType) {
                    "text" -> {
                        FileLogger.d(TAG, "Receive pipeline: applying remote text to Android clipboard")
                        clipboardMonitor.setTextToClipboard(decryptedContent)
                        FileLogger.d(TAG, "Synced text from $sourceDeviceName: ${decryptedContent.take(50)}...")
                    }
                    "image" -> {
                        FileLogger.d(TAG, "Receive pipeline: applying remote image to Android clipboard")
                        clipboardMonitor.setImageToClipboard(decryptedContent)
                        FileLogger.d(TAG, "Synced image from $sourceDeviceName (${decryptedContent.length} chars base64)")
                    }
                    else -> {
                        FileLogger.w(TAG, "Unknown content type in clipboard sync: $contentType, payload=$payload")
                        return@launch
                    }
                }

                saveToHistory(decryptedContent, contentType, checksum, sourceDeviceId, sourceDeviceName)
            } catch (e: Exception) {
                FileLogger.e(TAG, "Failed to handle incoming clipboard sync: $payload", e)
                _syncStatus.value = SyncStatus.Error("处理远端剪贴板同步失败")
            }
        }
    }

    /**
     * Handle incoming clipboard history from server.
     */
    fun handleHistoryResponse(payload: JsonObject) {
        scope.launch {
            if (isDestroyed) return@launch
            val itemsArray = payload["items"]
            if (itemsArray !is JsonArray) return@launch

            val items = itemsArray.jsonArray.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val content = obj["content"]?.jsonPrimitive?.content ?: return@mapNotNull null
                // Skip items with content too large
                val checksum = obj["checksum"]?.jsonPrimitive?.content ?: ""
                val sourceDeviceId = obj["source_device_id"]?.jsonPrimitive?.content ?: ""
                val sourceDeviceName = obj["source_device_name"]?.jsonPrimitive?.content ?: ""
                val createdAt = obj["created_at"]?.jsonPrimitive?.long ?: System.currentTimeMillis()
                val contentType = obj["content_type"]?.jsonPrimitive?.content ?: "text"

                if (!isWithinContentLimit(content, contentType)) {
                    return@mapNotNull null
                }

                ClipboardEntity(
                    content = content,
                    contentType = contentType,
                    checksum = checksum,
                    sourceDeviceId = sourceDeviceId,
                    sourceDeviceName = sourceDeviceName,
                    createdAt = createdAt
                )
            }

            // Insert into local database
            database.clipboardDao().insertAll(items)
            FileLogger.d(TAG, "Saved ${items.size} history items")
        }
    }

    /**
     * Request clipboard history from server.
     */
    fun requestHistory(limit: Int = 20) {
        if (!webSocketClient.isConnected()) return
        val message = WsMessageBuilder.clipboardPull(limit = limit)
        webSocketClient.send(message)
    }

    /**
     * Save content to local history database.
     */
    private fun saveToHistory(
        content: String,
        contentType: String,
        checksum: String,
        sourceDeviceId: String,
        sourceDeviceName: String = ""
    ) {
        scope.launch {
            // Skip saving if content is too large
            if (!isWithinContentLimit(content, contentType)) {
                FileLogger.w(TAG, "Content too large for history, skipping save")
                return@launch
            }
            val entity = ClipboardEntity(
                content = content,
                contentType = contentType,
                checksum = checksum,
                sourceDeviceId = sourceDeviceId,
                sourceDeviceName = sourceDeviceName,
                createdAt = System.currentTimeMillis()
            )
            database.clipboardDao().insert(entity)
            // Trim to last 50 items
            database.clipboardDao().deleteOldItems(50)
        }
    }

    /**
     * Reset the last sent checksum (e.g., after reconnect).
     */
    fun resetDeduplication() {
        lastSentChecksum = null
    }

    /**
     * Release resources and cancel all coroutines.
     */
    fun destroy() {
        isDestroyed = true
        scope.cancel()
        FileLogger.d(TAG, "SyncEngine destroyed")
    }

    private fun isWithinContentLimit(content: String, contentType: String): Boolean {
        return when (contentType) {
            "image" -> estimateImageBytes(content) <= maxImageSizeBytes
            else -> content.toByteArray(Charsets.UTF_8).size <= maxTextSizeBytes
        }
    }

    private fun estimateImageBytes(base64Content: String): Int {
        return runCatching {
            Base64.decode(base64Content, Base64.DEFAULT).size
        }.getOrElse {
            FileLogger.w(TAG, "Failed to decode image content while checking size, treating as oversized")
            Int.MAX_VALUE
        }
    }

    companion object {
        private const val TAG = "SyncEngine"
    }
}

private fun JsonObject.safeString(key: String): String? =
    runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()

private fun JsonObject.safeBoolean(key: String): Boolean? =
    runCatching { this[key]?.jsonPrimitive?.booleanOrNull }.getOrNull()

/**
 * Sync status sealed class.
 */
sealed class SyncStatus {
    object Idle : SyncStatus()
    object Active : SyncStatus()
    object Paused : SyncStatus()
    data class Error(val message: String) : SyncStatus()
}
