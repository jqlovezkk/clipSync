package com.clipsync.app.ime

import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.clipsync.app.core.ClipboardContentType
import com.clipsync.app.core.ClipboardMonitor
import com.clipsync.app.core.EncryptionHelper
import com.clipsync.app.core.FileLogger
import com.clipsync.app.core.SettingsManager
import com.clipsync.app.core.SyncEngine
import com.clipsync.app.data.AppDatabase
import com.clipsync.app.data.entities.ClipboardEntity
import com.clipsync.app.data.entities.DeviceEntity
import com.clipsync.app.network.ConnectionState
import com.clipsync.app.network.HeartbeatManager
import com.clipsync.app.network.MessageType
import com.clipsync.app.network.WebSocketClient
import com.clipsync.app.network.WsMessage
import com.clipsync.app.network.WsMessageBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

class ClipSyncInputMethodService : InputMethodService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var settingsManager: SettingsManager
    private lateinit var database: AppDatabase
    private lateinit var clipboardMonitor: ClipboardMonitor
    private lateinit var syncEngine: SyncEngine
    private lateinit var webSocketClient: WebSocketClient
    private lateinit var heartbeatManager: HeartbeatManager

    private var keyboardMode = KeyboardMode.Letters
    private var isUppercase = false
    private var isClipboardMonitoring = false
    private var currentConnectionState: ConnectionState = ConnectionState.Disconnected
    private var activePanel = ImePanel.Clipboard
    private var isPanelVisible = false

    private var rootView: LinearLayout? = null
    private var statusTextView: TextView? = null
    private var historyRow: LinearLayout? = null
    private var panelSection: LinearLayout? = null
    private var panelHost: LinearLayout? = null
    private var keyboardSection: LinearLayout? = null
    private var keyboardRowsContainer: LinearLayout? = null
    private var openPanelButton: Button? = null
    private var keyboardTabButton: Button? = null
    private var clipboardTabButton: Button? = null
    private var devicesTabButton: Button? = null

    override fun onCreate() {
        super.onCreate()
        settingsManager = SettingsManager(this)
        database = AppDatabase.getInstance(this)
        clipboardMonitor = ClipboardMonitor(this)
        webSocketClient = WebSocketClient()
        heartbeatManager = HeartbeatManager(webSocketClient)
        syncEngine = SyncEngine(
            webSocketClient = webSocketClient,
            clipboardMonitor = clipboardMonitor,
            settingsManager = settingsManager,
            database = database
        )

        observeClipboardFlows()
        observeConnectionState()
        observeMessages()
    }

    override fun onCreateInputView(): View {
        if (rootView == null) {
            rootView = buildInputView()
        }
        updateStatus()
        refreshHistoryStrip()
        refreshPanels()
        renderKeyboard()
        updateModeVisibility()
        return rootView!!
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        FileLogger.d(TAG, "IME input view started, restarting=$restarting")
        ensureConnected()
        startClipboardMonitoring()
        refreshHistoryStrip()
        refreshPanels()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        FileLogger.d(TAG, "IME input view finished, finishingInput=$finishingInput")
        stopClipboardMonitoring()
    }

    override fun onDestroy() {
        stopClipboardMonitoring()
        heartbeatManager.destroy()
        syncEngine.destroy()
        webSocketClient.destroy()
        scope.cancel()
        super.onDestroy()
    }

    private fun buildInputView(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F2F4F8"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(8), dp(8), dp(8), dp(10))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(6))
        }

        val titleView = TextView(this).apply {
            text = getString(com.clipsync.app.R.string.ime_service_name)
            setTextColor(Color.parseColor("#111827"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(titleView)

        statusTextView = TextView(this).apply {
            setTextColor(Color.parseColor("#2563EB"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        header.addView(statusTextView)
        root.addView(header)

        val historyScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, dp(8))
        }
        historyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        historyScroll.addView(historyRow)
        root.addView(historyScroll)

        val quickActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(8))
        }
        openPanelButton = Button(this).apply {
            text = getString(com.clipsync.app.R.string.ime_panel_open)
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setBackgroundColor(Color.parseColor("#DDE5F0"))
            setTextColor(Color.parseColor("#111827"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(40)
            )
            setOnClickListener {
                isPanelVisible = true
                updateModeVisibility()
                refreshPanels()
            }
        }
        quickActions.addView(openPanelButton)
        root.addView(quickActions)

        panelSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(8))
        }
        keyboardTabButton = createModeButton(
            getString(com.clipsync.app.R.string.ime_panel_keyboard)
        ) {
            isPanelVisible = false
            updateModeVisibility()
        }
        clipboardTabButton = createModeButton(
            getString(com.clipsync.app.R.string.ime_panel_clipboard)
        ) {
            isPanelVisible = true
            activePanel = ImePanel.Clipboard
            updateModeVisibility()
            refreshPanels()
        }
        devicesTabButton = createModeButton(
            getString(com.clipsync.app.R.string.ime_panel_devices)
        ) {
            isPanelVisible = true
            activePanel = ImePanel.Devices
            updateModeVisibility()
            refreshPanels()
        }
        tabRow.addView(keyboardTabButton)
        tabRow.addView(clipboardTabButton)
        tabRow.addView(devicesTabButton)
        panelSection?.addView(tabRow)

        val panelScroll = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(248)
            )
            setBackgroundColor(Color.parseColor("#E9EEF5"))
        }
        panelHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        panelScroll.addView(panelHost)
        panelSection?.addView(panelScroll)
        root.addView(panelSection)

        keyboardSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        keyboardRowsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        keyboardSection?.addView(keyboardRowsContainer)
        root.addView(keyboardSection)

        renderPanelSelection()
        return root
    }

    private fun createModeButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                leftMargin = dp(3)
                rightMargin = dp(3)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun updateModeVisibility() {
        panelSection?.visibility = if (isPanelVisible) View.VISIBLE else View.GONE
        keyboardSection?.visibility = if (isPanelVisible) View.GONE else View.VISIBLE
        renderPanelSelection()
    }

    private fun renderKeyboard() {
        val container = keyboardRowsContainer ?: return
        container.removeAllViews()
        buildRowsForCurrentMode().forEach { row ->
            container.addView(createRowView(row))
        }
    }

    private fun buildRowsForCurrentMode(): List<List<KeySpec>> {
        return when (keyboardMode) {
            KeyboardMode.Letters -> {
                val row1 = "q w e r t y u i o p".split(" ").map { letterKey(it) }
                val row2 = "a s d f g h j k l".split(" ").map { letterKey(it) }
                val row3 = listOf(
                    KeySpec.Special(SpecialKey.Shift, getString(com.clipsync.app.R.string.ime_action_shift), 1.35f)
                ) + "z x c v b n m".split(" ").map { letterKey(it) } + listOf(
                    KeySpec.Special(SpecialKey.Delete, getString(com.clipsync.app.R.string.ime_action_delete), 1.35f)
                )
                val row4 = listOf(
                    KeySpec.Special(SpecialKey.ModeNumbers, getString(com.clipsync.app.R.string.ime_action_numbers), 1.2f),
                    KeySpec.Special(SpecialKey.NextKeyboard, getString(com.clipsync.app.R.string.ime_action_next_keyboard), 1f),
                    KeySpec.Text(",", ",", 0.9f),
                    KeySpec.Special(SpecialKey.Space, getString(com.clipsync.app.R.string.ime_action_space), 3.2f),
                    KeySpec.Text(".", ".", 0.9f),
                    KeySpec.Special(SpecialKey.Enter, getString(com.clipsync.app.R.string.ime_action_enter), 1.3f)
                )
                listOf(row1, row2, row3, row4)
            }
            KeyboardMode.Numbers -> {
                val row1 = "1 2 3 4 5 6 7 8 9 0".split(" ").map { KeySpec.Text(it, it) }
                val row2 = "@ # ¥ _ & - + ( ) /".split(" ").map { KeySpec.Text(it, it) }
                val row3 = listOf(
                    KeySpec.Special(SpecialKey.ModeSymbols, getString(com.clipsync.app.R.string.ime_action_symbols), 1.25f)
                ) + "* \" ' : ; ! ?".split(" ").map { KeySpec.Text(it, it) } + listOf(
                    KeySpec.Special(SpecialKey.Delete, getString(com.clipsync.app.R.string.ime_action_delete), 1.35f)
                )
                val row4 = listOf(
                    KeySpec.Special(SpecialKey.ModeLetters, getString(com.clipsync.app.R.string.ime_action_letters), 1.2f),
                    KeySpec.Special(SpecialKey.NextKeyboard, getString(com.clipsync.app.R.string.ime_action_next_keyboard), 1f),
                    KeySpec.Text(",", ",", 0.9f),
                    KeySpec.Special(SpecialKey.Space, getString(com.clipsync.app.R.string.ime_action_space), 3.2f),
                    KeySpec.Text(".", ".", 0.9f),
                    KeySpec.Special(SpecialKey.Enter, getString(com.clipsync.app.R.string.ime_action_enter), 1.3f)
                )
                listOf(row1, row2, row3, row4)
            }
            KeyboardMode.Symbols -> {
                val row1 = "[ ] { } # % ^ * + =".split(" ").map { KeySpec.Text(it, it) }
                val row2 = "_ \\ | ~ < > € £ ¥ •".split(" ").map { KeySpec.Text(it, it) }
                val row3 = listOf(
                    KeySpec.Special(SpecialKey.ModeNumbers, getString(com.clipsync.app.R.string.ime_action_numbers), 1.25f)
                ) + ". , ? ! ' \" : ; /".split(" ").map { KeySpec.Text(it, it) } + listOf(
                    KeySpec.Special(SpecialKey.Delete, getString(com.clipsync.app.R.string.ime_action_delete), 1.35f)
                )
                val row4 = listOf(
                    KeySpec.Special(SpecialKey.ModeLetters, getString(com.clipsync.app.R.string.ime_action_letters), 1.2f),
                    KeySpec.Special(SpecialKey.NextKeyboard, getString(com.clipsync.app.R.string.ime_action_next_keyboard), 1f),
                    KeySpec.Text("-", "-", 0.9f),
                    KeySpec.Special(SpecialKey.Space, getString(com.clipsync.app.R.string.ime_action_space), 3.2f),
                    KeySpec.Text("@", "@", 0.9f),
                    KeySpec.Special(SpecialKey.Enter, getString(com.clipsync.app.R.string.ime_action_enter), 1.3f)
                )
                listOf(row1, row2, row3, row4)
            }
        }
    }

    private fun createRowView(keys: List<KeySpec>): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(6))
            keys.forEach { key ->
                addView(createKeyButton(key))
            }
        }
    }

    private fun createKeyButton(key: KeySpec): View {
        return Button(this).apply {
            text = key.label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.parseColor("#111827"))
            setBackgroundColor(
                when (key) {
                    is KeySpec.Special -> if (key.action == SpecialKey.Space) Color.parseColor("#FFFFFF") else Color.parseColor("#DDE5F0")
                    is KeySpec.Text -> Color.parseColor("#FFFFFF")
                }
            )
            layoutParams = LinearLayout.LayoutParams(0, dp(48), key.weight).apply {
                leftMargin = dp(3)
                rightMargin = dp(3)
            }
            setOnClickListener { onKeyPressed(key) }
        }
    }

    private fun onKeyPressed(key: KeySpec) {
        when (key) {
            is KeySpec.Text -> {
                currentInputConnection?.commitText(key.output, 1)
                if (keyboardMode == KeyboardMode.Letters && isUppercase) {
                    isUppercase = false
                    renderKeyboard()
                }
            }
            is KeySpec.Special -> handleSpecialKey(key.action)
        }
    }

    private fun handleSpecialKey(action: SpecialKey) {
        when (action) {
            SpecialKey.Shift -> {
                isUppercase = !isUppercase
                renderKeyboard()
            }
            SpecialKey.Delete -> currentInputConnection?.deleteSurroundingText(1, 0)
            SpecialKey.Space -> currentInputConnection?.commitText(" ", 1)
            SpecialKey.Enter -> {
                val handled = currentInputConnection?.performEditorAction(EditorInfo.IME_ACTION_DONE) ?: false
                if (!handled) {
                    currentInputConnection?.commitText("\n", 1)
                }
            }
            SpecialKey.ModeLetters -> {
                keyboardMode = KeyboardMode.Letters
                renderKeyboard()
            }
            SpecialKey.ModeNumbers -> {
                keyboardMode = KeyboardMode.Numbers
                renderKeyboard()
            }
            SpecialKey.ModeSymbols -> {
                keyboardMode = KeyboardMode.Symbols
                renderKeyboard()
            }
            SpecialKey.NextKeyboard -> switchToNextKeyboard()
        }
    }

    private fun switchToNextKeyboard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            switchToNextInputMethod(false)
        } else {
            val manager = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            manager?.showInputMethodPicker()
        }
    }

    private fun observeClipboardFlows() {
        scope.launch {
            clipboardMonitor.currentText.collectLatest { text ->
                if (text != null && isClipboardMonitoring) {
                    FileLogger.d(TAG, "IME observed clipboard text change, preparing sync")
                    syncEngine.pushToServer(text)
                    refreshHistoryStrip()
                    refreshPanels()
                }
            }
        }

        scope.launch {
            clipboardMonitor.currentContent.collectLatest { content ->
                if (content != null && isClipboardMonitoring && content.contentType == ClipboardContentType.IMAGE) {
                    FileLogger.d(TAG, "IME observed clipboard image change, preparing sync")
                    content.imageBase64?.let { base64 ->
                        syncEngine.pushImageToServer(
                            imageBase64 = base64,
                            format = content.imageFormat,
                            size = content.sizeBytes,
                            checksum = content.checksum
                        )
                    }
                    refreshHistoryStrip()
                    refreshPanels()
                }
            }
        }
    }

    private fun observeConnectionState() {
        scope.launch {
            webSocketClient.connectionState.collectLatest { state ->
                currentConnectionState = state
                updateStatus()
                when (state) {
                    is ConnectionState.Connected -> {
                        FileLogger.d(TAG, "IME WebSocket connected, sending auth")
                        sendAuth()
                    }
                    ConnectionState.Connecting -> FileLogger.d(TAG, "IME WebSocket connecting")
                    ConnectionState.Disconnected -> FileLogger.d(TAG, "IME WebSocket disconnected")
                    is ConnectionState.Error -> FileLogger.e(TAG, "IME WebSocket error: ${state.message}")
                }
            }
        }
    }

    private fun observeMessages() {
        scope.launch {
            webSocketClient.messages.collectLatest { message ->
                handleWebSocketMessage(message)
            }
        }
    }

    private fun handleWebSocketMessage(json: String) {
        val wsMessage = WsMessage.fromJson(json)
        if (wsMessage == null) {
            FileLogger.w(TAG, "IME failed to parse WebSocket message: ${json.take(200)}")
            return
        }

        when (wsMessage.type) {
            MessageType.AuthResponse -> handleAuthResponse(wsMessage.payload)
            MessageType.HeartbeatAck -> Unit
            MessageType.ClipboardSync -> {
                FileLogger.d(TAG, "IME received clipboard_sync")
                syncEngine.handleIncomingSync(wsMessage.payload)
                refreshHistoryStrip()
                refreshPanels()
            }
            MessageType.ClipboardHistory -> {
                syncEngine.handleHistoryResponse(wsMessage.payload)
                refreshHistoryStrip()
                refreshPanels()
            }
            MessageType.DeviceListResponse -> {
                handleDeviceListResponse(wsMessage.payload)
                refreshPanels()
            }
            MessageType.Error -> {
                val message = wsMessage.payload["message"]?.jsonPrimitive?.content ?: "Unknown error"
                FileLogger.e(TAG, "IME server error: $message")
            }
            MessageType.Ping -> webSocketClient.send(WsMessageBuilder.pong())
            else -> Unit
        }
    }

    private fun handleAuthResponse(payload: JsonObject) {
        val success = payload["success"]?.jsonPrimitive?.booleanOrNull ?: false
        val deviceId = payload["device_id"]?.jsonPrimitive?.content
        if (!success) {
            FileLogger.e(TAG, "IME auth failed")
            return
        }

        FileLogger.d(TAG, "IME auth successful, deviceId=$deviceId")
        deviceId?.let {
            scope.launch(Dispatchers.IO) {
                settingsManager.setDeviceId(it)
            }
        }
        heartbeatManager.start()
        syncEngine.requestHistory()
        webSocketClient.send(WsMessageBuilder.deviceList())
        refreshHistoryStrip()
        refreshPanels()
    }

    private fun handleDeviceListResponse(payload: JsonObject) {
        scope.launch(Dispatchers.IO) {
            val devices = payload["devices"]?.jsonArray?.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                DeviceEntity(
                    deviceId = obj["device_id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                    deviceName = obj["device_name"]?.jsonPrimitive?.content ?: "Unknown",
                    platform = obj["platform"]?.jsonPrimitive?.content ?: "unknown",
                    lastSeen = obj["last_seen"]?.jsonPrimitive?.content?.toLongOrNull() ?: System.currentTimeMillis(),
                    isOnline = obj["is_online"]?.jsonPrimitive?.booleanOrNull ?: false
                )
            }.orEmpty()
            database.deviceDao().insertAll(devices)
        }
    }

    private fun ensureConnected() {
        scope.launch(Dispatchers.IO) {
            val token = settingsManager.getToken()
            if (token.isEmpty()) {
                FileLogger.w(TAG, "IME cannot connect because token is empty")
                return@launch
            }
            if (!webSocketClient.isConnected()) {
                val wsUrl = settingsManager.getServerUrl()
                FileLogger.d(TAG, "IME connecting to $wsUrl")
                webSocketClient.connect(wsUrl)
            } else {
                sendAuth()
            }
        }
    }

    private fun sendAuth() {
        scope.launch(Dispatchers.IO) {
            val token = settingsManager.getToken()
            val deviceName = settingsManager.getDeviceName()
            if (token.isNotEmpty()) {
                webSocketClient.send(WsMessageBuilder.auth(token, deviceName))
            }
        }
    }

    private fun startClipboardMonitoring() {
        if (isClipboardMonitoring) return
        isClipboardMonitoring = true
        clipboardMonitor.start()
        clipboardMonitor.refreshNow()
    }

    private fun stopClipboardMonitoring() {
        if (!isClipboardMonitoring) return
        isClipboardMonitoring = false
        clipboardMonitor.stop()
    }

    private fun refreshHistoryStrip() {
        scope.launch(Dispatchers.IO) {
            val recentItems = database.clipboardDao().getRecent(6)
            launch(Dispatchers.Main) {
                renderHistory(recentItems)
            }
        }
    }

    private fun refreshPanels() {
        scope.launch(Dispatchers.IO) {
            val historyDeferred = async { database.clipboardDao().getRecent(20) }
            val devicesDeferred = async { database.deviceDao().getOnlineDevices() }
            val history = historyDeferred.await()
            val devices = devicesDeferred.await()
            launch(Dispatchers.Main) {
                renderPanelSelection()
                when (activePanel) {
                    ImePanel.Clipboard -> renderClipboardPanel(history)
                    ImePanel.Devices -> renderDevicesPanel(devices, history)
                }
            }
        }
    }

    private fun renderHistory(items: List<ClipboardEntity>) {
        val container = historyRow ?: return
        container.removeAllViews()

        if (items.isEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(com.clipsync.app.R.string.ime_history_empty)
                setTextColor(Color.parseColor("#6B7280"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(10), dp(8), dp(10), dp(8))
            })
            return
        }

        items.forEach { item ->
            val label = when (item.contentType) {
                "image" -> "[Image] ${item.sourceDeviceName.ifBlank { "ClipSync" }}"
                else -> item.content.replace("\n", " ").take(24).ifBlank { " " }
            }

            container.addView(TextView(this).apply {
                text = label
                setTextColor(Color.parseColor("#1F2937"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setBackgroundColor(Color.parseColor("#E5EEF9"))
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    rightMargin = dp(6)
                }
                setOnClickListener { onHistoryItemSelected(item) }
            })
        }
    }

    private fun renderClipboardPanel(items: List<ClipboardEntity>) {
        val container = panelHost ?: return
        container.removeAllViews()

        if (items.isEmpty()) {
            container.addView(createEmptyPanelText(getString(com.clipsync.app.R.string.ime_history_empty)))
            return
        }

        items.forEach { item ->
            val title = when (item.contentType) {
                "image" -> "[Image] ${item.sourceDeviceName.ifBlank { "ClipSync" }}"
                else -> item.content.replace("\n", " ").take(60).ifBlank { " " }
            }
            val subtitle = item.sourceDeviceName.ifBlank {
                getString(com.clipsync.app.R.string.ime_panel_recent)
            }
            container.addView(createPanelCard(title, subtitle) {
                onHistoryItemSelected(item)
            })
        }
    }

    private fun renderDevicesPanel(devices: List<DeviceEntity>, history: List<ClipboardEntity>) {
        val container = panelHost ?: return
        container.removeAllViews()

        if (devices.isEmpty()) {
            container.addView(createEmptyPanelText(getString(com.clipsync.app.R.string.ime_devices_empty)))
            return
        }

        devices.forEach { device ->
            val latestItem = history.firstOrNull { it.sourceDeviceId == device.deviceId }
            val subtitle = buildString {
                append(if (device.isOnline) "Online" else "Offline")
                append(" · ")
                append(
                    latestItem?.let {
                        if (it.contentType == "image") "[Image] ${it.sourceDeviceName.ifBlank { device.deviceName }}"
                        else it.content.replace("\n", " ").take(50)
                    } ?: getString(com.clipsync.app.R.string.ime_device_content_empty)
                )
            }
            container.addView(createPanelCard(device.deviceName, subtitle) {
                latestItem?.let { onHistoryItemSelected(it) }
            })
        }
    }

    private fun createPanelCard(title: String, subtitle: String, onClick: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
            addView(TextView(this@ClipSyncInputMethodService).apply {
                text = title
                setTextColor(Color.parseColor("#111827"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@ClipSyncInputMethodService).apply {
                text = subtitle
                setTextColor(Color.parseColor("#6B7280"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(4), 0, 0)
            })
            setOnClickListener { onClick() }
        }
    }

    private fun createEmptyPanelText(message: String): View {
        return TextView(this).apply {
            text = message
            setTextColor(Color.parseColor("#6B7280"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(10), dp(12), dp(10), dp(12))
        }
    }

    private fun onHistoryItemSelected(item: ClipboardEntity) {
        when (item.contentType) {
            "image" -> {
                clipboardMonitor.markNextReadFromInAppCopy()
                clipboardMonitor.setImageToClipboard(item.content)
                val imageBytes = runCatching {
                    android.util.Base64.decode(item.content, android.util.Base64.DEFAULT)
                }.getOrNull()
                if (imageBytes != null) {
                    syncEngine.pushImageToServer(
                        imageBase64 = item.content,
                        format = "image/png",
                        size = imageBytes.size,
                        checksum = EncryptionHelper.computeChecksum(imageBytes),
                        force = true
                    )
                }
            }
            else -> {
                currentInputConnection?.commitText(item.content, 1)
                clipboardMonitor.markNextReadFromInAppCopy()
                clipboardMonitor.setTextToClipboard(item.content)
                syncEngine.pushToServer(item.content, force = true)
            }
        }
        refreshPanels()
    }

    private fun updateStatus() {
        val target = statusTextView ?: return
        target.text = when (currentConnectionState) {
            is ConnectionState.Connected -> getString(com.clipsync.app.R.string.ime_status_connected)
            ConnectionState.Connecting -> getString(com.clipsync.app.R.string.ime_status_connecting)
            ConnectionState.Disconnected -> getString(com.clipsync.app.R.string.ime_status_disconnected)
            is ConnectionState.Error -> getString(com.clipsync.app.R.string.ime_status_disconnected)
        }
    }

    private fun renderPanelSelection() {
        val selectedColor = Color.parseColor("#2563EB")
        val normalColor = Color.parseColor("#DDE5F0")
        val selectedText = Color.parseColor("#FFFFFF")
        val normalText = Color.parseColor("#111827")

        keyboardTabButton?.apply {
            setBackgroundColor(if (!isPanelVisible) selectedColor else normalColor)
            setTextColor(if (!isPanelVisible) selectedText else normalText)
        }
        clipboardTabButton?.apply {
            val selected = isPanelVisible && activePanel == ImePanel.Clipboard
            setBackgroundColor(if (selected) selectedColor else normalColor)
            setTextColor(if (selected) selectedText else normalText)
        }
        devicesTabButton?.apply {
            val selected = isPanelVisible && activePanel == ImePanel.Devices
            setBackgroundColor(if (selected) selectedColor else normalColor)
            setTextColor(if (selected) selectedText else normalText)
        }
    }

    private fun letterKey(value: String): KeySpec.Text {
        val rendered = if (isUppercase) value.uppercase() else value.lowercase()
        return KeySpec.Text(rendered, rendered)
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()

    companion object {
        private const val TAG = "ClipSyncIME"
    }
}

private sealed class KeySpec(val label: String, val weight: Float) {
    class Text(label: String, val output: String, weight: Float = 1f) : KeySpec(label, weight)
    class Special(val action: SpecialKey, label: String, weight: Float = 1f) : KeySpec(label, weight)
}

private enum class KeyboardMode {
    Letters,
    Numbers,
    Symbols
}

private enum class ImePanel {
    Clipboard,
    Devices
}

private enum class SpecialKey {
    Shift,
    Delete,
    Space,
    Enter,
    ModeLetters,
    ModeNumbers,
    ModeSymbols,
    NextKeyboard
}
