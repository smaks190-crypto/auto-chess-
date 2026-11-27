package dev.nox.local

import org.junit.Assert.*
import org.junit.Test

class SearchNavigationTest {
    private val pkg = "org.telegram.messenger.web"
    private val back = GoalNode("", "Назад", "2", description = "Назад", kind = "ImageView", clickable = true)
    private val composer = GoalNode("", "Сообщение", "1", hint = "Сообщение", kind = "EditText", editable = true, clickable = true)
    private val translate = GoalNode("", "Перевести", "3", description = "Перевести", kind = "Button", clickable = true)
    private val search = GoalNode("", "Поиск", "4", description = "Поиск", kind = "ImageButton", clickable = true)
    private val field = GoalNode("", "Поиск чатов", "5", hint = "Поиск чатов", description = "Поиск чатов", kind = "EditText", editable = true)
    @Test fun chatListSearchUsesRealSemanticControls() {
        val g = GoalState("Open Telegram and open Search. Do not send anything")
        assertTrue(g.searchNavigation)
        g.launched(pkg, "Telegram")
        fun observe(nodes: List<GoalNode>) = GoalObservation(g.sequence(), pkg, nodes)
        val chat = observe(listOf(composer, back, translate))
        assertEquals("2", g.searchNext(chat)); assertNull(g.finish(chat))
        g.mutation()
        val list = observe(listOf(search))
        assertTrue(g.searchAfter(chat, list))
        assertEquals("4", g.searchNext(list)); assertNull(g.finish(list))
        g.mutation()
        val open = observe(listOf(field))
        assertTrue(g.searchAfter(list, open))
        assertNotNull(g.finish(open)); assertNull(g.finish(list))
    }
    @Test fun unchangedOrUnrelatedScreenDoesNotVerify() {
        val g = GoalState("Открой Telegram и открой поиск")
        g.launched(pkg, "Telegram")
        val before = GoalObservation(g.sequence(), pkg, listOf(search))
        g.mutation()
        assertFalse(g.searchAfter(before, GoalObservation(g.sequence(), pkg, listOf(search))))
        assertFalse(g.searchAfter(before, GoalObservation(g.sequence(), "other", listOf(field))))
        assertNull(g.finish(GoalObservation(g.sequence(), "other", listOf(field))))
    }
    @Test fun ambiguousAndTextOnlyControlsAreNotEvidence() {
        assertNull(SearchNavigation.next(listOf(search, search.copy(id = "9"))))
        assertNull(SearchNavigation.next(listOf(search.copy(description = ""))))
        assertEquals(SearchNavigation.State.UNKNOWN, SearchNavigation.state(listOf(field.copy(hint = "", description = ""))))
        assertEquals(SearchNavigation.State.UNKNOWN, SearchNavigation.state(listOf(field, composer)))
        assertNull(SearchNavigation.next(listOf(translate)))
    }
    @Test fun narrowRequestAndAppBinding() {
        assertNull(SearchNavigation.target("Open Telegram and open search and send hello"))
        assertNull(SearchNavigation.target("Открой Telegram и напиши привет"))
        val g = GoalState("Открой тг и открой поиск. Ничего не отправляй")
        assertTrue(g.matchesSearchApp(pkg, "Telegram"))
        assertFalse(g.matchesSearchApp("other.app", "Other"))
        assertNull(g.searchNext(GoalObservation(g.sequence(), pkg, listOf(search))))
    }
    @Test fun rejectedLaunchPreconditionIsRecoverableWithoutRepeatingMutation() {
        val calls = mutableListOf<String>()
        var i = 0
        val g = GoalState("Open Telegram and open Search")
        val decisions = listOf(AgentDecision.Tool("launch", pkg), AgentDecision.Tool("apps", "Telegram"), AgentDecision.Tool("launch", pkg), AgentDecision.Finish("done"))
        val core = AgentCore(decide = { decisions[i++] }, execute = { error("legacy") },
            cancelled = { false }, goal = g, verifyFinish = { "search verified" },
            executeTyped = { action ->
                calls.add(action.name)
                when {
                    calls.size == 1 -> ToolOutcome.RejectedBeforeAction("lookup required")
                    action.name == "apps" -> ToolOutcome.Ok("Telegram = $pkg")
                    else -> ToolOutcome.Ok("search", pkg)
                }
            })
        assertEquals("search verified", core.run("Open Telegram and open Search", ""))
        assertEquals(listOf("launch","apps","launch"), calls)
    }
}
