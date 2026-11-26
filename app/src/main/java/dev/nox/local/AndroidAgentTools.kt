package dev.nox.local

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** One instance per user task. Screen data and node handles never leave the device. */
internal class AndroidAgentTools(
    private val context: Context,
    private val cancelled: () -> Boolean,
    announce: (String) -> Unit
) {
    private val actionGate = AgentActionGate(cancelled, announce)
    private val main = Handler(Looper.getMainLooper())
    private val apps = mutableMapOf<String, String>()
    private var snapshot: Screen? = null

    private data class Node(val id: String, val path: List<Int>, val label: String, val kind: String,
        val resource: String, val bounds: String, val clickable: Boolean, val editable: Boolean,
        val scrollable: Boolean, val enabled: Boolean)
    private data class Screen(val pkg: String, val window: Int, val nodes: List<Node>) {
        fun describe(): String = buildString {
            append("Активное приложение: ").append(pkg).append('\n')
            nodes.forEach { n ->
                append(n.id).append(" | ").append(n.kind).append(" | ").append(n.label)
                if (n.clickable) append(" [click]")
                if (n.editable) append(" [type]")
                if (n.scrollable) append(" [scroll]")
                if (!n.enabled) append(" [disabled]")
                append('\n')
            }
        }.take(7200)
    }

    private fun checkActive() {
        if (cancelled() || context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true ||
            context.getSystemService(PowerManager::class.java)?.isInteractive != true) throw AgentDenied()
    }
    private fun nav() = NoxNavigationService.current ?: error("Разреши управление экраном Нокс в специальных возможностях")
    private fun <T> ui(block: () -> T): T {
        checkActive()
        val task = FutureTask<T> { checkActive(); block() }
        main.post(task)
        try { return task.get(5, TimeUnit.SECONDS) }
        finally { if (!task.isDone) task.cancel(false) }
    }
    private fun pause(ms: Long) {
        var remaining = ms
        while (remaining > 0) {
            checkActive()
            val delay = minOf(remaining, 100L)
            Thread.sleep(delay)
            remaining -= delay
        }
    }
    private fun permitted(pkg: String): Boolean {
        // Never operate on own UI, system permissions, lock screens, installers or settings.
        return pkg.isNotBlank() && pkg != context.packageName &&
            pkg != "android" && pkg != "com.android.systemui" &&
            pkg != "com.android.settings" && pkg != "com.google.android.permissioncontroller" &&
            !pkg.contains("permissioncontroller") && !pkg.contains("packageinstaller")
    }
    private fun readScreen(): Screen {
        val root = nav().rootInActiveWindow ?: error("Активный экран недоступен")
        val pkg = root.packageName?.toString().orEmpty()
        require(permitted(pkg)) { "Этот экран не доступен агенту: " + pkg }
        val nodes = mutableListOf<Node>()
        var visited = 0
        fun walk(n: AccessibilityNodeInfo, path: List<Int>, sensitive: Boolean) {
            if (++visited > 450 || nodes.size >= 90 || path.size > 20) return
            val hidden = sensitive || n.isPassword
            if (hidden) return
            if (n.isVisibleToUser && n.packageName?.toString() == pkg) {
                val label = listOfNotNull(n.text?.toString(), n.contentDescription?.toString())
                    .distinct().joinToString(" / ").replace(Regex("[\\r\\n\\t]+"), " ").take(180)
                if (label.isNotBlank() || n.isClickable || n.isEditable || n.isScrollable) {
                    val bounds = Rect().also { n.getBoundsInScreen(it) }.toShortString()
                    nodes.add(Node((nodes.size + 1).toString(), path, label,
                        n.className?.toString()?.substringAfterLast('.') ?: "View",
                        n.viewIdResourceName.orEmpty(), bounds, n.isClickable, n.isEditable,
                        n.isScrollable, n.isEnabled))
                }
            }
            for (i in 0 until n.childCount.coerceAtMost(100)) {
                val child = n.getChild(i) ?: continue
                walk(child, path + i, hidden)
            }
        }
        walk(root, emptyList(), false)
        return Screen(pkg, root.windowId, nodes)
    }

    private fun observe(): String {
        snapshot = null
        val screen = ui { readScreen() }
        snapshot = screen
        return screen.describe()
    }
    private fun describeAfter(prefix: String): String {
        pause(500)
        val view = runCatching { observe() }.getOrElse { "Экран не подтверждён: " + it.message }
        return prefix + "\n" + view
    }
    private fun confirmed(label: String) {
        checkActive()
        val decision = CompletableFuture<Boolean>()
        val pair = ui {
            val service = nav()
            val wm = requireNotNull(service.getSystemService(WindowManager::class.java))
            val density = context.resources.displayMetrics.density
            val panel = LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((16*density).toInt(), (12*density).toInt(), (16*density).toInt(), (12*density).toInt())
                background = GradientDrawable().apply { setColor(0xFF201B2C.toInt()); cornerRadius = 18*density }
                addView(TextView(service).apply {
                    text = "N.O.X. · Подтверждение\n" + label
                    setTextColor(0xFFFFFFFF.toInt()); textSize = 15f
                })
                addView(LinearLayout(service).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(Button(service).apply {
                        text = "Отмена"; filterTouchesWhenObscured = true; setOnClickListener { decision.complete(false) }
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(Button(service).apply {
                        text = "Разрешить"; filterTouchesWhenObscured = true; setOnClickListener { decision.complete(true) }
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                })
            }
            val params = WindowManager.LayoutParams(
                (context.resources.displayMetrics.widthPixels - 24*density).toInt().coerceAtLeast(1),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = (52*density).toInt() }
            wm.addView(panel, params)
            wm to panel
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
            while (System.nanoTime() < deadline) {
                checkActive()
                try {
                    if (decision.get(200, TimeUnit.MILLISECONDS)) return
                    throw AgentDenied()
                } catch (_: TimeoutException) {}
            }
            throw AgentDenied()
        } finally {
            // Cleanup must run even after cancellation; not routed through checkActive.
            main.post { runCatching { pair.first.removeView(pair.second) } }
        }
    }

    private fun actOnNode(action: AgentDecision.Tool): String {
        val before = snapshot ?: error("Сначала используй observe")
        val target = before.nodes.firstOrNull { it.id == action.arg } ?: error("Неизвестный ID; обнови экран")
        require(target.enabled && if (action.name == "type") target.editable else target.clickable) {
            "Узел не поддерживает это действие"
        }
        val actionText = if (action.name == "type") "Ввести «" + action.text.take(1000) + "»"
            else "Нажать «" + target.label.ifBlank { target.kind + " " + target.resource } + "»"
        actionGate.before(if (action.name == "type") "Сейчас введу текст: " + action.text.take(160) else "Сейчас нажму: " + target.label.ifBlank { target.kind + " " + target.resource }.take(120))
        confirmed(before.pkg + "\n" + actionText)
        pause(250)
        snapshot = null
        val accepted = ui {
            val current = readScreen()
            require(current == before) { "Экран изменился. Требуется новое наблюдение и подтверждение." }
            val root = nav().rootInActiveWindow ?: error("Экран исчез")
            require(root.windowId == before.window && root.packageName?.toString() == before.pkg)
            var node = root
            for (index in target.path) node = node.getChild(index) ?: error("Элемент исчез")
            require(node.isVisibleToUser && node.isEnabled && !node.isPassword)
            if (action.name == "type") {
                require(node.isEditable)
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.text)
                })
            } else {
                require(node.isClickable)
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
        return describeAfter(if (accepted) "Android принял действие: " + actionText + ". Проверяй фактический результат."
            else "Android отклонил действие.")
    }

    fun execute(action: AgentDecision.Tool): String {
        checkActive()
        return when (action.name) {
            "apps" -> {
                val pm = context.packageManager
                val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
                    .distinctBy { it.second }
                    .filter { permitted(it.second) }
                    .filter { action.arg.isBlank() || it.first.contains(action.arg, true) || it.second.contains(action.arg, true) }
                    .sortedBy { it.first.lowercase(Locale.ROOT) }.take(80)
                list.forEach { apps[it.second] = it.first }
                list.joinToString("\n") { it.first + " = " + it.second }.ifBlank { "Не найдено. Попробуй другое написание или пустой поиск." }
            }
            "launch" -> {
                require(action.arg in apps && permitted(action.arg)) { "Сначала получи точный пакет через apps" }
                actionGate.before("Сейчас открою " + apps.getValue(action.arg).take(100))
                snapshot = null
                ui {
                    val launch = context.packageManager.getLaunchIntentForPackage(action.arg) ?: error("Нет запускаемого экрана")
                    nav().startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                var lastScreen: Screen? = null
                repeat(6) {
                    pause(500)
                    lastScreen = runCatching { ui { readScreen() } }.getOrNull()
                    val observed = lastScreen
                    if (observed?.pkg == action.arg) {
                        snapshot = observed
                        return "Запуск подтверждён: " + action.arg + "\n" + observed.describe()
                    }
                }
                snapshot = lastScreen
                "Запуск НЕ подтверждён: " + action.arg + "\n" +
                    (lastScreen?.describe() ?: "Активный экран недоступен")
            }
            "observe" -> observe()
            "click", "type" -> actOnNode(action)
            "back", "home" -> {
                actionGate.before(if (action.name == "back") "Сейчас вернусь назад" else "Сейчас выйду на главный экран")
                snapshot = null
                val ok = ui { nav().performGlobalAction(if (action.name == "back") AccessibilityService.GLOBAL_ACTION_BACK
                    else AccessibilityService.GLOBAL_ACTION_HOME) }
                describeAfter(if (ok) "Android принял навигацию." else "Навигация отклонена.")
            }
            "scroll" -> {
                val before = snapshot ?: error("Сначала используй observe")
                val target = before.nodes.firstOrNull { it.scrollable && it.enabled } ?: error("Нет доступной прокрутки")
                actionGate.before(if (action.arg == "down") "Сейчас прокручу экран вниз" else "Сейчас прокручу экран вверх")
                snapshot = null
                val ok = ui {
                    require(readScreen() == before) { "Экран изменился" }
                    var n = nav().rootInActiveWindow ?: error("Экран исчез")
                    for (index in target.path) n = n.getChild(index) ?: error("Элемент исчез")
                    require(n.isScrollable && n.isVisibleToUser)
                    n.performAction(if (action.arg == "down") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                }
                describeAfter(if (ok) "Прокрутка принята." else "Прокрутка не выполнена.")
            }
            "volume" -> {
                actionGate.before(if (action.arg == "up") "Сейчас увеличу громкость" else "Сейчас уменьшу громкость")
                ui {
                val audio = requireNotNull(context.getSystemService(AudioManager::class.java))
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    if (action.arg == "up") AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                "Громкость медиа: " + audio.getStreamVolume(AudioManager.STREAM_MUSIC) + " из " + audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                }
            }
            else -> error("Неизвестный инструмент")
        }
    }
}
