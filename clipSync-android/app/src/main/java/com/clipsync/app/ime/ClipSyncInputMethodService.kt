package com.clipsync.app.ime

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.clipsync.app.R
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
    private var isChineseMode = false
    private var currentConnectionState: ConnectionState = ConnectionState.Disconnected
    private var activePanel = ImePanel.Clipboard
    private var isPanelVisible = false
    private var currentEditorInfo: EditorInfo? = null

    private var recentHistory: List<ClipboardEntity> = emptyList()
    private var onlineDevices: List<DeviceEntity> = emptyList()
    private var composingPinyin = ""
    private var composingCandidates: List<String> = emptyList()

    private val deleteRepeatHandler = Handler(Looper.getMainLooper())
    private val deleteRepeatRunnable = object : Runnable {
        override fun run() {
            deleteSelectedOrPreviousText()
            deleteRepeatHandler.postDelayed(this, DELETE_REPEAT_INTERVAL_MS)
        }
    }

    private var rootView: LinearLayout? = null
    private var statusBadgeView: TextView? = null
    private var composingBar: LinearLayout? = null
    private var composingTextView: TextView? = null
    private var candidateRow: LinearLayout? = null
    private var candidateScrollView: HorizontalScrollView? = null
    private var panelToggleRow: LinearLayout? = null
    private var clipboardTabButton: Button? = null
    private var devicesTabButton: Button? = null
    private var panelSection: LinearLayout? = null
    private var panelHost: LinearLayout? = null
    private var keyboardSection: LinearLayout? = null
    private var keyboardRowsContainer: LinearLayout? = null

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

        observeLocalData()
        observeConnectionState()
        observeMessages()
    }

    override fun onCreateInputView(): View {
        if (rootView == null) {
            rootView = buildInputView()
        }
        updateStatusBadge()
        refreshUiFromCache()
        renderKeyboard()
        updateModeVisibility()
        return rootView!!
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        FileLogger.d(TAG, "IME input view started, restarting=$restarting")
        currentEditorInfo = info
        syncKeyboardStateForEditor(info)
        ensureConnected()
        refreshUiFromCache()
        updateStatusBadge()
        renderKeyboard()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        FileLogger.d(TAG, "IME input view finished, finishingInput=$finishingInput")
        stopDeleteRepeat()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd
        )
        if (keyboardMode == KeyboardMode.Letters) {
            syncShiftStateForCursor()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isChineseMode && keyboardMode == KeyboardMode.Letters && composingPinyin.isNotEmpty()) {
            val index = when (keyCode) {
                KeyEvent.KEYCODE_1 -> 0
                KeyEvent.KEYCODE_2 -> 1
                KeyEvent.KEYCODE_3 -> 2
                KeyEvent.KEYCODE_4 -> 3
                KeyEvent.KEYCODE_5 -> 4
                KeyEvent.KEYCODE_6 -> 5
                KeyEvent.KEYCODE_7 -> 6
                KeyEvent.KEYCODE_8 -> 7
                KeyEvent.KEYCODE_9 -> 8
                else -> -1
            }
            if (index >= 0) {
                val candidate = composingCandidates.getOrNull(index)
                if (candidate != null) {
                    commitComposingText(selected = candidate)
                } else if (index == composingCandidates.size) {
                    commitComposingText(selected = composingPinyin)
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        stopDeleteRepeat()
        heartbeatManager.destroy()
        syncEngine.destroy()
        webSocketClient.destroy()
        scope.cancel()
        super.onDestroy()
    }

    private fun buildInputView(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#EAF0F7"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(8), dp(6), dp(8), dp(8))
        }

        root.addView(createHeader())
        root.addView(createComposingBar())
        root.addView(createPanelToggleRow())
        root.addView(createPanelSection())

        keyboardSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createContainerBackground(
                fillColor = Color.parseColor("#F8FBFF"),
                strokeColor = Color.parseColor("#D7E1F0"),
                radiusDp = 18
            )
            setPadding(dp(6), dp(8), dp(6), dp(4))
        }
        keyboardRowsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        keyboardSection?.addView(keyboardRowsContainer)
        root.addView(keyboardSection)

        renderPanelSelection()
        return root
    }

    private fun createHeader(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(8))

            addView(TextView(this@ClipSyncInputMethodService).apply {
                text = getString(R.string.ime_service_name)
                setTextColor(Color.parseColor("#0F172A"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })

            statusBadgeView = TextView(this@ClipSyncInputMethodService).apply {
                setPadding(dp(10), dp(5), dp(10), dp(5))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTypeface(typeface, Typeface.BOLD)
            }
            addView(statusBadgeView)
        }
    }

    private fun createComposingBar(): View {
        composingBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = createContainerBackground(
                fillColor = Color.parseColor("#FDFEFF"),
                strokeColor = Color.parseColor("#CAD7EA"),
                radiusDp = 18
            )
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }

        composingTextView = TextView(this).apply {
            setTextColor(Color.parseColor("#475569"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
        }
        composingBar?.addView(composingTextView)

        candidateScrollView = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(8), 0, 0)
        }
        candidateRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        candidateScrollView?.addView(candidateRow)
        composingBar?.addView(candidateScrollView)
        return composingBar!!
    }

    private fun createPanelToggleRow(): View {
        panelToggleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(8))
        }

        clipboardTabButton = createPanelToggleButton(
            label = getString(R.string.ime_panel_clipboard),
            iconRes = android.R.drawable.ic_menu_agenda
        ) {
            togglePanel(ImePanel.Clipboard)
        }
        devicesTabButton = createPanelToggleButton(
            label = getString(R.string.ime_panel_devices),
            iconRes = android.R.drawable.ic_menu_share
        ) {
            togglePanel(ImePanel.Devices)
        }

        panelToggleRow?.addView(clipboardTabButton)
        panelToggleRow?.addView(devicesTabButton)
        return panelToggleRow!!
    }

    private fun createPanelSection(): View {
        panelSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = createContainerBackground(
                fillColor = Color.parseColor("#F7FAFE"),
                strokeColor = Color.parseColor("#D6E0EE"),
                radiusDp = 18
            )
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }

        panelSection?.addView(TextView(this).apply {
            text = getString(R.string.ime_panel_hint)
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(4), dp(0), dp(4), dp(6))
        })

        val panelScroll = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(210)
            )
        }
        panelHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        panelScroll.addView(panelHost)
        panelSection?.addView(panelScroll)

        return panelSection!!
    }

    private fun createPanelToggleButton(
        label: String,
        iconRes: Int,
        onClick: () -> Unit
    ): Button {
        return Button(this).apply {
            text = "  $label"
            isAllCaps = false
            minHeight = 0
            minimumHeight = 0
            minimumWidth = 0
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
            compoundDrawablePadding = dp(4)
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply {
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

    private fun togglePanel(targetPanel: ImePanel) {
        if (isPanelVisible && activePanel == targetPanel) {
            isPanelVisible = false
            updateModeVisibility()
            return
        }
        activePanel = targetPanel
        isPanelVisible = true
        updateModeVisibility()
        renderActivePanel()
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
                    KeySpec.Special(SpecialKey.Shift, getString(R.string.ime_action_shift), 1.35f)
                ) + "z x c v b n m".split(" ").map { letterKey(it) } + listOf(
                    KeySpec.Special(SpecialKey.Delete, getString(R.string.ime_action_delete), 1.35f)
                )
                val row4 = listOf(
                    KeySpec.Special(SpecialKey.SystemKeyboard, getString(R.string.ime_action_switch_keyboard), 1.05f),
                    KeySpec.Special(SpecialKey.ToggleLanguage, resolveLanguageToggleLabel(), 1f),
                    KeySpec.Special(SpecialKey.ModeNumbers, getString(R.string.ime_action_numbers), 1f),
                    KeySpec.Text(resolveCommaLabel(), resolveCommaOutput(), 0.8f),
                    KeySpec.Special(SpecialKey.Space, resolveSpaceLabel(), 3.6f),
                    KeySpec.Text(resolvePeriodLabel(), resolvePeriodOutput(), 0.8f),
                    KeySpec.Special(SpecialKey.Enter, resolveEnterLabel(), 1.2f)
                )
                listOf(row1, row2, row3, row4)
            }

            KeyboardMode.Numbers -> {
                val row1 = "1 2 3 4 5 6 7 8 9 0".split(" ").map { KeySpec.Text(it, it) }
                val row2 = "@ # ¥ _ & - + ( ) /".split(" ").map { KeySpec.Text(it, it) }
                val row3 = listOf(
                    KeySpec.Special(SpecialKey.ModeSymbols, getString(R.string.ime_action_symbols), 1.2f)
                ) + "* \" ' : ; ! ?".split(" ").map { KeySpec.Text(it, it) } + listOf(
                    KeySpec.Special(SpecialKey.Delete, getString(R.string.ime_action_delete), 1.35f)
                )
                val row4 = listOf(
                    KeySpec.Special(SpecialKey.SystemKeyboard, getString(R.string.ime_action_switch_keyboard), 1.05f),
                    KeySpec.Special(SpecialKey.ToggleLanguage, resolveLanguageToggleLabel(), 1f),
                    KeySpec.Special(SpecialKey.ModeLetters, getString(R.string.ime_action_letters), 1f),
                    KeySpec.Text(resolveCommaLabel(), resolveCommaOutput(), 0.8f),
                    KeySpec.Special(SpecialKey.Space, resolveSpaceLabel(), 3.6f),
                    KeySpec.Text(resolvePeriodLabel(), resolvePeriodOutput(), 0.8f),
                    KeySpec.Special(SpecialKey.Enter, resolveEnterLabel(), 1.2f)
                )
                listOf(row1, row2, row3, row4)
            }

            KeyboardMode.Symbols -> {
                val row1 = "[ ] { } # % ^ * + =".split(" ").map { KeySpec.Text(it, it) }
                val row2 = "_ \\ | ~ < > € £ ¥ •".split(" ").map { KeySpec.Text(it, it) }
                val row3 = listOf(
                    KeySpec.Special(SpecialKey.ModeNumbers, getString(R.string.ime_action_numbers), 1.2f)
                ) + ". , ? ! ' \" : ; /".split(" ").map { KeySpec.Text(it, it) } + listOf(
                    KeySpec.Special(SpecialKey.Delete, getString(R.string.ime_action_delete), 1.35f)
                )
                val row4 = listOf(
                    KeySpec.Special(SpecialKey.SystemKeyboard, getString(R.string.ime_action_switch_keyboard), 1.05f),
                    KeySpec.Special(SpecialKey.ToggleLanguage, resolveLanguageToggleLabel(), 1f),
                    KeySpec.Special(SpecialKey.ModeLetters, getString(R.string.ime_action_letters), 1f),
                    KeySpec.Text("-", "-", 0.8f),
                    KeySpec.Special(SpecialKey.Space, resolveSpaceLabel(), 3.6f),
                    KeySpec.Text("@", "@", 0.8f),
                    KeySpec.Special(SpecialKey.Enter, resolveEnterLabel(), 1.2f)
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
            minHeight = 0
            minimumHeight = 0
            minimumWidth = 0
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (key.weight >= 3f) 13f else 15f)
            setTypeface(typeface, if (key is KeySpec.Text) Typeface.NORMAL else Typeface.BOLD)
            setTextColor(resolveKeyTextColor(key))
            background = createKeyBackground(key)
            layoutParams = LinearLayout.LayoutParams(0, dp(48), key.weight).apply {
                leftMargin = dp(3)
                rightMargin = dp(3)
            }
            setOnClickListener { onKeyPressed(key) }

            if (key is KeySpec.Special && key.action == SpecialKey.Delete) {
                setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> startDeleteRepeat()
                        MotionEvent.ACTION_UP,
                        MotionEvent.ACTION_CANCEL -> stopDeleteRepeat()
                    }
                    false
                }
            }

            if (key is KeySpec.Special && key.action == SpecialKey.SystemKeyboard) {
                setOnLongClickListener {
                    showInputMethodPicker()
                    true
                }
            }
        }
    }

    private fun onKeyPressed(key: KeySpec) {
        when (key) {
            is KeySpec.Text -> handleTextKey(key)
            is KeySpec.Special -> handleSpecialKey(key.action)
        }
    }

    private fun handleTextKey(key: KeySpec.Text) {
        if (isChineseMode && keyboardMode == KeyboardMode.Letters && key.output.length == 1 && key.output[0].isLetter()) {
            appendPinyin(key.output)
            return
        }

        if (isChineseMode && composingPinyin.isNotEmpty()) {
            commitComposingText()
        }

        currentInputConnection?.commitText(key.output, 1)
        if (keyboardMode == KeyboardMode.Letters && isUppercase && !isChineseMode) {
            isUppercase = false
            renderKeyboard()
        }
    }

    private fun handleSpecialKey(action: SpecialKey) {
        when (action) {
            SpecialKey.Shift -> {
                if (!isChineseMode) {
                    isUppercase = !isUppercase
                    renderKeyboard()
                }
            }

            SpecialKey.Delete -> {
                if (composingPinyin.isNotEmpty()) {
                    removeLastPinyinLetter()
                } else {
                    deleteSelectedOrPreviousText()
                }
            }

            SpecialKey.Space -> {
                if (composingPinyin.isNotEmpty()) {
                    commitComposingText(appendSpace = true)
                } else {
                    currentInputConnection?.commitText(" ", 1)
                }
            }

            SpecialKey.Enter -> {
                if (composingPinyin.isNotEmpty()) {
                    commitComposingText()
                }
                performEnterAction()
            }

            SpecialKey.ModeLetters -> {
                keyboardMode = KeyboardMode.Letters
                syncShiftStateForCursor(force = true)
                renderKeyboard()
            }

            SpecialKey.ModeNumbers -> {
                if (composingPinyin.isNotEmpty()) {
                    commitComposingText()
                }
                keyboardMode = KeyboardMode.Numbers
                renderKeyboard()
            }

            SpecialKey.ModeSymbols -> {
                if (composingPinyin.isNotEmpty()) {
                    commitComposingText()
                }
                keyboardMode = KeyboardMode.Symbols
                renderKeyboard()
            }

            SpecialKey.ToggleLanguage -> {
                if (composingPinyin.isNotEmpty()) {
                    commitComposingText()
                }
                isChineseMode = !isChineseMode
                keyboardMode = KeyboardMode.Letters
                syncShiftStateForCursor(force = true)
                renderComposingBar()
                renderKeyboard()
            }

            SpecialKey.SystemKeyboard -> switchToSystemKeyboard()
        }
    }

    private fun switchToSystemKeyboard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val switched = switchToNextInputMethod(false)
            if (!switched) {
                showInputMethodPicker()
            }
        } else {
            showInputMethodPicker()
        }
    }

    private fun showInputMethodPicker() {
        val manager = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.showInputMethodPicker()
    }

    private fun observeLocalData() {
        scope.launch {
            database.clipboardDao().getRecentFlow(20).collectLatest { items ->
                recentHistory = items
                launch(Dispatchers.Main) {
                    if (isPanelVisible) {
                        renderActivePanel()
                    }
                }
            }
        }

        scope.launch {
            database.deviceDao().getOnlineDevicesFlow().collectLatest { devices ->
                onlineDevices = devices
                launch(Dispatchers.Main) {
                    if (isPanelVisible && activePanel == ImePanel.Devices) {
                        renderDevicesPanel(onlineDevices, recentHistory)
                    }
                }
            }
        }
    }

    private fun observeConnectionState() {
        scope.launch {
            webSocketClient.connectionState.collectLatest { state ->
                currentConnectionState = state
                updateStatusBadge()
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
            MessageType.ClipboardSync -> Unit
            MessageType.ClipboardHistory -> Unit
            MessageType.DeviceListResponse -> handleDeviceListResponse(wsMessage.payload)
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
        webSocketClient.send(WsMessageBuilder.deviceList())
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

    private fun refreshUiFromCache() {
        renderComposingBar()
        if (isPanelVisible) {
            renderActivePanel()
        } else {
            renderPanelSelection()
        }
    }

    private fun renderActivePanel() {
        renderPanelSelection()
        when (activePanel) {
            ImePanel.Clipboard -> renderClipboardPanel(recentHistory)
            ImePanel.Devices -> renderDevicesPanel(onlineDevices, recentHistory)
        }
    }

    private fun renderClipboardPanel(items: List<ClipboardEntity>) {
        val container = panelHost ?: return
        container.removeAllViews()

        if (items.isEmpty()) {
            container.addView(createEmptyPanelText(getString(R.string.ime_history_empty)))
            return
        }

        items.forEach { item ->
            val title = when (item.contentType) {
                "image" -> getString(R.string.ime_item_image, item.sourceDeviceName.ifBlank { "ClipSync" })
                else -> item.content.replace("\n", " ").take(60).ifBlank { " " }
            }
            val subtitle = item.sourceDeviceName.ifBlank { getString(R.string.ime_panel_recent) }
            container.addView(createPanelCard(title, subtitle, getString(R.string.ime_action_insert)) {
                onHistoryItemSelected(item)
            })
        }
    }

    private fun renderDevicesPanel(devices: List<DeviceEntity>, history: List<ClipboardEntity>) {
        val container = panelHost ?: return
        container.removeAllViews()

        if (devices.isEmpty()) {
            container.addView(createEmptyPanelText(getString(R.string.ime_devices_empty)))
            return
        }

        devices.forEach { device ->
            val latestItem = history.firstOrNull { it.sourceDeviceId == device.deviceId }
            val subtitle = buildString {
                append(if (device.isOnline) getString(R.string.devices_online) else getString(R.string.devices_offline))
                append(" · ")
                append(
                    latestItem?.let {
                        if (it.contentType == "image") {
                            getString(R.string.ime_item_image, it.sourceDeviceName.ifBlank { device.deviceName })
                        } else {
                            it.content.replace("\n", " ").take(44)
                        }
                    } ?: getString(R.string.ime_device_content_empty)
                )
            }
            container.addView(createPanelCard(device.deviceName, subtitle, getString(R.string.ime_action_insert)) {
                latestItem?.let { onHistoryItemSelected(it) }
            })
        }
    }

    private fun createPanelCard(title: String, subtitle: String, actionLabel: String, onClick: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createContainerBackground(
                fillColor = Color.parseColor("#FFFFFF"),
                strokeColor = Color.parseColor("#D4DDEA"),
                radiusDp = 16
            )
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }

            addView(TextView(this@ClipSyncInputMethodService).apply {
                text = title
                setTextColor(Color.parseColor("#0F172A"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, Typeface.BOLD)
            })

            addView(TextView(this@ClipSyncInputMethodService).apply {
                text = subtitle
                setTextColor(Color.parseColor("#64748B"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(4), 0, dp(8))
            })

            addView(TextView(this@ClipSyncInputMethodService).apply {
                text = actionLabel
                setTextColor(Color.parseColor("#2563EB"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(typeface, Typeface.BOLD)
            })

            setOnClickListener { onClick() }
        }
    }

    private fun createEmptyPanelText(message: String): View {
        return TextView(this).apply {
            text = message
            setTextColor(Color.parseColor("#64748B"))
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

        isPanelVisible = false
        updateModeVisibility()
    }

    private fun updateStatusBadge() {
        val badge = statusBadgeView ?: return
        val (text, fillColor, textColor) = when (currentConnectionState) {
            is ConnectionState.Connected -> Triple(
                getString(R.string.ime_status_connected),
                Color.parseColor("#DBEAFE"),
                Color.parseColor("#1D4ED8")
            )

            ConnectionState.Connecting -> Triple(
                getString(R.string.ime_status_connecting),
                Color.parseColor("#FEF3C7"),
                Color.parseColor("#B45309")
            )

            ConnectionState.Disconnected,
            is ConnectionState.Error -> Triple(
                getString(R.string.ime_status_disconnected),
                Color.parseColor("#E2E8F0"),
                Color.parseColor("#475569")
            )
        }

        badge.text = text
        badge.setTextColor(textColor)
        badge.background = createContainerBackground(fillColor, fillColor, 999)
    }

    private fun renderPanelSelection() {
        val selectedFill = Color.parseColor("#DBEAFE")
        val selectedText = Color.parseColor("#1D4ED8")
        val normalFill = Color.parseColor("#EEF3F9")
        val normalText = Color.parseColor("#475569")

        clipboardTabButton?.apply {
            val selected = isPanelVisible && activePanel == ImePanel.Clipboard
            setBackgroundColor(if (selected) selectedFill else normalFill)
            setTextColor(if (selected) selectedText else normalText)
        }
        devicesTabButton?.apply {
            val selected = isPanelVisible && activePanel == ImePanel.Devices
            setBackgroundColor(if (selected) selectedFill else normalFill)
            setTextColor(if (selected) selectedText else normalText)
        }
    }

    private fun letterKey(value: String): KeySpec.Text {
        val rendered = if (!isChineseMode && isUppercase) value.uppercase() else value.lowercase()
        return KeySpec.Text(rendered, rendered)
    }

    private fun syncKeyboardStateForEditor(info: EditorInfo?) {
        keyboardMode = when (info?.inputType?.and(InputType.TYPE_MASK_CLASS)) {
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_DATETIME,
            InputType.TYPE_CLASS_PHONE -> KeyboardMode.Numbers
            else -> keyboardMode.takeIf { it == KeyboardMode.Symbols } ?: KeyboardMode.Letters
        }
        isUppercase = shouldAutoCapitalize(info)
        renderComposingBar()
    }

    private fun syncShiftStateForCursor(force: Boolean = false) {
        val newUppercase = shouldAutoCapitalize(currentEditorInfo)
        if (force || newUppercase != isUppercase) {
            isUppercase = newUppercase
        }
    }

    private fun shouldAutoCapitalize(info: EditorInfo?): Boolean {
        if (keyboardMode != KeyboardMode.Letters || isChineseMode) return false

        val inputType = info?.inputType ?: return false
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        if (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_URI ||
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
        ) {
            return false
        }

        val capsRequested = inputType and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS != 0 ||
            inputType and InputType.TYPE_TEXT_FLAG_CAP_WORDS != 0 ||
            inputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0
        if (!capsRequested) return false

        val extractedText = currentInputConnection?.getExtractedText(
            android.view.inputmethod.ExtractedTextRequest(),
            0
        ) ?: return false

        val selectionStart = extractedText.selectionStart
        val text = extractedText.text ?: return selectionStart <= 0
        if (selectionStart <= 0) return true
        if (selectionStart > text.length) return false

        val previousChar = text[selectionStart - 1]
        return previousChar == '.' || previousChar == '!' || previousChar == '?' || previousChar.isWhitespace()
    }

    private fun performEnterAction() {
        val action = currentEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
            ?: EditorInfo.IME_ACTION_UNSPECIFIED
        val handled = if (action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            currentInputConnection?.performEditorAction(action) ?: false
        } else {
            false
        }

        if (!handled) {
            currentInputConnection?.commitText("\n", 1)
        }
    }

    private fun resolveEnterLabel(): String {
        return when (currentEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)) {
            EditorInfo.IME_ACTION_GO -> getString(R.string.ime_action_go)
            EditorInfo.IME_ACTION_NEXT -> getString(R.string.ime_action_next)
            EditorInfo.IME_ACTION_SEARCH -> getString(R.string.ime_action_search)
            EditorInfo.IME_ACTION_SEND -> getString(R.string.ime_action_send)
            EditorInfo.IME_ACTION_DONE -> getString(R.string.ime_action_done)
            else -> getString(R.string.ime_action_enter)
        }
    }

    private fun resolveLanguageToggleLabel(): String {
        return if (isChineseMode) getString(R.string.ime_action_chinese) else getString(R.string.ime_action_english)
    }

    private fun resolveSpaceLabel(): String {
        if (composingPinyin.isNotEmpty()) {
            return composingCandidates.firstOrNull()?.take(6) ?: composingPinyin.take(6)
        }
        return getString(R.string.ime_action_space)
    }

    private fun resolveCommaLabel(): String = if (isChineseMode) "，" else ","

    private fun resolveCommaOutput(): String = if (isChineseMode) "，" else ","

    private fun resolvePeriodLabel(): String = if (isChineseMode) "。" else "."

    private fun resolvePeriodOutput(): String = if (isChineseMode) "。" else "."

    private fun deleteSelectedOrPreviousText() {
        val extractedText = currentInputConnection?.getExtractedText(
            android.view.inputmethod.ExtractedTextRequest(),
            0
        )

        if (extractedText == null) {
            currentInputConnection?.deleteSurroundingText(1, 0)
            return
        }

        val selectedCount = extractedText.selectionEnd - extractedText.selectionStart
        if (selectedCount > 0) {
            currentInputConnection?.commitText("", 1)
        } else {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }
    }

    private fun startDeleteRepeat() {
        stopDeleteRepeat()
        deleteRepeatHandler.postDelayed(deleteRepeatRunnable, DELETE_REPEAT_START_DELAY_MS)
    }

    private fun stopDeleteRepeat() {
        deleteRepeatHandler.removeCallbacks(deleteRepeatRunnable)
    }

    private fun createKeyBackground(key: KeySpec): GradientDrawable {
        val fillColor = when (key) {
            is KeySpec.Text -> Color.parseColor("#FFFFFF")
            is KeySpec.Special -> when (key.action) {
                SpecialKey.Enter -> Color.parseColor("#2563EB")
                SpecialKey.SystemKeyboard -> Color.parseColor("#DCE8F8")
                SpecialKey.ToggleLanguage -> if (isChineseMode) Color.parseColor("#C7DDFD") else Color.parseColor("#E2E8F0")
                SpecialKey.ModeLetters -> if (keyboardMode == KeyboardMode.Letters) Color.parseColor("#C7DDFD") else Color.parseColor("#E2E8F0")
                SpecialKey.ModeNumbers -> if (keyboardMode == KeyboardMode.Numbers) Color.parseColor("#C7DDFD") else Color.parseColor("#E2E8F0")
                SpecialKey.ModeSymbols -> if (keyboardMode == KeyboardMode.Symbols) Color.parseColor("#C7DDFD") else Color.parseColor("#E2E8F0")
                SpecialKey.Shift -> if (isUppercase) Color.parseColor("#C7DDFD") else Color.parseColor("#E2E8F0")
                else -> if (key.action == SpecialKey.Space) Color.parseColor("#FFFFFF") else Color.parseColor("#E2E8F0")
            }
        }

        return createContainerBackground(
            fillColor = fillColor,
            strokeColor = Color.parseColor("#D3DDEA"),
            radiusDp = 14
        )
    }

    private fun resolveKeyTextColor(key: KeySpec): Int {
        return if (key is KeySpec.Special && key.action == SpecialKey.Enter) {
            Color.parseColor("#FFFFFF")
        } else {
            Color.parseColor("#0F172A")
        }
    }

    private fun appendPinyin(value: String) {
        composingPinyin += value.lowercase()
        updateComposingState()
    }

    private fun removeLastPinyinLetter() {
        if (composingPinyin.isEmpty()) return
        composingPinyin = composingPinyin.dropLast(1)
        updateComposingState()
    }

    private fun updateComposingState() {
        composingCandidates = PinyinCandidateEngine.getCandidates(composingPinyin, CANDIDATE_LIMIT)
        if (composingPinyin.isEmpty()) {
            currentInputConnection?.finishComposingText()
        } else {
            currentInputConnection?.setComposingText(composingPinyin, 1)
        }
        renderComposingBar()
        renderKeyboard()
    }

    private fun commitComposingText(selected: String? = null, appendSpace: Boolean = false) {
        if (composingPinyin.isEmpty()) {
            if (appendSpace) {
                currentInputConnection?.commitText(" ", 1)
            }
            return
        }

        val committed = selected ?: composingCandidates.firstOrNull() ?: composingPinyin
        currentInputConnection?.finishComposingText()
        currentInputConnection?.commitText(committed, 1)
        if (appendSpace) {
            currentInputConnection?.commitText(" ", 1)
        }
        composingPinyin = ""
        composingCandidates = emptyList()
        renderComposingBar()
        renderKeyboard()
    }

    private fun renderComposingBar() {
        val bar = composingBar ?: return
        val textView = composingTextView ?: return
        val row = candidateRow ?: return

        val visible = isChineseMode && keyboardMode == KeyboardMode.Letters
        bar.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return

        textView.text = if (composingPinyin.isBlank()) {
            getString(R.string.ime_composing_hint)
        } else {
            getString(R.string.ime_composing_prefix, composingPinyin)
        }

        row.removeAllViews()
        if (composingPinyin.isBlank()) {
            return
        }

        if (composingCandidates.isEmpty()) {
            row.addView(createCandidateChip(getString(R.string.ime_candidate_raw, composingPinyin), emphasized = true) {
                commitComposingText(selected = composingPinyin)
            })
            return
        }

        composingCandidates.forEachIndexed { index, candidate ->
            row.addView(
                createCandidateChip(
                    label = "${index + 1}. $candidate",
                    emphasized = index == 0
                ) {
                    commitComposingText(selected = candidate)
                }
            )
        }

        row.addView(createCandidateChip(getString(R.string.ime_candidate_raw, composingPinyin), emphasized = false) {
            commitComposingText(selected = composingPinyin)
        })

        candidateScrollView?.post { candidateScrollView?.scrollTo(0, 0) }
    }

    private fun createCandidateChip(label: String, emphasized: Boolean, onClick: () -> Unit): View {
        val fillColor = if (emphasized) Color.parseColor("#2563EB") else Color.parseColor("#FFFFFF")
        val textColor = if (emphasized) Color.parseColor("#FFFFFF") else Color.parseColor("#0F172A")
        val strokeColor = if (emphasized) Color.parseColor("#2563EB") else Color.parseColor("#D4DDEA")

        return TextView(this).apply {
            text = label
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, if (emphasized) Typeface.BOLD else Typeface.NORMAL)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = createContainerBackground(fillColor, strokeColor, 12)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                rightMargin = dp(6)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun createContainerBackground(fillColor: Int, strokeColor: Int, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fillColor)
            setStroke(dp(1), strokeColor)
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()

    companion object {
        private const val TAG = "ClipSyncIME"
        private const val DELETE_REPEAT_START_DELAY_MS = 350L
        private const val DELETE_REPEAT_INTERVAL_MS = 50L
        private const val CANDIDATE_LIMIT = 12
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
    ToggleLanguage,
    SystemKeyboard
}
