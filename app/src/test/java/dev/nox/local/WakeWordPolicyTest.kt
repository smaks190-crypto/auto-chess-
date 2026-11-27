package dev.nox.local

import org.junit.Assert.assertEquals
import org.junit.Test

class WakeWordPolicyTest {
    private fun expect(mode: VoiceInputMode, input: String, expected: WakeInput) =
        assertEquals("$mode / $input", expected, WakeWordPolicy.route(mode, input))

    @Test fun ambientConversationCannotActivate() {
        for (mode in listOf(VoiceInputMode.WAKE, VoiceInputMode.LOCKED_WAKE)) {
            for (input in listOf("открой телеграм", "позови Нокса", "ноксик открой", "", "отмени задачу")) {
                expect(mode, input, WakeInput.Ignore)
            }
        }
    }

    @Test fun namedBackgroundRequestsOnlyPromptForManualInput() {
        for (input in listOf("Нокс!", "  НОКС, Открой Telegram!", "NOX — открой браузер",
                "Нокс, выключи микрофон", "Нокс, отмена")) {
            expect(VoiceInputMode.WAKE, input, WakeInput.ManualRequired)
            expect(VoiceInputMode.LOCKED_WAKE, input, WakeInput.UnlockRequired)
        }
    }

    @Test fun wakePromptDoesNotArmASecondAmbientUtterance() {
        expect(VoiceInputMode.WAKE, "Нокс", WakeInput.ManualRequired)
        expect(VoiceInputMode.WAKE, "открой браузер", WakeInput.Ignore)
    }

    @Test fun explicitManualMicrophoneAcceptsCommandWithOrWithoutName() {
        expect(VoiceInputMode.MANUAL, "привет", WakeInput.Command("привет"))
        expect(VoiceInputMode.MANUAL, "Нокс, открой браузер", WakeInput.Command("открой браузер"))
        expect(VoiceInputMode.MANUAL, "Нокс", WakeInput.Ignore)
        expect(VoiceInputMode.MANUAL, "  ", WakeInput.Ignore)
    }

    @Test fun speechCannotChangePersistentMicrophoneSetting() {
        for (phrase in listOf("перестань слушать", "отключи микрофон", "останови прослушивание")) {
            expect(VoiceInputMode.MANUAL, phrase, WakeInput.Ignore)
            expect(VoiceInputMode.MANUAL, "Нокс, $phrase", WakeInput.Ignore)
            expect(VoiceInputMode.WAKE, phrase, WakeInput.Ignore)
            expect(VoiceInputMode.LOCKED_WAKE, phrase, WakeInput.Ignore)
        }
    }
}
