package dev.nox.local

import android.app.*
import android.content.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Private reminder content; the keyguard notification exposes only a generic label. */
internal object NativeReminders {
    private const val FIRE = "dev.nox.local.REMINDER_FIRE"
    private const val CHANNEL = "nox_reminders"
    private data class Entry(val id: Int, val at: Long, val text: String)
    private fun prefs(c: Context) = c.getSharedPreferences("native_reminders", Context.MODE_PRIVATE)
    private fun read(c: Context): List<Entry> {
        val items = JSONArray(prefs(c).getString("items", "[]"))
        return (0 until items.length()).map { i ->
            val o = items.getJSONObject(i); Entry(o.getInt("id"), o.getLong("at"), o.getString("text"))
        }
    }
    private fun save(c: Context, items: List<Entry>, next: Int = prefs(c).getInt("next", 1)): Boolean =
        prefs(c).edit().putInt("next", next).putString("items", JSONArray(items.map {
            JSONObject().put("id", it.id).put("at", it.at).put("text", it.text)
        }).toString()).commit()
    private fun manager(c: Context) = requireNotNull(c.getSystemService(AlarmManager::class.java))
    private fun operation(c: Context, e: Entry) = PendingIntent.getBroadcast(c, e.id,
        Intent(c, NativeReminderReceiver::class.java).setAction(FIRE).putExtra("id", e.id).putExtra("at", e.at),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun format(at: Long) = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMMM HH:mm", Locale("ru")))
    private fun install(c: Context, e: Entry) = manager(c).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, e.at, operation(c, e))
    fun schedule(c: Context, command: ExtraCommand.Reminder): String {
        if (!manager(c).canScheduleExactAlarms()) return "Напоминание не создано. Разблокируй телефон и скажи «разрешение будильника»."
        if (c.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() != true)
            return "Напоминание не создано: разреши уведомления Нокса."
        val now = ZonedDateTime.now()
        val at = if (command.seconds != null) now.plusSeconds(command.seconds.toLong()).toInstant().toEpochMilli()
            else NativeCommands.nextAlarm(requireNotNull(command.clock), now).let {
                if (command.today && it.toLocalDate() != now.toLocalDate()) return "Это время сегодня уже прошло."
                it.toInstant().toEpochMilli()
            }
        val items = read(c); val id = prefs(c).getInt("next", 1)
        if (items.size >= 32 || id !in 1..999999999) return "Лимит напоминаний достигнут."
        val e = Entry(id, at, command.text)
        if (!save(c, items + e, id + 1)) return "Не удалось сохранить напоминание."
        return try {
            install(c, e)
            "Android принял напоминание №$id на " + format(at) + "."
        } catch (_: Exception) {
            save(c, items)
            "Напоминание не создано."
        }
    }
    fun list(c: Context): String = read(c).sortedBy { it.at }.take(32).joinToString("\n") {
        "№" + it.id + " · " + format(it.at) + " · " + it.text
    }.ifBlank { "Активных напоминаний нет." }
    fun cancel(c: Context, id: Int): String {
        val items = read(c); val e = items.singleOrNull { it.id == id } ?: return "Напоминание не найдено."
        manager(c).cancel(operation(c, e))
        return if (save(c, items - e)) "Напоминание №$id отменено." else "Сигнал отменён, запись не очищена."
    }
    fun receive(c: Context, intent: Intent) {
        val items = read(c)
        if (intent.action == FIRE) {
            val e = items.singleOrNull { it.id == intent.getIntExtra("id", -1) && it.at == intent.getLongExtra("at", -1L) } ?: return
            if (e.at > System.currentTimeMillis() + 1000L) return
            if (!save(c, items - e)) return
            notify(c, e)
        } else if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) {
            if (!manager(c).canScheduleExactAlarms()) return
            for (e in items) {
                if (e.at > System.currentTimeMillis()) runCatching { install(c, e) }
                else if (save(c, read(c).filterNot { it.id == e.id })) notify(c, e)
            }
        }
    }
    private fun notify(c: Context, e: Entry) {
        val nm = requireNotNull(c.getSystemService(NotificationManager::class.java))
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Напоминания Нокса", NotificationManager.IMPORTANCE_DEFAULT))
        val public = Notification.Builder(c, CHANNEL).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Напоминание Нокса").setContentText("Разблокируй телефон для просмотра").build()
        val n = Notification.Builder(c, CHANNEL).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Напоминание Нокса").setContentText(e.text)
            .setStyle(Notification.BigTextStyle().bigText(e.text)).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public).setAutoCancel(true).build()
        nm.notify("nox_reminder", e.id, n)
    }
}
class NativeReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching { NativeReminders.receive(context, intent) }
            .onFailure { android.util.Log.w("NoxReminder", "receiver_failed") }
    }
}
