package com.clipsync.app.network

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Manages heartbeat sending every 30 seconds.
 * Sends heartbeat messages and tracks sequence numbers.
 */
class HeartbeatManager(
    private val webSocketClient: WebSocketClient,
    private val intervalMs: Long = HEARTBEAT_INTERVAL_MS
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var heartbeatJob: Job? = null
    private var sequenceNumber = 0

    /**
     * Start sending heartbeats.
     */
    fun start() {
        stop()
        Log.d(TAG, "Starting heartbeat (interval: ${intervalMs}ms)")
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(intervalMs)
                if (webSocketClient.isConnected()) {
                    sequenceNumber++
                    val message = WsMessageBuilder.heartbeat(sequenceNumber)
                    val sent = webSocketClient.send(message)
                    if (!sent) {
                        Log.w(TAG, "Failed to send heartbeat #$sequenceNumber")
                    } else {
                        Log.d(TAG, "Heartbeat #$sequenceNumber sent")
                    }
                }
            }
        }
    }

    /**
     * Stop sending heartbeats.
     */
    fun stop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        Log.d(TAG, "Heartbeat stopped")
    }

    /**
     * Reset the sequence counter.
     */
    fun reset() {
        sequenceNumber = 0
    }

    /**
     * Clean up resources.
     */
    fun destroy() {
        stop()
        scope.cancel()
    }

    companion object {
        private const val TAG = "HeartbeatManager"
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
    }
}
