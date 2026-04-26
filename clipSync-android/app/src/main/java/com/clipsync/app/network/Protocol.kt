package com.clipsync.app.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * Protocol definitions for ClipSync WebSocket messages.
 * All messages follow the envelope format defined in ws-messages.schema.json
 */

val ProtocolJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
}

// ─── Message Envelope ───

@Serializable
data class WsMessage(
    val type: MessageType,
    val version: Int = 1,
    val timestamp: Long = System.currentTimeMillis(),
    @SerialName("device_id") val deviceId: String? = null,
    val payload: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.buildJsonObject { }
) {
    fun toJson(): String = ProtocolJson.encodeToString(this)

    companion object {
        fun fromJson(json: String): WsMessage? =
            runCatching { ProtocolJson.decodeFromString<WsMessage>(json) }.getOrNull()
    }
}

@Serializable
enum class MessageType {
    @SerialName("auth") Auth,
    @SerialName("auth_response") AuthResponse,
    @SerialName("heartbeat") Heartbeat,
    @SerialName("heartbeat_ack") HeartbeatAck,
    @SerialName("clipboard_push") ClipboardPush,
    @SerialName("clipboard_sync") ClipboardSync,
    @SerialName("clipboard_pull") ClipboardPull,
    @SerialName("clipboard_history") ClipboardHistory,
    @SerialName("device_list") DeviceList,
    @SerialName("device_list_response") DeviceListResponse,
    @SerialName("device_unregister") DeviceUnregister,
    @SerialName("error") Error,
    @SerialName("ping") Ping,
    @SerialName("pong") Pong
}

// ─── Payload Data Classes ───

@Serializable
data class AuthPayload(
    val token: String,
    @SerialName("device_name") val deviceName: String? = null,
    val platform: String = "android"
)

@Serializable
data class AuthResponsePayload(
    val success: Boolean,
    @SerialName("device_id") val deviceId: String? = null,
    val message: String? = null
)

@Serializable
data class HeartbeatPayload(
    val seq: Int = 0
)

@Serializable
data class HeartbeatAckPayload(
    val seq: Int = 0
)

@Serializable
data class ClipboardPushPayload(
    @SerialName("content_type") val contentType: ContentType,
    val content: String,
    val format: String = "text/plain",
    val size: Int = 0,
    val checksum: String
)

@Serializable
data class ClipboardSyncPayload(
    @SerialName("source_device_id") val sourceDeviceId: String,
    @SerialName("source_device_name") val sourceDeviceName: String,
    @SerialName("content_type") val contentType: ContentType,
    val content: String,
    val format: String = "text/plain",
    val size: Int = 0,
    val checksum: String,
    val encrypted: Boolean = false
)

@Serializable
data class ClipboardPullPayload(
    val limit: Int = 20,
    @SerialName("after_id") val afterId: Int? = null
)

@Serializable
data class ClipboardItemPayload(
    val id: Int,
    @SerialName("content_type") val contentType: ContentType,
    val content: String,
    val format: String = "text/plain",
    val size: Int = 0,
    val checksum: String,
    @SerialName("source_device_id") val sourceDeviceId: String,
    @SerialName("source_device_name") val sourceDeviceName: String,
    @SerialName("created_at") val createdAt: Long
)

@Serializable
data class ClipboardHistoryPayload(
    val items: List<ClipboardItemPayload>,
    val total: Int,
    @SerialName("has_more") val hasMore: Boolean
)

@Serializable
data class DevicePayload(
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String,
    val platform: String,
    @SerialName("last_seen") val lastSeen: Long,
    @SerialName("is_online") val isOnline: Boolean
)

@Serializable
data class DeviceListResponsePayload(
    val devices: List<DevicePayload>
)

@Serializable
data class DeviceUnregisterPayload(
    @SerialName("device_id") val deviceId: String
)

@Serializable
data class ErrorPayload(
    val code: ErrorCode,
    val message: String
)

