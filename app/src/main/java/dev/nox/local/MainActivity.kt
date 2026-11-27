package dev.nox.local

import android.Manifest
import android.app.Activity
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
class MainActivity : Activity() {
    companion object {
        /** True only while the chat Activity is on screen; a bound Service is not enough. */
        @Volatile internal var isVisible = false
        // onStart/onStop also cover a visible but paused split-screen Activity.
        @Volatile internal var isOnScreen = false
    }
    private var service: NoxService? = null
    private var bound = false
    private val catalog = ModelDownload.catalog
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
    private var activePage = 0
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
    private val perceptionRefresh = object : Runnable {
        override fun run() { renderPerception(); perceptionUi.postDelayed(this, 750) }
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
            service = null; render()
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
        top.addView(textLabel("ЛОКАЛЬНО", 11f, muted))
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
        settings.addView(textLabel("Системный жест или кнопка ассистента открывает панель Нокса. Выбор не включает микрофон автоматически; фоновое ожидание имени настраивается отдельно. Действия на заблокированном телефоне запрещены.", 13f, muted))
        settings.addView(textLabel("Голос вне приложения", 19f).apply { typeface = Typeface.DEFAULT_BOLD })
        backgroundButton = addButton(settings, "Включить фоновый голос", false) {
            toggleBackgroundVoice()
        }
        bluetoothInputButton = addButton(settings, "Предпочитать Bluetooth-микрофон", false) {
            service?.toggleBluetoothInput()
        }
        microphoneRouteLabel = textLabel("Источник микрофона пока не определён", 13f, muted)
        settings.addView(microphoneRouteLabel)
        overlayButton = addButton(settings, "Разрешить плавающую кнопку", false) {
            if (Settings.canDrawOverlays(this)) service?.syncOverlay()
            else startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
            render()
        }
        settings.addView(textLabel("После включения Нокс локально распознаёт имя «Нокс». Чтобы дать команду, разблокируй телефон, нажми микрофон и произнеси запрос. Фоновая фраза не сохраняется и не выполняется. Пока Нокс выполняет задачу или говорит, фоновое прослушивание приостановлено. Bluetooth — предпочтительный вход, фактический источник выбирает Android. Переключай фоновый голос здесь или удержанием стрелки отправки. Выключить его можно также в уведомлении.", 13f, muted))
        settings.addView(textLabel("Облачко действий видно только вне Нокса. Нажми «Остановить», чтобы отменить текущую задачу. Для действий нужен доступ к плавающему облачку; голосовые ответы на сообщения настраиваются отдельно.", 13f, muted))
        addButton(settings, "Разрешить агенту управление экраном", false) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
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
        addButton(settings, "Выбрать проверенную каталожную модель") {
            val index = models.selectedItemPosition
            val pkg = catalog[index]
            AlertDialog.Builder(this).setTitle("Проверенная копия " + pkg.name)
                .setMessage("Импортированные и прежние файлы останутся на устройстве. Если проверенной копии нет, потребуется скачать " + String.format(Locale.US, "%.2f", pkg.size / 1e9) + " ГБ.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Продолжить") { _, _ ->
                    saveSelection(index)
                    service?.selectModel(index, gpu.isChecked, vision.isChecked, forceCatalog = true)
                }.show()
        }
        settings.addView(textLabel("Диагностика", 19f).apply { typeface = Typeface.DEFAULT_BOLD })
        addButton(settings, "Сравнительный тест") { service?.benchmark(models.selectedItemPosition, gpu.isChecked, vision.isChecked) }
        addButton(settings, "Журнал тестов") {
            val file = service?.reportFile
            AlertDialog.Builder(this).setTitle("Журнал").setMessage(if (file?.isFile == true) file.readText().takeLast(30000) else "Тесты ещё не запускались").setPositiveButton("Закрыть", null).show()
        }
        addButton(settings, "Поделиться журналом") {
            val file = service?.reportFile
            if (file?.isFile == true) startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, file.readText().takeLast(100000))
            }, "Поделиться результатами"))
        }
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
        root.addView(nav, LinearLayout.LayoutParams(-1,-2))
        matrix = ObsidianMatrix(frame, catalog.map { it.name }, {
            MatrixChoice(service?.loaded?.index ?: models.selectedItemPosition.coerceIn(catalog.indices), effort)
        }, ::applyMatrix)
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

    @Deprecated("Back navigation for native sheet")
    override fun onBackPressed() {
        if (::matrix.isInitialized && matrix.dismiss()) return
        if (activePage != 0) showPage(0) else super.onBackPressed()
    }

    private fun render() {
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

    /** MainActivity is exported; never trust an ACTION or extra alone to start the microphone. */
    private fun consumeAssistantVoice() {
        if (!isVisible || service == null) return
        val ticket = intent?.getStringExtra(AssistantHandoff.EXTRA_TICKET) ?: return
        intent.removeExtra(AssistantHandoff.EXTRA_TICKET)
        if (!AssistantHandoff.gate.consume(ticket, AssistantHandoff.active(this), DeviceInteraction.unlocked(this))) return
        if (service?.busy != false) {
            header.text = "Нокс занят или загружает модель · нажми микрофон после готовности"
            return
        }
        voice(false)
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
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

    override fun onResume() {
        super.onResume()
        isVisible = true
        service?.listener = { render() }
        service?.syncOverlay()
        render()
        consumeAssistantVoice()
    }
    override fun onPause() {
        if (::matrix.isInitialized) matrix.dismiss()
        isVisible = false
        // A delayed handoff must not activate the microphone on a later unrelated visit.
        intent?.removeExtra(AssistantHandoff.EXTRA_TICKET)
        service?.listener = null
        super.onPause()
    }
    override fun onDestroy() {
        service?.listener = null
        if (bound) unbindService(connection)
        service = null
        super.onDestroy()
    }
}
