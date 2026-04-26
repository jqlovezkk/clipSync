package com.clipsync.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.clipsync.app.data.entities.DeviceEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(
    devices: List<DeviceEntity>,
    onUnregister: (String) -> Unit,
    onRefresh: () -> Unit
) {
    var deviceToUnregister: String? by rememberSaveable { mutableStateOf(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Devices") },
                actions = {
                    IconButton(onClick = onRefresh) {
                        androidx.compose.material.icons.Icons.Default
                        Text("Refresh")
                    }
                }
            )
        }
    ) { paddingValues ->
        if (devices.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "No other devices registered",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 8.dp)
            ) {
                items(devices, key = { it.deviceId }) { device ->
                    DeviceItem(
                        device = device,
                        onUnregister = { deviceToUnregister = device.deviceId }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }

    deviceToUnregister?.let { deviceId ->
        AlertDialog(
            onDismissRequest = { deviceToUnregister = null },
            title = { Text("Unregister Device") },
            text = { Text("Unregister this device?") },
            confirmButton = {
                TextButton(onClick = {
                    onUnregister(deviceId)
                    deviceToUnregister = null
                }) {
                    Text("Unregister")
                }
            },
            dismissButton = {
                TextButton(onClick = { deviceToUnregister = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun DeviceItem(
    device: DeviceEntity,
    onUnregister: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = getPlatformIcon(device.platform),
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.deviceName,
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row {
                    Text(
                        text = device.platform.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = " • ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (device.isOnline) "Online" else "Offline",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (device.isOnline) Color(0xFF3DDC84) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onUnregister) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Unregister",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

private fun getPlatformIcon(platform: String): ImageVector {
    return when (platform.lowercase()) {
        "windows" -> Icons.Default.Computer
        "macos" -> Icons.Default.Computer
        "android" -> Icons.Default.PhoneAndroid
        "ios" -> Icons.Default.PhoneAndroid
        else -> Icons.Default.Computer
    }
}
