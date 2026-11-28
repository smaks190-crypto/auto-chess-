package dev.nox.local

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Own alarm only, not a reader or editor of the user's existing Clock alarms. */
internal object NativeAlarm {
    private const val FIRE = "dev.nox.local.NATIVE_ALARM_FIRE"
    private const val PREFS = "native_alarm"
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun manager(c: Context) = requireNotNull(c.getSystemService(AlarmManager::class.java))
    private fun operation(c: Context, at: Long): PendingIntent =
        PendingIntent.getBroadcast(c, 114, Intent(c, NativeAlarmReceiver::class.java)
            .setAction(FIRE).putExtra("at", at), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun install(c: Context, at: Long) {
        val show = PendingIntent.getActivity(c, 115, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager(c).setAlarmClock(AlarmManager.AlarmClockInfo(at, show), operation(c, at))
    }
    fun schedule(c: Context, command: NativeCommand.Alarm): String {
        if (!manager(c).canScheduleExactAlarms()) return "Будильник не установлен. Разблокируй телефон и скажи «разрешение будильника», затем разреши точные сигналы Android."
        if (c.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() != true)
            return "Будильник не установлен. Разреши уведомления Нокса, чтобы видеть и останавливать сигнал."
        if (prefs(c).getLong("at", 0L) > 0L) return "У Нокса уже есть будильник. Сначала отмени его командой «отмени будильник Нокса»."
        val next = NativeCommands.nextAlarm(command, ZonedDateTime.now())
        val at = next.toInstant().toEpochMilli()
        val saved = prefs(c).edit().putLong("at", at).putInt("hour", command.hour).putInt("minute", command.minute)
            .putString("days", command.days.joinToString(",") { it.name }).commit()
        if (!saved) return "Не удалось сохранить будильник. Будильник не установлен."
        return try {
            install(c, at)
            android.util.Log.i("NoxAlarm", "schedule_accepted")
            "Android принял будильник Нокса: " + next.format(DateTimeFormatter.ofPattern("EEEE, d MMMM, HH:mm", Locale("ru"))) +
                if (command.days.isEmpty()) "." else ". С повторением в выбранные дни."
        } catch (_: Exception) {
            prefs(c).edit().clear().commit()
            "Не удалось установить будильник Нокса."
        }
    }
    fun cancel(c: Context): String {
        val at = prefs(c).getLong("at", 0L)
        if (at == 0L && !NativeAlarmRingService.ringing) return "Активного будильника Нокса нет."
        manager(c).cancel(operation(c, at))
        if (!prefs(c).edit().clear().commit()) return "Сигнал отменён, не удалось очистить запись. Проверь настройки."
        c.stopService(Intent(c, NativeAlarmRingService::class.java))
        return "Будильник Нокса отменён. Другие будильники не изменены."
    }
    private fun repeated(c: Context): NativeCommand.Alarm? {
        val p = prefs(c)
        val days = p.getString("days", "").orEmpty().split(",").mapNotNull {
            runCatching { DayOfWeek.valueOf(it) }.getOrNull()
        }.toSet()
        if (days.isEmpty()) return null
        val h = p.getInt("hour", -1); val m = p.getInt("minute", -1)
        if (h !in 0..23 || m !in 0..59) return null
        return NativeCommand.Alarm(h, m, days, false)
    }
    private fun reschedule(c: Context, at: Long) {
        if (!manager(c).canScheduleExactAlarms()) return
        if (prefs(c).edit().putLong("at", at).commit()) {
            runCatching { install(c, at) }.onFailure {
                prefs(c).edit().clear().commit()
                android.util.Log.w("NoxAlarm", "reschedule_failed")
            }
        }
    }
    fun receive(c: Context, intent: Intent) {
        val at = prefs(c).getLong("at", 0L)
        if (at <= 0L) return
        val now = System.currentTimeMillis()
        if (intent.action == FIRE) {
            if (intent.getLongExtra("at", -1L) != at || now + 1000L < at) return
            val repeat = repeated(c)
            if (repeat == null) prefs(c).edit().clear().commit()
            else runCatching {
                reschedule(c, NativeCommands.nextAlarm(repeat, ZonedDateTime.now().plusSeconds(1)).toInstant().toEpochMilli())
            }.onFailure { android.util.Log.w("NoxAlarm", "repeat_schedule_failed") }
            try {
                c.startForegroundService(Intent(c, NativeAlarmRingService::class.java).setAction(NativeAlarmRingService.RING))
            } catch (_: Exception) {
                // Notification channel carries an alarm sound even if service start is rejected.
                NativeAlarmRingService.postFallback(c)
            }
        } else if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) {
            val repeat = repeated(c)
            if (repeat != null) reschedule(c, NativeCommands.nextAlarm(repeat, ZonedDateTime.now()).toInstant().toEpochMilli())
            else if (at > now) reschedule(c, at)
            else prefs(c).edit().clear().commit()
        }
    }
}

class NativeAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching { NativeAlarm.receive(context, intent) }.onFailure {
            android.util.Log.w("NoxAlarm", "receiver_failed " + it.javaClass.simpleName)
        }
    }
}

class NativeAlarmRingService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var tone: Ringtone? = null
    private var wake: PowerManager.WakeLock? = null
    private val stop = Runnable { finish() }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != RING) { finish(); return START_NOT_STICKY }
        if (ringing) return START_NOT_STICKY
        try {
            startForeground(ID, notification(this, false), if (Build.VERSION.SDK_INT >= 34)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
            wake = getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:alarm")?.apply { setReferenceCounted(false); acquire(125_000L) }
            tone = requireNotNull(RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)))
            tone?.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            tone?.isLooping = true
            tone?.play()
            ringing = true
            android.util.Log.i("NoxAlarm", "ring_started")
            main.postDelayed(stop, 120_000L)
        } catch (_: Exception) {
            postFallback(this)
            finish()
        }
        return START_NOT_STICKY
    }
    private fun finish() { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() {
        main.removeCallbacks(stop)
        tone?.let { runCatching { it.stop() } }; tone = null
        wake?.let { if (it.isHeld) runCatching { it.release() } }; wake = null
        ringing = false
        super.onDestroy()
    }
    companion object {
        internal const val RING = "dev.nox.local.RING_NATIVE_ALARM"
        private const val STOP = "dev.nox.local.STOP_NATIVE_ALARM"
        private const val ID = 114
        internal var ringing = false
            private set
        private fun notification(c: Context, fallback: Boolean): Notification {
            val channel = if (fallback) "nox_alarm_fallback" else "nox_alarm_ring"
            val nm = requireNotNull(c.getSystemService(NotificationManager::class.java))
            nm.createNotificationChannel(NotificationChannel(channel, "Будильник Нокса", NotificationManager.IMPORTANCE_HIGH).apply {
                if (fallback) setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                else setSound(null, null)
            })
            val builder = Notification.Builder(c, channel).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Будильник Нокса").setContentText("Пора вставать")
                .setCategory(Notification.CATEGORY_ALARM).setVisibility(Notification.VISIBILITY_PUBLIC)
            if (!fallback) {
                val stop = PendingIntent.getService(c, 114, Intent(c, NativeAlarmRingService::class.java).setAction(STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                builder.setOngoing(true).addAction(Notification.Action.Builder(null, "Остановить сигнал", stop).build())
            } else builder.setAutoCancel(true)
            return builder.build()
        }
        internal fun postFallback(c: Context) {
            runCatching { c.getSystemService(NotificationManager::class.java)?.notify(ID + 1, notification(c, true)) }
        }
    }
}
