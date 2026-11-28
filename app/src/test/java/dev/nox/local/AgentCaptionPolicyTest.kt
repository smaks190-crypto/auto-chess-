package dev.nox.local

import org.junit.Assert.*
import org.junit.Test

class AgentCaptionPolicyTest {
    @Test fun captionNeverCoversOwnChat() {
        assertNull(AgentCaptionPolicy.select(true, "Открываю Telegram", "Ответ"))
    }
    @Test fun thinkingUsesRingWithoutCaption() {
        assertNull(AgentCaptionPolicy.select(false, null, ""))
    }
    @Test fun resolvedActionHasStopControlAndOverridesSpeech() {
        assertEquals(AgentCaption("Открываю Telegram", true),
            AgentCaptionPolicy.select(false, "Открываю Telegram", "Старый ответ"))
    }
    @Test fun finalSpokenAnswerIsNotAnAction() {
        assertEquals(AgentCaption("Ответ", false), AgentCaptionPolicy.select(false, null, "Ответ"))
    }
    @Test fun agentOverlayWorksWithoutBackgroundVoice() {
        assertTrue(AgentCaptionPolicy.overlay(false, true, true, true))
        assertTrue(AgentCaptionPolicy.overlay(true, false, true, true))
        assertFalse(AgentCaptionPolicy.overlay(false, false, true, true))
        assertFalse(AgentCaptionPolicy.overlay(true, true, false, true))
        assertFalse(AgentCaptionPolicy.overlay(true, true, true, false))
    }
}
