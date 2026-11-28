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
import android.os.SystemClock
import android.os.Debug
import android.os.Trace
import android.util.Log
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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

/** One instance per user task. Screen data and node handles never leave the device. */
internal class AndroidAgentTools(
    private val context: Context,
    private val cancelled: () -> Boolean,
    announce: (String) -> Unit,
    private val canLaunchDirectly: () -> Boolean = { false },
    private val goal: GoalState = GoalState(""),
    private val announceApproved: (String) -> Unit = announce
) {
    private val actionGate = AgentActionGate(cancelled, announce)
    private val main = Handler(Looper.getMainLooper())
    private val uiTraceIds = AtomicLong()
    private val runningUi = AtomicInteger()
    // Accessed only by tasks on the main thread.
    private var activeUiBudget: UiOperationBudget? = null
    private val apps = mutableMapOf<String, String>()
    private var snapshot: Screen? = null
    private var calcPlan: CalcPlanPermit? = null
    private var expectedLaunch: String? = null
    private val grantPrefs = context.getSharedPreferences("nox_agent_grants", Context.MODE_PRIVATE)
    private val permissions = AgentPermissionPolicy(
        saved = { pkg -> grantPrefs.getString(pkg, null)?.let { runCatching { AppGrant.valueOf(it) }.getOrNull() } },
        persist = { pkg, grant -> check(grantPrefs.edit().putString(pkg, grant.name).commit()) { "Не удалось сохранить разрешение" } }
    )

    private data class Node(val id: String, val path: List<Int>, val label: String, val kind: String,
        val resource: String, val hint: String, val bounds: String, val clickable: Boolean, val editable: Boolean,
        val scrollable: Boolean, val enabled: Boolean, val identity: NodeIdentity)
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
        if (cancelled() || !DeviceInteraction.unlocked(context)) throw AgentDenied()
    }
    private fun nav(): NoxNavigationService {
        NoxNavigationService.current?.let { return it }
        val code = NoxNavigationService.connectionCode(context)
        Log.w("NoxLC", "nav_missing code=$code pid=${android.os.Process.myPid()} t=${SystemClock.elapsedRealtime()}")
        throw AgentToolUnavailable(when (code) {
            "ACCESSIBILITY_DISABLED" -> "Управление экраном Нокс отключено в специальных возможностях."
            "SERVICE_RECONNECTING", "BOUND" -> "Служба управления экраном временно недоступна; возможно, переподключается."
            else -> "Не удалось проверить состояние службы управления экраном."
        })
    }
    private fun checkUiBudget() {
        if (cancelled()) throw AgentDenied()
        activeUiBudget?.check()
    }
    private fun <T> ui(timeoutMillis: Long = 5000, block: () -> T): T {
        checkActive()
        val id = uiTraceIds.incrementAndGet()
        val budget = UiOperationBudget(timeoutMillis, SystemClock::elapsedRealtime)
        val inFlight = runningUi.get()
        val task = FutureTask<T> {
            budget.start()
            runningUi.incrementAndGet()
            activeUiBudget = budget
            val cpu = Debug.threadCpuTimeNanos()
            var result = "OK"
            Trace.beginSection("NoxCapture#$id")
            try {
                checkActive()
                budget.check()
                block()
            } catch (e: Exception) {
                result = if (e is AgentUiTimeout) "DEADLINE" else e.javaClass.simpleName
                throw e
            } finally {
                budget.end()
                Trace.endSection()
                activeUiBudget = null
                runningUi.decrementAndGet()
                Log.i("NoxCapture", "id=$id result=$result stage=${budget.stage} queueMs=${budget.queueMs()} rootMs=${budget.rootMs} walkMs=${budget.walkMs} runMs=${budget.runMs()} cpuMs=${(Debug.threadCpuTimeNanos() - cpu) / 1000000} nodes=${budget.nodes} getChild=${budget.childCalls} inFlightAtPost=$inFlight")
            }
        }
        if (!main.post(task)) {
            task.cancel(false)
            throw AgentToolUnavailable("UI-поток недоступен. Задача остановлена.")
        }
        return try {
            awaitAgentUi(task, budget.remaining())
        } catch (e: AgentUiTimeout) {
            budget.cancel()
            Log.w("NoxCapture", "TIMEOUT id=$id state=${budget.timeoutState()} stage=${budget.stage} queueMs=${budget.queueMs()} runMs=${budget.runMs()} nodes=${budget.nodes} getChild=${budget.childCalls}")
            throw e
        } finally {
            // Also stop an already-running read when awaitAgentUi is interrupted.
            budget.cancel()
            main.removeCallbacks(task)
        }
    }
    private fun screenRoot(): AccessibilityNodeInfo? {
        checkUiBudget()
        val budget = activeUiBudget
        budget?.stage = "ROOT"
        val started = SystemClock.elapsedRealtime()
        try {
            return nav().rootInActiveWindow.also { checkUiBudget() }
        } finally {
            if (budget != null) budget.rootMs += SystemClock.elapsedRealtime() - started
        }
    }
    private fun screenChild(node: AccessibilityNodeInfo, index: Int): AccessibilityNodeInfo? {
        checkUiBudget()
        activeUiBudget?.let { it.childCalls++ }
        return node.getChild(index).also { checkUiBudget() }
    }
    private fun identity(node: AccessibilityNodeInfo): NodeIdentity = NodeIdentity(
        node.packageName?.toString().orEmpty(), node.viewIdResourceName.orEmpty(),
        node.className?.toString().orEmpty(), node.text?.toString(), node.contentDescription?.toString(),
        Rect().also { node.getBoundsInScreen(it) }.toShortString(),
        node.isEnabled, node.isVisibleToUser, node.isPassword, node.isClickable, node.isEditable, node.isScrollable
    )
    private fun resolveNode(before: Screen, target: Node): AccessibilityNodeInfo {
        activeUiBudget?.stage = "RESOLVE"
        val root = screenRoot() ?: throw NodeChanged("NO_ROOT")
        activeUiBudget?.stage = "RESOLVE"
        if (root.windowId != before.window || root.packageName?.toString() != before.pkg)
            throw NodeChanged("WINDOW")
        var node = root
        if (node.isPassword) throw NodeChanged("PASSWORD")
        for (index in target.path) {
            node = screenChild(node, index) ?: throw NodeChanged("MISSING")
            if (node.isPassword) throw NodeChanged("PASSWORD")
        }
        return node
    }
    private fun staleNode(e: NodeChanged): ToolOutcome {
        snapshot = null
        Log.w("NoxCapture", "NODE_CHANGED:${e.reason}")
        return ToolOutcome.RejectedBeforeAction("NODE_CHANGED:${e.reason}. Действие не выполнено. Сделай observe и выбери элемент заново.")
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
    private fun readScreen(root: AccessibilityNodeInfo = screenRoot() ?: error("Активный экран недоступен")): Screen {
        val budget = activeUiBudget
        budget?.stage = "WALK"
        val started = SystemClock.elapsedRealtime()
        try {
        val pkg = root.packageName?.toString().orEmpty()
        require(permitted(pkg)) { "Этот экран не доступен агенту: " + pkg }
        require(permissions.allowed(pkg)) { "Нет разрешения на управление текущим приложением" }
        val nodes = mutableListOf<Node>()
        var visited = 0
        fun walk(n: AccessibilityNodeInfo, path: List<Int>, sensitive: Boolean) {
            checkUiBudget()
            if (visited >= 450 || nodes.size >= 90 || path.size > 20) return
            visited++
            budget?.let { it.nodes++ }
            val hidden = sensitive || n.isPassword
            if (hidden) return
            if (n.isVisibleToUser && n.packageName?.toString() == pkg) {
                val label = listOfNotNull(n.text?.toString(), n.contentDescription?.toString())
                    .distinct().joinToString(" / ").replace(Regex("[\\r\\n\\t]+"), " ").take(180)
                if (label.isNotBlank() || n.isClickable || n.isEditable || n.isScrollable) {
                    val key = identity(n)
                    nodes.add(Node((nodes.size + 1).toString(), path, label,
                        key.cls.substringAfterLast('.').ifBlank { "View" },
                        key.resource, n.hintText?.toString()?.take(180).orEmpty(), key.bounds, key.clickable, key.editable,
                        key.scrollable, key.enabled, key))
                }
            }
            for (i in 0 until n.childCount.coerceAtMost(100)) {
                if (visited >= 450 || nodes.size >= 90 || path.size >= 20) break
                val child = screenChild(n, i) ?: continue
                walk(child, path + i, hidden)
            }
        }
        walk(root, emptyList(), false)
        checkUiBudget()
        return Screen(pkg, root.windowId, nodes)
        } finally {
            if (budget != null) budget.walkMs += SystemClock.elapsedRealtime() - started
        }
    }

    private fun Node.effectNode() = EffectNode(path, resource, kind, identity.text, hint,
        identity.description, editable, enabled)
    private fun Screen.effectFrame(seq: Long) = EffectFrame(seq, pkg, window, nodes.map { it.effectNode() })

    private fun goalObservation(screen: Screen) = GoalObservation(goal.sequence(), screen.pkg,
        screen.nodes.map { GoalNode(it.resource, it.label, it.id, it.hint,
            it.identity.description.orEmpty(), it.kind, it.enabled, it.clickable, it.editable, it.identity.text) })
    private fun describeGoal(screen: Screen): String = screen.describe() +
        (goal.hint(goalObservation(screen))?.let { "\n" + it } ?: "")

    /** Derive only one next action from the last independently verified snapshot.
     *  The ordinary executor still checks permission, veto, identity and postcondition.
     */
    fun fastDecision(): AgentDecision? {
        if (goal.directOpenTarget != null) {
            val screen = snapshot
            if (screen != null) return if (goal.finish(goalObservation(screen)) != null)
                AgentDecision.Finish("") else null
            return goal.directOpenPackage(apps)?.let { AgentDecision.Tool("launch", it) }
        }
        if (goal.telegramOpenWrite) {
            val screen = snapshot
            if (screen != null) return if (goal.finish(goalObservation(screen)) != null)
                AgentDecision.Finish("") else null
            return apps.keys.singleOrNull(TelegramDraft::isTelegram)?.let { AgentDecision.Tool("launch", it) }
        }
        val current = snapshot ?: return null
        val observed = goalObservation(current)
        if (goal.searchNavigation) {
            if (goal.finish(observed) != null) return AgentDecision.Finish("")
            val query = goal.searchQuery
            if (query != null) goal.searchInput(observed)?.let { return AgentDecision.Tool("type", it, query) }
            return goal.searchNext(observed)?.let { AgentDecision.Tool("click", it) }
        }
        if (goal.telegramDraftGoal && !goal.telegramSendRequested) {
            if (goal.finish(observed) != null) return AgentDecision.Finish("")
            goal.telegramComposer(observed)?.let {
                if (!TelegramDraft.empty(it)) return null
                return AgentDecision.Tool("type", it.id, goal.telegramDraftText.orEmpty())
            }
        }
        if (!goal.arithmetic || !goal.allowsLaunch(current.pkg)) return null
        if (goal.fastFinish(observed)) {
            Log.i("NoxPlan", "source=VERIFIED_GOAL action=finish")
            return AgentDecision.Finish("") // AgentCore requires another fresh finish verification.
        }
        val resource = goal.fastResource(observed) ?: return null
        val node = current.nodes.singleOrNull { it.resource == resource } ?: return null
        if (!node.enabled || !node.clickable || !node.identity.visible || node.identity.password) return null
        if (goal.reject(observed, "click", node.resource) != null) return null
        Log.i("NoxPlan", "source=VERIFIED_GOAL action=click")
        return AgentDecision.Tool("click", node.id)
    }

    fun verifyFinish(): String? {
        if (calcPlan?.complete() == false) return null
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
        var pkg = ui { nav().rootInActiveWindow?.packageName?.toString().orEmpty() }
        if (goal.telegramDraftGoal) {
            // The explicit system-assistant panel may just be closing. Wait for
            // the underlying window without navigating or reading another app.
            for (attempt in 0 until 6) {
                if (pkg != context.packageName && pkg.isNotBlank()) break
                pause(200)
                pkg = ui { nav().rootInActiveWindow?.packageName?.toString().orEmpty() }
            }
            if (!TelegramDraft.isTelegram(pkg)) throw AgentDenied("Открой нужный чат Telegram. Я не буду открывать приложения автоматически.")
        }
        require(permitted(pkg)) { "Этот экран не доступен агенту: " + pkg }
        require(expectedLaunch == null || expectedLaunch == pkg) { "Ожидается экран " + expectedLaunch }
        authorize(pkg)
        val screen = ui { readScreen().also { require(it.pkg == pkg) { "Активное приложение изменилось" } } }
        expectedLaunch = null
        snapshot = screen
        return describeGoal(screen)
    }
    private fun describeAfter(prefix: String, previous: Screen? = null, fastRead: Boolean = false,
                              changeSignal: UiChangeSignal? = null, changeMarker: Long = 0L,
                              condition: (Screen) -> Boolean = { true }): ToolOutcome {
        // This runs on the agent worker. Only bounded, read-only captures are posted to main.
        check(Looper.myLooper() != Looper.getMainLooper()) { "Post-action settle must run off main" }
        val started = SystemClock.elapsedRealtime()
        val deadline = started + 3000L
        var reason = "NO_ROOT"
        Log.i("NoxLC", "settle_start pid=${android.os.Process.myPid()} t=$started thread=${Thread.currentThread().name}")
        fun remaining() = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        fun waitForRead(ms: Long) { pause(minOf(ms, remaining())) }
        fun read(): Screen? {
            checkActive()
            val budget = remaining()
            if (budget == 0L) return null
            return try {
                ui(timeoutMillis = budget) {
                    val root = screenRoot()
                    if (root == null) {
                        reason = "NO_ROOT"
                        return@ui null
                    }
                    if (previous != null && (root.windowId != previous.window || root.packageName?.toString() != previous.pkg)) {
                        reason = "WINDOW_CHANGED"
                        return@ui null
                    }
                    readScreen(root)
                }
            } catch (e: AgentUiTimeout) {
                reason = "UI_TIMEOUT"
                null
            } catch (e: AgentDenied) {
                throw e
            } catch (e: AgentToolUnavailable) {
                reason = NoxNavigationService.connectionCode(context).let { if (it == "BOUND") "SERVICE_UNAVAILABLE" else it }
                throw e // settleObservation retries only the read, never the mutation.
            } catch (_: Exception) {
                reason = "READ_FAILED"
                null
            }
        }
        try {
            fun matches(observed: Screen): Boolean {
                checkActive()
                if (condition(observed)) return true
                reason = "EXPECT_NOT_MET"
                return false
            }
            val screen = if (fastRead && changeSignal != null) {
                var marker = changeMarker
                var first = true
                var result: Screen? = null
                var eventWakes = 0
                var fallbackWakes = 0
                var reads = 0
                while (remaining() > 0L) {
                    checkActive()
                    // Bound read rate under event storms; use fallback even if Android emits no event.
                    if (!first) waitForRead(40L)
                    val next = try {
                        changeSignal.awaitAfter(marker, minOf(if (first) 100L else 200L, remaining()), ::checkActive)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw AgentDenied("Ожидание изменения экрана прервано.")
                    }
                    if (next != marker) eventWakes++ else fallbackWakes++
                    marker = next
                    first = false
                    if (remaining() == 0L) break
                    reads++
                    val observed = try { read() } catch (_: AgentToolUnavailable) { null }
                    // The event is never proof of success. Only the fresh postcondition is.
                    if (observed != null && remaining() > 0L && matches(observed)) {
                        result = observed
                        break
                    }
                }
                Log.i("NoxCapture", "settle_event wakes=$eventWakes fallback=$fallbackWakes reads=$reads verified=${result != null}")
                result
            } else {
                waitForRead(if (fastRead) 100 else 150)
                settleObservation(5, { waitForRead(if (fastRead) 300 else 650) }, ::read, ::matches)
            }
            checkActive()
            if (screen == null) {
                snapshot = null
                val detail = when (reason) {
                    "SERVICE_RECONNECTING", "SERVICE_UNAVAILABLE" -> "Служба управления экраном временно недоступна."
                    "ACCESSIBILITY_DISABLED" -> "Служба управления экраном отключена в специальных возможностях."
                    "UI_TIMEOUT" -> "Интерфейс не ответил вовремя."
                    "EXPECT_NOT_MET" -> "Ожидаемое изменение экрана не подтверждено."
                    else -> "Не удалось подтвердить состояние экрана."
                }
                return ToolOutcome.Unverified("$detail Результат действия неизвестен; не повторяю автоматически. [$reason]")
            }
            reason = "VERIFIED"
            snapshot = screen
            return ToolOutcome.Ok(prefix + "\n" + describeGoal(screen), screen.pkg)
        } catch (e: AgentDenied) {
            reason = "CANCELLED"
            snapshot = null
            throw e
        } finally {
            Log.i("NoxLC", "settle_end reason=$reason elapsedMs=${SystemClock.elapsedRealtime() - started} pid=${android.os.Process.myPid()} thread=${Thread.currentThread().name}")
        }
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
    private fun offerLaunch(pkg: String, label: String, launchIntent: Intent): ToolOutcome {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return ToolOutcome.NeedsUserTap(
                "Не могу открыть «" + label + "» из фона: для этого нужен доступ к уведомлениям. " +
                "Разреши уведомления N.O.X. или открой его чат на экране и повтори команду.")
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
        return ToolOutcome.NeedsUserTap(
            "Я подготовил открытие «" + label + "». Нажми уведомление N.O.X. для запуска. " +
            "Пока ты не нажмёшь его, приложение не открыто.")
    }

    private fun actOnNode(action: AgentDecision.Tool): ToolOutcome {
        val before = snapshot ?: return ToolOutcome.RejectedBeforeAction("Сначала используй observe")
        if (goal.arithmetic && !goal.allowsLaunch(before.pkg)) return ToolOutcome.RejectedBeforeAction("Нужен экран калькулятора.")
        val target = before.nodes.firstOrNull { it.id == action.arg } ?: return ToolOutcome.RejectedBeforeAction("Неизвестный ID; обнови экран")
        if (!(target.enabled && if (action.name == "type") target.editable else target.clickable)) {
            snapshot = null
            return ToolOutcome.RejectedBeforeAction("UNSUPPORTED_NODE: узел не поддерживает это действие. Выбери другой доступный узел после observe.")
        }
        val beforeGoal = goalObservation(before)
        if (goal.searchNavigation) {
            val allowed = when (action.name) {
                "click" -> goal.searchNext(beforeGoal) == target.id
                "type" -> goal.searchQuery != null && action.text == goal.searchQuery && goal.searchInput(beforeGoal) == target.id
                else -> false
            }
            if (!allowed) return ToolOutcome.RejectedBeforeAction(
                "Поиск: разрешена только проверенная навигация или точный запрошенный текст в однозначном поле поиска. Не вводи сообщения.\n" + describeGoal(before))
        }
        if (goal.telegramDraftGoal) {
            val composer = goal.telegramComposer(beforeGoal)
            val allowed = action.name == "type" && action.text == goal.telegramDraftText &&
                composer != null && composer.id == target.id && TelegramDraft.empty(composer)
            if (!allowed) return ToolOutcome.RejectedBeforeAction(
                "Telegram: разрешён только точный запрошенный текст в однозначном поле текущего чата. Отправка сообщения не выполняется.\n" + describeGoal(before))
        }
        goal.reject(beforeGoal, action.name, target.resource)?.let {
            if (calcPlan != null) { calcPlan?.revoke(); throw AgentDenied("План изменился. Задача остановлена.") }
            return ToolOutcome.RejectedBeforeAction(it + "\n" + describeGoal(before))
        }
        val generic = !goal.arithmetic && !goal.searchNavigation
        val beforeEffect = before.effectFrame(beforeGoal.seq)
        val targetEffect = target.effectNode()
        if (generic || action.name == "type") {
            val rejection = if (action.name == "click") EffectCheck.clickRejection(beforeEffect, action.expect)
                else EffectCheck.typeRejection(targetEffect, action.text)
            if (rejection != null)
                return ToolOutcome.RejectedBeforeAction(rejection + "\n" + describeGoal(before))
        }
        authorize(before.pkg)
        val actionText = if (action.name == "type") "Ввести «" + action.text.take(1000) + "»"
            else "Нажать «" + target.label.ifBlank { target.kind + " " + target.resource } + "»"
        val narration = if (action.name == "type") AgentNarration.type(target.hint.ifBlank { target.label }, target.resource, action.text)
            else AgentNarration.click(target.label, target.resource)
        val safe = AgentPermissionPolicy.safeAction(before.pkg, action.name, target.label, target.resource, target.kind)
        if (calcPlan == null && safe) {
            val sequence = goal.approvedSequence(beforeGoal)
            if (sequence != null && sequence.firstOrNull() == target.resource) {
                val keys = sequence.joinToString(" ") { resource ->
                    when (val key = resource.substringAfterLast('_')) {
                        "add" -> "+"
                        "sub" -> "−"
                        "equal" -> "="
                        else -> key.removePrefix("0")
                    }
                }
                actionGate.before("План калькулятора: " + keys)
                if (choose("Калькулятор · выполнить только эти клавиши по порядку:\n" + keys +
                        "\nПосле каждой клавиши проверю экран. При отклонении остановлюсь.",
                        listOf("Выполнить план", "Отмена")) != 0) throw AgentDenied()
                checkActive()
                pause(250) // allow the approval overlay to disappear before verifying the screen
                calcPlan = CalcPlanPermit(before.pkg, before.window, sequence,
                    SystemClock.elapsedRealtime() + 30000L, SystemClock::elapsedRealtime)
            }
        }
        val plan = calcPlan
        if (plan != null) {
            if (!safe || action.name != "click" || !plan.allows(before.pkg, before.window, target.resource)) {
                plan.revoke()
                throw AgentDenied("Разрешённый план изменился или истёк. Задача остановлена.")
            }
            checkActive()
            announceApproved(narration)
            checkActive()
        } else {
            actionGate.before(narration)
            if (!safe) confirmed(appLabel(before.pkg) + " · " + before.pkg + "\n" + actionText)
            pause(250)
        }
        snapshot = null
        // Subscribe before dispatch; marker captured immediately before the single mutation.
        val changeSignal = if (plan != null) NoxNavigationService.uiChanges.begin(before.pkg, before.window) else null
        var changeMarker = 0L
        try {
        val accepted = try {
            ui {
                require(permissions.allowed(before.pkg)) { "Разрешение отозвано" }
                val current = readScreen()
                if (current != before) throw NodeChanged("SCREEN")
                val node = resolveNode(before, target)
                withVerifiedNode(target.identity, identity(node)) {
                    checkActive()
                    require(permissions.allowed(before.pkg)) { "Разрешение отозвано" }
                    checkUiBudget()
                    activeUiBudget?.stage = "DISPATCH"
                    if (action.name == "type") {
                        if (!node.isEditable) throw NodeChanged("CAPABILITY")
                        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.text)
                        })
                    } else {
                        if (!node.isClickable) throw NodeChanged("CAPABILITY")
                        if (plan != null && !plan.dispatch(before.pkg, before.window, target.resource))
                            throw AgentDenied("Разрешённый план истёк или изменился. Действие не выполнено.")
                        changeMarker = changeSignal?.mark() ?: 0L
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                }
            }
        } catch (e: NodeChanged) {
            if (plan != null) { plan.revoke(); throw AgentDenied("Экран изменился. План остановлен; действие не выполнено.") }
            return staleNode(e)
        } catch (e: Exception) {
            plan?.revoke()
            throw e
        }
        val mutation = goal.mutation()
        if (!accepted) {
            plan?.revoke()
            return ToolOutcome.Unverified("Android не подтвердил действие. Повторять не буду.")
        }
        val outcome = try {
            describeAfter("Android принял действие: " + actionText + ". Проверяй фактический результат.",
                if (goal.arithmetic) before else null, fastRead = plan != null,
                changeSignal = changeSignal, changeMarker = changeMarker) { after ->
                when {
                    goal.arithmetic -> goal.afterClick(beforeGoal, target.resource, goalObservation(after))
                    goal.searchNavigation && action.name == "type" -> {
                        val observed = goalObservation(after)
                        goal.searchTyped(beforeGoal, observed) &&
                            EffectCheck.typed(beforeEffect, mutation, after.effectFrame(observed.seq), targetEffect, action.text)
                    }
                    goal.telegramDraftGoal && action.name == "type" -> {
                        val observed = goalObservation(after)
                        goal.telegramTyped(beforeGoal, observed, action.text) &&
                            EffectCheck.typed(beforeEffect, mutation, after.effectFrame(observed.seq), targetEffect, action.text)
                    }
                    goal.searchNavigation -> goal.searchAfter(beforeGoal, goalObservation(after))
                    action.name == "click" -> EffectCheck.clicked(beforeEffect, mutation, after.effectFrame(goal.sequence()), action.expect)
                    else -> EffectCheck.typed(beforeEffect, mutation, after.effectFrame(goal.sequence()), targetEffect, action.text)
                }
            }
        } catch (e: Exception) { plan?.revoke(); throw e }
        if (plan != null) {
            if (outcome is ToolOutcome.Ok) {
                if (!plan.verified()) throw AgentDenied("План потерял подтверждение. Задача остановлена.")
            } else plan.revoke()
        }
        return outcome
        } finally {
            if (changeSignal != null) NoxNavigationService.uiChanges.end(changeSignal)
        }
    }

    fun execute(action: AgentDecision.Tool): ToolOutcome {
        checkActive()
        if (calcPlan != null && action.name != "click") {
            calcPlan?.revoke()
            throw AgentDenied("Для разрешённого плана допустимы только подтверждённые клавиши. Задача остановлена.")
        }
        if (goal.searchNavigation && action.name !in setOf("apps", "launch", "observe", "click") &&
            !(action.name == "type" && goal.searchQuery != null))
            return ToolOutcome.RejectedBeforeAction("Для поиска допустимы только поиск приложения, наблюдение, проверенная навигация и явно запрошенный ввод поиска.")
        if (goal.directOpenTarget != null && action.name !in setOf("apps", "launch", "observe"))
            return ToolOutcome.RejectedBeforeAction("Запрошено только открытие приложения; другие действия запрещены.")
        if (goal.telegramOpenWrite && action.name !in setOf("apps", "launch", "observe"))
            return ToolOutcome.RejectedBeforeAction("Сначала открой Telegram и уточни получателя и текст. Не вводи и не отправляй сообщение.")
        if (goal.telegramDraftGoal && (goal.telegramSendRequested || action.name !in setOf("observe", "type")))
            return ToolOutcome.RejectedBeforeAction("Для текущего чата Telegram допустимы только наблюдение и ввод точного текста черновика; запуск приложений и отправка не выполняются.")
        if (goal.arithmetic && action.name !in setOf("apps", "launch", "observe", "click"))
            return ToolOutcome.RejectedBeforeAction("Для этой задачи нужны только кнопки калькулятора.")
        if (action.name == "launch" && !goal.allowsLaunch(action.arg))
            return ToolOutcome.RejectedBeforeAction("Нужен калькулятор Samsung.")
        return when (action.name) {
            "apps" -> {
                val pm = context.packageManager
                val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
                    .distinctBy { it.second }
                    .filter { permitted(it.second) }
                    .filter { action.arg.isBlank() || it.first.contains(action.arg, true) || it.second.contains(action.arg, true) ||
                        SearchNavigation.matchesApp(action.arg, it.first, it.second) }
                    .sortedBy { it.first.lowercase(Locale.ROOT) }.take(80)
                list.forEach { apps[it.second] = it.first }
                ToolOutcome.Ok(list.joinToString("\n") { it.first + " = " + it.second }.ifBlank { "Не найдено. Попробуй другое написание или пустой поиск." })
            }
            "launch" -> {
                if (!permitted(action.arg))
                    return ToolOutcome.RejectedBeforeAction("Пакет запрещён политикой управления.")
                val label = apps[action.arg] ?: return ToolOutcome.RejectedBeforeAction(
                    "Сначала вызови apps по имени приложения из запроса. Запуск не выполнялся. Не угадывай пакет.")
                if (!goal.matchesSearchApp(action.arg, label))
                    return ToolOutcome.RejectedBeforeAction("Выбрано не то приложение. Найди через apps именно приложение из запроса.")
                authorize(action.arg)
                goal.launched(action.arg, label)
                val already = runCatching { ui {
                    require(nav().rootInActiveWindow?.packageName?.toString() == action.arg) { "Другое приложение" }
                    readScreen()
                } }.getOrNull()
                if (already?.pkg == action.arg) {
                    expectedLaunch = null
                    snapshot = already
                    return ToolOutcome.Ok("Приложение уже открыто; продолжай с текущего экрана.\n" + describeGoal(already), already.pkg)
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
                    return ToolOutcome.Failed("ERROR[APP_NOT_FOUND] Не удалось найти экран запуска для «" + label + "».")
                }
                if (NoxNavigationService.current == null) {
                    return ToolOutcome.Unverified(
                        "Отправил Android команду открыть «" + label + "», но проверить результат без " +
                        "службы специальных возможностей не могу.")
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
                        return ToolOutcome.Ok("Запуск подтверждён: " + action.arg + "\n" + describeGoal(observed), observed.pkg)
                    }
                }
                snapshot = null
                // Accessibility is connected, but Android may still be switching windows.
                // AgentCore performs bounded read-only observations; no clicks until verified.
                return ToolOutcome.LaunchRecheck(
                    "Android получил запрос на запуск «" + label + "», но экран ещё не подтвердил переход.")
            }
            "observe" -> ToolOutcome.Ok(observe(), snapshot?.pkg)
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
                if (ok) describeAfter("Android принял навигацию.") else ToolOutcome.Unverified("Навигация не подтверждена Android.")
            }
            "scroll" -> {
                val before = snapshot ?: error("Сначала используй observe")
                authorize(before.pkg)
                val target = before.nodes.firstOrNull { it.scrollable && it.enabled } ?: error("Нет доступной прокрутки")
                actionGate.before(if (action.arg == "down") "Листаю вниз." else "Листаю вверх.")
                snapshot = null
                val ok = try {
                    ui {
                        if (readScreen() != before) throw NodeChanged("SCREEN")
                        val n = resolveNode(before, target)
                        withVerifiedNode(target.identity, identity(n)) {
                            checkActive()
                            require(permissions.allowed(before.pkg)) { "Разрешение отозвано" }
                            checkUiBudget()
                            if (!n.isScrollable) throw NodeChanged("CAPABILITY")
                            activeUiBudget?.stage = "DISPATCH"
                            n.performAction(if (action.arg == "down") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                        }
                    }
                } catch (e: NodeChanged) { return staleNode(e) }
                goal.mutation()
                if (ok) describeAfter("Прокрутка принята.")
                else ToolOutcome.Unverified("Прокрутка не подтверждена Android.")
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
                ToolOutcome.Ok("Громкость медиа: " + audio.getStreamVolume(AudioManager.STREAM_MUSIC) + " из " + audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                }
            }
            else -> error("Неизвестный инструмент")
        }
    }
}
