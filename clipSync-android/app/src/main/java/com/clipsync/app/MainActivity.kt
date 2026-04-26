package com.clipsync.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.clipsync.app.ui.screens.DeviceListScreen
import com.clipsync.app.ui.screens.HistoryScreen
import com.clipsync.app.ui.screens.HomeScreen
import com.clipsync.app.ui.screens.LoginScreen
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
                ClipSyncApp(
                    mainViewModel = mainViewModel,
                    settingsViewModel = settingsViewModel
                )
            }
        }
    }
}

@Composable
fun ClipSyncApp(
    mainViewModel: MainViewModel,
    settingsViewModel: SettingsViewModel
) {
    val navController = rememberNavController()
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

    NavHost(
        navController = navController,
        startDestination = if (uiState is MainUiState.Authenticated) "home" else "login"
    ) {
        composable("login") {
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

        composable("home") {
            HomeScreen(
                connectionState = connectionState,
                syncStatus = syncStatus,
                currentUsername = username,
                onNavigateToHistory = { navController.navigate("history") },
                onNavigateToDevices = { navController.navigate("devices") },
                onNavigateToSettings = { navController.navigate("settings") },
                onLogout = {
                    mainViewModel.logout()
                    navController.navigate("login") {
                        popUpTo("home") { inclusive = true }
                    }
                }
            )
        }

        composable("history") {
            HistoryScreen(
                history = history,
                onCopy = { content -> mainViewModel.copyToClipboard(content) },
                onClearHistory = { mainViewModel.clearHistory() }
            )
        }

        composable("devices") {
            DeviceListScreen(
                devices = devices,
                onUnregister = { deviceId -> mainViewModel.unregisterDevice(deviceId) },
                onRefresh = { mainViewModel.requestDeviceList() }
            )
        }

        composable("settings") {
            SettingsScreen(
                serverUrl = serverUrl,
                httpUrl = httpUrl,
                syncEnabled = syncEnabled,
                encryptionEnabled = encryptionEnabled,
                deviceName = deviceName,
                username = username,
                onServerUrlChange = { settingsViewModel.setServerUrl(it) },
                onHttpUrlChange = { settingsViewModel.setHttpUrl(it) },
                onSyncEnabledChange = { settingsViewModel.setSyncEnabled(it) },
                onEncryptionEnabledChange = { settingsViewModel.setEncryptionEnabled(it) },
                onDeviceNameChange = { settingsViewModel.setDeviceName(it) },
                onClearHistory = { mainViewModel.clearHistory() },
                onLogout = {
                    mainViewModel.logout()
                    navController.navigate("login") {
                        popUpTo("settings") { inclusive = true }
                    }
                }
            )
        }
    }
}
