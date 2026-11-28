package dev.nox.local

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.AlarmClock

/** Only a preinstalled Clock handler may receive a timer request. No local timer,
 * arbitrary intent, background countdown or fallback to Accessibility. */
internal object SystemClockTimerPolicy {
    const val SAMSUNG_CLOCK = "com.sec.android.app.clockpackage"
    data class Handler(val pkg: String, val activity: String, val system: Boolean)
    fun durationAllowed(seconds: Int) = seconds in 1..3600
    fun select(handlers: List<Handler>, manufacturer: String): Handler? {
        val trusted = handlers.filter { it.system }.distinctBy { it.pkg to it.activity }
        if (manufacturer.equals("samsung", true)) {
            return trusted.filter { it.pkg == SAMSUNG_CLOCK }.singleOrNull()
        }
        return trusted.singleOrNull()
    }
}

internal object SystemClockTimer {
    @Suppress("DEPRECATION")
    private fun resolve(context: Context, action: String): ComponentName? {
        val query = Intent(action).addCategory(Intent.CATEGORY_DEFAULT)
        val candidates = context.packageManager.queryIntentActivities(query, PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { info ->
                val activity = info.activityInfo ?: return@mapNotNull null
                if (!activity.exported || !activity.enabled || !activity.applicationInfo.enabled) return@mapNotNull null
                val flags = activity.applicationInfo.flags
                SystemClockTimerPolicy.Handler(activity.packageName, activity.name,
                    flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
            }
        return SystemClockTimerPolicy.select(candidates, Build.MANUFACTURER)
            ?.let { ComponentName(it.pkg, it.activity) }
    }

    /** Caller has already validated explicit user intent and current session.
     * Android Clock owns the countdown, notifications, sound and persistence.
     * startActivity is dispatch, not proof that the timer is running. */
    fun request(context: Context, seconds: Int, allowed: () -> Boolean): String {
        if (!SystemClockTimerPolicy.durationAllowed(seconds)) return "Укажи время таймера от одной секунды до одного часа."
        if (!allowed()) return "Запрос таймера отменён."
        return try {
            val target = resolve(context, AlarmClock.ACTION_SET_TIMER)
                ?: return "Штатное приложение «Часы» не поддерживает запуск таймера."
            if (!allowed()) return "Запрос таймера отменён."
            context.startActivity(Intent(AlarmClock.ACTION_SET_TIMER)
                .setComponent(target)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
            android.util.Log.i("NoxClock", "timer_dispatched seconds=" + seconds)
            "Передал приложению «Часы» запрос на запуск таймера на $seconds сек. Начало отсчёта не подтверждено."
        } catch (_: Exception) {
            "Не удалось передать таймер приложению «Часы». Если экран заблокирован, разблокируй телефон и повтори."
        }
    }

    /** No portable identifier for a running external timer. SHOW_TIMERS never
     * dismisses unrelated or expired timers; the user chooses which one to stop. */
    fun showTimers(context: Context, allowed: () -> Boolean): String {
        if (!allowed()) return "Запрос отменён."
        return try {
            val target = resolve(context, AlarmClock.ACTION_SHOW_TIMERS)
                ?: return "Открой приложение «Часы» и останови нужный таймер. Таймер не изменён."
            if (!allowed()) return "Запрос отменён."
            context.startActivity(Intent(AlarmClock.ACTION_SHOW_TIMERS).setComponent(target)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "Передал запрос на открытие списка таймеров. Выбери нужный для остановки. Таймер не отменён."
        } catch (_: Exception) {
            "Не удалось открыть таймеры. Таймер не изменён."
        }
    }
}
