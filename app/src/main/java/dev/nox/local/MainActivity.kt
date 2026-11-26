package dev.nox.local

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.*
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val model = LocalModel()
    private lateinit var status: TextView
    private lateinit var answer: TextView
    private lateinit var command: EditText
    private lateinit var gpu: Switch
    private lateinit var speak: Switch
    private val controls = mutableListOf<View>()
    private var image: File? = null
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var offlineTts = false
    private var busy = false
    private var destroyed = false
    private val modelFile get() = File(filesDir, "model.litertlm")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 32)
        }
        setContentView(ScrollView(this).apply { addView(root) })
        fun label(text: String, size: Float = 16f) = TextView(this).apply {
            this.text = text; textSize = size
            root.addView(this)
        }
        fun button(text: String, action: () -> Unit) = Button(this).apply {
            this.text = text; setOnClickListener { action() }
            root.addView(this); controls.add(this)
        }
        label("N.O.X.  /  LOCAL", 28f)
        label("Экспериментальная версия • без управления телефоном")
        status = label("Импортируйте файл .litertlm или загрузите сохранённую модель.")
        gpu = Switch(this).apply { text = "GPU для языка (выключено: CPU)"; isChecked = true; root.addView(this) }
        controls.add(gpu)
        button("Импорт модели") { pick(10, "*/*") }
        button("Загрузить сохранённую модель") { load(null) }
        button("Выбрать изображение") { pick(11, "image/*") }
        button("Убрать изображение") { image?.delete(); image = null; status.text = "Режим текста" }
        command = EditText(this).apply {
            hint = "Что сделать или проанализировать?"
            minLines = 2
            root.addView(this)
        }
        button("Голосовая команда") { startVoice() }
        speak = Switch(this).apply { text = "Озвучивать ответ офлайн"; root.addView(this) }
        button("Спросить Нокс") { ask() }
        answer = label("", 18f)
        initTts()
    }

    private fun pick(request: Int, mime: String) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
        }, request)
    }

    @Deprecated("Uses Activity result API for this minimal prototype")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (result != RESULT_OK || busy) return
        val uri = data?.data ?: return
        when (request) {
            10 -> load(uri)
            11 -> prepareImage(uri)
        }
    }

    private fun runWork(label: String, block: () -> String, finished: (String) -> Unit = {}) {
        if (busy) return
        busy = true
        controls.forEach { it.isEnabled = false }
        status.text = label
        worker.execute {
            val result = runCatching(block)
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                busy = false
                controls.forEach { it.isEnabled = true }
                result.fold(onSuccess = { status.text = "Готово"; finished(it) },
                    onFailure = { status.text = "Ошибка: " + (it.message ?: it.javaClass.simpleName) })
            }
        }
    }

    private fun load(uri: Uri?) {
        val useGpu = gpu.isChecked
        runWork("Подготовка и загрузка модели…", {
            if (uri != null) {
                val tmp = File(filesDir, "model.import")
                try {
                    contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "Не удалось открыть файл" }
                        tmp.outputStream().use { output -> input.copyTo(output) }
                    }
                    require(tmp.length() > 0) { "Пустой файл" }
                    model.close()
                    check(tmp.renameTo(modelFile)) { "Не удалось сохранить модель" }
                } finally { tmp.delete() }
            }
            model.load(modelFile, useGpu)
            "Модель загружена локально"
        }, { status.text = it })
    }

    private fun prepareImage(uri: Uri) {
        runWork("Подготовка изображения…", {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
            require(opts.outWidth > 0 && opts.outHeight > 0) { "Неизвестный формат изображения" }
            var sample = 1
            while (maxOf(opts.outWidth, opts.outHeight) / sample > 1280) sample *= 2
            val bitmap = contentResolver.openInputStream(uri).use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: error("Не удалось прочитать изображение")
            val file = File(cacheDir, "observation.jpg")
            try {
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            } finally { bitmap.recycle() }
            file.absolutePath
        }, { image = File(it); status.text = "Изображение подготовлено" })
    }

    private fun ask() {
        val text = command.text.toString()
        val currentImage = image
        val say = speak.isChecked
        runWork("Локальный инференс…", {
            val start = System.nanoTime()
            val output = model.analyze(text, currentImage)
            val millis = (System.nanoTime() - start) / 1_000_000
            "$millis мс\n\n$output"
        }, {
            answer.text = it
            if (say && offlineTts) tts?.speak(it.substringAfter("\n\n"), TextToSpeech.QUEUE_FLUSH, null, "nox")
            else if (say) status.text = "Офлайн-голос русского языка недоступен"
        })
    }

    private fun initTts() {
        tts = TextToSpeech(this) { state ->
            if (state == TextToSpeech.SUCCESS && !destroyed) {
                val engine = tts
                val voice = engine?.voices?.firstOrNull {
                    it.locale.language == "ru" && !it.isNetworkConnectionRequired
                }
                if (voice != null) offlineTts = engine?.setVoice(voice) == TextToSpeech.SUCCESS
            }
        }
    }

    private fun startVoice() {
        if (busy) return
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            status.text = "Системное офлайн-распознавание недоступно. Используйте текст."
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 12)
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { status.text = "Слушаю…" }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { status.text = "Распознаю…" }
                override fun onError(error: Int) { status.text = "Распознавание не удалось: $error" }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (text.isNullOrBlank()) status.text = "Нет команды"
                    else { command.setText(text); status.text = "Команда распознана. Нажмите «Спросить Нокс»." }
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            })
        }
    }

    override fun onDestroy() {
        destroyed = true
        recognizer?.destroy()
        tts?.stop(); tts?.shutdown()
        worker.execute { runCatching { model.close() } }
        worker.shutdown()
        super.onDestroy()
    }
}
