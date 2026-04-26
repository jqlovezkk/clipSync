package com.clipsync.app.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.clipsync.app.core.ClipboardMonitor
import com.clipsync.app.core.EncryptionHelper
import com.clipsync.app.core.SettingsManager
import com.clipsync.app.core.SyncEngine
import com.clipsync.app.core.SyncStatus
import com.clipsync.app.data.AppDatabase
import com.clipsync.app.data.entities.ClipboardEntity
import com.clipsync.app.data.entities.DeviceEntity
import com.clipsync.app.network.ApiClient
import com.clipsync.app.network.AuthResponse
import com.clipsync.app.network.ConnectionState
import com.clipsync.app.network.DeviceListResponse
import com.clipsync.app.network.HeartbeatManager
import com.clipsync.app.network.MessageType
import com.clipsync.app.network.WebSocketClient
import com.clipsync.app.network.WsMessage
import com.clipsync.app.network.WsMessageBuilder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Main ViewModel handling authentication, WebSocket connection, clipboard sync,
 * history, and device management.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsManager = SettingsManager(application)
    private val database = AppDatabase.getInstance(application)
    private val webSocketClient = WebSocketClient()
    private val clipboardMonitor = ClipboardMonitor(application)
    private val apiClient = ApiClient()
    private val heartbeatManager = HeartbeatManager(webSocketClient)

    private val syncEngine = SyncEngine(
        webSocketClient = webSocketClient,
        clipboardMonitor = clipboardMonitor,
        settingsManager = settingsManager,
        database = database
    )

    // ─── UI State ───

    private val _uiState = MutableStateFlow<MainUiState>(MainUiState.Loading)
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val _history = MutableStateFlow<List<ClipboardEntity>>(emptyList())
    val history: StateFlow<List<ClipboardEntity>> = _history.asStateFlow()

    private val _devices = MutableStateFlow<List<DeviceEntity>>(emptyList())
    val devices: StateFlow<List<DeviceEntity>> = _devices.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        observeWebSocketState()
        observeClipboardChanges()
        observeHistory()
        observeDevices()
        checkLoginState()
    }

    private fun checkLoginState() {
        viewModelScope.launch {
            val isLoggedIn = settingsManager.isLoggedIn()
            if (isLoggedIn) {
                _uiState.value = MainUiState.Authenticated
                connectToServer()
            } else {
                _uiState.value = MainUiState.Unauthenticated
            }
        }
    }

    private fun observeWebSocketState() {
        viewModelScope.launch {
            webSocketClient.connectionState.collectLatest { state ->
                _connectionState.value = state
                when (state) {
                    is ConnectionState.Connected -> {
                        sendAuth()
                    }
                    is ConnectionState.Disconnected -> {
                        heartbeatManager.stop()
                    }
                    else -> {}
                }
            }
        }

        viewModelScope.launch {
            webSocketClient.messages.collectLatest { message ->
                handleWebSocketMessage(message)
            }
        }
    }

    private fun observeClipboardChanges() {
        viewModelScope.launch {
            clipboardMonitor.currentText.collectLatest { text ->
                if (text != null) {
                    syncEngine.pushToServer(text)
                }
            }
        }
    }

    private fun observeHistory() {
        viewModelScope.launch {
            database.clipboardDao().getAll().collectLatest { items ->
                // Only keep recent items; filter out oversized content to save memory
                _history.value = items
                    .take(50)
                    .filter { it.content.length <= 512 * 1024 }
            }
        }
    }

    private fun observeDevices() {
        viewModelScope.launch {
            database.deviceDao().getAll().collectLatest { deviceList ->
                _devices.value = deviceList
            }
        }
    }

    private fun handleWebSocketMessage(json: String) {
        val wsMessage = WsMessage.fromJson(json) ?: return

        when (wsMessage.type) {
            MessageType.AuthResponse -> handleAuthResponse(wsMessage.payload)
            MessageType.HeartbeatAck -> { /* acknowledged */ }
            MessageType.ClipboardSync -> syncEngine.handleIncomingSync(wsMessage.payload)
            MessageType.ClipboardHistory -> syncEngine.handleHistoryResponse(wsMessage.payload)
            MessageType.DeviceListResponse -> handleDeviceListResponse(wsMessage.payload)
            MessageType.Error -> handleError(wsMessage.payload)
            MessageType.Ping -> webSocketClient.send(WsMessageBuilder.pong())
            else -> Log.d(TAG, "Unhandled: ${wsMessage.type}")
        }
    }

    private fun handleAuthResponse(payload: JsonObject) {
        val success = payload["success"]?.jsonPrimitive?.booleanOrNull ?: false
        val deviceId = payload["device_id"]?.jsonPrimitive?.content
        val message = payload["message"]?.jsonPrimitive?.content

        if (success) {
            Log.d(TAG, "Auth successful")
            deviceId?.let {
                viewModelScope.launch { settingsManager.setDeviceId(it) }
            }
            heartbeatManager.start()
            clipboardMonitor.start()
            syncEngine.requestHistory()
            requestDeviceList()
        } else {
            Log.e(TAG, "Auth failed: $message")
            _errorMessage.value = message ?: "Authentication failed"
        }
    }

    private fun handleDeviceListResponse(payload: JsonObject) {
        viewModelScope.launch {
            val devicesArray = payload["devices"]?.jsonArray ?: return@launch
            val deviceList = devicesArray.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                DeviceEntity(
                    deviceId = obj["device_id"]?.jsonPrimitive?.content ?: "",
                    deviceName = obj["device_name"]?.jsonPrimitive?.content ?: "",
                    platform = obj["platform"]?.jsonPrimitive?.content ?: "unknown",
                    lastSeen = obj["last_seen"]?.jsonPrimitive?.long ?: 0,
                    isOnline = obj["is_online"]?.jsonPrimitive?.booleanOrNull ?: false
                )
            }
            database.deviceDao().insertAll(deviceList)
        }
    }

    private fun handleError(payload: JsonObject) {
        val code = payload["code"]?.jsonPrimitive?.content
        val message = payload["message"]?.jsonPrimitive?.content
        Log.e(TAG, "Server error: $code - $message")
        _errorMessage.value = message ?: "Server error"
    }

    // ─── Public Actions ───

    fun login(serverUrl: String, httpUrl: String, username: String, password: String) {
        viewModelScope.launch {
            _uiState.value = MainUiState.LoggingIn
            settingsManager.setServerUrl(serverUrl)
            settingsManager.setHttpUrl(httpUrl)
            settingsManager.setUsername(username)

            apiClient.baseUrl = httpUrl
            val result = apiClient.login(
                username = username,
                password = password,
                deviceName = settingsManager.getDeviceName()
            )

            result.fold(
                onSuccess = { response ->
                    if (response.success && response.token != null) {
                        settingsManager.setToken(response.token)
                        response.deviceId?.let { settingsManager.setDeviceId(it) }
                        _uiState.value = MainUiState.Authenticated
                        connectToServer()
                    } else {
                        _uiState.value = MainUiState.Unauthenticated
                        _errorMessage.value = response.error ?: "Login failed"
                    }
                },
                onFailure = { error ->
                    _uiState.value = MainUiState.Unauthenticated
                    _errorMessage.value = error.message ?: "Network error"
                }
            )
        }
    }

    fun register(serverUrl: String, httpUrl: String, username: String, password: String) {
        viewModelScope.launch {
            _uiState.value = MainUiState.LoggingIn
            settingsManager.setServerUrl(serverUrl)
            settingsManager.setHttpUrl(httpUrl)
            settingsManager.setUsername(username)

            apiClient.baseUrl = httpUrl
            val result = apiClient.register(
                username = username,
                password = password,
                deviceName = settingsManager.getDeviceName()
            )

            result.fold(
                onSuccess = { response ->
                    if (response.success && response.token != null) {
                        settingsManager.setToken(response.token)
                        response.deviceId?.let { settingsManager.setDeviceId(it) }
                        _uiState.value = MainUiState.Authenticated
                        connectToServer()
                    } else {
                        _uiState.value = MainUiState.Unauthenticated
                        _errorMessage.value = response.error ?: "Registration failed"
                    }
                },
                onFailure = { error ->
                    _uiState.value = MainUiState.Unauthenticated
                    _errorMessage.value = error.message ?: "Network error"
                }
            )
        }
    }

    fun logout() {
        viewModelScope.launch {
            clipboardMonitor.stop()
            heartbeatManager.stop()
            webSocketClient.disconnect()
            settingsManager.clearAll()
            _uiState.value = MainUiState.Unauthenticated
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            database.clipboardDao().deleteAll()
        }
    }

    fun copyToClipboard(content: String) {
        clipboardMonitor.setTextToClipboard(content)
    }

    fun requestDeviceList() {
        if (!webSocketClient.isConnected()) return
        webSocketClient.send(WsMessageBuilder.deviceList())
    }

    fun unregisterDevice(deviceId: String) {
        viewModelScope.launch {
            val token = settingsManager.getToken()
            val result = apiClient.unregisterDevice(token, deviceId)
            result.fold(
                onSuccess = {
                    database.deviceDao().deleteById(deviceId)
                    requestDeviceList()
                },
                onFailure = { error ->
                    _errorMessage.value = error.message ?: "Failed to unregister device"
                }
            )
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    private fun connectToServer() {
        viewModelScope.launch {
            val wsUrl = settingsManager.getServerUrl()
            val httpUrl = settingsManager.getHttpUrl()
            apiClient.baseUrl = httpUrl
            webSocketClient.connect(wsUrl)
        }
    }

    private fun sendAuth() {
        viewModelScope.launch {
            val token = settingsManager.getToken()
            val deviceName = settingsManager.getDeviceName()
            if (token.isNotEmpty()) {
                webSocketClient.send(WsMessageBuilder.auth(token, deviceName))
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        clipboardMonitor.stop()
        heartbeatManager.destroy()
        syncEngine.destroy()
        webSocketClient.destroy()
    }

    companion object {
        private const val TAG = "MainViewModel"
    }
}

/**
 * UI state sealed class.
 */
sealed class MainUiState {
    object Loading : MainUiState()
    object Unauthenticated : MainUiState()
    object LoggingIn : MainUiState()
    object Authenticated : MainUiState()
}
