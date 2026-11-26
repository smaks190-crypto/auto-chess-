package dev.nox.local

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import java.util.Locale

/** Optional navigation service; does not retrieve the screen or perform arbitrary clicks. */
class NoxNavigationService : AccessibilityService() {
    override fun onServiceConnected() { current = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }
    companion object {
        @Volatile internal var current: NoxNavigationService? = null
            private set
    }
}

internal object PhoneActions {
    fun execute(context: Context, command: PhoneCommand): String {
        if (command == PhoneCommand.Greeting) return "Привет! Я на связи."
        if (context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true)
            return "Сначала разблокируй телефон."
        return when (command) {
            is PhoneCommand.Open -> open(context, command.app)
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
        val aliases = mapOf("телеграм" to "telegram", "ютуб" to "youtube",
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
