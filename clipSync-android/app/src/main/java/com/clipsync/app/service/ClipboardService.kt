package com.clipsync.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import com.clipsync.app.core.FileLogger
import com.clipsync.app.core.ClipboardContentType
import com.clipsync.app.core.ClipboardMonitor
import com.clipsync.app.core.EncryptionHelper
import com.clipsync.app.core.SettingsManager
import com.clipsync.app.core.SyncEngine
import com.clipsync.app.core.SyncStatus
import com.clipsync.app.data.AppDatabase
import com.clipsync.app.network.ConnectionState
import com.clipsync.app.network.HeartbeatManager
import com.clipsync.app.network.MessageType
import com.clipsync.app.network.WebSocketClient
import com.clipsync.app.network.WsMessage
import com.clipsync.app.network.WsMessageBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Foreground service that monitors clipboard and syncs via WebSocket.
 * Runs persistently to ensure clipboard changes are captured even when app is in background.
 */
class ClipboardService : Service() {

    // Binder for local service access
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): ClipboardService = this@ClipboardService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    // Exposed state flows for bound clients (e.g., MainViewModel)
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var settingsManager: SettingsManager
    private lateinit var clipboardMonitor: ClipboardMonitor
    private lateinit var syncEngine: SyncEngine
    private lateinit var webSocketClient: WebSocketClient
    private lateinit var heartbeatManager: HeartbeatManager
    private lateinit var database: AppDatabase

    private var isMonitoring = false

    override fun onCreate() {
        super.onCreate()
        FileLogger.d(TAG, "Service created")

        // Initialize components
        settingsManager = SettingsManager(this)
        database = AppDatabase.getInstance(this)
        clipboardMonitor = ClipboardMonitor(this)
        webSocketClient = WebSocketClient()
        heartbeatManager = HeartbeatManager(webSocketClient)

        syncEngine = SyncEngine(
            webSocketClient = webSocketClient,
            clipboardMonitor = clipboardMonitor,
            settingsManager = settingsManager,
            database = database
        )

        // 启动前台服务通知，Android 14+ 需要与 Manifest 中声明的服务类型一致
        createNotificationChannel()
        FileLogger.d(TAG, "Starting foreground service with type=dataSync")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        // Setup WebSocket message handling
        setupMessageHandler()
        observeConnectionState()

        // Setup clipboard change monitoring
        setupClipboardMonitoring()

        // Observe sync engine status
        observeSyncStatus()

        // Connect to server
        connectToServer()
    }

    private fun observeSyncStatus() {
        serviceScope.launch {
            syncEngine.syncStatus.collectLatest { status ->
                _syncStatus.value = status
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        FileLogger.d(TAG, "Service started")
        return START_STICKY
    }

    override fun onDestroy() {
        FileLogger.d(TAG, "Service destroyed")
        isMonitoring = false
        clipboardMonitor.stop()
        heartbeatManager.destroy()
        syncEngine.destroy()
        webSocketClient.destroy()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ClipSync Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Clipboard sync service notification"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.apply {
            setContentTitle("ClipSync Running")
            setContentText("Monitoring clipboard for sync")
            setSmallIcon(android.R.drawable.ic_menu_send)
            setOngoing(true)
        }.build()
    }

    private fun connectToServer() {
        serviceScope.launch {
            val wsUrl = settingsManager.getServerUrl()
            val token = settingsManager.getToken()

            if (token.isEmpty()) {
                FileLogger.w(TAG, "No token available, cannot connect")
                return@launch
            }

            FileLogger.d(TAG, "Connecting service WebSocket to $wsUrl")
            webSocketClient.connect(wsUrl)
        }
    }

    private fun observeConnectionState() {
        serviceScope.launch {
            webSocketClient.connectionState.collectLatest { state ->
                _connectionState.value = state
                when (state) {
                    is ConnectionState.Connected -> {
                        FileLogger.d(TAG, "Service WebSocket connected, sending auth")
                        sendAuth()
                    }
                    ConnectionState.Connecting -> {
                        FileLogger.d(TAG, "Service WebSocket connecting")
                    }
                    ConnectionState.Disconnected -> {
                        FileLogger.d(TAG, "Service WebSocket disconnected")
                        _syncStatus.value = SyncStatus.Idle
                    }
                    is ConnectionState.Error -> {
                        FileLogger.e(TAG, "Service WebSocket error: ${state.message}")
                    }
                }
            }
        }
    }

    private fun setupMessageHandler() {
        serviceScope.launch {
            webSocketClient.messages.collectLatest { message ->
                handleWebSocketMessage(message)
            }
        }
    }

    private fun handleWebSocketMessage(json: String) {
        val wsMessage = WsMessage.fromJson(json)
        if (wsMessage == null) {
            FileLogger.w(TAG, "Failed to parse WebSocket message: ${json.take(200)}")
            return
        }

        when (wsMessage.type) {
            MessageType.AuthResponse -> handleAuthResponse(wsMessage.payload)
            MessageType.HeartbeatAck -> { /* Heartbeat acknowledged */ }
            MessageType.ClipboardSync -> handleClipboardSync(wsMessage.payload)
            MessageType.ClipboardHistory -> syncEngine.handleHistoryResponse(wsMessage.payload)
            MessageType.DeviceListResponse -> handleDeviceListResponse(wsMessage.payload)
            MessageType.Error -> handleError(wsMessage.payload)
            MessageType.Ping -> handlePing()
            else -> FileLogger.d(TAG, "Unhandled message type: ${wsMessage.type}")
        }
    }

    private fun handleAuthResponse(payload: JsonObject) {
        val success = payload["success"]?.jsonPrimitive?.booleanOrNull ?: false
        val deviceId = payload["device_id"]?.jsonPrimitive?.content
        val message = payload["message"]?.jsonPrimitive?.content

        if (success) {
            FileLogger.d(TAG, "Auth successful, device_id: $deviceId")
            deviceId?.let {
                serviceScope.launch {
                    settingsManager.setDeviceId(it)
                }
            }
            // Start heartbeat
            heartbeatManager.start()
            // Start clipboard monitoring
            startClipboardMonitoring()
            // Request initial history
            syncEngine.requestHistory()
        } else {
            FileLogger.e(TAG, "Auth failed: $message")
        }
    }

    private fun handleClipboardSync(payload: JsonObject) {
        val sourceDeviceName = payload["source_device_name"]?.jsonPrimitive?.content ?: "Unknown device"
        val contentType = payload["content_type"]?.jsonPrimitive?.content ?: "text"
        FileLogger.d(TAG, "Incoming clipboard_sync from $sourceDeviceName, type=$contentType")
        syncEngine.handleIncomingSync(payload)
    }

    private fun handleDeviceListResponse(payload: JsonObject) {
        serviceScope.launch {
            val devices = payload["devices"]?.jsonArray?.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                com.clipsync.app.data.entities.DeviceEntity(
                    deviceId = obj["device_id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                    deviceName = obj["device_name"]?.jsonPrimitive?.content ?: "Unknown",
                    platform = obj["platform"]?.jsonPrimitive?.content ?: "unknown",
                    lastSeen = obj["last_seen"]?.jsonPrimitive?.content?.toLongOrNull() ?: System.currentTimeMillis(),
                    isOnline = obj["is_online"]?.jsonPrimitive?.booleanOrNull ?: false
                )
            }.orEmpty()
            database.deviceDao().insertAll(devices)
            FileLogger.d(TAG, "Device list saved: count=${devices.size}")
        }
    }

    private fun handleError(payload: JsonObject) {
        val code = payload["code"]?.jsonPrimitive?.content
        val message = payload["message"]?.jsonPrimitive?.content
        FileLogger.e(TAG, "Server error: $code - $message")
    }

    private fun handlePing() {
        webSocketClient.send(WsMessageBuilder.pong())
    }

    private fun setupClipboardMonitoring() {
        FileLogger.d(TAG, "Setting up clipboard monitoring")
        // Monitor text changes
        serviceScope.launch {
            clipboardMonitor.currentText.collectLatest { text ->
                FileLogger.d(TAG, "Clipboard text flow: text=${text?.take(30)}, isMonitoring=$isMonitoring")
                if (text != null && isMonitoring) {
                    FileLogger.d(TAG, "Push pipeline: observed local clipboard text, preparing send")
                    syncEngine.pushToServer(text)
                }
            }
        }

        // Monitor image changes
        serviceScope.launch {
            clipboardMonitor.currentContent.collectLatest { content ->
                FileLogger.d(TAG, "Clipboard content flow: type=${content?.contentType}, isMonitoring=$isMonitoring")
                if (content != null && isMonitoring) {
                    when (content.contentType) {
                        ClipboardContentType.TEXT -> {
                            // Already handled by currentText flow
                        }
                        ClipboardContentType.IMAGE -> {
                            content.imageBase64?.let { base64 ->
                                FileLogger.d(TAG, "Push pipeline: observed local clipboard image, preparing send (${content.sizeBytes} bytes)")
                                syncEngine.pushImageToServer(
                                    imageBase64 = base64,
                                    format = content.imageFormat,
                                    size = content.sizeBytes,
                                    checksum = content.checksum
                                )
                            }
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    private fun startClipboardMonitoring() {
        FileLogger.d(TAG, "startClipboardMonitoring() called, current isMonitoring=$isMonitoring")
        if (!isMonitoring) {
            clipboardMonitor.start()
            isMonitoring = true
            FileLogger.d(TAG, "Clipboard monitoring STARTED in service, isMonitoring=$isMonitoring")
        } else {
            FileLogger.d(TAG, "Clipboard monitoring already active, skipping")
        }
    }

    /**
     * Send auth message over WebSocket.
     */
    fun sendAuth() {
        serviceScope.launch {
            val token = settingsManager.getToken()
            val deviceName = settingsManager.getDeviceName()
            if (token.isNotEmpty()) {
                FileLogger.d(TAG, "Sending auth for device=$deviceName")
                val message = WsMessageBuilder.auth(token, deviceName)
                if (!webSocketClient.send(message)) {
                    FileLogger.w(TAG, "Failed to send auth message")
                }
            } else {
                FileLogger.w(TAG, "Skipping auth send because token is empty")
            }
        }
    }

    /**
     * Request device list from server.
     */
    fun requestDeviceList() {
        if (webSocketClient.isConnected()) {
            webSocketClient.send(WsMessageBuilder.deviceList())
        } else {
            FileLogger.w(TAG, "Cannot request device list: WebSocket not connected")
        }
    }

    /**
     * Retry reading the current clipboard while the app is foregrounded.
     * Android blocks background clipboard reads, so we do a catch-up refresh on resume.
     */
    fun refreshClipboardNow() {
        if (!isMonitoring) {
            FileLogger.d(TAG, "Skipping clipboard refresh because monitoring is not active yet")
            return
        }
        FileLogger.d(TAG, "Refreshing clipboard from foreground context")
        clipboardMonitor.refreshNow()
    }

    /**
     * Push clipboard content that was explicitly copied from inside the app UI.
     * This bypasses passive clipboard listener timing and allows intentional re-sends.
     */
    fun pushClipboardContentNow(content: String, contentType: String) {
        FileLogger.d(TAG, "Push pipeline: explicit in-app copy requested, type=$contentType")
        when (contentType) {
            "image" -> {
                val imageBytes = runCatching {
                    android.util.Base64.decode(content, android.util.Base64.DEFAULT)
                }.getOrElse {
                    FileLogger.e(TAG, "Failed to decode image copied from app UI", it)
                    return
                }
                syncEngine.pushImageToServer(
                    imageBase64 = content,
                    format = "image/png",
                    size = imageBytes.size,
                    checksum = EncryptionHelper.computeChecksum(imageBytes),
                    force = true
                )
            }
            else -> {
                syncEngine.pushToServer(content, force = true)
            }
        }
    }

    companion object {
        private const val TAG = "ClipboardService"
        private const val CHANNEL_ID = "clipsync_service_channel"
        private const val NOTIFICATION_ID = 1
    }
}
