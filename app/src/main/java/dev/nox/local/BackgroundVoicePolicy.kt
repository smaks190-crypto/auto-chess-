package dev.nox.local

/** Background microphone is opt-in. A locked session can only recognize the name or turn off. */
internal object BackgroundVoicePolicy {
    fun autoMode(enabled: Boolean, busy: Boolean, agentCanCancel: Boolean, listening: Boolean,
                 speechPending: Boolean, unlocked: Boolean, blocked: Boolean): VoiceInputMode? {
        if (!enabled || listening || speechPending || blocked) return null
        if (busy) return if (agentCanCancel && unlocked) VoiceInputMode.STOP_ONLY else null
        return if (unlocked) VoiceInputMode.WAKE else VoiceInputMode.LOCKED_WAKE
    }

    fun canCapture(mode: VoiceInputMode, enabled: Boolean, unlocked: Boolean): Boolean =
        if (mode == VoiceInputMode.LOCKED_WAKE) enabled && !unlocked
        else unlocked && (enabled || mode == VoiceInputMode.MANUAL)

    fun restoreAfterSystemRestart(nullIntent: Boolean, enabledBefore: Boolean, permission: Boolean): Boolean =
        nullIntent && enabledBefore && permission
}
