package com.clipsync.app.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.clipsync.app.core.ClipboardMonitor
import com.clipsync.app.core.SettingsManager
import com.clipsync.app.core.SyncStatus
import com.clipsync.app.data.AppDatabase
import com.clipsync.app.data.entities.ClipboardHistoryItem
import com.clipsync.app.data.entities.DeviceEntity
import com.clipsync.app.network.ApiClient
import com.clipsync.app.network.ConnectionState
import com.clipsync.app.service.ClipboardService
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
import kotlinx.serialization.json.long

/**
 * Main ViewModel handling authentication, UI state, and local data.
 * Network and clipboard sync are handled by ClipboardService.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsManager = SettingsManager(application)
    private val database = AppDatabase.getInstance(application)
    private val apiClient = ApiClient().apply {
        viewModelScope.launch { baseUrl = settingsManager.getHttpUrl() }
    }

    // ClipboardService binding
    private var clipboardService: ClipboardService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ClipboardService.LocalBinder
            clipboardService = binder.getService()
            isBound = true
            Log.d(TAG, "ClipboardService bound successfully")

            // Read current state from service
            updateConnectionStateFromService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            clipboardService = null
            isBound = false
            Log.d(TAG, "ClipboardService disconnected")
        }
    }

    // ─── UI State ───

    private val _uiState = MutableStateFlow<MainUiState>(MainUiState.Loading)
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val _history = MutableStateFlow<List<ClipboardHistoryItem>>(emptyList())
    val history: StateFlow<List<ClipboardHistoryItem>> = _history.asStateFlow()

    private val _devices = MutableStateFlow<List<DeviceEntity>>(emptyList())
    val devices: StateFlow<List<DeviceEntity>> = _devices.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // ClipboardMonitor for local clipboard operations (copyToClipboard, setTextToClipboard)
    private val clipboardMonitor = ClipboardMonitor(application)

    init {
        observeHistory()
        observeDevices()
        checkLoginState()
        bindToClipboardService()
    }

    private fun checkLoginState() {
        viewModelScope.launch {
            val isLoggedIn = settingsManager.isLoggedIn()
            if (isLoggedIn) {
                _uiState.value = MainUiState.Authenticated
                setClipboardServiceRunning(true)
            } else {
                _uiState.value = MainUiState.Unauthenticated
            }
        }
    }

    private fun bindToClipboardService() {
        val intent = Intent(getApplication(), ClipboardService::class.java)
        val bound = getApplication<Application>().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        if (bound) {
            Log.d(TAG, "Binding to ClipboardService requested")
        } else {
            Log.w(TAG, "Failed to bind to ClipboardService")
        }
    }

    private fun updateConnectionStateFromService() {
        val service = clipboardService ?: return
        viewModelScope.launch {
            service.connectionState.collectLatest { state ->
                _connectionState.value = state
            }
        }
        viewModelScope.launch {
            service.syncStatus.collectLatest { status ->
                _syncStatus.value = status
            }
        }
    }

    private fun observeHistory() {
        viewModelScope.launch {
            database.clipboardDao().getHistorySummaries().collectLatest { items ->
                Log.d(TAG, "历史摘要已刷新: count=${items.size}")
                _history.value = items
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
                        setClipboardServiceRunning(true)
                    } else {
                        _uiState.value = MainUiState.Unauthenticated
                        _errorMessage.value = response.message ?: response.error ?: "Login failed"
                    }
                },
                onFailure = { error ->
                    Log.e("MainViewModel", "Login failed", error)
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
                        setClipboardServiceRunning(true)
                    } else {
                        _uiState.value = MainUiState.Unauthenticated
                        _errorMessage.value = response.message ?: response.error ?: "Registration failed"
                    }
                },
                onFailure = { error ->
                    Log.e("MainViewModel", "Register failed", error)
                    _uiState.value = MainUiState.Unauthenticated
                    _errorMessage.value = error.message ?: "Network error"
                }
            )
        }
    }

    fun logout() {
        viewModelScope.launch {
            clipboardMonitor.stop()
            setClipboardServiceRunning(false)
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

    fun copyToClipboard(historyId: Int) {
        viewModelScope.launch {
            val entity = database.clipboardDao().getById(historyId)
            if (entity == null) {
                Log.w(TAG, "复制历史项失败，未找到记录: id=$historyId")
                _errorMessage.value = "未找到要复制的历史记录"
                return@launch
            }

            when (entity.contentType) {
                "image" -> {
                    Log.d(TAG, "复制历史图片到剪贴板: id=$historyId, size=${entity.content.length}")
                    clipboardMonitor.setImageToClipboard(entity.content)
                }
                else -> {
                    Log.d(TAG, "复制历史文本到剪贴板: id=$historyId, length=${entity.content.length}")
                    clipboardMonitor.setTextToClipboard(entity.content)
                }
            }
        }
    }

    fun requestDeviceList() {
        clipboardService?.requestDeviceList()
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

    private fun setClipboardServiceRunning(enabled: Boolean) {
        val application = getApplication<Application>()
        val serviceIntent = Intent(application, ClipboardService::class.java)
        if (enabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                application.startForegroundService(serviceIntent)
            } else {
                application.startService(serviceIntent)
            }
        } else {
            application.stopService(serviceIntent)
        }
    }

    override fun onCleared() {
        super.onCleared()
        clipboardMonitor.stop()
        if (isBound) {
            getApplication<Application>().unbindService(serviceConnection)
            isBound = false
        }
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
