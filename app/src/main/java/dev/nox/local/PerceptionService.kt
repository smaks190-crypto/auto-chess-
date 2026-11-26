package dev.nox.local

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** Explicit system-approved session; captured media stays in RAM, never drives tools directly. */
internal object LivePerception {
    val buffer = PerceptionBuffer()
    @Volatile var active = false
    @Volatile var status = "Восприятие выключено"
    @Volatile var visualSummary = ""
    @Volatile var frames = 0L
    @Volatile var audioSamples = 0L
    @Volatile var epoch = 0L
    fun clear() { buffer.clear(); visualSummary = ""; frames = 0; audioSamples = 0 }
}

class PerceptionService : Service() {
    companion object {
        const val START = "dev.nox.local.PERCEPTION_START"
        const val STOP = "dev.nox.local.PERCEPTION_STOP"
        const val RESULT = "projection_result"
        const val TOKEN = "projection_token"
        const val UID = "playback_uid"
        private const val CHANNEL = "nox_perception"
        private const val ID = 101
    }

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var videoThread: HandlerThread? = null
    private var videoHandler: Handler? = null
    private var audioThread: Thread? = null
    @Volatile private var audio: AudioRecord? = null
    @Volatile private var running = false
    @Volatile private var generation = 0L
    private var receiverRegistered = false
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != START) { stopSelf(); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY
        val result = intent.getIntExtra(RESULT, Activity.RESULT_CANCELED)
        val token = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(TOKEN, Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(TOKEN)
        val uid = intent.getIntExtra(UID, -1)
        if (result != Activity.RESULT_OK || token == null || uid < 0) {
            stopSelf(); return START_NOT_STICKY
        }
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Нокс — восприятие", NotificationManager.IMPORTANCE_LOW))
            val stop = PendingIntent.getService(this, 1, Intent(this, PerceptionService::class.java).setAction(STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("N.O.X. · Восприятие включено")
                .setContentText("Захват экрана и звука выбранного приложения")
                .setContentIntent(open).setOngoing(true).setVisibility(Notification.VISIBILITY_PRIVATE)
                .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Остановить", stop).build())
                .build()
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(result, token)
                ?: error("Не получено разрешение Android")
            projection = mp
            LivePerception.clear()
            LivePerception.epoch++
            val session = LivePerception.epoch
            generation = session
            running = true
            LivePerception.active = true
            LivePerception.status = "Подключение экрана и звука…"
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
                override fun onCapturedContentResize(width: Int, height: Int) {
                    if (running && display != null) runCatching { resize(width, height) }
                        .onFailure { LivePerception.status = "Ошибка изменения размера экрана"; stopSelf() }
                }
            }, main)
            registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
            val thread = HandlerThread("nox-vision").apply { start() }
            videoThread = thread
            videoHandler = Handler(thread.looper)
            val bounds = getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
            val (w,h) = dimensions(bounds.width(), bounds.height())
            val first = makeReader(w,h,session)
            reader = first
            display = mp.createVirtualDisplay("NoxPerception", w,h, resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, first.surface, null, main)
                ?: error("Не удалось создать виртуальный экран")
            audioThread = Thread({ captureAudio(mp, uid, session) }, "nox-playback-asr").apply { start() }
        } catch (e: Exception) {
            LivePerception.status = "Восприятие: " + (e.message ?: e.javaClass.simpleName)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun dimensions(width: Int, height: Int): Pair<Int,Int> {
        val factor = minOf(1f, 960f / maxOf(width,height).coerceAtLeast(1))
        return (width * factor).roundToInt().coerceAtLeast(2) to (height * factor).roundToInt().coerceAtLeast(2)
    }

    private fun makeReader(w: Int, h: Int, session: Long): ImageReader {
        val result = ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,3)
        var last = 0L
        result.setOnImageAvailableListener({ source ->
            try {
                source.acquireLatestImage()?.use { image ->
                    val now = SystemClock.elapsedRealtime()
                    if (!running || session != LivePerception.epoch || now - last < 250L) return@use
                    last = now
                    val plane = image.planes[0]
                    val strideWidth = plane.rowStride / plane.pixelStride
                    val padded = Bitmap.createBitmap(strideWidth, image.height, Bitmap.Config.ARGB_8888)
                    try {
                        padded.copyPixelsFromBuffer(plane.buffer)
                        val cropped = Bitmap.createBitmap(padded, 0,0,image.width,image.height)
                        try {
                            val jpeg = ByteArrayOutputStream().use { out ->
                                check(cropped.compress(Bitmap.CompressFormat.JPEG, 75, out)); out.toByteArray()
                            }
                            if (running && session == LivePerception.epoch) {
                                LivePerception.buffer.frame(jpeg,now); LivePerception.frames++
                            }
                        } finally { if (cropped !== padded) cropped.recycle() }
                    } finally { padded.recycle() }
                }
            } catch (_: IllegalStateException) { /* reader was replaced or closed */ }
        }, videoHandler)
        return result
    }

    private fun resize(width: Int, height: Int) {
        val (w,h) = dimensions(width,height)
        if (reader?.width == w && reader?.height == h) return
        val next = makeReader(w,h,generation)
        val old = reader
        try {
            display?.resize(w,h,resources.displayMetrics.densityDpi)
            display?.surface = next.surface
            reader = next
            old?.close()
        } catch (e: Exception) { next.close(); throw e }
    }

    @SuppressLint("MissingPermission")
    private fun captureAudio(mp: MediaProjection, uid: Int, session: Long) {
        var record: AudioRecord? = null
        try {
            // Only playback of the explicitly selected UID, never the microphone.
            val config = AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUid(uid)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
            val rate = 16000
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(min > 0)
            val r = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(min,6400)).build()
            record = r; audio = r
            check(r.state == AudioRecord.STATE_INITIALIZED) { "Звук приложения недоступен" }
            Model(SilentRecognizer.prepareSharedModel(this).absolutePath).use { model ->
                Recognizer(model,rate.toFloat()).use { asr ->
                    if (!running || session != LivePerception.epoch) return
                    r.startRecording()
                    check(r.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                    LivePerception.status = "Экран и потоковая речь · локально"
                    val samples = ShortArray(1600)
                    while (running && session == LivePerception.epoch) {
                        val n = r.read(samples,0,samples.size,AudioRecord.READ_NON_BLOCKING)
                        if (n < 0) error("Ошибка потока звука: " + n)
                        if (n == 0) { Thread.sleep(20); continue }
                        if (!running || session != LivePerception.epoch) break
                        LivePerception.audioSamples += n
                        if (asr.acceptWaveForm(samples,n)) {
                            LivePerception.buffer.finalText(JSONObject(asr.result).optString("text"))
                        } else {
                            LivePerception.buffer.partial(JSONObject(asr.partialResult).optString("partial"))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (running && session == LivePerception.epoch) LivePerception.status = "Экран активен, звук недоступен: " + (e.message ?: e.javaClass.simpleName)
        } finally {
            record?.let { runCatching { it.stop() }; runCatching { it.release() } }
            if (audio === record) audio = null
        }
    }

    override fun onDestroy() {
        running = false
        LivePerception.active = false
        LivePerception.epoch++
        LivePerception.clear()
        LivePerception.status = "Восприятие выключено"
        audio?.let { runCatching { it.stop() } }
        display?.release(); display = null
        reader?.close(); reader = null
        videoThread?.quitSafely(); videoThread = null; videoHandler = null
        projection?.stop(); projection = null
        if (receiverRegistered) { runCatching { unregisterReceiver(screenOff) }; receiverRegistered = false }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
