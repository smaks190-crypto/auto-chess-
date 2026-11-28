package dev.nox.local

import org.junit.Assert.*
import org.junit.Test

class RequestRouterTest {
    private fun noClassifier(request: String, expected: RequestMode, image: Boolean = false) {
        assertEquals(expected, RequestRouter.route(request, image) { error("Unexpected classification") })
    }

    @Test fun greetingsAndArithmeticNeverEnterAgent() {
        noClassifier("Привет!", RequestMode.CHAT)
        noClassifier("Нокс, сколько будет два плюс два?", RequestMode.CHAT)
    }
    @Test fun explicitInterfaceActionsUseAgent() {
        noClassifier("Открой Telegram и напиши Сане привет", RequestMode.AGENT)
        noClassifier("Прочитай экран", RequestMode.AGENT)
    }
    @Test fun imageRequestsDoNotUseAgent() {
        noClassifier("Открой Telegram", RequestMode.CHAT, true)
    }
    @Test fun ambiguousRequestsAreClassifiedWithoutPriorCommands() {
        assertEquals(RequestMode.AGENT, RequestRouter.route("Напиши Сане привет", false) { "AGENT" })
        assertEquals(RequestMode.CHAT, RequestRouter.route("Как открыть Telegram?", false) { "CHAT" })
        assertEquals(RequestMode.CHAT, RequestRouter.route("Напиши стихотворение", false) { "CHAT" })
    }
    @Test fun explicitTextTasksAvoidExtraGeneration() {
        noClassifier("Перефразируй одним предложением: встреча завтра", RequestMode.CHAT)
        noClassifier("Напиши короткое письмо о встрече", RequestMode.CHAT)
        noClassifier("Объясни принцип работы", RequestMode.CHAT)
        noClassifier("Как открыть Telegram?", RequestMode.CHAT)
    }
    @Test fun combinedTextAndDeviceRequestsStillUseClassifier() {
        var called = false
        val mode = RequestRouter.route("Перефразируй текст и отправь его в Telegram", false) {
            called = true
            "AGENT"
        }
        assertTrue(called)
        assertEquals(RequestMode.AGENT, mode)
    }
    @Test fun ambiguousRecipientAndLiveAppRequestsAreNotForcedToChat() {
        for (request in listOf("Напиши сообщение Сане", "Объясни что видно на экране", "Как открыть Telegram и отправь сообщение")) {
            assertEquals(RequestMode.AGENT, RequestRouter.route(request, false) { "AGENT" })
        }
    }
    @Test fun invalidClassificationCannotGrantTools() {
        assertEquals(RequestMode.CHAT, RequestRouter.route("Расскажи о себе", false) { "AGENT and then CHAT" })
    }
}
