package dev.nox.local

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicReference
import android.view.accessibility.AccessibilityEvent
import java.util.Locale

/** Optional navigation service; only the agent's permission-gated tools may use it. */
class NoxNavigationService : AccessibilityService() {
    private fun lifecycle(event: String) {
        Log.i("NoxLC", "$event instance=${System.identityHashCode(this)} pid=${android.os.Process.myPid()} t=${SystemClock.elapsedRealtime()} thread=${Thread.currentThread().name}")
    }
    override fun onCreate() { super.onCreate(); lifecycle("create") }
    override fun onServiceConnected() { super.onServiceConnected(); active.set(this); lifecycle("connected") }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (current !== this || event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                uiChanges.publish(event.packageName?.toString(), event.windowId)
        }
    }
    override fun onInterrupt() { lifecycle("interrupt") }
    override fun onUnbind(intent: Intent?): Boolean {
        active.compareAndSet(this, null)
        lifecycle("unbind")
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        active.compareAndSet(this, null)
        lifecycle("destroy")
        super.onDestroy()
    }
    companion object {
        private val active = AtomicReference<NoxNavigationService?>()
        internal val uiChanges = UiChangeRelay()
        internal val current: NoxNavigationService? get() = active.get()

        // Compare parsed components, not their short/long string spellings.
        internal fun enabledInSettings(context: Context): Boolean? = runCatching {
            val component = ComponentName(context, NoxNavigationService::class.java)
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component }
        }.getOrNull()

        internal fun connectionCode(context: Context): String {
            if (current != null) return "BOUND"
            return when (enabledInSettings(context)) {
                true -> "SERVICE_RECONNECTING"
                false -> "ACCESSIBILITY_DISABLED"
                null -> "SERVICE_STATUS_UNKNOWN"
            }
        }
    }
}

internal object PhoneActions {
    fun execute(context: Context, command: PhoneCommand): String {
        if (command == PhoneCommand.Greeting) return "Привет! Я на связи."
        if (!DeviceInteraction.unlocked(context))
            return "Сначала разблокируй телефон."
        return when (command) {
            is PhoneCommand.Open -> open(context, command.app)
            is PhoneCommand.OpenWithFollowUp -> open(context, command.app) +
                " Второе действие пока не поддерживается. Сообщение не отправлено."
            is PhoneCommand.Volume -> {
                val audio = requireNotNull(context.getSystemService(AudioManager::class.java))
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    if (command.direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI)
                "Громкость медиа: " + audio.getStreamVolume(AudioManager.STREAM_MUSIC) +
                    " из " + audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) + "."
            }
            PhoneCommand.Home -> navigate(AccessibilityService.GLOBAL_ACTION_HOME)
            PhoneCommand.Back -> navigate(AccessibilityService.GLOBAL_ACTION_BACK)
            PhoneCommand.Greeting -> "Привет!"
        }
    }

    private fun navigate(action: Int): String {
        val service = NoxNavigationService.current
            ?: return "Включи службу навигации Нокс в настройках специальных возможностей."
        return if (service.performGlobalAction(action)) "Команда навигации принята Android."
        else "Android отклонил команду навигации."
    }

    private fun open(context: Context, name: String): String {
        val aliases = mapOf("телеграм" to "telegram", "телеграмм" to "telegram", "ютуб" to "youtube",
            "ютьюб" to "youtube", "вк" to "vk", "браузер" to "chrome")
        val normalized = aliases[name] ?: name
        val pm = context.packageManager
        val launcher = NoxNavigationService.current ?: context
        if (normalized == "настройки") {
            launcher.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "Отправила Android запрос на открытие настроек."
        }
        val candidates = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.loadLabel(pm).toString().trim().lowercase(Locale.ROOT) == normalized ||
                it.activityInfo.packageName.lowercase(Locale.ROOT) == normalized }
            .distinctBy { it.activityInfo.packageName }
        if (candidates.isEmpty()) return "Не нашла приложение «" + name + "». Назови его как в меню телефона."
        if (candidates.size > 1) return "Найдено несколько приложений. Уточни название или имя пакета."
        val target = candidates.single().activityInfo
        launcher.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(target.packageName, target.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        // Background launch may be rejected by Android even without an exception.
        return "Отправила запрос на открытие «" + candidates.single().loadLabel(pm) + "»."
    }
}
