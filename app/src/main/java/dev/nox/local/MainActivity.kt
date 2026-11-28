package dev.nox.local

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import java.util.Locale

/** Chat is a view of the session; the foreground service owns the model and microphone. */
class MainActivity : ComponentActivity() {
    companion object {
        /** True only while the chat Activity is on screen; a bound Service is not enough. */
        @Volatile internal var isVisible = false
        // onStart/onStop also cover a visible but paused split-screen Activity.
        @Volatile internal var isOnScreen = false
        private var foregroundActivity: java.lang.ref.WeakReference<MainActivity>? = null
        internal fun foregroundAssistantTarget(): MainActivity? = foregroundActivity?.get()?.takeIf {
            AssistantVoiceCommand.useChat(isVisible, isOnScreen, it.topResumed, it.isInMultiWindowMode,
                AssistantHandoff.active(it), DeviceInteraction.unlocked(it)) && !it.isFinishing && !it.isDestroyed
        }
    }
    private var topResumed = false
    private var service: NoxService? = null
    private var bound = false
    private val catalog = ModelDownload.catalog
    private val telemetryWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var telemetry: DeviceTelemetry? = null
    private var telemetryEpoch = 0L
    private var telemetryPending = false
    private val telemetryTick = object : Runnable {
        override fun run() {
            if (!isVisible || advanced || webPage != "settings" || !::obsidian.isInitialized ||
                !obsidian.ready || !DeviceInteraction.unlocked(this@MainActivity)) return
            val sampler = telemetry ?: DeviceTelemetry(this@MainActivity).also { telemetry = it }
            if (!telemetryPending) {
                telemetryPending = true
                val epoch = telemetryEpoch
                telemetryWorker.execute {
                    val result = runCatching { sampler.sample() }.getOrNull()
                    perceptionUi.post {
                        telemetryPending = false
                        if (epoch == telemetryEpoch && isVisible && !advanced && webPage == "settings" &&
                            !isDestroyed && DeviceInteraction.unlocked(this@MainActivity) && result != null) {
                            obsidian.send(result)
                        }
                    }
                }
            }
            perceptionUi.postDelayed(this, 2000L)
        }
    }

    private fun stopTelemetry() {
        perceptionUi.removeCallbacks(telemetryTick)
        telemetryEpoch++
        telemetry = null
    }

