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
    @Test fun invalidClassificationCannotGrantTools() {
        assertEquals(RequestMode.CHAT, RequestRouter.route("Расскажи о себе", false) { "AGENT and then CHAT" })
    }
}