@Serializable
enum class ContentType {
    @SerialName("text") Text,
    @SerialName("image") Image,
    @SerialName("file") File
}

@Serializable
enum class ErrorCode {
    @SerialName("AUTH_FAILED") AuthFailed,
    @SerialName("TOKEN_EXPIRED") TokenExpired,
    @SerialName("RATE_LIMITED") RateLimited,
    @SerialName("INVALID_PAYLOAD") InvalidPayload,
    @SerialName("CONTENT_TOO_LARGE") ContentTooLarge,
    @SerialName("DEVICE_NOT_FOUND") DeviceNotFound,
    @SerialName("INTERNAL_ERROR") InternalError,
    @SerialName("DUPLICATE_CONTENT") DuplicateContent
}

// ─── HTTP API Data Classes ───

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
    @SerialName("device_name") val deviceName: String,
    val platform: String = "android"
)

@Serializable
data class RegisterRequest(
    val username: String,
    val password: String,
    @SerialName("device_name") val deviceName: String,
    val platform: String = "android"
)

@Serializable
data class AuthResponse(
    val success: Boolean,
    val token: String? = null,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("expires_at") val expiresAt: Long? = null,
    val error: String? = null,
    val message: String? = null
)

@Serializable
data class DeviceListResponse(
    val devices: List<DevicePayload>
)

@Serializable
data class GenericSuccessResponse(
    val success: Boolean
)

// ─── Helper: Build typed messages ───

object WsMessageBuilder {
    fun auth(token: String, deviceName: String): WsMessage = WsMessage(
        type = MessageType.Auth,
        payload = kotlinx.serialization.json.buildJsonObject {
            put("token", JsonPrimitive(token))
            put("device_name", JsonPrimitive(deviceName))
            put("platform", JsonPrimitive("android"))
        }
    )

    fun heartbeat(seq: Int): WsMessage = WsMessage(
        type = MessageType.Heartbeat,
        payload = kotlinx.serialization.json.buildJsonObject {
            put("seq", JsonPrimitive(seq))
        }
    )

    fun clipboardPush(content: String, checksum: String, size: Int): WsMessage = WsMessage(
        type = MessageType.ClipboardPush,
        payload = kotlinx.serialization.json.buildJsonObject {
            put("content_type", JsonPrimitive("text"))
            put("content", JsonPrimitive(content))
            put("format", JsonPrimitive("text/plain"))
            put("size", JsonPrimitive(size))
            put("checksum", JsonPrimitive(checksum))
        }
    )

    fun clipboardPush(contentType: String, content: String, checksum: String, size: Int, format: String = "text/plain"): WsMessage = WsMessage(
        type = MessageType.ClipboardPush,
        payload = kotlinx.serialization.json.buildJsonObject {
            put("content_type", JsonPrimitive(contentType))
            put("content", JsonPrimitive(content))
            put("format", JsonPrimitive(format))
            put("size", JsonPrimitive(size))
            put("checksum", JsonPrimitive(checksum))
        }
    )

    fun clipboardPull(limit: Int = 20, afterId: Int? = null): WsMessage = WsMessage(
        type = MessageType.ClipboardPull,
        payload = kotlinx.serialization.json.buildJsonObject {
            put("limit", JsonPrimitive(limit))
            afterId?.let { put("after_id", JsonPrimitive(it)) }
        }
    )

    fun deviceList(): WsMessage = WsMessage(
        type = MessageType.DeviceList,
        payload = kotlinx.serialization.json.buildJsonObject { }
    )

    fun deviceUnregister(deviceId: String): WsMessage = WsMessage(
        type = MessageType.DeviceUnregister,
        payload = kotlinx.serialization.json.buildJsonObject {
            put("device_id", JsonPrimitive(deviceId))
        }
    )

    fun pong(): WsMessage = WsMessage(
        type = MessageType.Pong,
        payload = kotlinx.serialization.json.buildJsonObject { }
    )
}
