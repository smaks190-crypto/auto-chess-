package dev.nox.local

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.UUID

internal object AssistantHandoff {
    const val EXTRA_TICKET = "dev.nox.local.ASSISTANT_TICKET"
    val gate = AssistantLaunchGate(SystemClock::elapsedRealtime, { UUID.randomUUID().toString() })
    fun active(context: Context): Boolean = runCatching {
        VoiceInteractionService.isActiveService(context, ComponentName(context, NoxSystemAssistant::class.java))
    }.getOrDefault(false)
}

/** Android owns this service's lifetime. Selection alone never starts a microphone. */
class NoxSystemAssistant : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        runCatching {
            setDisabledShowContext(VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
        }
    }
    override fun onShutdown() {
        AssistantHandoff.gate.revoke()
        super.onShutdown()
    }
}

class NoxAssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = NoxAssistantSession(this)
}

/** Use the existing app and its single microphone owner rather than capturing in a second service.
 * No assist bundle, screenshot or externally supplied command is read. */
private class NoxAssistantSession(private val ctx: Context) : VoiceInteractionSession(ctx) {
    private var status: TextView? = null
    private var talk: Button? = null
    private var chat: Button? = null
    private var shown = false

    override fun onCreateContentView(): View {
        val pad = (20 * ctx.resources.displayMetrics.density).toInt()
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.rgb(23, 25, 34))
        }
        panel.addView(TextView(ctx).apply {
            text = "N.O.X."
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(182, 161, 255))
        })
        status = TextView(ctx).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
        }.also { panel.addView(it) }
        talk = Button(ctx).apply {
            text = "Говорить"
            isAllCaps = false
            setOnClickListener { launch(true) }
        }.also { panel.addView(it) }
        chat = Button(ctx).apply {
            text = "Открыть Нокс"
            isAllCaps = false
            setOnClickListener { launch(false) }
        }.also { panel.addView(it) }
        panel.addView(Button(ctx).apply {
            text = "Закрыть"
            isAllCaps = false
            setOnClickListener { hide() }
        })
        refresh()
        return panel
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        shown = true
        refresh()
    }
    override fun onHide() {
        shown = false
        super.onHide()
    }
    private fun refresh() {
        val allowed = shown && AssistantHandoff.active(ctx) && DeviceInteraction.unlocked(ctx)
        status?.text = if (allowed) "Локальный ассистент · выбери действие" else "Разблокируй телефон или выбери Нокса системным ассистентом"
        talk?.isEnabled = allowed
        chat?.isEnabled = allowed
    }
    private fun launch(voice: Boolean) {
        if (!shown || !AssistantHandoff.active(ctx) || !DeviceInteraction.unlocked(ctx)) {
            refresh(); return
        }
        val ticket = if (voice) AssistantHandoff.gate.issue(true, true) ?: return else null
        if (!voice) AssistantHandoff.gate.revoke()
        val intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (ticket != null) intent.putExtra(AssistantHandoff.EXTRA_TICKET, ticket)
        try {
            // Use the ordinary chat task so subsequent agent launches are not trapped
            // in the special assistant task layer. A visible assistant session initiates this.
            ctx.startActivity(intent)
            hide()
        } catch (e: RuntimeException) {
            AssistantHandoff.gate.revoke()
            status?.text = "Android не открыл чат. Открой Нокс вручную."
        }
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
