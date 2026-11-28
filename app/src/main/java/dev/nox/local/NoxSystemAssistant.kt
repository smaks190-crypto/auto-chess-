package dev.nox.local

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.Manifest
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.text.InputFilter
import android.text.TextWatcher
import android.text.Editable
import android.widget.EditText
import android.widget.ImageButton
import kotlin.math.sin
import kotlin.math.pow
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.SystemClock
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.UUID

internal object AssistantHandoff {
    const val EXTRA_TICKET = "dev.nox.local.ASSISTANT_TICKET"
    val gate = AssistantLaunchGate(SystemClock::elapsedRealtime, { UUID.randomUUID().toString() })
    fun issueDraft(context: Context, text: String, execute: Boolean = false): String? {
        val isActive = active(context)
        val unlocked = DeviceInteraction.unlocked(context)
        val ticket = (if (execute) gate.issueCommand(isActive, unlocked, text)
            else gate.issueDraft(isActive, unlocked, text)) ?: return null
        Handler(Looper.getMainLooper()).postDelayed({ gate.expire(ticket) }, 5000L)
        return ticket
    }
    fun revoke() { gate.revoke() }
    fun active(context: Context): Boolean = runCatching {
        VoiceInteractionService.isActiveService(context, ComponentName(context, NoxSystemAssistant::class.java))
    }.getOrDefault(false)
}

/** Android owns this service's lifetime. Selection alone never starts a microphone. */
class NoxSystemAssistant : VoiceInteractionService() {
    companion object {
        private var readyService: java.lang.ref.WeakReference<NoxSystemAssistant>? = null
        internal var wakeCommand: Pair<String, Long>? = null
            private set
        internal fun takeWakeCommand(): String? {
            val value = wakeCommand
            wakeCommand = null
            return value?.takeIf { SystemClock.elapsedRealtime() < it.second }?.first
        }
        internal fun openFromWake(raw: String): Boolean {
            val service = readyService?.get() ?: return false
            if (!AssistantHandoff.active(service) || !DeviceInteraction.unlocked(service)) return false
            val command = AssistantVoiceCommand.fromResult(true, true, raw).orEmpty()
            wakeCommand = command to (SystemClock.elapsedRealtime() + 5000L)
            return runCatching { service.showSession(Bundle(), 0); true }.getOrElse {
                wakeCommand = null
                false
            }
        }
    }

    override fun onReady() {
        super.onReady()
        readyService = java.lang.ref.WeakReference(this)
        runCatching {
            setDisabledShowContext(VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
        }
    }
    override fun onShutdown() {
        if (readyService?.get() === this) readyService = null
        wakeCommand = null
        AssistantHandoff.revoke()
        super.onShutdown()
    }
}

class NoxAssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = NoxAssistantSession(this)
}

/** Use the existing app and its single microphone owner rather than capturing in a second service.
 * No assist bundle, screenshot or externally supplied command is read. */
