package dev.nox.local

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.*
import java.util.concurrent.CompletableFuture

/** One finite, user-requested local timer. It never launches another app or unlocks Android. */
class NativeTimerService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var wake: PowerManager.WakeLock? = null
    private var ringtone: Ringtone? = null
    private var deadline = 0L
    private val fire = Runnable { ring() }
    private val silence = Runnable { finishTimer() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) {
            finishTimer()
            return START_NOT_STICKY
        }
        val ticket = pending
        if (ticket == null || intent?.getLongExtra("ticket", -1L) != ticket.id) {
            if (deadline == 0L) stopSelf(startId)
            return START_NOT_STICKY
        }
        pending = null
        if (!ticket.allowed() || SystemClock.elapsedRealtime() >= ticket.expires || deadline != 0L) {
            ticket.reply.complete("Таймер не установлен: запрос отменён или уже идёт отсчёт.")
            if (deadline == 0L) stopSelf(startId)
            return START_NOT_STICKY
        }
        try {
            val pm = requireNotNull(getSystemService(PowerManager::class.java))
            val nm = requireNotNull(getSystemService(NotificationManager::class.java))
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Таймер Нокса", NotificationManager.IMPORTANCE_LOW))
            deadline = SystemClock.elapsedRealtime() + ticket.seconds * 1000L
            val cancel = PendingIntent.getService(this, 0, Intent(this, NativeTimerService::class.java).setAction(CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Таймер Нокса")
                .setContentText("Идёт отсчёт")
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true).setOnlyAlertOnce(true)
                .setWhen(System.currentTimeMillis() + ticket.seconds * 1000L)
                .setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
                .addAction(Notification.Action.Builder(null, "Отменить", cancel).build()).build()
            startForeground(ID, notification, if (Build.VERSION.SDK_INT >= 34)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:timer").apply {
                setReferenceCounted(false)
                acquire(ticket.seconds * 1000L + 30_000L)
            }
            // A bounded partial wake lock keeps Handler time advancing with display off.
            check(main.postDelayed(fire, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)))
            current = this
            android.util.Log.i("NoxTimer", "started seconds=" + ticket.seconds)
            ticket.reply.complete("Таймер Нокса запущен на " + ticket.seconds + " сек.")
        } catch (_: Exception) {
            finishTimer()
            ticket.reply.complete("Не удалось запустить таймер. Таймер не установлен.")
        }
        return START_NOT_STICKY
    }

    private fun ring() {
        if (deadline == 0L) return
        val left = deadline - SystemClock.elapsedRealtime()
        if (left > 0L) { main.postDelayed(fire, left); return }
        android.util.Log.i("NoxTimer", "elapsed")
        ringtone = runCatching {
            RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                ?.apply {
                    audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                    play()
                }
        }.getOrNull()
        main.postDelayed(silence, 15_000L)
    }

    private fun finishTimer() {
        cleanup()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup() {
        main.removeCallbacks(fire); main.removeCallbacks(silence)
        ringtone?.let { runCatching { it.stop() } }; ringtone = null
        wake?.let { if (it.isHeld) runCatching { it.release() } }; wake = null
        deadline = 0L
        if (current === this) current = null
    }

    override fun onDestroy() { cleanup(); super.onDestroy() }

    companion object {
        private const val ID = 112
        private const val CHANNEL = "nox_timer"
        private const val CANCEL = "dev.nox.local.CANCEL_TIMER"
        private data class Ticket(val id: Long, val seconds: Int, val expires: Long,
                                  val allowed: () -> Boolean, val reply: CompletableFuture<String>)
        private var next = 0L
        private var pending: Ticket? = null
        private var current: NativeTimerService? = null

        /** Main-thread only, one timer, no arbitrary Intent arguments. */
        internal fun request(context: Context, seconds: Int, allowed: () -> Boolean): CompletableFuture<String> {
            val answer = CompletableFuture<String>()
            if (seconds !in 1..3600 || !allowed() || pending != null || current != null) {
                answer.complete("Таймер не установлен. Допустим один таймер от 1 секунды до 1 часа.")
                return answer
            }
            val ticket = Ticket(++next, seconds, SystemClock.elapsedRealtime() + 5000L, allowed, answer)
            pending = ticket
            try {
                context.startForegroundService(Intent(context, NativeTimerService::class.java).putExtra("ticket", ticket.id))
                Handler(Looper.getMainLooper()).postDelayed({
                    if (pending === ticket) {
                        pending = null
                        answer.complete("Android не подтвердил запуск таймера.")
                    }
                }, 5000L)
            } catch (_: Exception) {
                pending = null
                answer.complete("Android не разрешил запуск таймера.")
            }
            return answer
        }

        internal fun cancel(): String {
            pending?.reply?.complete("Таймер отменён.")
            val hadPending = pending != null
            pending = null
            val service = current
            service?.finishTimer()
            return if (service != null || hadPending) "Таймер отменён." else "Активного таймера нет."
        }
    }
}
