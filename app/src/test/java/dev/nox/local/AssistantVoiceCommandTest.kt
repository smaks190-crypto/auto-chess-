package dev.nox.local

import org.junit.Assert.*
import org.junit.Test

class AssistantVoiceCommandTest {
    @Test fun onlyFinalArmedSystemVoiceRunsCommand() {
        assertEquals("открой калькулятор", AssistantVoiceCommand.fromResult(true, true, "Нокс, открой калькулятор"))
        assertNull(AssistantVoiceCommand.fromResult(true, false, "открой"))
        assertNull(AssistantVoiceCommand.fromResult(false, true, "открой калькулятор"))
    }
    @Test fun blankAndStopPhraseAreNotCommands() {
        assertNull(AssistantVoiceCommand.fromResult(true, true, ""))
        assertNull(AssistantVoiceCommand.fromResult(true, true, "Нокс"))
        assertNull(AssistantVoiceCommand.fromResult(true, true, "стоп"))
        assertNull(AssistantVoiceCommand.fromResult(true, true, "Nox, стоп"))
        assertNull(AssistantVoiceCommand.fromResult(true, true, "выключи микрофон"))
        assertNull(AssistantVoiceCommand.fromResult(true, true, "x".repeat(2001)))
    }
}
