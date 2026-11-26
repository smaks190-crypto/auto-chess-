package dev.nox.local

internal object VoiceLoopPolicy {
    fun canListen(enabled: Boolean, busy: Boolean, listening: Boolean, speechPending: Boolean,
        unlocked: Boolean, blocked: Boolean): Boolean =
        enabled && !busy && !listening && !speechPending && unlocked && !blocked

    // Android SpeechRecognizer error codes. Missing language/permission and client errors require user action.
    fun retryDelay(error: Int, failures: Int): Long? = when (error) {
        6, 7 -> 400L // speech timeout, no match
        1, 2, 3, 4, 8, 11, 14 -> (1000L shl (failures - 1).coerceIn(0, 3)).coerceAtMost(8000L)
        else -> null
    }
}
