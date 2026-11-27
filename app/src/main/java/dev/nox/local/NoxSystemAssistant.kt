package dev.nox.local

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
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
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Button
import android.widget.ImageView
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
        val density = ctx.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        val root = FrameLayout(ctx).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { hide() }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnApplyWindowInsetsListener { v, insets ->
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                insets
            }
        }
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = GradientDrawable().apply {
                setColor(Color.rgb(25, 25, 28))
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Color.rgb(54, 52, 61))
            }
            elevation = dp(12).toFloat()
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_nox)
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        header.addView(logo, LinearLayout.LayoutParams(dp(48), dp(48)))
        val identity = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        identity.addView(TextView(ctx).apply {
            text = "N.O.X."
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(245, 243, 248))
        })
        status = TextView(ctx).apply {
            textSize = 13f
            setTextColor(Color.rgb(190, 187, 198))
            maxLines = 2
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }.also { identity.addView(it) }
        header.addView(identity, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(header)

        talk = action("Говорить", R.drawable.ic_nox_mic, Color.rgb(201, 182, 255), Color.rgb(32, 29, 40)) {
            launch(true)
        }.also { panel.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
            topMargin = dp(16)
        }) }
        val secondary = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        chat = action("Открыть чат", R.drawable.ic_nox_chat, Color.rgb(232, 229, 238), Color.rgb(43, 43, 48)) {
            launch(false)
        }.also { secondary.addView(it, LinearLayout.LayoutParams(0, dp(48), 1f)) }
        secondary.addView(action("Закрыть", R.drawable.ic_nox_close, Color.rgb(208, 205, 215), Color.rgb(43, 43, 48)) {
            hide()
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(8) })
        panel.addView(secondary, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
            topMargin = dp(8)
        })
        val panelParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        panelParams.setMargins(dp(12), 0, dp(12), dp(12))
        root.addView(panel, panelParams)
        refresh()
        return root
    }

    private fun action(label: String, icon: Int, foreground: Int, backgroundColor: Int, onClick: () -> Unit): Button {
        val density = ctx.resources.displayMetrics.density
        val height = (48 * density + 0.5f).toInt()
        return Button(ctx).apply {
            text = label
            isAllCaps = false
            stateListAnimator = null
            minWidth = 0
            minimumWidth = 0
            setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
            compoundDrawablePadding = (6 * density).toInt()
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(foreground)
            val surface = GradientDrawable().apply {
                setColor(backgroundColor)
                cornerRadius = height / 2f
            }
            backgroundTintList = null
            background = RippleDrawable(ColorStateList.valueOf(Color.rgb(80, 70, 100)), surface, null)
            isClickable = true
            isFocusable = true
            minHeight = height
            contentDescription = label
            setOnClickListener { onClick() }
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        getWindow().window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.28f)
        }
        shown = true
        refresh()
    }
    override fun onHide() {
        shown = false
        super.onHide()
    }
    private fun refresh() {
        val allowed = shown && AssistantHandoff.active(ctx) && DeviceInteraction.unlocked(ctx)
        status?.text = if (allowed) "Локальный ассистент готов" else "Разблокируй телефон или выбери Нокса системным ассистентом"
        talk?.isEnabled = allowed
        chat?.isEnabled = allowed
        talk?.alpha = if (allowed) 1f else 0.45f
        chat?.alpha = if (allowed) 1f else 0.45f
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
