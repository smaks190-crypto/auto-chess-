package dev.nox.local

import org.junit.Assert.*
import org.junit.Test

class WakeWordPolicyTest {
    private fun expect(mode: VoiceInputMode, input: String, expected: WakeInput) =
        assertEquals("$mode / $input", expected, WakeWordPolicy.route(mode, input))

    @Test fun ambientConversationCannotActivate() {
        expect(VoiceInputMode.WAKE, "открой телеграм", WakeInput.Ignore)
        expect(VoiceInputMode.WAKE, "позови Нокса", WakeInput.Ignore)
        expect(VoiceInputMode.WAKE, "ноксик открой", WakeInput.Ignore)
        expect(VoiceInputMode.WAKE, "", WakeInput.Ignore)
    }
    @Test fun exactNameArmsCommandWindow() {
        expect(VoiceInputMode.WAKE, "Нокс!", WakeInput.Arm)
        expect(VoiceInputMode.COMMAND, "Нокс", WakeInput.Ignore)
    }
    @Test fun commandCanFollowNameInSameUtterance() {
        expect(VoiceInputMode.WAKE, "  НОКС, Открой Telegram!", WakeInput.Command("Открой Telegram!"))
        expect(VoiceInputMode.WAKE, "NOX — открой браузер", WakeInput.Command("открой браузер"))
    }
    @Test fun commandWindowAcceptsSpeechWithoutRepeatingName() {
        expect(VoiceInputMode.COMMAND, "открой браузер", WakeInput.Command("открой браузер"))
    }
    @Test fun explicitManualMicrophoneNeedsNoName() {
        expect(VoiceInputMode.MANUAL, "привет", WakeInput.Command("привет"))
    }
    @Test fun disableRequiresNameInStandby() {
        expect(VoiceInputMode.WAKE, "Нокс, перестань слушать", WakeInput.Disable)
        expect(VoiceInputMode.WAKE, "перестань слушать", WakeInput.Ignore)
    }
    @Test fun runningTaskOnlyAcceptsNamedCancellationOrDisable() {
        expect(VoiceInputMode.STOP_ONLY, "отмена", WakeInput.Ignore)
        expect(VoiceInputMode.STOP_ONLY, "Нокс, отмена", WakeInput.CancelTask)
        expect(VoiceInputMode.STOP_ONLY, "Нокс, открой Telegram", WakeInput.Ignore)
        expect(VoiceInputMode.STOP_ONLY, "Нокс, выключи микрофон", WakeInput.Disable)
    }
}
