package com.clipsync.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.clipsync.app.core.SettingsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * ViewModel for the Settings screen.
 * Manages server URL, sync toggle, encryption toggle, and device name.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsManager = SettingsManager(application)

    private val _serverUrl = MutableStateFlow("")
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _httpUrl = MutableStateFlow("")
    val httpUrl: StateFlow<String> = _httpUrl.asStateFlow()

    private val _syncEnabled = MutableStateFlow(true)
    val syncEnabled: StateFlow<Boolean> = _syncEnabled.asStateFlow()

    private val _encryptionEnabled = MutableStateFlow(false)
    val encryptionEnabled: StateFlow<Boolean> = _encryptionEnabled.asStateFlow()

    private val _deviceName = MutableStateFlow("")
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    private val _username = MutableStateFlow("")
    val username: StateFlow<String> = _username.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            launch {
                settingsManager.serverUrlFlow.collectLatest { _serverUrl.value = it }
            }
            launch {
                settingsManager.httpUrlFlow.collectLatest { _httpUrl.value = it }
            }
            launch {
                settingsManager.syncEnabledFlow.collectLatest { _syncEnabled.value = it }
            }
            launch {
                settingsManager.encryptionEnabledFlow.collectLatest { _encryptionEnabled.value = it }
            }
            launch {
                settingsManager.deviceNameFlow.collectLatest { _deviceName.value = it }
            }
            launch {
                settingsManager.usernameFlow.collectLatest { _username.value = it }
            }
        }
    }

    fun setServerUrl(url: String) {
        viewModelScope.launch {
            settingsManager.setServerUrl(url)
        }
    }

    fun setHttpUrl(url: String) {
        viewModelScope.launch {
            settingsManager.setHttpUrl(url)
        }
    }

    fun setSyncEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.setSyncEnabled(enabled)
        }
    }

    fun setEncryptionEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.setEncryptionEnabled(enabled)
        }
    }

    fun setDeviceName(name: String) {
        viewModelScope.launch {
            settingsManager.setDeviceName(name)
        }
    }
}
