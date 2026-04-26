package com.clipsync.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.clipsync.app.core.ClipboardContentType
import com.clipsync.app.core.ClipboardMonitor
import com.clipsync.app.core.SettingsManager
import com.clipsync.app.core.SyncEngine
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Foreground service that monitors clipboard and syncs via WebSocket.
 * Runs persistently to ensure clipboard changes are captured even when app is in background.
 */
class ClipboardService : Service() {

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
        Log.d(TAG, "Service created")

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
        Log.d(TAG, "Starting foreground service with type=dataSync")
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

        // Connect to server
        connectToServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
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
                Log.w(TAG, "No token available, cannot connect")
                return@launch
            }

            Log.d(TAG, "Connecting service WebSocket to $wsUrl")
            webSocketClient.connect(wsUrl)
        }
    }

    private fun observeConnectionState() {
        serviceScope.launch {
            webSocketClient.connectionState.collectLatest { state ->
                when (state) {
                    ConnectionState.Connected -> {
                        Log.d(TAG, "Service WebSocket connected, sending auth")
                        sendAuth()
                    }
                    ConnectionState.Connecting -> {
                        Log.d(TAG, "Service WebSocket connecting")
                    }
                    ConnectionState.Disconnected -> {
                        Log.d(TAG, "Service WebSocket disconnected")
                    }
                    is ConnectionState.Error -> {
                        Log.e(TAG, "Service WebSocket error: ${state.message}")
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
            Log.w(TAG, "Failed to parse WebSocket message: ${json.take(200)}")
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
            else -> Log.d(TAG, "Unhandled message type: ${wsMessage.type}")
        }
    }

    private fun handleAuthResponse(payload: JsonObject) {
        val success = payload["success"]?.jsonPrimitive?.booleanOrNull ?: false
        val deviceId = payload["device_id"]?.jsonPrimitive?.content
        val message = payload["message"]?.jsonPrimitive?.content

        if (success) {
            Log.d(TAG, "Auth successful, device_id: $deviceId")
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
            Log.e(TAG, "Auth failed: $message")
        }
    }

    private fun handleClipboardSync(payload: JsonObject) {
        syncEngine.handleIncomingSync(payload)
    }

    private fun handleDeviceListResponse(payload: JsonObject) {
        // Device list handling can be extended here
        Log.d(TAG, "Device list received: $payload")
    }

    private fun handleError(payload: JsonObject) {
        val code = payload["code"]?.jsonPrimitive?.content
        val message = payload["message"]?.jsonPrimitive?.content
        Log.e(TAG, "Server error: $code - $message")
    }

    private fun handlePing() {
        webSocketClient.send(WsMessageBuilder.pong())
    }

    private fun setupClipboardMonitoring() {
        // Monitor text changes
        serviceScope.launch {
            clipboardMonitor.currentText.collectLatest { text ->
                if (text != null && isMonitoring) {
                    syncEngine.pushToServer(text)
                }
            }
        }

        // Monitor image changes
        serviceScope.launch {
            clipboardMonitor.currentContent.collectLatest { content ->
                if (content != null && isMonitoring) {
                    when (content.contentType) {
                        ClipboardContentType.TEXT -> {
                            // Already handled by currentText flow
                        }
                        ClipboardContentType.IMAGE -> {
                            content.imageBase64?.let { base64 ->
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
        if (!isMonitoring) {
            clipboardMonitor.start()
            isMonitoring = true
            Log.d(TAG, "Clipboard monitoring started in service")
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
                Log.d(TAG, "Sending auth for device=$deviceName")
                val message = WsMessageBuilder.auth(token, deviceName)
                if (!webSocketClient.send(message)) {
                    Log.w(TAG, "Failed to send auth message")
                }
            } else {
                Log.w(TAG, "Skipping auth send because token is empty")
            }
        }
    }

    companion object {
        private const val TAG = "ClipboardService"
        private const val CHANNEL_ID = "clipsync_service_channel"
        private const val NOTIFICATION_ID = 1
    }
}
