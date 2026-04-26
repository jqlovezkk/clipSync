package com.clipsync.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.clipsync.app.core.SyncStatus
import com.clipsync.app.data.entities.ClipboardHistoryItem
import com.clipsync.app.data.entities.DeviceEntity
import com.clipsync.app.network.ConnectionState
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * WeChat-style main screen with bottom tab navigation.
 * Supports horizontal swipe gesture to switch tabs.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainScreenWithTabs(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    connectionState: ConnectionState,
    syncStatus: SyncStatus,
    history: List<ClipboardHistoryItem>,
    devices: List<DeviceEntity>,
    serverUrl: String,
    httpUrl: String,
    syncEnabled: Boolean,
    encryptionEnabled: Boolean,
    deviceName: String,
    username: String,
    errorMessage: String?,
    onCopyClipboard: (Int) -> Unit,
    onClearHistory: () -> Unit,
    onUnregisterDevice: (String) -> Unit,
    onRefreshDevices: () -> Unit,
    onServerUrlChange: (String) -> Unit,
    onHttpUrlChange: (String) -> Unit,
    onSyncEnabledChange: (Boolean) -> Unit,
    onEncryptionEnabledChange: (Boolean) -> Unit,
    onDeviceNameChange: (String) -> Unit,
    onLogout: () -> Unit,
    onClearError: () -> Unit
) {
    val pagerState = rememberPagerState(
        initialPage = selectedTab,
        pageCount = { 4 }
    )

    // Sync pager state when selectedTab changes from external (e.g., button click)
    LaunchedEffect(selectedTab) {
        if (pagerState.currentPage != selectedTab) {
            pagerState.animateScrollToPage(selectedTab)
        }
    }

    // Listen to pager state changes and update selectedTab (for swipe gestures)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { page ->
                onTabSelected(page)
            }
    }

    Scaffold(
        bottomBar = {
            WeChatBottomNavigation(
                selectedTab = selectedTab,
                onTabSelected = { newTab ->
                    onTabSelected(newTab)
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = true,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    0 -> HomeScreen(
                        connectionState = connectionState,
                        syncStatus = syncStatus,
                        currentUsername = username,
                        onNavigateToHistory = { onTabSelected(1) },
                        onNavigateToDevices = { onTabSelected(2) },
                        onNavigateToSettings = { onTabSelected(3) },
                        onLogout = onLogout
                    )
                    1 -> HistoryScreen(
                        history = history,
                        onCopy = onCopyClipboard,
                        onClearHistory = onClearHistory
                    )
                    2 -> DeviceListScreen(
                        devices = devices,
                        onUnregister = onUnregisterDevice,
                        onRefresh = onRefreshDevices
                    )
                    3 -> SettingsScreen(
                        serverUrl = serverUrl,
                        httpUrl = httpUrl,
                        syncEnabled = syncEnabled,
                        encryptionEnabled = encryptionEnabled,
                        deviceName = deviceName,
                        username = username,
                        onServerUrlChange = onServerUrlChange,
                        onHttpUrlChange = onHttpUrlChange,
                        onSyncEnabledChange = onSyncEnabledChange,
                        onEncryptionEnabledChange = onEncryptionEnabledChange,
                        onDeviceNameChange = onDeviceNameChange,
                        onClearHistory = onClearHistory,
                        onLogout = onLogout
                    )
                }
            }
        }
    }
}

/**
 * WeChat-style bottom navigation with 4 tabs.
 */
@Composable
fun WeChatBottomNavigation(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit
) {
    val tabs = listOf(
        TabItem("Home", Icons.Default.Home),
        TabItem("History", Icons.Default.History),
        TabItem("Devices", Icons.Default.Computer),
        TabItem("Settings", Icons.Default.Settings)
    )

    NavigationBar(
        containerColor = Color(0xFF111827),
        tonalElevation = 8.dp
    ) {
        tabs.forEachIndexed { index, tab ->
            val isSelected = selectedTab == index
            NavigationBarItem(
                icon = {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label,
                        tint = if (isSelected) Color(0xFF3DDC84) else Color(0xFF6B7280)
                    )
                },
                label = {
                    Text(
                        text = tab.label,
                        color = if (isSelected) Color(0xFF3DDC84) else Color(0xFF6B7280)
                    )
                },
                selected = isSelected,
                onClick = { onTabSelected(index) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color(0xFF3DDC84),
                    unselectedIconColor = Color(0xFF6B7280),
                    selectedTextColor = Color(0xFF3DDC84),
                    unselectedTextColor = Color(0xFF6B7280),
                    indicatorColor = Color.Transparent
                )
            )
        }
    }
}

data class TabItem(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)