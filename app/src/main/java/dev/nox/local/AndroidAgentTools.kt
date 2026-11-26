package dev.nox.local

import android.accessibilityservice.AccessibilityService
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.os.Build
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
    announce: (String) -> Unit,
    private val canLaunchDirectly: () -> Boolean = { false },
    private val goal: GoalState = GoalState("")
) {
    private val actionGate = AgentActionGate(cancelled, announce)
    private val main = Handler(Looper.getMainLooper())
    private val apps = mutableMapOf<String, String>()
    private var snapshot: Screen? = null
    private var expectedLaunch: String? = null
    private val grantPrefs = context.getSharedPreferences("nox_agent_grants", Context.MODE_PRIVATE)
    private val permissions = AgentPermissionPolicy(
        saved = { pkg -> grantPrefs.getString(pkg, null)?.let { runCatching { AppGrant.valueOf(it) }.getOrNull() } },
        persist = { pkg, grant -> check(grantPrefs.edit().putString(pkg, grant.name).commit()) { "Не удалось сохранить разрешение" } }
    )

    private data class Node(val id: String, val path: List<Int>, val label: String, val kind: String,
        val resource: String, val hint: String, val bounds: String, val clickable: Boolean, val editable: Boolean,
        val scrollable: Boolean, val enabled: Boolean)
    private data class Screen(val pkg: String, val window: Int, val nodes: List<Node>) {
        fun describe(): String = buildString {
            append("Активное приложение: ").append(pkg).append('\n')
            nodes.forEach { n ->
                append(n.id).append(" | ").append(n.kind).append(" | ").append(n.label)
                if (n.hint.isNotBlank() && n.hint != n.label) append(" [hint: ").append(n.hint).append("]")
                if (n.resource.isNotBlank()) append(" [resource: ").append(n.resource.substringAfterLast('/')).append("]")
                if (n.enabled && n.clickable) append(" [click]")
                if (n.enabled && n.editable) append(" [type]")
                if (n.enabled && n.scrollable) append(" [scroll]")
                if (!n.enabled) append(" [disabled]")
                append('\n')
            }
        }.take(7200)
    }

    private fun checkActive() {
        if (cancelled() || context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true ||
            context.getSystemService(PowerManager::class.java)?.isInteractive != true) throw AgentDenied()
    }
    private fun nav() = NoxNavigationService.current ?: throw AgentToolUnavailable()
    private fun <T> ui(block: () -> T): T {
        checkActive()
        val task = FutureTask<T> { checkActive(); block() }
        if (!main.post(task)) throw AgentToolUnavailable("UI-поток недоступен. Задача остановлена.")
        return awaitAgentUi(task)
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
            pkg != "dev.nox.local" && pkg != "dev.nox.local.dev" &&
            pkg != "android" && pkg != "com.android.systemui" &&
            pkg != "com.android.settings" && pkg != "com.google.android.permissioncontroller" &&
            !pkg.contains("permissioncontroller") && !pkg.contains("packageinstaller")
    }
    private fun readScreen(): Screen {
        val root = nav().rootInActiveWindow ?: error("Активный экран недоступен")
        val pkg = root.packageName?.toString().orEmpty()
        require(permitted(pkg)) { "Этот экран не доступен агенту: " + pkg }
        require(permissions.allowed(pkg)) { "Нет разрешения на управление текущим приложением" }
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
                        n.viewIdResourceName.orEmpty(), n.hintText?.toString()?.take(180).orEmpty(), bounds, n.isClickable, n.isEditable,
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

    private fun goalObservation(screen: Screen) = GoalObservation(goal.sequence(), screen.pkg,
        screen.nodes.map { GoalNode(it.resource, it.label) })
    private fun describeGoal(screen: Screen): String = screen.describe() +
        (goal.hint(goalObservation(screen))?.let { "\n" + it } ?: "")

    fun verifyFinish(): String? {
        val previous = snapshot ?: return null
        val current = ui {
            require(nav().rootInActiveWindow?.packageName?.toString() == previous.pkg) { "Экран изменился" }
            readScreen()
        }
        snapshot = current
        return goal.finish(goalObservation(current))
    }

    private fun observe(): String {
        snapshot = null
        val pkg = ui { nav().rootInActiveWindow?.packageName?.toString().orEmpty() }
        require(permitted(pkg)) { "Этот экран не доступен агенту: " + pkg }
        require(expectedLaunch == null || expectedLaunch == pkg) { "Ожидается экран " + expectedLaunch }
        authorize(pkg)
        val screen = ui { readScreen().also { require(it.pkg == pkg) { "Активное приложение изменилось" } } }
        expectedLaunch = null
        snapshot = screen
        return describeGoal(screen)
    }
    private fun describeAfter(prefix: String): String {
        pause(500)
        val view = try { observe() } catch (e: AgentDenied) { throw e } catch (e: AgentToolUnavailable) { throw e } catch (e: Exception) {
            return AgentToolStatus.ACTION_UNVERIFIED + "Действие могло быть выполнено, но экран не подтверждён. Не повторяю автоматически. " + (e.message ?: e.javaClass.simpleName)
        }
        return prefix + "\n" + (snapshot?.describe() ?: view)
    }
    private fun choose(label: String, options: List<String>): Int {
        checkActive()
        val decision = CompletableFuture<Int>()
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
                    orientation = LinearLayout.VERTICAL
                    options.forEachIndexed { index, option ->
                        addView(Button(service).apply {
                            text = option; filterTouchesWhenObscured = true
                            setOnClickListener { decision.complete(index) }
                        }, LinearLayout.LayoutParams(-1, -2))
                    }
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
                    return decision.get(200, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {}
            }
            throw AgentDenied("Время ожидания подтверждения истекло. Задача остановлена.")
        } finally {
            // Cleanup must run even after cancellation; not routed through checkActive.
            main.post { runCatching { pair.first.removeView(pair.second) } }
        }
    }

    private fun appLabel(pkg: String): String = apps[pkg] ?: runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun authorize(pkg: String) {
        checkActive()
        require(permitted(pkg)) { "Управление этим приложением запрещено" }
        if (permissions.blocked(pkg)) throw AgentDenied("Управление «" + appLabel(pkg) + "» запрещено в настройках Нокса.")
        if (permissions.allowed(pkg)) return
        val choice = choose("Управление «" + appLabel(pkg) + "»\n" + pkg +
            "\nРазрешает открытие, чтение экрана и проверенные безопасные действия. Остальные действия требуют отдельного подтверждения.",
            listOf("Разрешать всегда", "Разрешить на эту задачу", "Запретить всегда", "Отмена"))
        checkActive()
        when (choice) {
            0 -> permissions.apply(pkg, AppGrant.ALWAYS)
            1 -> permissions.apply(pkg, AppGrant.ONCE)
            2 -> { permissions.apply(pkg, AppGrant.NEVER); throw AgentDenied("Управление приложением запрещено.") }
            else -> throw AgentDenied()
        }
    }

    private fun confirmed(label: String) {
        if (choose(label, listOf("Разрешить действие", "Отмена")) != 0) throw AgentDenied()
        checkActive()
    }

    /**
     * A background service cannot reliably open another app on recent Android versions.
     * A direct Activity PendingIntent in a user-tapped notification is the supported fallback.
     * Do not trampoline through our service or claim that the target was opened on posting.
     */
    private fun offerLaunch(pkg: String, label: String, launchIntent: Intent): String {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return AgentToolStatus.LAUNCH_NEEDS_TAP +
                "Не могу открыть «" + label + "» из фона: для этого нужен доступ к уведомлениям. " +
                "Разреши уведомления N.O.X. или открой его чат на экране и повтори команду."
        }
        val manager = requireNotNull(context.getSystemService(NotificationManager::class.java))
        val channelId = "nox_launch_actions"
        manager.createNotificationChannel(NotificationChannel(channelId, "Нокс — открытие приложений",
            NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Подтверждение запуска приложения из фонового режима"
        })
        // The pending intent targets the app directly. A tap is a fresh user gesture.
        val pending = PendingIntent.getActivity(context, pkg.hashCode(), Intent(launchIntent),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("N.O.X. · Открыть " + label)
            .setContentText("Нажми, чтобы открыть приложение.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000L)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
        manager.notify(pkg.hashCode(), notification)
        return AgentToolStatus.LAUNCH_NEEDS_TAP +
            "Я подготовил открытие «" + label + "». Нажми уведомление N.O.X. для запуска. " +
            "Пока ты не нажмёшь его, приложение не открыто."
    }

    private fun actOnNode(action: AgentDecision.Tool): String {
        val before = snapshot ?: error("Сначала используй observe")
        if (goal.arithmetic && !goal.allowsLaunch(before.pkg)) return AgentToolStatus.GOAL_REJECTED + "Нужен экран калькулятора."
        val target = before.nodes.firstOrNull { it.id == action.arg } ?: error("Неизвестный ID; обнови экран")
        if (!(target.enabled && if (action.name == "type") target.editable else target.clickable)) {
            snapshot = null
            return "ERROR[UNSUPPORTED_NODE] Узел не поддерживает это действие. Выбери другой доступный узел после observe."
        }
        val beforeGoal = goalObservation(before)
        goal.reject(beforeGoal, action.name, target.resource)?.let {
            return AgentToolStatus.GOAL_REJECTED + it + "\n" + describeGoal(before)
        }
        authorize(before.pkg)
        val actionText = if (action.name == "type") "Ввести «" + action.text.take(1000) + "»"
            else "Нажать «" + target.label.ifBlank { target.kind + " " + target.resource } + "»"
        actionGate.before(if (action.name == "type") AgentNarration.type(target.hint.ifBlank { target.label }, target.resource, action.text)
            else AgentNarration.click(target.label, target.resource))
        if (!AgentPermissionPolicy.safeAction(before.pkg, action.name, target.label, target.resource, target.kind))
            confirmed(appLabel(before.pkg) + " · " + before.pkg + "\n" + actionText)
        pause(250)
        snapshot = null
        goal.mutation()
        val accepted = ui {
            require(permissions.allowed(before.pkg)) { "Разрешение отозвано" }
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
        if (!accepted) return "ERROR[ACTION_REJECTED] Android отклонил действие. Выполни observe и выбери доступный элемент."
        val result = describeAfter("Android принял действие: " + actionText + ". Проверяй фактический результат.")
        if (action.name == "click" && goal.arithmetic) {
            val after = snapshot
            if (after == null || !goal.afterClick(beforeGoal, target.resource, goalObservation(after)))
                return AgentToolStatus.ACTION_UNVERIFIED + "После нажатия поле калькулятора не соответствует ожидаемому состоянию. Повторять действие не буду."
        }
        return result + (snapshot?.let { goal.hint(goalObservation(it)) }?.let { "\n" + it } ?: "")
    }

    fun execute(action: AgentDecision.Tool): String {
        checkActive()
        if (goal.arithmetic && action.name !in setOf("apps", "launch", "observe", "click"))
            return AgentToolStatus.GOAL_REJECTED + "Для этой задачи нужны только кнопки калькулятора."
        if (action.name == "launch" && !goal.allowsLaunch(action.arg))
            return AgentToolStatus.GOAL_REJECTED + "Нужен калькулятор Samsung."
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
                val label = apps.getValue(action.arg)
                authorize(action.arg)
                goal.launched(action.arg, label)
                val already = runCatching { ui {
                    require(nav().rootInActiveWindow?.packageName?.toString() == action.arg) { "Другое приложение" }
                    readScreen()
                } }.getOrNull()
                if (already?.pkg == action.arg) {
                    expectedLaunch = null
                    snapshot = already
                    return "Приложение уже открыто; продолжай с текущего экрана.\n" + describeGoal(already)
                }
                expectedLaunch = action.arg
                actionGate.before(AgentNarration.launch(label))
                snapshot = null
                goal.mutation()
                val launch = context.packageManager.getLaunchIntentForPackage(action.arg)
                    ?: error("У приложения «" + label + "» нет запускаемого экрана")
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (!canLaunchDirectly()) return offerLaunch(action.arg, label, launch)
                try {
                    // Starting activities needs no Accessibility permission when our activity is
                    // visible, or our visible overlay qualifies for Android's BAL exemption.
                    ui { context.startActivity(launch) }
                } catch (e: SecurityException) {
                    // Android may deny a start from the foreground service even with its overlay.
                    return offerLaunch(action.arg, label, launch)
                } catch (e: android.content.ActivityNotFoundException) {
                    return "ERROR[APP_NOT_FOUND] Не удалось найти экран запуска для «" + label + "»."
                }
                if (NoxNavigationService.current == null) {
                    return AgentToolStatus.LAUNCH_UNVERIFIED +
                        "Отправил Android команду открыть «" + label + "», но проверить результат без " +
                        "службы специальных возможностей не могу."
                }
                repeat(6) {
                    pause(500)
                    val observed = runCatching { ui {
                        require(nav().rootInActiveWindow?.packageName?.toString() == action.arg) { "Переход ещё не подтверждён" }
                        readScreen()
                    } }.getOrNull()
                    if (observed?.pkg == action.arg) {
                        expectedLaunch = null
                        snapshot = observed
                        return "Запуск подтверждён: " + action.arg + "\n" + describeGoal(observed)
                    }
                }
                snapshot = null
                // Accessibility is connected, but Android may still be switching windows.
                // AgentCore performs bounded read-only observations; no clicks until verified.
                return AgentToolStatus.LAUNCH_RECHECK +
                    "Android получил запрос на запуск «" + label + "», но экран ещё не подтвердил переход."
            }
            "observe" -> observe()
            "click", "type" -> actOnNode(action)
            "back", "home" -> {
                val pkg = ui { nav().rootInActiveWindow?.packageName?.toString().orEmpty() }
                authorize(pkg)
                confirmed(appLabel(pkg) + "\n" + if (action.name == "back") "Назад" else "На главный экран")
                actionGate.before(if (action.name == "back") "Возвращаюсь назад." else "Перехожу на главный экран.")
                snapshot = null
                goal.mutation()
                val ok = ui { require(nav().rootInActiveWindow?.packageName?.toString() == pkg && permissions.allowed(pkg)) { "Экран изменился" }; nav().performGlobalAction(if (action.name == "back") AccessibilityService.GLOBAL_ACTION_BACK
                    else AccessibilityService.GLOBAL_ACTION_HOME) }
                if (ok) describeAfter("Android принял навигацию.") else "ERROR[ACTION_REJECTED] Навигация отклонена."
            }
            "scroll" -> {
                val before = snapshot ?: error("Сначала используй observe")
                authorize(before.pkg)
                val target = before.nodes.firstOrNull { it.scrollable && it.enabled } ?: error("Нет доступной прокрутки")
                actionGate.before(if (action.arg == "down") "Листаю вниз." else "Листаю вверх.")
                snapshot = null
                goal.mutation()
                val ok = ui {
                    require(readScreen() == before) { "Экран изменился" }
                    var n = nav().rootInActiveWindow ?: error("Экран исчез")
                    for (index in target.path) n = n.getChild(index) ?: error("Элемент исчез")
                    require(n.isScrollable && n.isVisibleToUser)
                    n.performAction(if (action.arg == "down") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                }
                if (ok) describeAfter("Прокрутка принята.")
                else "ERROR[ACTION_REJECTED] Прокрутка не выполнена. Обнови экран."
            }
            "volume" -> {
                confirmed("Изменить громкость устройства")
                actionGate.before(if (action.arg == "up") "Увеличиваю громкость." else "Уменьшаю громкость.")
                goal.mutation()
                snapshot = null
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
