package dev.nox.local

import org.junit.Assert.*
import org.junit.Test

class AssistantVoiceCommandTest {
    @Test fun routeToChatOnlyForForegroundUnlockedNox() {
        assertTrue(AssistantVoiceCommand.useChat(true, true, true, false, true, true))
        assertTrue(AssistantVoiceCommand.useChat(true, true, true, true, true, true))
        assertFalse(AssistantVoiceCommand.useChat(true, true, false, true, true, true))
        assertFalse(AssistantVoiceCommand.useChat(false, true, true, false, true, true))
        assertFalse(AssistantVoiceCommand.useChat(true, false, true, false, true, true))
        assertFalse(AssistantVoiceCommand.useChat(true, true, true, false, false, true))
        assertFalse(AssistantVoiceCommand.useChat(true, true, true, false, true, false))
    }

    @Test fun missingNativeToolsAreNeverReportedAsExecuted() {
        assertNotNull(AssistantVoiceCommand.dedicatedToolUnavailable("Поставь таймер на минуту"))
        assertNotNull(AssistantVoiceCommand.dedicatedToolUnavailable("Поищи в интернете новости"))
        assertNull(AssistantVoiceCommand.dedicatedToolUnavailable("Что такое таймер?"))
        assertNull(AssistantVoiceCommand.dedicatedToolUnavailable("Расскажи анекдот"))
    }

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
