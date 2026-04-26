package com.clipsync.app.network

import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP API client for authentication and device management.
 * Uses raw HttpURLConnection to avoid Retrofit dependency.
 */
class ApiClient {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    var baseUrl: String = "http://10.0.2.2:8081"

    /**
     * Login to the server.
     */
    suspend fun login(
        username: String,
        password: String,
        deviceName: String,
        platform: String = "android"
    ): Result<AuthResponse> = runCatching {
        val request = LoginRequest(username, password, deviceName, platform)
        val response = post("/api/v1/auth/login", json.encodeToString(request))
        json.decodeFromString<AuthResponse>(response)
    }

    /**
     * Register a new account.
     */
    suspend fun register(
        username: String,
        password: String,
        deviceName: String,
        platform: String = "android"
    ): Result<AuthResponse> = runCatching {
        val request = RegisterRequest(username, password, deviceName, platform)
        val response = post("/api/v1/auth/register", json.encodeToString(request))
        json.decodeFromString<AuthResponse>(response)
    }

    /**
     * Refresh the auth token.
     */
    suspend fun refreshToken(token: String): Result<AuthResponse> = runCatching {
        val response = postWithAuth("/api/v1/auth/refresh", "", token)
        json.decodeFromString<AuthResponse>(response)
    }

    /**
     * Get the list of registered devices.
     */
    suspend fun getDevices(token: String): Result<DeviceListResponse> = runCatching {
        val response = getWithAuth("/api/v1/devices", token)
        json.decodeFromString<DeviceListResponse>(response)
    }

    /**
     * Unregister a device.
     */
    suspend fun unregisterDevice(token: String, deviceId: String): Result<GenericSuccessResponse> =
        runCatching {
            val response = deleteWithAuth("/api/v1/devices/$deviceId", token)
            json.decodeFromString<GenericSuccessResponse>(response)
        }

    /**
     * Health check.
     */
    suspend fun healthCheck(): Result<String> = runCatching {
        get("/api/v1/health")
    }

    private fun post(path: String, body: String): String {
        val url = URL("$baseUrl$path")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(body.toByteArray()) }
        return readResponse(connection)
    }

    private fun postWithAuth(path: String, body: String, token: String): String {
        val url = URL("$baseUrl$path")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.outputStream.use { it.write(body.toByteArray()) }
        return readResponse(connection)
    }

    private fun getWithAuth(path: String, token: String): String {
        val url = URL("$baseUrl$path")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Authorization", "Bearer $token")
        return readResponse(connection)
    }

    private fun deleteWithAuth(path: String, token: String): String {
        val url = URL("$baseUrl$path")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "DELETE"
        connection.setRequestProperty("Authorization", "Bearer $token")
        return readResponse(connection)
    }

    private fun get(path: String): String {
        val url = URL("$baseUrl$path")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        return readResponse(connection)
    }

    private fun readResponse(connection: HttpURLConnection): String {
        val inputStream = if (connection.responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream ?: connection.inputStream
        }
        val body = inputStream?.bufferedReader()?.use { it.readText() } ?: ""
        connection.disconnect()
        if (connection.responseCode !in 200..299) {
            throw IOException("HTTP ${connection.responseCode}: $body")
        }
        return body
    }

    companion object {
        private const val TAG = "ApiClient"
    }
}