private class NoxAssistantSession(private val ctx: Context) : VoiceInteractionSession(ctx) {
    private val main = Handler(Looper.getMainLooper())
    private var status: TextView? = null
    private var input: EditText? = null
    private var talk: ImageButton? = null
    private var chat: ImageButton? = null
    private var send: ImageButton? = null
    private var wave: AssistantWave? = null
    private var panel: FrameLayout? = null
    private var service: NoxService? = null
    private var bound = false
    private var shown = false
    private var inAppTarget: MainActivity? = null
    private var autoStartPending = false
    private var autoExecuteRecording = false
    private var session: Long? = null
    private var draftBefore = ""
    private var lastPartial = ""
    private var notice: String? = null
    private var answer: TextView? = null
    private var pendingCommand: Pair<String, Long>? = null
    private var modelLoadAttempted = false
    private var requestId: Long? = null
    private fun dp(value: Int) = (value * ctx.resources.displayMetrics.density + .5f).toInt()
    private fun allowed() = shown && AssistantHandoff.active(ctx) && DeviceInteraction.unlocked(ctx)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (!shown) return
            service = (binder as? NoxService.LocalBinder)?.service
            if (pendingCommand != null) drainCommand() else maybeAutoStart()
            refresh()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            autoStartPending = false
            autoExecuteRecording = false
            if (session != null) input?.setText(draftBefore)
            session = null
            notice = "Ядро отключено"; refresh()
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!shown) return
            if (!allowed()) {
                cancelRecording(); refresh()
                return
            }
            refresh()
            main.postDelayed(this, if (session != null) 40L else 100L)
        }
    }

    override fun onCreateContentView(): View {
        val root = FrameLayout(ctx).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { hide() }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnApplyWindowInsetsListener { v, insets ->
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                v.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                insets
            }
        }
        val surface = FrameLayout(ctx).apply {
            isClickable = true
            isFocusableInTouchMode = true
            background = GradientDrawable().apply {
                setColor(Color.rgb(34, 34, 37))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.rgb(55, 54, 61))
            }
            clipToOutline = true
            elevation = dp(8).toFloat()
        }
        panel = surface
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(10), dp(10))
        }
        status = TextView(ctx).apply {
            textSize = 11f
            setTextColor(Color.rgb(169, 161, 183))
            maxLines = 2
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
        }.also { content.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        answer = TextView(ctx).apply {
            textSize = 15f
            setTextColor(Color.rgb(244, 242, 248))
            setTextIsSelectable(true)
            visibility = View.GONE
        }
        val answerScroll = android.widget.ScrollView(ctx).apply {
            isFillViewport = false
            addView(answer, FrameLayout.LayoutParams(-1, -2))
            layoutParams = LinearLayout.LayoutParams(-1, dp(180))
            visibility = View.GONE
        }
        content.addView(answerScroll)
        input = EditText(ctx).apply {
            hint = "Спроси что-нибудь…"
            contentDescription = "Сообщение для Нокс"
            setHintTextColor(Color.rgb(156, 153, 163))
            setTextColor(Color.rgb(244, 242, 248))
            textSize = 16f
            background = null
            setPadding(dp(2), dp(4), dp(2), dp(4))
            minHeight = dp(44)
            maxLines = 3
            gravity = Gravity.TOP or Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            filters = arrayOf(InputFilter.LengthFilter(2000))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updateButtons() }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }.also { content.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        val controls = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        chat = control(R.drawable.ic_nox_chat, "Открыть чат") { launch(false) }
            .also { controls.addView(it, LinearLayout.LayoutParams(dp(48), dp(48))) }
        controls.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        talk = control(R.drawable.ic_nox_mic, "Начать диктовку") { toggleRecording() }
            .also { controls.addView(it, LinearLayout.LayoutParams(dp(48), dp(48))) }
        send = control(R.drawable.ic_nox_send, "Отправить команду") {
            val text = input?.text?.toString()?.trim().orEmpty()
            if (session == null && text.isNotEmpty()) submitInPlace(text)
        }.also { controls.addView(it, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(5) }) }
        controls.addView(control(R.drawable.ic_nox_close, "Закрыть") { hide() },
            LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(5) })
        content.addView(controls, LinearLayout.LayoutParams(-1, -2))
        wave = AssistantWave(ctx).also {
            it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            it.visibility = View.GONE
            content.addView(it, LinearLayout.LayoutParams(-1, dp(42)))
        }
        surface.addView(content, FrameLayout.LayoutParams(-1, -2))
        val params = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)
        params.setMargins(dp(12), 0, dp(12), dp(10))
        root.addView(surface, params)
        surface.requestFocus()
        refresh()
        return root
    }

    private fun control(icon: Int, label: String, click: () -> Unit): ImageButton = ImageButton(ctx).apply {
        setImageResource(icon)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        contentDescription = label
        backgroundTintList = null
        val surface = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.rgb(47, 46, 51))
        }
        background = RippleDrawable(ColorStateList.valueOf(Color.rgb(94, 82, 120)), surface, null)
        isFocusable = true
        setOnClickListener { click() }
    }

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        inAppTarget = MainActivity.foregroundAssistantTarget()?.takeIf {
            it.assistantCanStayInChat() && NoxSystemAssistant.wakeCommand?.first.isNullOrBlank()
        }
        // Disable UI before Android creates or shows the assistant window.
        setUiEnabled(inAppTarget == null)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val target = inAppTarget
        inAppTarget = null
        val wake = NoxSystemAssistant.takeWakeCommand()
        if (target != null) {
            target.acceptAssistantInvocation()
            main.post { hide() }
            return
        }
        getWindow().window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(.18f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }
        val newlyShown = !shown
        shown = true; notice = null
        if (newlyShown) autoStartPending = allowed() && wake.isNullOrBlank()
        if (!wake.isNullOrBlank() && allowed()) {
            autoStartPending = false
            modelLoadAttempted = false
            pendingCommand = wake to (SystemClock.elapsedRealtime() + 120_000L)
            input?.setText(wake)
        }
        if (allowed() && !bound) {
            bound = runCatching { ctx.bindService(Intent(ctx, NoxService::class.java), connection, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        }
        panel?.apply {
            requestFocus()
            if (ValueAnimator.areAnimatorsEnabled()) {
                alpha = 0f; translationY = dp(12).toFloat()
                animate().alpha(1f).translationY(0f).setDuration(180L).start()
            }
        }
        if (pendingCommand != null) drainCommand() else maybeAutoStart()
        refresh()
        main.removeCallbacks(tick); main.post(tick)
    }

    override fun onHide() {
        shown = false
        release()
        super.onHide()
    }

    override fun onDestroy() {
        shown = false
        release()
        super.onDestroy()
    }

    private fun release() {
        main.removeCallbacks(tick)
        inAppTarget = null
        autoStartPending = false
        pendingCommand = null
        requestId = null
        showAnswer("")
        cancelRecording()
        wave?.setListening(false, 0f, false)
        panel?.animate()?.cancel()
        if (bound) { bound = false; runCatching { ctx.unbindService(connection) } }
        service = null
        input?.setText("")
        notice = null
    }

    private fun maybeAutoStart() {
        if (!autoStartPending || service == null) return
        autoStartPending = false
        if (allowed() && session == null) toggleRecording()
    }

    private fun toggleRecording() {
        autoStartPending = false
        if (!allowed()) { refresh(); return }
        val s = service ?: run { notice = "Ядро подключается"; refresh(); return }
        session?.let {
            // A manual stop may retain partial text, never run an unfinished command.
            autoExecuteRecording = false
            s.stopWebDictation(it); refresh(); return
        }
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            notice = "Разреши микрофон в чате Нокс"; refresh(); return
        }
        draftBefore = input?.text?.toString().orEmpty()
        lastPartial = ""; notice = null
        input?.clearFocus()
        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
            ?.hideSoftInputFromWindow(input?.windowToken, 0)
        session = s.startWebDictation()
        autoExecuteRecording = session != null
        if (session == null) notice = if (s.busy) "Нокс занят" else "Микрофон недоступен"
        refresh()
    }

    private fun cancelRecording() {
        autoStartPending = false
        autoExecuteRecording = false
        val id = session ?: return
        session = null
        service?.cancelWebDictation(id)
        input?.setText(draftBefore)
        lastPartial = ""
    }

    private fun joined(text: String) = listOf(draftBefore.trimEnd(), text.trim()).filter { it.isNotEmpty() }.joinToString(" ").take(2000)

    private fun showAnswer(text: String) {
        answer?.apply {
            if (this.text.toString() != text) this.text = text
            visibility = if (text.isBlank()) View.GONE else View.VISIBLE
            (parent as? View)?.visibility = visibility
        }
    }

    private fun submitInPlace(text: String) {
        if (!allowed() || session != null || requestId != null || pendingCommand != null ||
            text.isBlank() || text.length > 2000) return
        autoStartPending = false
        showAnswer("")
        modelLoadAttempted = false
        pendingCommand = text to (SystemClock.elapsedRealtime() + 120_000L)
        drainCommand()
        refresh()
    }

    private fun drainCommand() {
        val (command, deadline) = pendingCommand ?: return
        if (!allowed() || SystemClock.elapsedRealtime() >= deadline) {
            pendingCommand = null
            notice = "Команда отменена или истекло время ожидания"
            return
        }
        val s = service ?: return
        if (s.busy) return
        val telegram = TelegramDraft.request(command)
        if (s.loaded == null && telegram == null && !modelLoadAttempted && AssistantVoiceCommand.dedicatedToolUnavailable(command) == null) {
            modelLoadAttempted = true
            if (s.prepareAssistantModel()) {
                notice = "Загружаю локальную модель…"
                return
            }
        }
        pendingCommand = null
        // Keep the finite task alive when the assistant panel releases its binding.
        // The panel must not cover the underlying Telegram composer during observation.
        if (telegram != null && !telegram.send) {
            val started = runCatching {
                ctx.startService(Intent(ctx, NoxService::class.java).setAction(NoxService.KEEP_ALIVE)) != null
            }.getOrDefault(false)
            if (!started) { notice = "Android не разрешил выполнение команды в фоне"; return }
        }
        requestId = s.askInPlace(command)
        if (requestId == null) notice = "Не удалось запустить команду"
        else {
            notice = null
            input?.setText(command)
            if (telegram != null && !telegram.send && s.busy) main.post { hide() }
        }
    }

    private fun refresh() {
        val permitted = allowed()
        val s = service
        var burst = false
        val id = session
        if (id != null) {
            if (!permitted || s == null || s.webDictationSession != id) {
                cancelRecording()
            } else if (s.webDictationActive) {
                val partial = s.recognizedText
                burst = partial.isNotEmpty() && partial != lastPartial
                if (partial != lastPartial) {
                    lastPartial = partial
                    input?.setText(joined(partial))
                }
            } else {
                val final = s.webDictationResult
                val command = AssistantVoiceCommand.fromResult(autoExecuteRecording, s.webDictationFinal, final)
                autoExecuteRecording = false
                input?.setText(joined(final))
                session = null
                s.cancelWebDictation(id)
                notice = if (final.isEmpty()) s.status else null
                if (command != null && allowed() && !s.busy) {
                    submitInPlace(command)
                    return
                }
            }
        }
        if (permitted) drainCommand()
        val running = requestId?.let { s != null && s.assistantRequestId == it && s.busy } == true
        requestId?.let { id ->
            if (s != null && s.assistantRequestId == id) {
                if (running) showAnswer(s.partial)
                else {
                    showAnswer(s.assistantReply.ifBlank { s.status })
                    requestId = null
                }
            } else requestId = null
        }
        val recording = session != null
        val message = when {
            !permitted -> "Разблокируй телефон"
            recording -> if (s?.status == "Слушаю…") "Слушаю…" else "Подключаю микрофон…"
            pendingCommand != null -> if (s == null) "Ядро подключается…" else s.status
            running -> s?.status ?: "Думаю…"
            notice != null -> notice
            s == null -> "Ядро подключается…"
            else -> null
        }
        status?.apply {
            if (text.toString() != message.orEmpty()) text = message.orEmpty()
            visibility = if (message == null) View.GONE else View.VISIBLE
        }
        input?.apply {
            isFocusableInTouchMode = permitted && !recording
            isFocusable = permitted && !recording
            isCursorVisible = permitted && !recording
        }
        wave?.setListening(recording && permitted, s?.manualVoiceLevel ?: 0f, burst)
        updateButtons()
    }

    private fun updateButtons() {
        val permitted = allowed()
        val recording = session != null
        talk?.apply {
            val enabled = permitted && service != null && (recording || service?.busy == false)
            isEnabled = enabled
            alpha = if (enabled) 1f else .45f
            val label = if (recording) "Остановить диктовку" else "Начать голосовую команду"
            if (contentDescription != label) {
                setImageResource(if (recording) R.drawable.ic_nox_stop else R.drawable.ic_nox_mic)
                contentDescription = label
            }
        }
        chat?.apply { isEnabled = permitted && !recording; alpha = if (isEnabled) 1f else .45f }
        send?.apply {
            visibility = if (!recording && !input?.text.isNullOrBlank()) View.VISIBLE else View.GONE
            isEnabled = permitted && requestId == null && pendingCommand == null && service?.busy == false
        }
    }

    private fun launch(submit: Boolean, draft: String = "", execute: Boolean = false) {
        if (!allowed() || session != null) { refresh(); return }
        autoStartPending = false
        val text = if (submit) draft.trim() else input?.text?.toString()?.trim().orEmpty()
        val ticket = if (text.isNotEmpty()) AssistantHandoff.issueDraft(ctx, text, execute) ?: return else null
        if (ticket == null) AssistantHandoff.revoke()
        val intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (ticket != null) intent.putExtra(AssistantHandoff.EXTRA_TICKET, ticket)
        try { ctx.startActivity(intent); hide() }
        catch (_: RuntimeException) {
            AssistantHandoff.revoke()
            notice = "Не удалось открыть чат"; refresh()
        }
    }
}

