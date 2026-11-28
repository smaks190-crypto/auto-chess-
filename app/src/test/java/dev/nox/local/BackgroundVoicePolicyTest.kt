package dev.nox.local

import org.junit.Assert.*
import org.junit.Test

class BackgroundVoicePolicyTest {
    private fun mode(enabled: Boolean = true, busy: Boolean = false,
        listening: Boolean = false, speech: Boolean = false, unlocked: Boolean = true,
        blocked: Boolean = false) =
        BackgroundVoicePolicy.autoMode(enabled, busy, listening, speech, unlocked, blocked)

    @Test fun wakeHandoffSuspendsStandbyUntilPanelClosesOrRequestExpires() {
        assertTrue(BackgroundVoicePolicy.wakeHandoffBlocksStandby(false, 1000, 7000))
        assertTrue(BackgroundVoicePolicy.wakeHandoffBlocksStandby(true, 8000, 0))
        assertFalse(BackgroundVoicePolicy.wakeHandoffBlocksStandby(false, 7000, 7000))
        assertFalse(BackgroundVoicePolicy.wakeHandoffBlocksStandby(false, 8000, 0))
    }

    @Test fun standbyContinuesWhileLockedOnlyWhenOptedIn() {
        assertEquals(VoiceInputMode.LOCKED_WAKE, mode(unlocked = false))
        assertEquals(VoiceInputMode.WAKE, mode())
        assertNull(mode(enabled = false, unlocked = false))
        assertNull(mode(blocked = true, unlocked = false))
    }
    @Test fun noSecondCaptureOrSelfActivationDuringSpeech() {
        assertNull(mode(listening = true, unlocked = false))
        assertNull(mode(speech = true, unlocked = false))
        assertNull(mode(speech = true))
    }
    @Test fun busyAgentNeverProcessesAmbientSpeech() {
        assertNull(mode(busy = true))
        assertNull(mode(busy = true, unlocked = false))
    }
    @Test fun lockTransitionInvalidatesOldVoiceSession() {
        assertFalse(BackgroundVoicePolicy.canCapture(VoiceInputMode.WAKE, true, false))
        assertFalse(BackgroundVoicePolicy.canCapture(VoiceInputMode.MANUAL, true, false))
        assertTrue(BackgroundVoicePolicy.canCapture(VoiceInputMode.LOCKED_WAKE, true, false))
        assertFalse(BackgroundVoicePolicy.canCapture(VoiceInputMode.LOCKED_WAKE, true, true))
        assertTrue(BackgroundVoicePolicy.canCapture(VoiceInputMode.MANUAL, false, true))
        assertFalse(BackgroundVoicePolicy.canCapture(VoiceInputMode.LOCKED_WAKE, false, false))
    }
    @Test fun lockedNameNeverReturnsAnExecutableCommand() {
        assertEquals(WakeInput.UnlockRequired, WakeWordPolicy.route(VoiceInputMode.LOCKED_WAKE, "Нокс, открой Telegram"))
        assertEquals(WakeInput.UnlockRequired, WakeWordPolicy.route(VoiceInputMode.LOCKED_WAKE, "Нокс"))
        assertEquals(WakeInput.Ignore, WakeWordPolicy.route(VoiceInputMode.LOCKED_WAKE, "открой Telegram"))
        assertEquals(WakeInput.Ignore, WakeWordPolicy.route(VoiceInputMode.LOCKED_WAKE, "Ноксик"))
        assertEquals(WakeInput.UnlockRequired, WakeWordPolicy.route(VoiceInputMode.LOCKED_WAKE, "Нокс, выключи микрофон"))
    }
    @Test fun swipeKeyguardAndUnknownStateAlsoDenyCommands() {
        assertTrue(BackgroundVoicePolicy.screenAllowsCommands(true, false, false))
        assertFalse(BackgroundVoicePolicy.screenAllowsCommands(true, true, false))
        assertFalse(BackgroundVoicePolicy.screenAllowsCommands(true, false, true))
        assertFalse(BackgroundVoicePolicy.screenAllowsCommands(false, false, false))
        assertFalse(BackgroundVoicePolicy.screenAllowsCommands(true, null, false))
    }
    @Test fun onlySystemRecreationOfOptedInSessionMayRestore() {
        assertTrue(BackgroundVoicePolicy.restoreAfterSystemRestart(true, true, true))
        assertFalse(BackgroundVoicePolicy.restoreAfterSystemRestart(false, true, true))
        assertFalse(BackgroundVoicePolicy.restoreAfterSystemRestart(true, false, true))
        assertFalse(BackgroundVoicePolicy.restoreAfterSystemRestart(true, true, false))
    }
}
