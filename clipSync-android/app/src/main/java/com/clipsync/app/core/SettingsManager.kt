package com.clipsync.app.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Settings manager using DataStore (Preferences).
 * Persists app configuration across restarts.
 */
class SettingsManager(private val context: Context) {

    // Preference keys
    private val SERVER_URL_KEY = stringPreferencesKey("server_url")
    private val HTTP_URL_KEY = stringPreferencesKey("http_url")
    private val USERNAME_KEY = stringPreferencesKey("username")
    private val TOKEN_KEY = stringPreferencesKey("token")
    private val DEVICE_ID_KEY = stringPreferencesKey("device_id")
    private val DEVICE_NAME_KEY = stringPreferencesKey("device_name")
    private val SYNC_ENABLED_KEY = booleanPreferencesKey("sync_enabled")
    private val ENCRYPTION_ENABLED_KEY = booleanPreferencesKey("encryption_enabled")

    // Default values
    private val defaultWsUrl = "ws://8.141.100.238:8080"
    private val defaultHttpUrl = "http://8.141.100.238:8081"

    // ─── Server URL ───

    val serverUrlFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[SERVER_URL_KEY] ?: defaultWsUrl
    }

    suspend fun getServerUrl(): String = serverUrlFlow.first()

    suspend fun setServerUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[SERVER_URL_KEY] = url
        }
    }

    // ─── HTTP URL ───

    val httpUrlFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[HTTP_URL_KEY] ?: defaultHttpUrl
    }

    suspend fun getHttpUrl(): String = httpUrlFlow.first()

    suspend fun setHttpUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[HTTP_URL_KEY] = url
        }
    }

    // ─── Username ───

    val usernameFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[USERNAME_KEY] ?: ""
    }

    suspend fun getUsername(): String = usernameFlow.first()

    suspend fun setUsername(username: String) {
        context.dataStore.edit { prefs ->
            prefs[USERNAME_KEY] = username
        }
    }

    // ─── Token ───

    val tokenFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[TOKEN_KEY] ?: ""
    }

    suspend fun getToken(): String = tokenFlow.first()

    suspend fun setToken(token: String) {
        context.dataStore.edit { prefs ->
            prefs[TOKEN_KEY] = token
        }
    }

    // ─── Device ID ───

    val deviceIdFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[DEVICE_ID_KEY] ?: ""
    }

    suspend fun getDeviceId(): String {
        val existing = deviceIdFlow.first()
        if (existing.isNotEmpty()) return existing
        // Generate new device ID
        val newId = UUID.randomUUID().toString()
        setDeviceId(newId)
        return newId
    }

    suspend fun setDeviceId(deviceId: String) {
        context.dataStore.edit { prefs ->
            prefs[DEVICE_ID_KEY] = deviceId
        }
    }

    // ─── Device Name ───

    val deviceNameFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[DEVICE_NAME_KEY] ?: android.os.Build.MODEL
    }

    suspend fun getDeviceName(): String = deviceNameFlow.first()

    suspend fun setDeviceName(name: String) {
        context.dataStore.edit { prefs ->
            prefs[DEVICE_NAME_KEY] = name
        }
    }

    // ─── Sync Enabled ───

    val syncEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[SYNC_ENABLED_KEY] ?: true
    }

    suspend fun isSyncEnabled(): Boolean = syncEnabledFlow.first()

    suspend fun setSyncEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[SYNC_ENABLED_KEY] = enabled
        }
    }

    // ─── Encryption Enabled ───

    val encryptionEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENCRYPTION_ENABLED_KEY] ?: false
    }

    suspend fun isEncryptionEnabled(): Boolean = encryptionEnabledFlow.first()

    suspend fun setEncryptionEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENCRYPTION_ENABLED_KEY] = enabled
        }
    }

    // ─── Clear all settings ───

    suspend fun clearAll() {
        context.dataStore.edit { prefs ->
            prefs.clear()
        }
    }

    // ─── Check if logged in ───

    suspend fun isLoggedIn(): Boolean {
        return getToken().isNotEmpty() && getUsername().isNotEmpty()
    }
}
