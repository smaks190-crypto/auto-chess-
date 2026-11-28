package dev.nox.local

import org.junit.Assert.*
import org.junit.Test

class AssistantVoiceCommandTest {
    @Test fun explicitScreenRequestOnly() {
        for (text in listOf("Нокс, посмотри на экран!", "Что на экране?", "Опиши экран", "Посмотри на экран"))
            assertTrue(text, AssistantVoiceCommand.isScreenRequest(text))
        for (text in listOf("Не смотри на экран", "Как посмотреть на экран?", "Посмотри на экран и отправь сообщение", "Что такое экран?"))
            assertFalse(text, AssistantVoiceCommand.isScreenRequest(text))
    }

    @Test fun routeToChatOnlyForForegroundUnlockedNox() {
        assertTrue(AssistantVoiceCommand.useChat(true, true, true, false, true, true))
        assertTrue(AssistantVoiceCommand.useChat(true, true, true, true, true, true))
        assertFalse(AssistantVoiceCommand.useChat(true, true, false, true, true, true))
        assertFalse(AssistantVoiceCommand.useChat(false, true, true, false, true, true))
        assertFalse(AssistantVoiceCommand.useChat(true, false, true, false, true, true))
        assertFalse(AssistantVoiceCommand.useChat(true, true, true, false, false, true))
        assertFalse(AssistantVoiceCommand.useChat(true, true, true, false, true, false))
    }

    @Test fun compactAgentRequiresActiveRoleAndUnlockedDevice() {
        assertTrue(AssistantVoiceCommand.canRunAgent(true, true))
        assertFalse(AssistantVoiceCommand.canRunAgent(false, true))
        assertFalse(AssistantVoiceCommand.canRunAgent(true, false))
        assertFalse(AssistantVoiceCommand.canRunAgent(false, false))
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
