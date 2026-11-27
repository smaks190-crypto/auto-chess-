package dev.nox.local

/** Background microphone is opt-in. A background session can only recognize the name. */
internal object BackgroundVoicePolicy {
    fun autoMode(enabled: Boolean, busy: Boolean, listening: Boolean,
                 speechPending: Boolean, unlocked: Boolean, blocked: Boolean): VoiceInputMode? {
        if (!enabled || busy || listening || speechPending || blocked) return null
        return if (unlocked) VoiceInputMode.WAKE else VoiceInputMode.LOCKED_WAKE
    }

    fun canCapture(mode: VoiceInputMode, enabled: Boolean, unlocked: Boolean): Boolean =
        if (mode == VoiceInputMode.LOCKED_WAKE) enabled && !unlocked
        else unlocked && (enabled || mode == VoiceInputMode.MANUAL)

    fun screenAllowsCommands(interactive: Boolean?, keyguardShowing: Boolean?, deviceLocked: Boolean?): Boolean =
        interactive == true && keyguardShowing == false && deviceLocked == false

    fun restoreAfterSystemRestart(nullIntent: Boolean, enabledBefore: Boolean, permission: Boolean): Boolean =
        nullIntent && enabledBefore && permission
}
