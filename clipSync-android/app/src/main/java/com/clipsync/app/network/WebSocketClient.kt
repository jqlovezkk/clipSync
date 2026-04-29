package com.clipsync.app.network

import com.clipsync.app.core.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

/**
 * WebSocket client using OkHttp for real-time communication.
 * Handles connection lifecycle, message sending/receiving, and state management.
 */
class WebSocketClient {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var webSocket: WebSocket? = null
    private var client: OkHttpClient? = null

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val reconnectHandler = ReconnectHandler(
        onReconnect = { url -> connect(url) },
        onStateChange = { state ->
            _connectionState.value = state
        }
    )

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            FileLogger.d(TAG, "WebSocket connected")
            _connectionState.value = ConnectionState.Connected
            reconnectHandler.onConnected()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // Drop messages that are too large to prevent OOM
            if (text.length > MAX_MESSAGE_CHARS) {
                FileLogger.w(TAG, "Dropping oversized message: ${text.length} chars")
                return
            }
            FileLogger.d(TAG, "WebSocket message received: ${text.take(100)}")
            scope.launch {
                _messages.emit(text)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (bytes.size > MAX_MESSAGE_BYTES) {
                FileLogger.w(TAG, "Dropping oversized binary message: ${bytes.size} bytes")
                return
            }
            FileLogger.d(TAG, "WebSocket binary message received")
            scope.launch {
                _messages.emit(bytes.utf8())
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            FileLogger.d(TAG, "WebSocket closed: $code - $reason")
            _connectionState.value = ConnectionState.Disconnected
            reconnectHandler.onDisconnected()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            FileLogger.e(TAG, "WebSocket failure", t)
            _connectionState.value = ConnectionState.Disconnected
            reconnectHandler.onDisconnected()
        }
    }

    /**
     * Connect to the WebSocket server.
     */
    fun connect(url: String) {
        if (_connectionState.value == ConnectionState.Connected) {
            FileLogger.w(TAG, "Already connected, ignoring connect call")
            return
        }

        reconnectHandler.trackConnection(url)
        _connectionState.value = ConnectionState.Connecting
        FileLogger.d(TAG, "Connecting to $url")

        client = OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // No read timeout for WebSocket
            .minWebSocketMessageToCompress(8192) // Compress messages > 8KB
            .build()

        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = client?.newWebSocket(request, listener)
    }

    /**
     * Send a text message over WebSocket.
     */
    fun send(message: String): Boolean {
        val ws = webSocket
        if (ws == null || _connectionState.value != ConnectionState.Connected) {
            FileLogger.w(TAG, "Cannot send: not connected")
            return false
        }
        return ws.send(message)
    }

    /**
     * Send a protocol message.
     */
    fun send(message: WsMessage): Boolean {
        return send(message.toJson())
    }

    /**
     * Disconnect from the WebSocket server.
     */
    fun disconnect() {
        FileLogger.d(TAG, "Disconnecting")
        reconnectHandler.cancel()
        webSocket?.close(NORMAL_CLOSE_CODE, "Client disconnect")
        webSocket = null
        client = null
        _connectionState.value = ConnectionState.Disconnected
    }

    /**
     * Disconnect and release all resources including coroutine scope.
     */
    fun destroy() {
        disconnect()
        scope.cancel()
        FileLogger.d(TAG, "WebSocketClient destroyed")
    }

    /**
     * Check if currently connected.
     */
    fun isConnected(): Boolean = _connectionState.value == ConnectionState.Connected

    companion object {
        private const val TAG = "WebSocketClient"
        private const val NORMAL_CLOSE_CODE = 1000
        private const val MAX_MESSAGE_CHARS = 1 * 1024 * 1024 // 1MB max text message
        private const val MAX_MESSAGE_BYTES = 1 * 1024 * 1024 // 1MB max binary message
    }
}

/**
 * Connection state sealed class.
 */
sealed class ConnectionState {
    object Connected : ConnectionState()
    object Connecting : ConnectionState()
    object Disconnected : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}
