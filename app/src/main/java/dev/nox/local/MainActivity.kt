package dev.nox.local

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
    private lateinit var deep: Switch
    private lateinit var modelButton: Button
    private lateinit var cancel: Button
    private lateinit var progress: ProgressBar
    private lateinit var backgroundButton: Button
    private lateinit var overlayButton: Button
    private lateinit var details: TextView
    private lateinit var openSettings: Button
    private val workControls = mutableListOf<View>()
    private var lastChat = ""
    private var lastTurns: List<NoxService.Turn>? = null
    private var partialView: TextView? = null
    private var pendingVoice = false
    private var pendingBackground = false
    private var importIndex = 0
    private val bg = 0xFF08090C.toInt()
    private val surface = 0xFF171922.toInt()
    private val ink = 0xFFF0EFF5.toInt()
    private val muted = 0xFFA8A8B5.toInt()
    private val accent = 0xFFB6A1FF.toInt()
    private val prefs by lazy { getSharedPreferences("nox_ui", MODE_PRIVATE) }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as? NoxService.LocalBinder)?.service ?: return
            service = s
            s.listener = { render() }
            val loaded = s.loaded
            if (loaded != null) {
                models.setSelection(loaded.index)
                gpu.isChecked = loaded.gpu
                vision.isChecked = loaded.vision
            } else {
                val index = models.selectedItemPosition
                if (index in catalog.indices && !s.busy && !s.listening && s.modelFile(index).isFile) {
                    s.selectModel(index, gpu.isChecked, vision.isChecked)
                }
            }
            render()
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
        setContentView(root)
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(android.view.WindowInsets.Type.ime())
            view.setPadding(dp(16) + bars.left, dp(12) + bars.top, dp(16) + bars.right, dp(10) + maxOf(bars.bottom, ime.bottom))
            insets
        }
        root.requestApplyInsets()
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val brand = textLabel("N.O.X.", 24f).apply { typeface = Typeface.DEFAULT_BOLD }
        top.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        openSettings = button("Настройки") { toggleSettings() }
        top.addView(openSettings, LinearLayout.LayoutParams(-2, dp(48)))
        root.addView(top)
        header = textLabel("Локальный ассистент", 13f, accent)
        root.addView(header)

        chatPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chatScroll = ScrollView(this).apply { isFillViewport = true }
        messages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM }
        chatScroll.addView(messages, ScrollView.LayoutParams(-1, -2))
        chatPage.addView(chatScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        attachment = textLabel("", 13f, muted).apply {
            visibility = View.GONE; setOnClickListener { service?.removeImage() }
        }
        chatPage.addView(attachment)
        val composer = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; background = panel(surface, 24)
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        val attach = button("+") { pick(11, "image/*") }.apply { contentDescription = "Прикрепить изображение" }
        composer.addView(attach, LinearLayout.LayoutParams(dp(44), dp(48))); workControls.add(attach)
        input = EditText(this).apply {
            hint = "Сообщение Ноксу"; setHintTextColor(muted); setTextColor(ink); textSize = 16f
            background = null; maxLines = 4; setPadding(dp(4), dp(6), dp(4), dp(6))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        composer.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        mic = button("◉") { voice(false) }.apply { contentDescription = "Голосовая команда"; setTextColor(accent) }
        composer.addView(mic, LinearLayout.LayoutParams(dp(48), dp(48)))
        send = button("↑") {
            val s = service ?: return@button
            val command = input.text.toString()
            if (command.isNotBlank() && !s.busy && !s.listening) {
                s.ask(command, s.attachedImage, tts.isChecked, deep.isChecked)
                input.text.clear()
            }
        }.apply { contentDescription = "Отправить"; setTextColor(bg); background = panel(accent, 24) }
        composer.addView(send, LinearLayout.LayoutParams(dp(48), dp(48)))
        chatPage.addView(composer, LinearLayout.LayoutParams(-1, -2))
        root.addView(chatPage, LinearLayout.LayoutParams(-1, 0, 1f))

        settingsPage = ScrollView(this).apply { visibility = View.GONE }
        val settings = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        settingsPage.addView(settings)
        root.addView(settingsPage, LinearLayout.LayoutParams(-1, 0, 1f))
        settings.addView(textLabel("Голос вне приложения", 19f).apply { typeface = Typeface.DEFAULT_BOLD })
        backgroundButton = addButton(settings, "Включить фоновый голос", false) {
            if (service?.background == true) service?.disableBackground() else voice(true)
        }
        overlayButton = addButton(settings, "Разрешить плавающую кнопку", false) {
            if (Settings.canDrawOverlays(this)) service?.syncOverlay()
            else startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
            render()
        }
        settings.addView(textLabel("Нажми круг N поверх другого приложения или «Говорить» в уведомлении. Микрофон не слушает постоянно.", 13f, muted))
        addButton(settings, "Разрешить команды «Назад» и «Домой»", false) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        settings.addView(textLabel("Команды: «открой Telegram», «громче», «тише», «назад», «на главный экран». Произвольные нажатия не поддерживаются.", 13f, muted))

        settings.addView(textLabel("Модель", 19f).apply { typeface = Typeface.DEFAULT_BOLD })
        models = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, catalog.map { it.name })
            setSelection(prefs.getInt("model_index", 0).coerceIn(catalog.indices))
        }
        settings.addView(models, LinearLayout.LayoutParams(-1, dp(52))); workControls.add(models)
        gpu = Switch(this).apply { text = "GPU для языка"; setTextColor(ink); isChecked = prefs.getBoolean("gpu", true) }
        vision = Switch(this).apply { text = "Анализ изображений"; setTextColor(ink); isChecked = prefs.getBoolean("vision", true) }
        tts = Switch(this).apply { text = "Озвучивать текстовые ответы"; setTextColor(ink); isChecked = false }
        deep = Switch(this).apply { text = "Глубокий анализ (медленнее)"; setTextColor(ink); isChecked = false }
        settings.addView(gpu); settings.addView(vision); settings.addView(tts); settings.addView(deep)
        workControls.add(gpu); workControls.add(vision)
        modelButton = addButton(settings, "Скачать и запустить") { selectModel() }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        settings.addView(progress)
        cancel = addButton(settings, "Отменить скачивание", false) { service?.cancelDownload() }.apply { visibility = View.GONE }
        addButton(settings, "Импортировать файл вручную") { importIndex = models.selectedItemPosition; pick(10, "*/*") }
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
        bound = bindService(Intent(this, NoxService::class.java), connection, BIND_AUTO_CREATE)
        render()
    }

    private fun toggleSettings() {
        val show = settingsPage.visibility != View.VISIBLE
        settingsPage.visibility = if (show) View.VISIBLE else View.GONE
        chatPage.visibility = if (show) View.GONE else View.VISIBLE
        openSettings.text = if (show) "Чат" else "Настройки"
        render()
    }

    private fun render() {
        val s = service
        val index = models.selectedItemPosition.coerceIn(catalog.indices)
        val pkg = catalog[index]
        val busy = s == null || s.busy || s.listening
        header.text = if (s == null) "Подключение…" else s.status
        details.text = if (s == null) "" else (s.loaded?.let { catalog[it.index].name + if (it.gpu) " · GPU" else " · CPU" } ?: "Модель не загружена") +
            (s.elapsedMs?.let { "\nПоследний ответ: " + it + " мс" } ?: "")
        val exists = s?.modelFile(index)?.isFile == true
        modelButton.text = if (exists) "Запустить " + pkg.name else "Скачать и запустить · " +
            String.format(Locale.US, "%.2f", pkg.size / 1e9) + " ГБ"
        backgroundButton.text = if (s?.background == true) "Выключить фоновый голос" else "Включить фоновый голос"
        overlayButton.text = if (Settings.canDrawOverlays(this)) "Плавающая кнопка разрешена" else "Разрешить плавающую кнопку"
        workControls.forEach { it.isEnabled = !busy }
        gpu.isEnabled = !busy && !pkg.cpuOnly
        vision.isEnabled = !busy && pkg.vision
        send.isEnabled = !busy; mic.isEnabled = !busy
        progress.visibility = if (s?.progress != null) View.VISIBLE else View.GONE
        progress.progress = s?.progress ?: 0
        cancel.visibility = progress.visibility
        attachment.text = "Изображение прикреплено · убрать"
        attachment.visibility = if (s?.attachedImage != null) View.VISIBLE else View.GONE
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
                messages.addView(textLabel("Я Нокс. Чем помочь?", 24f))
                messages.addView(textLabel("Для начала выбери модель в настройках. Простые команды работают и без модели.", 14f, muted))
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
        if (result != RESULT_OK) return
        val uri = data?.data ?: return
        when (request) {
            10 -> { saveSelection(importIndex); service?.selectModel(importIndex, gpu.isChecked, vision.isChecked, uri) }
            11 -> service?.prepareImage(uri)
        }
    }

    override fun onResume() { super.onResume(); service?.listener = { render() }; service?.syncOverlay(); render() }
    override fun onPause() { service?.listener = null; super.onPause() }
    override fun onDestroy() {
        service?.listener = null
        if (bound) unbindService(connection)
        service = null
        super.onDestroy()
    }
}
