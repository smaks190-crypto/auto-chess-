package dev.nox.local

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream

/** Local PCM capture. No system speech service, audio files, or network calls. */
internal class SilentRecognizer(private val context: Context) : AutoCloseable {
    interface Listener {
        fun onReady()
        fun onRouteChanged(route: String) {}
        fun onLevel(level: Float)
        fun onPartial(text: String)
        fun onResult(text: String)
        fun onError(message: String, permanent: Boolean)
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val epoch = AtomicLong()
    private val closed = AtomicBoolean(false)
    // Model and recognizer are owned exclusively by worker.
    private var model: Model? = null

    private fun current(token: Long) = !closed.get() && epoch.get() == token
    private fun post(token: Long, callback: () -> Unit) {
        main.post { if (current(token)) callback() }
    }

    fun start(listener: Listener, acceptFinal: (String) -> Boolean = { true },
              emitLive: Boolean = true, preferBluetooth: Boolean = false) {
        check(!closed.get())
        val token = epoch.incrementAndGet()
        worker.execute { if (current(token)) capture(token, listener, acceptFinal, emitLive, preferBluetooth) }
    }

    fun stop() { epoch.incrementAndGet() }

    @SuppressLint("MissingPermission")
    private fun capture(token: Long, listener: Listener, acceptFinal: (String) -> Boolean,
                        emitLive: Boolean, preferBluetooth: Boolean) {
        var audio: AudioRecord? = null
        var opened = false
        try {
            val active = model ?: Model(prepareModel().absolutePath).also { model = it }
            if (!current(token)) return
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(min > 0) { "Неподдерживаемый формат микрофона" }
            val recorder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(min, 6400))
                .build()
            audio = recorder
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Микрофон недоступен" }
            // Request an available input only. Never change the system's communication mode
            // or steal an active call route. The actual routedDevice is reported below.
            if (preferBluetooth) runCatching {
                context.getSystemService(AudioManager::class.java)
                    ?.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    ?.firstOrNull { it.isSource && isBluetoothInput(it.type) }
                    ?.let { recorder.setPreferredDevice(it) }
            }
            Recognizer(active, RATE.toFloat()).use { recognizer ->
                if (!current(token)) return
                recorder.startRecording()
                check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Микрофон занят" }
                opened = true
                post(token) { listener.onReady() }
                val buffer = ShortArray(1600)
                var lastPartial = ""
                var lastRoute: String? = null
                var nextRouteCheck = 0L
                var lastAudio = android.os.SystemClock.elapsedRealtime()
                while (current(token)) {
                    val count = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                    if (count < 0) throw IOException("Ошибка чтения микрофона: " + count)
                    if (count == 0) {
                        check(android.os.SystemClock.elapsedRealtime() - lastAudio < 5000) { "Микрофон не передаёт звук" }
                        Thread.sleep(20)
                        continue
                    }
                    lastAudio = android.os.SystemClock.elapsedRealtime()
                    if (lastAudio >= nextRouteCheck) {
                        nextRouteCheck = lastAudio + 1000L
                        val type = runCatching { recorder.routedDevice?.type }.getOrNull()
                        val route = when {
                            type == null -> "Источник не определён"
                            isBluetoothInput(type) -> "Bluetooth-микрофон"
                            type == AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Микрофон телефона"
                            else -> "Внешний микрофон"
                        }
                        val label = if (preferBluetooth && type != null && !isBluetoothInput(type))
                            "$route · Bluetooth не выбран Android" else route
                        if (label != lastRoute) {
                            lastRoute = label
                            post(token) { listener.onRouteChanged(label) }
                        }
                    }
                    if (emitLive) {
                        val level = VoiceMeter.level(buffer, count)
                        post(token) { listener.onLevel(level) }
                    }
                    if (recognizer.acceptWaveForm(buffer, count)) {
                        val result = JSONObject(recognizer.result).optString("text").trim()
                        // Standby rejects unrelated speech on the capture worker.
                        // Never send rejected utterances or live partials to the service.
                        if (result.isNotEmpty() && acceptFinal(result)) {
                            // Release the microphone before sending the result to the service.
                            recorder.stop()
                            recorder.release()
                            audio = null
                            post(token) { listener.onResult(result) }
                            return
                        }
                        if (lastPartial.isNotEmpty()) {
                            lastPartial = ""
                            post(token) { listener.onPartial("") }
                        }
                    } else if (emitLive) {
                        val partial = JSONObject(recognizer.partialResult).optString("partial").trim()
                        if (partial != lastPartial) {
                            lastPartial = partial
                            post(token) { listener.onPartial(partial) }
                        }
                    }
                }
            }
        } catch (error: Exception) {
            val message = error.message ?: "Ошибка локального распознавания"
            val permanent = model == null || error is SecurityException
            post(token) { listener.onError(message, permanent) }
        } catch (error: LinkageError) {
            post(token) { listener.onError("Движок Vosk недоступен: " + error.javaClass.simpleName, true) }
        } finally {
            audio?.let {
                if (opened) runCatching { it.stop() }
                runCatching { it.release() }
            }
        }
    }

    private fun prepareModel(): File = prepareSharedModel(context)

    companion object {
        private const val RATE = 16000
        private fun isBluetoothInput(type: Int): Boolean =
            type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == AudioDeviceInfo.TYPE_BLE_HEADSET
        @Synchronized internal fun prepareSharedModel(context: Context): File {
        val root = File(context.filesDir, "asr-ru-0.22")
        val ready = File(root, ".ready")
        val modelDir = File(root, "vosk-model-small-ru-0.22")
        if (ready.isFile && modelDir.isDirectory) return modelDir
        val stage = File(context.filesDir, "asr-ru-0.22-stage")
        check(stage.deleteRecursively() && stage.mkdirs()) { "Не удалось подготовить каталог речи" }
        try {
            val base = stage.canonicalPath + File.separator
            var total = 0L
            context.assets.open("vosk-ru-0.22.zip").use { input ->
                ZipInputStream(input).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val target = File(stage, entry.name).canonicalFile
                        check(target.path.startsWith(base)) { "Некорректный путь в модели речи" }
                        if (entry.isDirectory) {
                            check(target.isDirectory || target.mkdirs())
                        } else {
                            val parent = checkNotNull(target.parentFile)
                            check(parent.isDirectory || parent.mkdirs())
                            target.outputStream().use { out ->
                                val buffer = ByteArray(65536)
                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    check(total <= 256L * 1024 * 1024) { "Слишком большой архив модели речи" }
                                    out.write(buffer, 0, count)
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
            val extracted = File(stage, "vosk-model-small-ru-0.22")
            check(File(extracted, "am/final.mdl").isFile && File(extracted, "conf/model.conf").isFile) {
                "Встроенная модель речи повреждена"
            }
            File(stage, ".ready").writeText("ru-0.22")
            check(root.deleteRecursively() && stage.renameTo(root)) { "Не удалось установить модель речи" }
            return modelDir
        } finally { stage.deleteRecursively() }
    }

    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stop()
        worker.execute { runCatching { model?.close() }; model = null }
        worker.shutdown()
    }

}
