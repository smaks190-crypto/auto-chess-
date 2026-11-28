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
        assertEquals(SearchNavigation.Request("Telegram"), SearchNavigation.request("Open Telegram and open Search. Do not send anything"))
        val g = GoalState("Открой тг и открой поиск. Ничего не отправляй")
        assertTrue(g.matchesSearchApp(pkg, "Telegram"))
        assertFalse(g.matchesSearchApp("other.app", "Other"))
        assertNull(g.searchNext(GoalObservation(g.sequence(), pkg, listOf(search))))
    }
    @Test fun explicitTelegramSearchQueriesAreParsedNarrowly() {
        assertEquals(SearchNavigation.Request("Telegram", "дурка"), SearchNavigation.request("Открой Telegram и напиши в поиске дурка"))
        assertEquals(SearchNavigation.Request("тг", "дурка"), SearchNavigation.request("Открой тг и введи в поиск дурка"))
        assertEquals(SearchNavigation.Request("Telegram", "durka"), SearchNavigation.request("Open Telegram and type durka in search"))
        assertEquals(SearchNavigation.Request("Telegram", "two words"), SearchNavigation.request("Открой Telegram и напиши в поиске \"two words\""))
        assertNull(SearchNavigation.request("Открой Telegram и напиши в поиске \"\""))
        assertNull(SearchNavigation.request("Открой Telegram и напиши в поиске дурка и отправь"))
        assertNull(SearchNavigation.request("Открой Telegram и напиши привет"))
        assertEquals(SearchNavigation.Request("WhatsApp", "дурка"), SearchNavigation.request("Открой WhatsApp и напиши в поиске дурка"))
        assertNull(SearchNavigation.request("Открой Telegram и напиши в поиске два слова"))
        assertNull(SearchNavigation.request("Открой Telegram и напиши в поиске \"bad\nquery\""))
    }
    @Test fun onlyExplicitSearchOpenAndExactQueryAreSafeWithoutSecondApproval() {
        val goal = GoalState("Открой Telegram и напиши в поиске дурка")
        goal.launched(pkg, "Telegram")
        fun screen(vararg nodes: GoalNode) = GoalObservation(goal.sequence(), pkg, nodes.toList())
        val list = screen(search)
        assertTrue(goal.safeSearchAction(list, "click", search.id, ""))
        assertFalse(goal.safeSearchAction(screen(composer, back), "click", back.id, ""))
        assertFalse(goal.safeSearchAction(screen(search, search.copy(id = "9")), "click", search.id, ""))
        val input = screen(field)
        assertTrue(goal.safeSearchAction(input, "type", field.id, "дурка"))
        assertFalse(goal.safeSearchAction(input, "type", field.id, "wrong"))
        assertFalse(goal.safeSearchAction(screen(composer), "type", composer.id, "дурка"))
        goal.mutation()
        assertFalse(goal.safeSearchAction(input, "type", field.id, "дурка"))
    }
    @Test fun typedQueryRequiresFreshExactSearchField() {
        val goal = GoalState("Открой Telegram и напиши в поиске дурка")
        assertTrue(goal.searchNavigation)
        assertEquals("дурка", goal.searchQuery)
        goal.launched(pkg, "Telegram")
        fun observed(vararg nodes: GoalNode, app: String = pkg) =
            GoalObservation(goal.sequence(), app, nodes.toList())
        val empty = field.copy(text = "")
        val before = observed(empty)
        assertNull(goal.finish(before))
        assertEquals(field.id, goal.searchInput(before))
        assertNull(goal.searchInput(observed(composer)))
        assertNull(goal.searchInput(observed(empty, composer)))
        assertNull(goal.searchInput(observed(empty, empty.copy(id = "9"))))
        assertNull(goal.searchInput(observed(empty, app = "other")))
        goal.mutation()
        assertNull(goal.searchInput(before))
        assertFalse(goal.searchTyped(before, observed(empty.copy(text = "wrong"))))
        assertFalse(goal.searchTyped(before, observed(composer.copy(text = "дурка"))))
        assertFalse(goal.searchTyped(before, observed(empty.copy(text = "дурка"), app = "other")))
        val after = observed(empty.copy(text = "дурка"))
        assertTrue(goal.searchTyped(before, after))
        assertNotNull(goal.finish(after))
        assertNull(goal.searchInput(after))
        assertNull(goal.finish(observed(empty.copy(text = "wrong"))))
        assertNull(goal.finish(observed(empty.copy(text = "дурка"), composer)))
    }

    @Test fun openOnlyDoesNotPermitSearchInputAndQueryDoesNotBecomeArithmetic() {
        val open = GoalState("Открой Telegram и открой поиск")
        open.launched(pkg, "Telegram")
        val o = GoalObservation(open.sequence(), pkg, listOf(field))
        assertNull(open.searchInput(o))
        assertNotNull(open.finish(o))
        val query = GoalState("""Открой Telegram и напиши в поиске "calculator 2+2"""")
        assertEquals("calculator 2+2", query.searchQuery)
        assertFalse(query.arithmetic)
    }

    @Test fun deterministicSearchRunsWithoutModelAndVerifiesGoal() {
        val request = "Открой Telegram и открой поиск"
        val g = GoalState(request)
        val calls = mutableListOf<String>()
        var launched = false
        var searched = false
        val core = AgentCore(decide = { error("Search must not call model") }, execute = { error("typed tools required") },
            cancelled = { false }, goal = g,
            executeTyped = { action ->
                calls.add(action.name)
                when (action.name) {
                    "apps" -> { assertEquals("Telegram", action.arg); ToolOutcome.Ok("Telegram = $pkg") }
                    "launch" -> { g.launched(pkg, "Telegram"); launched = true; ToolOutcome.Ok("list", pkg) }
                    "click" -> { assertEquals("4", action.arg); searched = true; ToolOutcome.Ok("search", pkg) }
                    else -> error("Unexpected action")
                }
            }, fastDecision = {
                when {
                    searched -> AgentDecision.Finish("")
                    launched -> AgentDecision.Tool("click", "4")
                    else -> g.searchLaunchPackage(mapOf(pkg to "Telegram"))?.let { AgentDecision.Tool("launch", it) }
                }
            }, verifyFinish = { if (searched) g.finish(GoalObservation(g.sequence(), pkg, listOf(field))) else null })
        assertTrue(g.requiresNoModel)
        assertEquals("В приложении Telegram подтверждено открытие поиска. Сообщения не отправлял.", core.run(request, ""))
        assertEquals(listOf("apps", "launch", "click"), calls)
        assertNull(g.searchLaunchPackage(mapOf("one.app" to "Telegram", "two.app" to "Telegram")))
        assertNull(g.searchLaunchPackage(mapOf("other.app" to "Telegram Plus")))
    }

    @Test fun missingSearchAppStopsWithoutModel() {
        val calls = mutableListOf<String>()
        val g = GoalState("Open Telegram and open Search")
        val core = AgentCore(decide = { error("No guessed app or model fallback") }, execute = { error("legacy") },
            cancelled = { false }, goal = g,
            executeTyped = { action ->
                calls.add(action.name)
                ToolOutcome.Ok("Not found")
            })
        assertTrue(core.run("Open Telegram and open Search", "").contains("поиск"))
        assertEquals(listOf("apps"), calls)
    }
}