    private fun syncTelemetry() {
        stopTelemetry()
        if (isVisible && !advanced && webPage == "settings") perceptionUi.post(telemetryTick)
    }
    private lateinit var header: TextView
    private lateinit var chatPage: LinearLayout
    private lateinit var settingsPage: ScrollView
    private lateinit var chatScroll: ScrollView
    private lateinit var messages: LinearLayout
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var mic: Button
    private lateinit var attachment: TextView
    private lateinit var models: Spinner
    private lateinit var gpu: Switch
    private lateinit var vision: Switch
    private lateinit var tts: Switch
    private lateinit var modelButton: Button
    private lateinit var cancel: Button
    private lateinit var stopGeneration: Button
    private lateinit var progress: ProgressBar
    private lateinit var backgroundButton: Button
    private lateinit var systemAssistantButton: Button
    private lateinit var bluetoothInputButton: Button
    private lateinit var microphoneRouteLabel: TextView
    private lateinit var overlayButton: Button
    private lateinit var details: TextView
    private lateinit var visionPage: ScrollView
    private lateinit var actionsPage: ScrollView
    private lateinit var actionStatus: TextView
    private lateinit var actionJournal: TextView
    private lateinit var actionStop: Button
    private lateinit var visionInfo: TextView
    private lateinit var analyzeImage: Button
    private lateinit var effortChip: Button
    private lateinit var effortSetting: Button
    private lateinit var matrix: ObsidianMatrix
    private val navButtons = mutableListOf<Button>()
    private lateinit var obsidian: ObsidianHost
    private lateinit var legacyRoot: LinearLayout
    private var advanced = false
    private var webPage = "chat"
    private var webVoiceOpen = false
    private var webDictationId: Long? = null
    private var assistantDictationId: Long? = null
    private var pendingInAppAssistantUntil = 0L
    private var pendingAssistantDraft: String? = null
    private var pendingAssistantCommand: Pair<String, Long>? = null
    private var pendingAssistantShowChat = false
    private var webImageKey = ""
    private var webStateKey = ""
    private val voiceRefresh = object : Runnable {
        override fun run() {
            if (!isVisible || !webVoiceOpen || advanced) return
            val s = service
            val level = if (s != null && webDictationId != null && s.webDictationSession == webDictationId && s.webDictationActive)
                s.manualVoiceLevel else 0f
            if (::obsidian.isInitialized) obsidian.send(org.json.JSONObject().put("type", "level").put("value", level.toDouble()))
            perceptionUi.postDelayed(this, 100)
        }
    }
    private var activePage = 0
    private var matrixVisible = false
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (advanced) {
                if (::matrix.isInitialized && matrix.dismiss()) return
                closeAdvanced()
                return
            }
            if (::obsidian.isInitialized) obsidian.back { handled ->
                if (!handled && !isFinishing) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        }
    }

    private fun updateBackCallback() {
        backCallback.isEnabled = true
    }
    private var effort = ChatEffort.AUTO
    private val workControls = mutableListOf<View>()
    private var lastChat = "\u0000"
    private var lastTurns: List<NoxService.Turn>? = null
    private var partialView: TextView? = null
    private var pendingVoice = false
    private var pendingBackground = false
    private var importIndex = 0
    private var perceptionUid = -1
    private lateinit var perceptionButton: Button
    private lateinit var perceptionStatus: TextView
    private val perceptionUi = android.os.Handler(android.os.Looper.getMainLooper())
    private val assistantCommandExpiry = Runnable { if (isVisible) drainAssistantCommand() }
    private val perceptionRefresh = object : Runnable {
        override fun run() { renderPerception(); if (isVisible && webPage == "vision") renderWeb(); perceptionUi.postDelayed(this, 750) }
    }
    private val bg = 0xFF111113.toInt()
    private val surface = 0xFF19191C.toInt()
    private val ink = 0xFFF3F2F0.toInt()
    private val muted = 0xFFA3A1A4.toInt()
    private val accent = 0xFFC9B6FF.toInt()
    private val prefs by lazy { getSharedPreferences("nox_ui", MODE_PRIVATE) }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as? NoxService.LocalBinder)?.service ?: return
            service = s
            s.listener = { render() }
            s.syncOverlay()
            val loaded = s.loaded
            if (loaded != null) {
                models.setSelection(loaded.index)
                gpu.isChecked = loaded.gpu
                vision.isChecked = loaded.vision
            } else {
                val index = models.selectedItemPosition
                if (index in catalog.indices && !s.busy && s.modelFile(index).isFile) {
                    s.selectModel(index, gpu.isChecked, vision.isChecked)
                }
            }
            render()
            consumeAssistantVoice()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null; pendingAssistantCommand = null; closeWebDictation(); render()
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun panel(color: Int, radius: Int = 20) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun textLabel(text: String, size: Float = 16f, color: Int = ink) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
        setPadding(dp(4), dp(6), dp(4), dp(6))
    }
    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 14f
        setTextColor(ink); background = panel(surface, 18)
        minHeight = dp(48); minimumHeight = dp(48)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        setOnClickListener { action() }
    }
    private fun addButton(parent: LinearLayout, text: String, work: Boolean = true, action: () -> Unit): Button {
        return button(text, action).also {
            parent.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            if (work) workControls.add(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = bg; window.navigationBarColor = bg
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(bg)
            setPadding(dp(16), dp(12), dp(16), dp(10))
        }
        val frame = FrameLayout(this).apply { setBackgroundColor(bg) }
        legacyRoot = root
        root.visibility = View.GONE
        frame.addView(root, FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)
        frame.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(android.view.WindowInsets.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        frame.requestApplyInsets()
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val brand = textLabel("N.O.X.", 24f).apply { typeface = Typeface.DEFAULT_BOLD }
        top.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(button("← Obsidian") { closeAdvanced() }, LinearLayout.LayoutParams(-2, -2))
        effort = runCatching { ChatEffort.valueOf(prefs.getString("chat_effort", "AUTO") ?: "AUTO") }.getOrDefault(ChatEffort.AUTO)
        root.addView(top)
        header = textLabel("Локальный ассистент", 13f, accent)
        root.addView(header)

        chatPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chatScroll = ScrollView(this).apply { isFillViewport = true }
        messages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.TOP }
        chatScroll.addView(messages, FrameLayout.LayoutParams(-1, -2))
        chatPage.addView(chatScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        attachment = textLabel("", 13f, muted).apply {
            visibility = View.GONE; setOnClickListener { service?.removeImage() }
        }
        chatPage.addView(attachment)
        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = panel(0xFF222225.toInt(), 24).apply { setStroke(dp(1), 0xFF45404C.toInt()) }
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val attach = button("+") { pick(11, "image/*") }.apply { contentDescription = "Прикрепить изображение" }
        controls.addView(attach, LinearLayout.LayoutParams(dp(44), dp(48))); workControls.add(attach)
        input = EditText(this).apply {
            hint = "Сообщение Ноксу"; setHintTextColor(muted); setTextColor(ink); textSize = 16f
            background = null; maxLines = 4; setPadding(dp(4), dp(6), dp(4), dp(6))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        composer.addView(input, LinearLayout.LayoutParams(-1, -2))
        effortChip = button(effort.label) {}.apply { setTextColor(accent); textSize = 13f; setPadding(dp(8),0,dp(8),0) }
        controls.addView(effortChip, LinearLayout.LayoutParams(-2,dp(48))); workControls.add(effortChip)
        controls.addView(View(this), LinearLayout.LayoutParams(0,1,1f))
        mic = button("◉") { voice(false) }.apply { contentDescription = "Голосовая команда"; setTextColor(accent) }
        controls.addView(mic, LinearLayout.LayoutParams(dp(48), dp(48)))
        send = button("↑") {
            val s = service ?: return@button
            val command = input.text.toString()
            if (command.isNotBlank() && !s.busy) {
                s.ask(command, s.attachedImage, tts.isChecked, thinkingBudget = effort.budget(command, s.attachedImage != null))
                input.text.clear()
            }
        }.apply {
            contentDescription = "Отправить. Удерживай для фонового голоса"
            tooltipText = "Удерживай для переключения фонового голоса"
            setTextColor(bg)
            background = panel(accent, 24)
            setOnLongClickListener {
                toggleBackgroundVoice()
                true // Consume the hold: releasing must not send or clear the draft.
            }
        }
        controls.addView(send, LinearLayout.LayoutParams(dp(48), dp(48)))
        stopGeneration = button("■") { service?.cancelGeneration() }.apply {
            contentDescription = "Остановить генерацию"
            visibility = View.GONE
        }
        controls.addView(stopGeneration, LinearLayout.LayoutParams(dp(48), dp(48)))
        composer.addView(controls, LinearLayout.LayoutParams(-1,-2))
        chatPage.addView(composer, LinearLayout.LayoutParams(-1, -2))
        root.addView(chatPage, LinearLayout.LayoutParams(-1, 0, 1f))

        visionPage = ScrollView(this).apply { visibility = View.GONE }
        val visionContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        visionPage.addView(visionContent)
        root.addView(visionPage, LinearLayout.LayoutParams(-1, 0, 1f))
        visionContent.addView(textLabel("Vision", 28f).apply { typeface = Typeface.DEFAULT_BOLD })
        visionContent.addView(textLabel("Контекст, который видит ассистент", 13f, muted))
        visionInfo = textLabel("Изображение не выбрано", 15f).apply { background = panel(surface, 18); setPadding(dp(16),dp(16),dp(16),dp(16)) }
        visionContent.addView(visionInfo, LinearLayout.LayoutParams(-1, -2))
        addButton(visionContent, "Прикрепить изображение") { pick(11, "image/*") }
        analyzeImage = addButton(visionContent, "Анализировать изображение") {
            val s = service ?: return@addButton
            val image = s.attachedImage ?: return@addButton
            if (s.loaded?.vision != true || s.busy) return@addButton
            val query = "Опиши, что видно на изображении."
            s.ask(query, image, tts.isChecked, thinkingBudget = effort.budget(query, true))
            showPage(0)
        }
        addButton(visionContent, "Убрать изображение") { service?.removeImage() }
        actionsPage = ScrollView(this).apply { visibility = View.GONE }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        actionsPage.addView(actions)
        root.addView(actionsPage, LinearLayout.LayoutParams(-1,0,1f))
        actions.addView(textLabel("Действия", 28f).apply { typeface = Typeface.DEFAULT_BOLD })
        actionStatus = textLabel("Подключение…", 16f, accent)
        actions.addView(actionStatus)
        actionStop = addButton(actions, "Остановить текущую задачу", false) { service?.cancelGeneration() }
        addButton(actions, "Разрешения агента", false) { showAgentGrants() }
        actions.addView(textLabel("Журнал текущего сеанса", 18f))
        actionJournal = textLabel("Действий пока нет", 13f, muted).apply { background = panel(surface, 16); setPadding(dp(12),dp(12),dp(12),dp(12)) }
        actions.addView(actionJournal, LinearLayout.LayoutParams(-1,-2))

        settingsPage = ScrollView(this).apply { visibility = View.GONE }
        val settings = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        settingsPage.addView(settings)
        root.addView(settingsPage, LinearLayout.LayoutParams(-1, 0, 1f))
        settings.addView(textLabel("Системный ассистент Android", 19f).apply { typeface = Typeface.DEFAULT_BOLD })
        systemAssistantButton = addButton(settings, "Выбрать Нокс системным ассистентом", false) {
            chooseSystemAssistant()
        }
        settings.addView(textLabel("Системный жест или кнопка открывает Нокс и запускает прослушивание. Завершённая фраза становится командой. Сам выбор ассистента не включает микрофон. На заблокированном телефоне команды запрещены.", 13f, muted))
        settings.addView(textLabel("Голос вне приложения", 19f).apply { typeface = Typeface.DEFAULT_BOLD })
        backgroundButton = addButton(settings, "Включить фоновый голос", false) {
            toggleBackgroundVoice()
        }
        bluetoothInputButton = addButton(settings, "Предпочитать Bluetooth-микрофон", false) {
            service?.toggleBluetoothInput()
        }
        microphoneRouteLabel = textLabel("Источник микрофона пока не определён", 13f, muted)
        settings.addView(microphoneRouteLabel)
        overlayButton = addButton(settings, "Разрешить плавающую кнопку", false) { openOverlayPermission() }
        settings.addView(textLabel("После включения Нокс локально распознаёт имя «Нокс». Чтобы дать команду, разблокируй телефон, нажми микрофон и произнеси запрос. Фоновая фраза не сохраняется и не выполняется. Пока Нокс выполняет задачу или говорит, фоновое прослушивание приостановлено. Bluetooth — предпочтительный вход, фактический источник выбирает Android. Переключай фоновый голос здесь или удержанием стрелки отправки. Выключить его можно также в уведомлении.", 13f, muted))
        settings.addView(textLabel("Облачко действий видно только вне Нокса. Нажми «Остановить», чтобы отменить текущую задачу. Для действий нужен доступ к плавающему облачку; голосовые ответы на сообщения настраиваются отдельно.", 13f, muted))
        addButton(settings, "Разрешить агенту управление экраном", false) { openAccessibilityPermission() }
        settings.addView(textLabel("Агент запрашивает доступ к каждому приложению. Проверенные безопасные действия выполняются самостоятельно; неизвестные и критические требуют отдельного подтверждения.", 13f, muted))
        addButton(settings, "Разрешения агента") { showAgentGrants() }

        visionContent.addView(textLabel("Зрение и слух", 19f).apply { typeface = Typeface.DEFAULT_BOLD })
        perceptionButton = addButton(visionContent, "Включить восприятие", false) {
            if (LivePerception.active) stopService(Intent(this, PerceptionService::class.java))
            else choosePerceptionApp()
        }
        perceptionStatus = textLabel("", 13f, muted)
        visionContent.addView(perceptionStatus)
        addButton(visionContent, "Обсудить увиденное и услышанное") {
            service?.reviewPerception()
            showPage(0)
        }
        visionContent.addView(textLabel("Выбери приложение для звука и в системном окне Android выбери тот же экран. Захват продолжается вне чата до остановки или блокировки экрана. Аудио не записывается в файл. Последний кадр и распознанная речь хранятся в памяти; для Gemma создаётся временное изображение, которое удаляется после анализа.", 13f, muted))
        settings.addView(textLabel("Модель", 19f).apply { typeface = Typeface.DEFAULT_BOLD })
        models = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, catalog.map { it.name })
            setSelection(prefs.getInt("model_index", 0).coerceIn(catalog.indices))
        }
        settings.addView(models, LinearLayout.LayoutParams(-1, dp(52))); workControls.add(models)
        gpu = Switch(this).apply { text = "GPU для языка"; setTextColor(ink); isChecked = prefs.getBoolean("gpu", true) }
        vision = Switch(this).apply { text = "Анализ изображений"; setTextColor(ink); isChecked = prefs.getBoolean("vision", true) }
        tts = Switch(this).apply { text = "Озвучивать текстовые ответы"; setTextColor(ink); isChecked = false }
        effortSetting = button("Мышление · " + effort.label) {}
        settings.addView(gpu); settings.addView(vision); settings.addView(tts)
        settings.addView(effortSetting, LinearLayout.LayoutParams(-1, dp(48))); workControls.add(effortSetting)
        settings.addView(textLabel("Авто: 0 токенов для короткого текста, 256 для длинного запроса или изображения. Быстро: 0, Стандарт: 256, Глубоко: 1024. Планировщик агента работает отдельно.", 12f, muted))
        workControls.add(gpu); workControls.add(vision)
        modelButton = addButton(settings, "Скачать и запустить") { selectModel() }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        settings.addView(progress)
        cancel = addButton(settings, "Отменить скачивание", false) { service?.cancelDownload() }.apply { visibility = View.GONE }
        addButton(settings, "Импортировать файл вручную") { importIndex = models.selectedItemPosition; pick(10, "*/*") }
        addButton(settings, "Выбрать проверенную каталожную модель") { chooseCatalogModel() }
        settings.addView(textLabel("Диагностика", 19f).apply { typeface = Typeface.DEFAULT_BOLD })
        addButton(settings, "Сравнительный тест") { service?.benchmark(models.selectedItemPosition, gpu.isChecked, vision.isChecked) }
        addButton(settings, "Журнал тестов") { showBenchmarkLog() }
        addButton(settings, "Поделиться журналом") { shareBenchmarkLog() }
        addButton(settings, "Очистить чат") { service?.clearChat() }
        details = textLabel("", 13f, muted); settings.addView(details)
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0,dp(8),0,0) }
        listOf("Чат", "Vision", "Действия", "Настройки").forEachIndexed { index, name ->
            val item = button(name) { showPage(index) }.apply {
                textSize = 12f; minWidth = 0; minimumWidth = 0; setPadding(dp(2),0,dp(2),0)
            }
            navButtons.add(item)
            nav.addView(item, LinearLayout.LayoutParams(0,dp(52),1f).apply { marginEnd = if (index == 3) 0 else dp(4) })
        }
        nav.visibility = View.GONE
        root.addView(nav, LinearLayout.LayoutParams(-1,-2))
        matrix = ObsidianMatrix(frame, catalog.map { it.name }, {
            MatrixChoice(service?.loaded?.index ?: models.selectedItemPosition.coerceIn(catalog.indices), effort)
        }, ::applyMatrix) { visible ->
            matrixVisible = visible
            updateBackCallback()
        }
        onBackPressedDispatcher.addCallback(this, backCallback)
        obsidian = ObsidianHost(this, ::handleWebCommand)
        frame.addView(obsidian.view, FrameLayout.LayoutParams(-1, -1))
        obsidian.start()
        updateBackCallback()
        matrix.bind(effortChip)
        matrix.bind(effortSetting)
        updateNavigation()
        models.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val pkg = catalog[position]
                if (pkg.cpuOnly) gpu.isChecked = false
                if (!pkg.vision) vision.isChecked = false
                render()
            }
        }
        gpu.setOnCheckedChangeListener { _, _ -> render() }
        vision.setOnCheckedChangeListener { _, _ -> render() }
        runCatching { startService(Intent(this, NoxService::class.java).setAction(NoxService.KEEP_ALIVE)) }
        bound = bindService(Intent(this, NoxService::class.java), connection, BIND_AUTO_CREATE)
        render()
    }

    private fun showAgentGrants() {
        val grants = getSharedPreferences("nox_agent_grants", MODE_PRIVATE)
        val entries = grants.all.entries.sortedBy { it.key }
        val labels = entries.map { entry ->
            val name = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(entry.key, 0)).toString() }.getOrDefault(entry.key)
            name + " · " + when (entry.value) {
                AppGrant.ALWAYS.name -> "Разрешено всегда"
                AppGrant.NEVER.name -> "Запрещено"
                else -> "Спрашивать"
            }
        }.toTypedArray()
        val dialog = AlertDialog.Builder(this).setTitle("Разрешения агента")
            .setNegativeButton("Закрыть", null)
        if (entries.isEmpty()) dialog.setMessage("Постоянных разрешений пока нет.")
        else dialog.setItems(labels) { _, index ->
            val pkg = entries[index].key
            AlertDialog.Builder(this).setTitle(labels[index])
                .setItems(arrayOf("Спрашивать снова", "Запретить всегда")) { _, option ->
                    val edit = grants.edit()
                    if (option == 0) edit.remove(pkg) else edit.putString(pkg, AppGrant.NEVER.name)
                    edit.apply()
                }.setNegativeButton("Отмена", null).show()
        }.setNeutralButton("Сбросить все") { _, _ ->
            AlertDialog.Builder(this).setMessage("Удалить все постоянные разрешения и запреты?")
                .setPositiveButton("Сбросить") { _, _ -> grants.edit().clear().apply() }
                .setNegativeButton("Отмена", null).show()
        }
        dialog.show()
    }

    private fun renderPerception() {
        if (!::perceptionStatus.isInitialized) return
        perceptionButton.text = if (LivePerception.active) "Остановить восприятие" else "Включить восприятие"
        val snap = LivePerception.buffer.snapshot(android.os.SystemClock.elapsedRealtime())
        perceptionStatus.text = LivePerception.status +
            if (LivePerception.active) "\nКадры: " + LivePerception.frames + " · аудиосэмплы: " + LivePerception.audioSamples +
                "\n" + LivePerception.audioStatus + "\nСлышу: " + snap.speech.takeLast(500) + "\nВижу: " + LivePerception.visualSummary.takeLast(500) else ""
    }

    private fun choosePerceptionApp() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 32)
            return
        }
        val apps = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != packageName && it.activityInfo.packageName !in setOf("dev.nox.local", "dev.nox.local.dev", "com.android.settings") }
            .distinctBy { it.activityInfo.packageName }.sortedBy { it.loadLabel(packageManager).toString().lowercase(Locale.ROOT) }
        if (apps.isEmpty()) return
        AlertDialog.Builder(this).setTitle("Какое приложение смотреть и слушать?")
            .setItems(apps.map { it.loadLabel(packageManager).toString() }.toTypedArray()) { _, index ->
                perceptionUid = apps[index].activityInfo.applicationInfo.uid
                val manager = requireNotNull(getSystemService(android.media.projection.MediaProjectionManager::class.java))
                startActivityForResult(manager.createScreenCaptureIntent(), 31)
            }.setNegativeButton("Отмена", null).show()
    }

    private fun showPage(page: Int) {
        activePage = page
        updateBackCallback()
        listOf(chatPage, visionPage, actionsPage, settingsPage).forEachIndexed { i, view -> view.visibility = if (i == page) View.VISIBLE else View.GONE }
        if (page != 0) {
            input.clearFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
        }
        updateNavigation()
        render()
    }

    private fun updateNavigation() {
        navButtons.forEachIndexed { i, button ->
            button.isSelected = i == activePage
            button.setTextColor(if (i == activePage) accent else muted)
            button.background = panel(if (i == activePage) 0xFF29243A.toInt() else surface, 14)
        }
    }

    private fun applyMatrix(choice: MatrixChoice) {
        val s = service ?: return
        if (s.busy) return
        effort = choice.effort
        prefs.edit().putString("chat_effort", effort.name).apply()
        effortChip.text = effort.label
        effortSetting.text = "Мышление · " + effort.label
        if (choice.model != s.loaded?.index) {
            models.setSelection(choice.model)
            val pkg = catalog[choice.model]
            if (pkg.cpuOnly) gpu.isChecked = false
            if (!pkg.vision) vision.isChecked = false
            selectModel()
        }
        render()
    }

    private fun closeAdvanced() {
        advanced = false
        legacyRoot.visibility = View.GONE
        obsidian.view.visibility = View.VISIBLE
        webStateKey = ""
        render()
    }

    private fun ack(op: String, ok: Boolean, message: String = "") {
        obsidian.send(org.json.JSONObject().put("type", "ack").put("op", op).put("ok", ok).put("message", message))
    }

    private fun closeWebDictation() {
        pendingInAppAssistantUntil = 0L
        assistantDictationId = null
        val id = webDictationId
        webDictationId = null
        webVoiceOpen = false
        perceptionUi.removeCallbacks(voiceRefresh)
        if (id != null) service?.cancelWebDictation(id)
    }

    private fun handleWebCommand(raw: String) {
        val c = ObsidianCommand.parse(raw) ?: return
        if (!::obsidian.isInitialized || isFinishing || isDestroyed) return
        if (c.op == "ready") { webStateKey = ""; webImageKey = ""; renderWeb(); syncTelemetry(); return }
        if (!isVisible || advanced || !DeviceInteraction.unlocked(this)) { ack(c.op, false, "Открой и разблокируй Нокс"); return }
        val s = service
        if (c.op in setOf("send", "clear", "cancel", "choice", "voice_start", "model_select",
                "model_load", "model_import", "model_catalog", "benchmark") ||
            (c.op == "page" && c.text != "chat")) {
            pendingAssistantCommand = null
            pendingInAppAssistantUntil = 0L
        }
        if (c.op == "page") {
            webPage = c.text
            if (webPage != "chat") closeWebDictation()
            renderWeb(); syncTelemetry(); return
        }
        if (c.op == "voice_open") {
            if (webPage != "chat") { ack(c.op, false); return }
            webVoiceOpen = true
            perceptionUi.removeCallbacks(voiceRefresh)
            perceptionUi.post(voiceRefresh)
            return
        }
        if (c.op == "voice_start") {
            if (!webVoiceOpen || webPage != "chat" || webDictationId != null || s == null || s.busy) {
                closeWebDictation(); ack(c.op, false, "Диктовка недоступна"); return
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                closeWebDictation()
                ack(c.op, false, "Разреши микрофон и нажми ещё раз")
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 33)
                return
            }
            webDictationId = s.startWebDictation()
            if (webDictationId == null) {
                closeWebDictation(); ack(c.op, false, "Микрофон недоступен"); return
            }
            ack(c.op, true)
            renderWeb()
            return
        }
        if (c.op == "voice_close" || c.op == "voice_stop") {
            if (c.op == "voice_close") closeWebDictation()
            else {
                assistantDictationId = null
                webDictationId?.let { s?.stopWebDictation(it) }
            }
            renderWeb()
            return
        }
        if (c.op == "copy") {
            getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("N.O.X", c.text))
            ack(c.op, true, "Скопировано"); return
        }
        val settingsOps = setOf("system_assistant", "overlay_permission", "accessibility_permission",
            "model_select", "model_gpu", "model_vision", "model_load", "model_import", "model_catalog",
            "model_cancel", "benchmark", "benchmark_log", "benchmark_share", "reduce_motion", "float_button")
        if (c.op in settingsOps && webPage != "settings") { ack(c.op, false, "Открой настройки"); return }
        when (c.op) {
            "system_assistant" -> { chooseSystemAssistant(); return }
            "overlay_permission" -> { openOverlayPermission(); return }
            "accessibility_permission" -> { openAccessibilityPermission(); return }
            "benchmark_log" -> { showBenchmarkLog(); return }
            "benchmark_share" -> { shareBenchmarkLog(); return }
            "reduce_motion", "float_button" -> {
                prefs.edit().putBoolean(c.op, c.enabled).apply()
                renderWeb(); return
            }
        }
        if (c.op == "grants") { showAgentGrants(); return }
        if (s == null) { ack(c.op, false, "Ядро подключается"); return }
        if (c.op == "cancel") { s.cancelGeneration(); return }
        if (c.op == "model_cancel") {
            if (s.progress != null) s.cancelDownload()
            return
        }
        if (s.busy && c.op !in setOf("perception", "background")) { ack(c.op, false, "Нокс занят"); return }
        when (c.op) {
            "model_select" -> {
                if (c.model !in catalog.indices) { ack(c.op, false); return }
                models.setSelection(c.model)
                if (catalog[c.model].cpuOnly) gpu.isChecked = false
                if (!catalog[c.model].vision) vision.isChecked = false
            }
            "model_gpu" -> {
                if (catalog[models.selectedItemPosition.coerceIn(catalog.indices)].cpuOnly && c.enabled) { ack(c.op, false, "Модель поддерживает только CPU"); return }
                gpu.isChecked = c.enabled
            }
            "model_vision" -> {
                if (!catalog[models.selectedItemPosition.coerceIn(catalog.indices)].vision && c.enabled) { ack(c.op, false, "Модель не поддерживает изображения"); return }
                vision.isChecked = c.enabled
            }
            "model_load" -> selectModel()
            "model_import" -> { importIndex = models.selectedItemPosition; pick(10, "*/*") }
            "model_catalog" -> chooseCatalogModel()
            "benchmark" -> AlertDialog.Builder(this).setTitle("Сравнительный тест")
                .setMessage("Тест загрузит выбранную модель, выполнит 8 запросов и выгрузит модель. Это может занять несколько минут.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Запустить") { _, _ ->
                    s.benchmark(models.selectedItemPosition, gpu.isChecked, vision.isChecked)
                }.show()
            "send" -> {
                if (c.agent && s.attachedImage != null) { ack(c.op, false, "Убери изображение для управления телефоном"); return }
                s.ask(c.text, s.attachedImage, tts.isChecked, thinkingBudget = effort.budget(c.text, s.attachedImage != null), forceAgent = c.agent)
                ack(c.op, true)
            }
            "choice" -> {
                if (c.model !in catalog.indices || c.effort !in ChatEffort.entries.indices) { ack(c.op, false); return }
                applyMatrix(MatrixChoice(c.model, ChatEffort.entries[c.effort]))
                ack(c.op, true)
                webStateKey = ""
            }
            "clear" -> AlertDialog.Builder(this).setTitle("Новый чат")
                .setMessage("Очистить историю текущего диалога?")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Очистить") { _, _ -> s.clearChat() }.show()
            "toggle_vision" -> {
                val loaded = s.loaded ?: return
                if (!catalog[loaded.index].vision) { ack(c.op, false, "Модель не поддерживает изображения"); return }
                models.setSelection(loaded.index); gpu.isChecked = loaded.gpu; vision.isChecked = !loaded.vision
                selectModel()
            }
            "background" -> toggleBackgroundVoice()
            "tts" -> tts.isChecked = !tts.isChecked
            "bluetooth" -> s.toggleBluetoothInput()
            "pick_image" -> pick(11, "image/*")
            "remove_image" -> s.removeImage()
            "perception" -> {
                if (LivePerception.active) stopService(Intent(this, PerceptionService::class.java))
                else if (!s.busy) choosePerceptionApp()
            }
            "analyze" -> {
                val image = s.attachedImage ?: return
                if (s.loaded?.vision != true) { ack(c.op, false, "Модель не поддерживает изображения"); return }
                val q = "Опиши, что видно на изображении."
                s.ask(q, image, tts.isChecked, thinkingBudget = effort.budget(q, true))
            }
            "review" -> s.reviewPerception()
        }
        render()
    }

    private fun renderWeb() {
        if (!::obsidian.isInitialized || !obsidian.ready || advanced) return
        val s = service
        val loaded = s?.loaded
        val selected = models.selectedItemPosition.coerceIn(catalog.indices)
        val pkg = catalog[selected]
        val dictationId = webDictationId?.takeIf { s != null && s.webDictationSession == it }
        val dictationActive = dictationId != null && s?.webDictationActive == true
        val image = s?.attachedImage
        val frame = if (image == null && isVisible && webPage == "vision" && LivePerception.active)
            LivePerception.buffer.snapshot(android.os.SystemClock.elapsedRealtime()).frame else null
        val attachedKey = image?.let { it.name + ":" + it.lastModified() + ":" + it.length() }
            ?: frame?.let { "screen:" + LivePerception.epoch + ":" + it.contentHashCode() }.orEmpty()
        val st = org.json.JSONObject()
            .put("type", "state")
            .put("connected", s != null)
            .put("status", if (s == null) "Подключение…" else s.status)
            .put("busy", s == null || s.busy)
            .put("generating", s?.generating == true)
            .put("modelIndex", loaded?.index ?: models.selectedItemPosition.coerceIn(catalog.indices))
            .put("modelName", loaded?.let { catalog[it.index].name } ?: "Модель не загружена")
            .put("modelDetails", details.text.toString())
            .put("selectedModel", selected)
            .put("selectedGpu", gpu.isChecked && !pkg.cpuOnly)
            .put("selectedVision", vision.isChecked && pkg.vision)
            .put("gpuSupported", !pkg.cpuOnly)
            .put("visionSupported", pkg.vision)
            .put("loadedGpu", loaded?.gpu ?: org.json.JSONObject.NULL)
            .put("modelAvailable", s?.modelFile(selected)?.isFile == true)
            .put("modelSizeBytes", pkg.size)
            .put("downloadProgress", s?.progress ?: org.json.JSONObject.NULL)
            .put("assistantActive", AssistantHandoff.active(this))
            .put("overlayAllowed", Settings.canDrawOverlays(this))
            .put("accessibilityAllowed", android.provider.Settings.Secure.getString(contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':').any {
                    ComponentName.unflattenFromString(it) == ComponentName(this, NoxNavigationService::class.java)
                })
            .put("reduceMotion", prefs.getBoolean("reduce_motion", false))
            .put("floatButton", prefs.getBoolean("float_button", false))
            .put("decodeTokens", s?.decodeStats?.tokens ?: org.json.JSONObject.NULL)
            .put("decodeRate", s?.decodeStats?.tokensPerSecond ?: org.json.JSONObject.NULL)
            .put("firstTokenMs", s?.decodeStats?.firstTokenMs ?: org.json.JSONObject.NULL)
            .put("loaded", loaded != null)
            .put("effort", effort.ordinal)
            .put("vision", loaded?.vision == true)
            .put("attached", image != null)
            .put("imageKey", attachedKey)
            .put("perception", LivePerception.active)
            .put("perceptionText", if (webPage == "vision" && ::perceptionStatus.isInitialized) perceptionStatus.text.toString() else "")
            .put("background", s?.background == true)
            .put("tts", tts.isChecked)
            .put("bluetooth", s?.preferBluetooth == true)
            .put("micRoute", s?.microphoneRoute.orEmpty())
            .put("manualListening", s?.listening == true && !s.waitingForName)
            .put("recognized", if (dictationActive) s?.recognizedText.orEmpty() else "")
            .put("dictationSession", dictationId ?: org.json.JSONObject.NULL)
            .put("dictationActive", dictationActive)
            .put("dictationResult", if (dictationId != null && !dictationActive) s?.webDictationResult.orEmpty() else "")
            .put("elapsedMs", s?.elapsedMs ?: org.json.JSONObject.NULL)
            .put("partial", s?.partial.orEmpty())
            .put("turns", org.json.JSONArray(s?.turns.orEmpty().map { org.json.JSONObject().put("user", it.user).put("text", it.text) }))
            .put("events", org.json.JSONArray(s?.agentJournal.orEmpty()))
        val key = st.toString()
        if (key != webStateKey) { webStateKey = key; obsidian.send(st) }
        if (isVisible && DeviceInteraction.unlocked(this) && pendingAssistantShowChat) {
            pendingAssistantShowChat = false
            obsidian.send(org.json.JSONObject().put("type", "assistant_show_chat"))
        }
        if (isVisible && DeviceInteraction.unlocked(this)) pendingAssistantDraft?.let { draft ->
            pendingAssistantDraft = null
            obsidian.send(org.json.JSONObject().put("type", "draft").put("text", draft))
        }
        if (pendingInAppAssistantUntil != 0L) perceptionUi.post { startInAppAssistantIfReady() }
        if (webImageKey != attachedKey) {
            webImageKey = attachedKey
            runCatching {
                val bytes = if (image != null && image.isFile && image.length() in 1..2_000_000L) image.readBytes()
                    else frame?.takeIf { it.size in 1..2_000_000 }
                if (bytes != null) {
                    val data = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    obsidian.send(org.json.JSONObject().put("type", "image").put("key", attachedKey).put("data", data))
                }
            }
        }
    }

    private fun render() {
        finishInAppAssistantDictation()
        drainAssistantCommand()
        val s = service
        val index = models.selectedItemPosition.coerceIn(catalog.indices)
        val pkg = catalog[index]
        val busy = s == null || s.busy
        header.text = if (s == null) "Подключение…" else if (s.listening && s.recognizedText.isNotBlank()) "Слышу: " + s.recognizedText else s.status
        details.text = if (s == null) "" else (s.loaded?.let { catalog[it.index].name + (if (it.imported) " · импорт" else " · каталог") + if (it.gpu) " · GPU" else " · CPU" } ?: "Модель не загружена") +
            (s.elapsedMs?.let { "\nПоследний ответ: " + it + " мс" } ?: "")
        val exists = s?.modelFile(index)?.isFile == true
        modelButton.text = if (exists) "Запустить " + pkg.name else "Скачать и запустить · " +
            String.format(Locale.US, "%.2f", pkg.size / 1e9) + " ГБ"
        systemAssistantButton.text = if (AssistantHandoff.active(this)) "Нокс — системный ассистент · настройки" else "Выбрать Нокс системным ассистентом"
        backgroundButton.text = if (s?.background == true) "Выключить фоновый голос" else "Включить фоновый голос"
        bluetoothInputButton.text = if (s?.preferBluetooth == true) "Bluetooth предпочтителен · отключить" else "Предпочитать Bluetooth-микрофон"
        bluetoothInputButton.isEnabled = s != null && !busy
        microphoneRouteLabel.text = s?.microphoneRoute?.takeIf { it.isNotBlank() } ?: "Источник микрофона пока не определён"
        send.contentDescription = "Отправить. Удерживай, чтобы " + if (s?.background == true) "выключить фоновый голос" else "включить фоновый голос"
        overlayButton.text = if (Settings.canDrawOverlays(this)) "Плавающая кнопка разрешена" else "Разрешить плавающую кнопку"
        workControls.forEach { it.isEnabled = !busy }
        gpu.isEnabled = !busy && !pkg.cpuOnly
        vision.isEnabled = !busy && pkg.vision
        send.isEnabled = !busy
        mic.isEnabled = !busy && (s?.listening != true || s?.waitingForName == true)
        send.visibility = if (s?.generating == true) View.GONE else View.VISIBLE
        stopGeneration.visibility = if (s?.generating == true) View.VISIBLE else View.GONE
        progress.visibility = if (s?.progress != null) View.VISIBLE else View.GONE
        progress.progress = s?.progress ?: 0
        cancel.visibility = progress.visibility
        attachment.text = "Изображение прикреплено · убрать"
        attachment.visibility = if (s?.attachedImage != null) View.VISIBLE else View.GONE
        val attached = s?.attachedImage
        visionInfo.text = (if (attached != null) "Изображение прикреплено · " + attached.length() + " байт" else "Изображение не выбрано") +
            "\nАнализ: " + if (s?.loaded?.vision == true) "доступен" else "модель без Vision или не загружена"
        analyzeImage.isEnabled = !busy && attached != null && s?.loaded?.vision == true
        actionStop.isEnabled = s?.generating == true
        actionStatus.text = s?.status ?: "Подключение…"
        if (activePage == 2) actionJournal.text = s?.agentJournal?.takeIf { it.isNotEmpty() }?.joinToString("\n") ?: "Действий в этом сеансе пока нет"
        effortChip.text = effort.label
        effortChip.contentDescription = "Мышление " + effort.label + ". Модель " + (s?.loaded?.let { catalog[it.index].name } ?: "не загружена") + ". Удерживай для выбора"
        effortSetting.text = "Мышление · " + effort.label
        renderWeb()
        if (!advanced) return
        val turns = s?.turns.orEmpty()
        val partial = s?.partial.orEmpty()
        val signature = turns.toString() + partial
        if (signature == lastChat) return
        lastChat = signature
        if (lastTurns != turns) {
            lastTurns = turns
            messages.removeAllViews()
            partialView = null
            if (turns.isEmpty() && partial.isEmpty()) {
                messages.addView(textLabel("С чего начнём?", 30f).apply { typeface = Typeface.DEFAULT_BOLD })
                messages.addView(textLabel("Локальный ассистент · спроси, покажи или поручи действие.", 14f, muted))
                addButton(messages, "Посмотреть экран", false) { showPage(1) }
                addButton(messages, "Журнал действий", false) { showPage(2) }
                addButton(messages, "Голосовая команда", false) { voice(false) }
                addButton(messages, "Настройки модели", false) { showPage(3) }
            }
            for (turn in turns) addTurn(turn.user, turn.text)
        }
        if (partial.isNotBlank()) {
            val row = partialView ?: addTurn(false, partial).also { partialView = it }
            row.text = partial
        } else {
            partialView?.let { messages.removeView(it) }
            partialView = null
        }
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun addTurn(user: Boolean, text: String): TextView {
        val row = textLabel(text, 16f).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10))
            if (user) background = panel(0xFF29243F.toInt(), 18)
        }
        messages.addView(row, LinearLayout.LayoutParams(if (user) -2 else -1, -2).apply {
            gravity = if (user) Gravity.END else Gravity.START
            topMargin = dp(8); bottomMargin = dp(6)
            if (user) leftMargin = dp(30) else rightMargin = dp(12)
        })
        return row
    }

    private fun openOverlayPermission() {
        if (Settings.canDrawOverlays(this)) service?.syncOverlay()
        else runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName))) }
            .onFailure { ack("overlay_permission", false, "Android не открыл настройки разрешения") }
        render()
    }

    private fun openAccessibilityPermission() {
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .onFailure { ack("accessibility_permission", false, "Android не открыл настройки специальных возможностей") }
    }

    private fun chooseCatalogModel() {
        if (service?.busy != false) return
        val index = models.selectedItemPosition.coerceIn(catalog.indices)
        val pkg = catalog[index]
        val useGpu = gpu.isChecked && !pkg.cpuOnly
        val useVision = vision.isChecked && pkg.vision
        AlertDialog.Builder(this).setTitle("Проверенная копия " + pkg.name)
            .setMessage("Импортированные и прежние файлы останутся на устройстве. Если проверенной копии нет, потребуется скачать " + String.format(Locale.US, "%.2f", pkg.size / 1e9) + " ГБ.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Продолжить") { _, _ ->
                if (service?.busy == false) {
                    saveSelection(index)
                    service?.selectModel(index, useGpu, useVision, forceCatalog = true)
                }
            }.show()
    }

    private fun showBenchmarkLog() {
        val file = service?.reportFile
        AlertDialog.Builder(this).setTitle("Журнал")
            .setMessage(if (file?.isFile == true) file.readText().takeLast(30000) else "Тесты ещё не запускались")
            .setPositiveButton("Закрыть", null).show()
    }

    private fun shareBenchmarkLog() {
        val file = service?.reportFile
        if (file?.isFile == true) startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, file.readText().takeLast(100000))
        }, "Поделиться результатами"))
        else ack("benchmark_share", false, "Журнал пока пуст")
    }

    private fun selectModel() {
        val s = service ?: return
        val index = models.selectedItemPosition
        val pkg = catalog[index]
        saveSelection(index)
        if (s.modelFile(index).isFile) s.selectModel(index, gpu.isChecked, vision.isChecked)
        else AlertDialog.Builder(this).setTitle("Скачать " + pkg.name + "?")
            .setMessage("Рекомендуется Wi-Fi. Размер " + String.format(Locale.US, "%.2f", pkg.size / 1e9) + " ГБ.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Скачать") { _, _ -> s.selectModel(index, gpu.isChecked, vision.isChecked) }.show()
    }

    private fun saveSelection(index: Int) {
        prefs.edit().putInt("model_index", index).putBoolean("gpu", gpu.isChecked)
            .putBoolean("vision", vision.isChecked).apply()
    }

    private fun chooseSystemAssistant() {
        val role = getSystemService(RoleManager::class.java)
        if (role != null && role.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && !role.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
            if (runCatching { startActivityForResult(role.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT), 41) }.isSuccess)
                return
        }
        if (runCatching { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }.isFailure)
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
                .onFailure { header.text = "Открой настройки Android → Приложения по умолчанию → Цифровой помощник" }
    }

    internal fun assistantCanStayInChat(): Boolean = !advanced && webPage == "chat"

    /** Called in-process only by the active system assistant, not by an exported Intent. */
    internal fun acceptAssistantInvocation() {
        if (foregroundAssistantTarget() !== this) return
        if (::matrix.isInitialized) matrix.dismiss()
        val s = service
        val existing = webDictationId
        if (existing != null && s != null && s.webDictationSession == existing && s.webDictationActive &&
            webVoiceOpen && webPage == "chat" && !advanced) {
            // An explicit second invocation may arm the existing capture, but never starts a second one.
            assistantDictationId = existing
            return
        }
        closeWebDictation()
        pendingAssistantCommand = null
        pendingAssistantDraft = null
        webPage = "chat"
        pendingAssistantShowChat = true
        pendingInAppAssistantUntil = android.os.SystemClock.elapsedRealtime() + 5_000L
        if (advanced) closeAdvanced() else renderWeb()
        syncTelemetry()
    }

    private fun startInAppAssistantIfReady() {
        val deadline = pendingInAppAssistantUntil
        if (deadline == 0L) return
        if (android.os.SystemClock.elapsedRealtime() >= deadline || foregroundAssistantTarget() !== this ||
            advanced || webPage != "chat") {
            pendingInAppAssistantUntil = 0L
            return
        }
        val s = service ?: return
        if (!::obsidian.isInitialized || !obsidian.ready) return
        pendingInAppAssistantUntil = 0L
        if (s.busy) {
            Toast.makeText(this, "Нокс занят", Toast.LENGTH_SHORT).show()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 33)
            return
        }
        webVoiceOpen = true
        obsidian.send(org.json.JSONObject().put("type", "assistant_listen"))
        val id = s.startWebDictation()
        webDictationId = id
        assistantDictationId = id
        if (id == null) {
            closeWebDictation()
            obsidian.send(org.json.JSONObject().put("type", "assistant_voice_cancel"))
            Toast.makeText(this, "Микрофон недоступен", Toast.LENGTH_SHORT).show()
            return
        }
        perceptionUi.removeCallbacks(voiceRefresh)
        perceptionUi.post(voiceRefresh)
        renderWeb()
    }

    private fun finishInAppAssistantDictation() {
        val id = assistantDictationId ?: return
        val s = service ?: return
        if (s.webDictationSession != id || webDictationId != id) {
            assistantDictationId = null
            return
        }
        if (s.webDictationActive) return
        assistantDictationId = null
        val command = AssistantVoiceCommand.fromResult(foregroundAssistantTarget() === this,
            s.webDictationFinal, s.webDictationResult) ?: return
        // Cancel the UI-owned session before ask(); this restores any pre-existing typed draft.
        closeWebDictation()
        if (::obsidian.isInitialized) obsidian.send(org.json.JSONObject().put("type", "assistant_voice_cancel"))
        pendingAssistantCommand = command to (android.os.SystemClock.elapsedRealtime() + 120_000L)
        perceptionUi.removeCallbacks(assistantCommandExpiry)
        perceptionUi.postDelayed(assistantCommandExpiry, 120_000L)
    }

    /** Only an authenticated, one-shot system-panel handoff may submit a voice command. */
    private fun consumeAssistantVoice() {
        if (!isVisible || service == null) return
        val ticket = intent?.getStringExtra(AssistantHandoff.EXTRA_TICKET) ?: return
        intent.removeExtra(AssistantHandoff.EXTRA_TICKET)
        val handoff = AssistantHandoff.gate.consumeHandoff(ticket, AssistantHandoff.active(this),
            DeviceInteraction.unlocked(this)) ?: return
        pendingAssistantCommand = null
        if (handoff.execute) {
            pendingAssistantShowChat = true
            closeWebDictation()
            pendingAssistantCommand = handoff.text to (android.os.SystemClock.elapsedRealtime() + 120_000L)
            if (service?.busy == true && service?.loaded == null)
                Toast.makeText(this, "Команда принята · жду загрузку модели", Toast.LENGTH_SHORT).show()
            perceptionUi.removeCallbacks(assistantCommandExpiry)
            perceptionUi.postDelayed(assistantCommandExpiry, 120_000L)
        } else {
            pendingAssistantDraft = handoff.text
        }
        if (advanced) closeAdvanced()
        render()
    }

    private fun drainAssistantCommand() {
        val (command, deadline) = pendingAssistantCommand ?: return
        if (!isVisible || !isOnScreen || !DeviceInteraction.unlocked(this) || !AssistantHandoff.active(this)) {
            pendingAssistantCommand = null
            return
        }
        val s = service ?: return
        if (android.os.SystemClock.elapsedRealtime() >= deadline || (s.busy && s.loaded != null)) {
            pendingAssistantCommand = null
            pendingAssistantDraft = command
            Toast.makeText(this, "Команда сохранена в поле ввода · Нокс не готов", Toast.LENGTH_SHORT).show()
            renderWeb()
            return
        }
        if (s.busy) return
        // Clear before ask(), which can synchronously publish another render.
        perceptionUi.removeCallbacks(assistantCommandExpiry)
        pendingAssistantCommand = null
        s.askInPlace(command)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeAssistantVoice()
    }

    private fun toggleBackgroundVoice() {
        val s = service ?: return
        if (s.background) s.disableBackground() else voice(true)
    }

    private fun voice(background: Boolean) {
        val permissions = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            permissions.add(Manifest.permission.RECORD_AUDIO)
        if (background && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        if (permissions.isNotEmpty()) {
            pendingBackground = background; pendingVoice = !background
            requestPermissions(permissions.toTypedArray(), 12); return
        }
        if (background) {
            runCatching { startForegroundService(Intent(this, NoxService::class.java).setAction(NoxService.ENABLE)) }
                .onFailure { header.text = "Не удалось запустить фоновый сервис: " + it.message }
        } else service?.listen()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 32) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) choosePerceptionApp()
            return
        }
        if (requestCode != 12) return
        val bg = pendingBackground; val local = pendingVoice
        pendingBackground = false; pendingVoice = false
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            header.text = "Доступ к микрофону не предоставлен"; return
        }
        if (bg && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !Settings.canDrawOverlays(this)) {
            header.text = "Разреши уведомления или плавающую кнопку для остановки фонового голоса"; return
        }
        if (bg) runCatching { startForegroundService(Intent(this, NoxService::class.java).setAction(NoxService.ENABLE)) }
            .onFailure { header.text = "Не удалось запустить фоновый сервис: " + it.message }
        else if (local) service?.listen()
    }

    private fun pick(request: Int, mime: String) {
        if (service?.busy != false) return
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = mime }, request)
    }
    @Deprecated("Prototype document picker")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request == 41) { render(); return }
        if (request == 31) {
            if (result == RESULT_OK && data != null && perceptionUid >= 0) {
                runCatching {
                    startService(Intent(this, NoxService::class.java).setAction(NoxService.KEEP_ALIVE))
                    startForegroundService(Intent(this, PerceptionService::class.java).setAction(PerceptionService.START)
                        .putExtra(PerceptionService.RESULT, result).putExtra(PerceptionService.TOKEN, data)
                        .putExtra(PerceptionService.UID, perceptionUid))
                }.onFailure { perceptionStatus.text = "Не удалось запустить: " + it.message }
            }
            perceptionUid = -1
            return
        }
        if (result != RESULT_OK) return
        val uri = data?.data ?: return
        when (request) {
            10 -> { saveSelection(importIndex); service?.selectModel(importIndex, gpu.isChecked, vision.isChecked, uri) }
            11 -> service?.prepareImage(uri)
        }
    }

    override fun onStart() {
        super.onStart()
        isOnScreen = true
        perceptionUi.removeCallbacks(perceptionRefresh)
        perceptionUi.post(perceptionRefresh)
        service?.syncOverlay()
    }
    override fun onStop() {
        isOnScreen = false
        perceptionUi.removeCallbacks(perceptionRefresh)
        service?.syncOverlay()
        super.onStop()
    }

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        topResumed = isTopResumedActivity
    }

    override fun onResume() {
        super.onResume()
        isVisible = true
        foregroundActivity = java.lang.ref.WeakReference(this)
        syncTelemetry()
        if (webVoiceOpen && !advanced) { perceptionUi.removeCallbacks(voiceRefresh); perceptionUi.post(voiceRefresh) }
        service?.listener = { render() }
        service?.syncOverlay()
        render()
        consumeAssistantVoice()
    }
    override fun onPause() {
        if (::matrix.isInitialized) matrix.dismiss()
        isVisible = false
        if (foregroundActivity?.get() === this) foregroundActivity = null
        stopTelemetry()
        closeWebDictation()
        pendingAssistantDraft = null
        pendingAssistantCommand = null
        pendingAssistantShowChat = false
        perceptionUi.removeCallbacks(assistantCommandExpiry)
        // A delayed handoff must not activate the microphone on a later unrelated visit.
        intent?.removeExtra(AssistantHandoff.EXTRA_TICKET)
        service?.listener = null
        super.onPause()
    }
    override fun onDestroy() {
        stopTelemetry()
        telemetryWorker.shutdownNow()
        closeWebDictation()
        pendingAssistantDraft = null
        pendingAssistantCommand = null
        pendingAssistantShowChat = false
        perceptionUi.removeCallbacks(assistantCommandExpiry)
        if (::obsidian.isInitialized) obsidian.destroy()
        service?.listener = null
        if (bound) unbindService(connection)
        service = null
        super.onDestroy()
    }
}
