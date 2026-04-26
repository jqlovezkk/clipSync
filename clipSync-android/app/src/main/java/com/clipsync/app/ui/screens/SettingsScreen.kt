package com.clipsync.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsScreen(
    serverUrl: String,
    httpUrl: String,
    syncEnabled: Boolean,
    encryptionEnabled: Boolean,
    deviceName: String,
    username: String,
    onServerUrlChange: (String) -> Unit,
    onHttpUrlChange: (String) -> Unit,
    onSyncEnabledChange: (Boolean) -> Unit,
    onEncryptionEnabledChange: (Boolean) -> Unit,
    onDeviceNameChange: (String) -> Unit,
    onClearHistory: () -> Unit,
    onLogout: () -> Unit
) {
    var showClearHistoryDialog by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Account section
        SettingsSection(title = "Account") {
            Text(
                text = "Logged in as: $username",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Server section
        SettingsSection(title = "Server") {
            OutlinedTextField(
                value = serverUrl,
                onValueChange = onServerUrlChange,
                label = { Text("WebSocket URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = httpUrl,
                onValueChange = onHttpUrlChange,
                label = { Text("HTTP URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Sync section
        SettingsSection(title = "Sync") {
            SettingRow(
                title = "Enable Sync",
                description = "Automatically sync clipboard content"
            ) {
                Switch(
                    checked = syncEnabled,
                    onCheckedChange = onSyncEnabledChange
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Security section
        SettingsSection(title = "Security") {
            SettingRow(
                title = "Enable Encryption",
                description = "Encrypt clipboard content before sending"
            ) {
                Switch(
                    checked = encryptionEnabled,
                    onCheckedChange = onEncryptionEnabledChange
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Device section
        SettingsSection(title = "Device") {
            OutlinedTextField(
                value = deviceName,
                onValueChange = onDeviceNameChange,
                label = { Text("Device Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Actions
        Button(
            onClick = { showClearHistoryDialog = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Clear History")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onLogout,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            )
        ) {
            Text("Logout")
        }
    }

    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            title = { Text("Clear History") },
            text = { Text("Clear all clipboard history?") },
            confirmButton = {
                TextButton(onClick = {
                    onClearHistory()
                    showClearHistoryDialog = false
                }) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            content()
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    description: String? = null,
    action: @Composable () -> Unit
) {
    Column {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge
                )
                description?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            action()
        }
    }
}
