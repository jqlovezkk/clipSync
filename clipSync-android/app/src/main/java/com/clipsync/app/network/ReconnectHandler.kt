package com.clipsync.app.network

import com.clipsync.app.core.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Handles automatic reconnection with exponential backoff.
 * Backoff: 1s → 2s → 4s → 8s → 16s → 32s → 60s (max)
 */
class ReconnectHandler(
    private val onReconnect: (String) -> Unit,
    private val onStateChange: (ConnectionState) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var reconnectJob: Job? = null
    private var currentBackoffMs = INITIAL_BACKOFF_MS
    private var lastUrl: String = ""
    private var consecutiveFailures = 0

    /**
     * Called when connection is established successfully.
     */
    fun onConnected() {
        consecutiveFailures = 0
        currentBackoffMs = INITIAL_BACKOFF_MS
        reconnectJob?.cancel()
        reconnectJob = null
    }

    /**
     * Called when connection is lost.
     */
    fun onDisconnected() {
        consecutiveFailures++
        if (reconnectJob?.isActive == true) return

        val delayMs = currentBackoffMs
        FileLogger.d(TAG, "Scheduling reconnect in ${delayMs}ms (attempt $consecutiveFailures)")
        onStateChange(ConnectionState.Error("Disconnected, retrying in ${delayMs / 1000}s"))

        reconnectJob = scope.launch {
            delay(delayMs)
            currentBackoffMs = (currentBackoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            if (lastUrl.isNotEmpty()) {
                onStateChange(ConnectionState.Connecting)
                onReconnect(lastUrl)
            }
        }
    }

    /**
     * Start tracking a connection attempt.
     */
    fun trackConnection(url: String) {
        lastUrl = url
        consecutiveFailures = 0
        currentBackoffMs = INITIAL_BACKOFF_MS
    }

    /**
     * Cancel any pending reconnection.
     */
    fun cancel() {
        reconnectJob?.cancel()
        reconnectJob = null
        consecutiveFailures = 0
        currentBackoffMs = INITIAL_BACKOFF_MS
    }

    /**
     * Cancel and release all resources.
     */
    fun destroy() {
        cancel()
        scope.cancel()
    }

    companion object {
        private const val TAG = "ReconnectHandler"
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 60_000L
    }
}
