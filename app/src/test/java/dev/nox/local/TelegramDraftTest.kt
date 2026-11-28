package dev.nox.local

import org.junit.Assert.*
import org.junit.Test

class TelegramDraftTest {
    private val pkg = "org.telegram.messenger"
    private val composer = GoalNode("", "", "1", hint = "Сообщение", kind = "EditText", enabled = true, editable = true, text = "")
    @Test fun openAndWriteDoesNotTreatRecipientAsMessage() {
        val request = "открой телеграм и напиши андер александр"
        val goal = GoalState(request)
        assertTrue(goal.telegramOpenWrite)
        assertTrue(goal.supported)
        assertFalse(goal.telegramDraftGoal)
        assertTrue(goal.matchesSearchApp("org.telegram.messenger.web", "Telegram"))
        assertFalse(goal.matchesSearchApp("other.app", "Other"))
        goal.launched("org.telegram.messenger.web", "Telegram")
        val result = goal.finish(GoalObservation(goal.sequence(), "org.telegram.messenger.web", emptyList()))
        assertNotNull(result)
        assertTrue(result!!.contains("Уточни"))
        assertTrue(result.contains("Сообщение не отправлено"))
        assertFalse(TelegramDraft.openWriteNeedsClarification("открой телеграм и напиши в поиске привет"))
    }
    @Test fun parsesExactCurrentChatRequest() {
        assertEquals(TelegramDraft.Request("привет", false), TelegramDraft.request("напиши привет в чате тг"))
        assertEquals(TelegramDraft.Request("Привет!", false), TelegramDraft.request("В чате Telegram напиши «Привет!»"))
        assertEquals(TelegramDraft.Request("hello", false), TelegramDraft.request("write hello in the current telegram chat"))
        assertEquals(TelegramDraft.Request("привет", true), TelegramDraft.request("отправь привет в чате ТГ"))
        assertNull(TelegramDraft.request("напиши стихотворение"))
        assertNull(TelegramDraft.request("write hello"))
    }
    @Test fun alreadyOpenChatNeedsNoLaunchAndVerifiesAfterHintDisappears() {
        val goal = GoalState("напиши привет в чате тг")
        fun observed(node: GoalNode = composer) = GoalObservation(goal.sequence(), pkg, listOf(node))
        val before = observed()
        assertEquals("1", goal.telegramComposer(before)?.id)
        goal.mutation()
        val typed = composer.copy(text = "привет", label = "привет", hint = "")
        assertTrue(goal.telegramTyped(before, observed(typed), "привет"))
        assertNotNull(goal.finish(observed(typed)))
        assertNull(goal.finish(observed(typed.copy(text = "другой текст"))))
    }
    @Test fun wrongAppSearchAndAmbiguousFieldsAreRejected() {
        val goal = GoalState("напиши привет в чате тг")
        fun observation(app: String, vararg nodes: GoalNode) = GoalObservation(goal.sequence(), app, nodes.toList())
        assertNull(goal.telegramComposer(observation("other.app", composer)))
        assertNull(goal.telegramComposer(observation(pkg, composer.copy(hint = "Поиск"))))
        assertNull(goal.telegramComposer(observation(pkg, composer, composer.copy(id = "2"))))
        val before = observation(pkg, composer)
        goal.mutation()
        assertFalse(goal.telegramTyped(before, observation(pkg, composer.copy(text = "привет всем")), "привет"))
        assertFalse(goal.telegramTyped(before, observation("other.app", composer.copy(text = "привет")), "привет"))
    }
    @Test fun explicitSendIsNotVerifiedAsDraft() {
        val goal = GoalState("отправь привет в чате тг")
        val before = GoalObservation(goal.sequence(), pkg, listOf(composer))
        assertNull(goal.telegramComposer(before))
        goal.mutation()
        val after = GoalObservation(goal.sequence(), pkg, listOf(composer.copy(text = "привет")))
        assertTrue(goal.telegramSendRequested)
        assertFalse(goal.telegramTyped(before, after, "привет"))
        assertNull(goal.finish(after))
    }
    @Test fun modelClassifierNotNeededForExactTelegramGoal() {
        assertEquals(RequestMode.AGENT, RequestRouter.route("напиши привет в чате тг", false) { error("No model needed") })
    }
}
