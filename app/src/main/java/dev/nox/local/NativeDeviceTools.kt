package dev.nox.local

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CompletableFuture

/** All tools use a fixed command type. The model cannot supply arbitrary intents or actions. */
internal object NativeDeviceTools {
    private val main = Handler(Looper.getMainLooper())
    private var started: Long? = null
    private var accumulated = 0L

    fun execute(c: Context, cmd: NativeCommand, unlocked: Boolean, allowed: () -> Boolean): CompletableFuture<String> {
        if (!allowed()) return CompletableFuture.completedFuture("Команда отменена.")
        if (cmd is NativeCommand.Torch) return torch(c, cmd.enabled, allowed)
        return CompletableFuture.completedFuture(runCatching {
            when (cmd) {
                is NativeCommand.Alarm -> NativeAlarm.schedule(c, cmd)
                NativeCommand.CancelAlarm -> NativeAlarm.cancel(c)
                NativeCommand.AlarmInvalid -> "Укажи точное время: например, «поставь будильник завтра на 7:30»."
                NativeCommand.AlarmPermission -> {
                    if (!unlocked) "Для выдачи разрешения разблокируй телефон."
                    else if (c.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true)
                        "Разрешение на точные будильники уже есть."
                    else {
                        c.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:" + c.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        "Открываю разрешение Android. После его выдачи повтори команду."
                    }
                }
                NativeCommand.Battery -> {
                    val level = c.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    if (level != null && level in 0..100) "Заряд телефона: $level%." else "Заряд недоступен."
                }
                NativeCommand.Time -> "Сейчас " + ZonedDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm")) + "."
                NativeCommand.Date -> ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale("ru")))
                is NativeCommand.Volume -> {
                    val audio = requireNotNull(c.getSystemService(AudioManager::class.java))
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        if (cmd.delta > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
                    "Громкость медиа: " + audio.getStreamVolume(AudioManager.STREAM_MUSIC) +
                        " из " + audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) + "."
                }
                is NativeCommand.Media -> {
                    val key = when (cmd.action) {
                        "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
                        "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                        "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                        "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                        else -> error("Неизвестная команда")
                    }
                    val audio = requireNotNull(c.getSystemService(AudioManager::class.java))
                    audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
                    audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
                    "Команда передана медиаплееру. Воспроизведение не подтверждено."
                }
                is NativeCommand.Stopwatch -> stopwatch(cmd.action)
                is NativeCommand.Torch -> error("Фонарик использует отдельный callback")
            }
        }.getOrElse { "Android не подтвердил выполнение команды." })
    }

    private fun stopwatch(action: String): String {
        val now = SystemClock.elapsedRealtime()
        when (action) {
            "start" -> if (started == null) started = now
            "stop" -> { started?.let { accumulated += now - it }; started = null }
            "reset" -> { accumulated = 0L; started = null }
            "read" -> Unit
            else -> error("Неизвестная команда")
        }
        val s = (accumulated + (started?.let { now - it } ?: 0L)) / 1000L
        return "Секундомер " + (if (started != null) "идёт: " else "остановлен: ") +
            (s / 3600).toString() + " ч " + (s / 60 % 60) + " мин " + (s % 60) + " сек."
    }

    private fun torch(c: Context, enabled: Boolean, allowed: () -> Boolean): CompletableFuture<String> {
        val result = CompletableFuture<String>()
        val manager = c.getSystemService(CameraManager::class.java)
        if (manager == null) return CompletableFuture.completedFuture("Фонарик недоступен.")
        val id = runCatching { manager.cameraIdList.firstOrNull {
            val info = manager.getCameraCharacteristics(it)
            info.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                info.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } }.getOrNull() ?: return CompletableFuture.completedFuture("Фонарик недоступен.")
        var sent = false
        val callback = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, on: Boolean) {
                if (cameraId != id || result.isDone) return
                if (!allowed() && !sent) {
                    result.complete("Команда отменена.")
                } else if (on == enabled) {
                    result.complete(if (on) "Фонарик включён." else "Фонарик выключен.")
                } else if (!sent) {
                    // First callback establishes actual initial state; the next matching
                    // callback proves the single requested mutation.
                    sent = true
                    try { manager.setTorchMode(id, enabled) }
                    catch (_: Exception) { result.complete("Android не разрешил управление фонариком.") }
                }
                if (result.isDone) runCatching { manager.unregisterTorchCallback(this) }
            }
            override fun onTorchModeUnavailable(cameraId: String) {
                if (cameraId == id) {
                    result.complete("Фонарик недоступен.")
                    runCatching { manager.unregisterTorchCallback(this) }
                }
            }
        }
        try {
            manager.registerTorchCallback(callback, main)
            main.postDelayed({
                runCatching { manager.unregisterTorchCallback(callback) }
                result.complete(if (sent) "Команда фонарику отправлена, состояние не подтверждено."
                    else "Фонарик не изменён: Android не сообщил его состояние.")
            }, 2000L)
        } catch (_: Exception) {
            runCatching { manager.unregisterTorchCallback(callback) }
            result.complete("Android не разрешил управление фонариком.")
        }
        return result
    }
}
