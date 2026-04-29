package com.clipsync.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clipsync.app.ui.screens.DeviceListScreen
import com.clipsync.app.ui.screens.HistoryScreen
import com.clipsync.app.ui.screens.HomeScreen
import com.clipsync.app.ui.screens.LoginScreen
import com.clipsync.app.ui.screens.MainScreenWithTabs
import com.clipsync.app.ui.screens.SettingsScreen
import com.clipsync.app.ui.theme.ClipSyncTheme
import com.clipsync.app.viewmodel.MainUiState
import com.clipsync.app.viewmodel.MainViewModel
import com.clipsync.app.viewmodel.SettingsViewModel

/**
 * Main activity that hosts the Compose navigation graph.
 */
class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ClipSyncTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ClipSyncApp(
                        mainViewModel = mainViewModel,
                        settingsViewModel = settingsViewModel
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        mainViewModel.onAppForegrounded()
    }
}

@Composable
fun ClipSyncApp(
    mainViewModel: MainViewModel,
    settingsViewModel: SettingsViewModel
) {
    val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()
    val connectionState by mainViewModel.connectionState.collectAsStateWithLifecycle()
    val syncStatus by mainViewModel.syncStatus.collectAsStateWithLifecycle()
    val history by mainViewModel.history.collectAsStateWithLifecycle()
    val devices by mainViewModel.devices.collectAsStateWithLifecycle()
    val errorMessage by mainViewModel.errorMessage.collectAsStateWithLifecycle()

    val serverUrl by settingsViewModel.serverUrl.collectAsStateWithLifecycle()
    val httpUrl by settingsViewModel.httpUrl.collectAsStateWithLifecycle()
    val syncEnabled by settingsViewModel.syncEnabled.collectAsStateWithLifecycle()
    val encryptionEnabled by settingsViewModel.encryptionEnabled.collectAsStateWithLifecycle()
    val deviceName by settingsViewModel.deviceName.collectAsStateWithLifecycle()
    val username by settingsViewModel.username.collectAsStateWithLifecycle()

    // WeChat-style bottom tab navigation
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    when (uiState) {
        is MainUiState.Loading -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
        is MainUiState.Unauthenticated,
        is MainUiState.LoggingIn -> {
            LoginScreen(
                onLogin = { serverUrl, httpUrl, user, password ->
                    mainViewModel.login(serverUrl, httpUrl, user, password)
                },
                onRegister = { serverUrl, httpUrl, user, password ->
                    mainViewModel.register(serverUrl, httpUrl, user, password)
                },
                isLoggingIn = uiState is MainUiState.LoggingIn,
                errorMessage = errorMessage,
                onClearError = { mainViewModel.clearError() }
            )
        }
        is MainUiState.Authenticated -> {
            MainScreenWithTabs(
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
                connectionState = connectionState,
                syncStatus = syncStatus,
                history = history,
                devices = devices,
                serverUrl = serverUrl,
                httpUrl = httpUrl,
                syncEnabled = syncEnabled,
                encryptionEnabled = encryptionEnabled,
                deviceName = deviceName,
                username = username,
                errorMessage = errorMessage,
                onCopyClipboard = { id -> mainViewModel.copyToClipboard(id) },
                onClearHistory = { mainViewModel.clearHistory() },
                onUnregisterDevice = { deviceId -> mainViewModel.unregisterDevice(deviceId) },
                onRefreshDevices = { mainViewModel.requestDeviceList() },
                onServerUrlChange = { settingsViewModel.setServerUrl(it) },
                onHttpUrlChange = { settingsViewModel.setHttpUrl(it) },
                onSyncEnabledChange = { settingsViewModel.setSyncEnabled(it) },
                onEncryptionEnabledChange = { settingsViewModel.setEncryptionEnabled(it) },
                onDeviceNameChange = { settingsViewModel.setDeviceName(it) },
                onLogout = {
                    mainViewModel.logout()
                    selectedTab = 0
                },
                onClearError = { mainViewModel.clearError() }
            )
        }
    }
}
