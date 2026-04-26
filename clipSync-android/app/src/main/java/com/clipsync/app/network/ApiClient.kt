package com.clipsync.app.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    private val json = Json { 
        ignoreUnknownKeys = true 
        isLenient = true 
        encodeDefaults = true 
    }

    var baseUrl: String = ""

    /**
     * Login to the server.
     */
    suspend fun login(
        username: String,
        password: String,
        deviceName: String,
        platform: String = "android"
    ): Result<AuthResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val request = LoginRequest(username, password, deviceName, platform)
            val response = post("/api/v1/auth/login", json.encodeToString(request))
            json.decodeFromString<AuthResponse>(response)
        }
    }

    /**
     * Register a new account.
     */
    suspend fun register(
        username: String,
        password: String,
        deviceName: String,
        platform: String = "android"
    ): Result<AuthResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val request = RegisterRequest(username, password, deviceName, platform)
            val response = post("/api/v1/auth/register", json.encodeToString(request))
            json.decodeFromString<AuthResponse>(response)
        }
    }

    /**
     * Refresh the auth token.
     */
    suspend fun refreshToken(token: String): Result<AuthResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val response = postWithAuth("/api/v1/auth/refresh", "", token)
            json.decodeFromString<AuthResponse>(response)
        }
    }

    /**
     * Get the list of registered devices.
     */
    suspend fun getDevices(token: String): Result<DeviceListResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val response = getWithAuth("/api/v1/devices", token)
            json.decodeFromString<DeviceListResponse>(response)
        }
    }

    /**
     * Unregister a device.
     */
    suspend fun unregisterDevice(token: String, deviceId: String): Result<GenericSuccessResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = deleteWithAuth("/api/v1/devices/$deviceId", token)
                json.decodeFromString<GenericSuccessResponse>(response)
            }
        }

    /**
     * Health check.
     */
    suspend fun healthCheck(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            get("/api/v1/health")
        }
    }

    private fun post(path: String, body: String): String {
        val urlString = "$baseUrl$path"
        Log.d(TAG, "POST $urlString")
        Log.d(TAG, "Body: $body")
        try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000 // 10 seconds timeout
            connection.readTimeout = 10000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
            val response = readResponse(connection)
            Log.d(TAG, "POST $urlString Response: $response")
            return response
        } catch (e: Exception) {
            Log.e(TAG, "POST $urlString failed", e)
            throw e
        }
    }

    private fun postWithAuth(path: String, body: String, token: String): String {
        val urlString = "$baseUrl$path"
        Log.d(TAG, "POST (Auth) $urlString")
        try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.outputStream.use { it.write(body.toByteArray()) }
            val response = readResponse(connection)
            Log.d(TAG, "POST (Auth) $urlString Response: $response")
            return response
        } catch (e: Exception) {
            Log.e(TAG, "POST (Auth) $urlString failed", e)
            throw e
        }
    }

    private fun getWithAuth(path: String, token: String): String {
        val urlString = "$baseUrl$path"
        Log.d(TAG, "GET (Auth) $urlString")
        try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $token")
            val response = readResponse(connection)
            Log.d(TAG, "GET (Auth) $urlString Response: $response")
            return response
        } catch (e: Exception) {
            Log.e(TAG, "GET (Auth) $urlString failed", e)
            throw e
        }
    }

    private fun deleteWithAuth(path: String, token: String): String {
        val urlString = "$baseUrl$path"
        Log.d(TAG, "DELETE (Auth) $urlString")
        try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.requestMethod = "DELETE"
            connection.setRequestProperty("Authorization", "Bearer $token")
            val response = readResponse(connection)
            Log.d(TAG, "DELETE (Auth) $urlString Response: $response")
            return response
        } catch (e: Exception) {
            Log.e(TAG, "DELETE (Auth) $urlString failed", e)
            throw e
        }
    }

    private fun get(path: String): String {
        val urlString = "$baseUrl$path"
        Log.d(TAG, "GET $urlString")
        try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.requestMethod = "GET"
            val response = readResponse(connection)
            Log.d(TAG, "GET $urlString Response: $response")
            return response
        } catch (e: Exception) {
            Log.e(TAG, "GET $urlString failed", e)
            throw e
        }
    }

    private fun readResponse(connection: HttpURLConnection): String {
        val responseCode = connection.responseCode
        Log.d(TAG, "HTTP Response Code: $responseCode")
        val inputStream = if (responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream ?: connection.inputStream
        }
        val body = inputStream?.bufferedReader()?.use { it.readText() } ?: ""
        connection.disconnect()
        
        if (responseCode !in 200..299) {
            Log.e(TAG, "HTTP Error $responseCode: $body")
            // If it looks like JSON, return it so the caller can parse the structured error.
            if (!body.trimStart().startsWith("{")) {
                throw IOException("HTTP $responseCode: $body")
            }
        }
        return body
    }

    companion object {
        private const val TAG = "ApiClient"
    }
}