/** Low-cost coloured point field, driven only by the actual recording level. */
private class AssistantWave(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val colors = IntArray(80) { i -> Color.HSVToColor(floatArrayOf((310f - i * 3.35f + 360f) % 360f, .64f, 1f)) }
    private var active = false
    private var level = 0f
    private var smooth = 0f
    private var pulseAt = 0L
    private val density = resources.displayMetrics.density

    fun setListening(listening: Boolean, amplitude: Float, burst: Boolean) {
        active = listening
        level = if (listening) amplitude.coerceIn(0f, 1f) else 0f
        if (burst && listening) pulseAt = SystemClock.uptimeMillis()
        visibility = if (listening) VISIBLE else GONE
        if (!listening) { smooth = 0f; pulseAt = 0L }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!active || !isShown || width == 0 || height == 0) return
        val animated = ValueAnimator.areAnimatorsEnabled()
        val now = SystemClock.uptimeMillis()
        smooth = if (animated) VoiceMeter.follow(smooth, level) else level
        val cols = (width / (4.0f * density)).toInt().coerceIn(24, 80)
        val t = if (animated) now / 330.0 else 0.0
        for (i in 0 until cols) {
            val u = i.toFloat() / (cols - 1)
            val envelope = sin(u * Math.PI).pow(1.7).toFloat()
            val ridge = envelope * smooth * (.78f + .22f * sin(i * .24 + t).toFloat())
            val count = (ridge * 11f).toInt().coerceIn(0, 11)
            val x = (i + .5f) * width / cols
            for (j in 0..count) {
                paint.color = colors[i * (colors.size - 1) / (cols - 1)]
                paint.alpha = ((.24f + .76f * smooth) * (1f - j * .04f) * 255).toInt().coerceIn(0, 255)
                canvas.drawCircle(x, height - (j * 3.4f + 2.6f) * density, 1.05f * density, paint)
            }
        }
        val age = (now - pulseAt) / 1000f
        if (animated && pulseAt != 0L && age in 0f..0.7f) {
            for (i in 0..16) {
                paint.color = colors[(i * 11) % colors.size]
                paint.alpha = ((1f - age / .7f) * 175).toInt()
                canvas.drawCircle(width * (.22f + i * .033f) + sin(i + t).toFloat() * 7f * density,
                    height - age * 28f * density, .9f * density, paint)
            }
        }
        if (animated) postInvalidateOnAnimation()
    }
}

/** Android requires a recognition-service component in assistant metadata for compatibility.
 * Not a general dictation provider: reject third-party recognition without recording anything.
 * All actual Nox recognition remains in NoxService / SilentRecognizer. */
class NoxAssistantRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback) {
        listener.error(SpeechRecognizer.ERROR_CLIENT)
    }
    override fun onStopListening(listener: Callback) {
        listener.error(SpeechRecognizer.ERROR_CLIENT)
    }
    override fun onCancel(listener: Callback) = Unit
}
