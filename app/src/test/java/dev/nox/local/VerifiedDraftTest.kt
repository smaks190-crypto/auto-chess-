package dev.nox.local

import org.junit.Assert.*
import org.junit.Test

class VerifiedDraftTest {
    private val registry = GoalTemplateRegistry()
    private val command = "Подготовь черновик в Telegram для «Александр»: «Привет!»"
    private val fields = DraftFields("org.telegram.messenger.web", "trusted-header", "trusted-composer")
    private fun goal() = requireNotNull(registry.draft(command))
    private fun screen(seq: Long, text: String = "Привет!", title: String = "Александр", window: Int = 1,
                       pkg: String = fields.pkg) = DraftObservation(seq,pkg,window,listOf(
        DraftField("trusted-header",title,false,false),
        DraftField("trusted-composer",text,true,false)
    ))
    @Test fun messageComesFromExactUserDelimitedSlots() {
        assertEquals("Привет!",goal().text)
        assertEquals("Александр",goal().recipient)
        assertNull(registry.draft("напиши Александру привет"))
        assertNull(registry.draft("Подготовь черновик в Telegram для «Александр»: «»"))
    }
    @Test fun russianCommandKeywordsAreUnicodeCaseInsensitiveWithoutChangingPayload() {
        val result = requireNotNull(registry.draft("ПОДГОТОВЬ ЧЕРНОВИК В TELEGRAM ДЛЯ «Александр»: «ПрИвЕт!»"))
        assertEquals("Александр",result.recipient)
        assertEquals("ПрИвЕт!",result.text)
    }
    @Test fun exactRecipientComposerAndPackageAreRequired() {
        val g = goal()
        assertNotNull(VerifiedDraft.verify(g,fields,screen(3),2))
        assertNull(VerifiedDraft.verify(g,fields,screen(3,text="Привет"),2))
        assertNull(VerifiedDraft.verify(g,fields,screen(3,title="Александр Иванов"),2))
        assertNull(VerifiedDraft.verify(g,fields,screen(3,pkg="other.app"),2))
        assertNull(VerifiedDraft.verify(g,fields,screen(2),2))
    }
    @Test fun arbitraryMatchingTextAndHintNeverCountAsEvidence() {
        val g = goal()
        val o = screen(3)
        assertNull(VerifiedDraft.verify(g,fields,o.copy(nodes=listOf(
            DraftField("message-bubble","Александр",false,false),
            DraftField("trusted-composer","Привет!",true,false))),2))
        assertNull(VerifiedDraft.verify(g,fields,o.copy(nodes=listOf(
            DraftField("trusted-header","Александр",false,false),
            DraftField("trusted-composer","Привет!",true,true))),2))
        assertNull(VerifiedDraft.verify(g,fields,o.copy(nodes=o.nodes + o.nodes.last()),2))
    }
    @Test fun approvalIsSingleUseAndBoundToExactFreshScreen() {
        val g = goal()
        val gate = DraftApprovalGate { 1000L }
        val evidence = requireNotNull(VerifiedDraft.verify(g,fields,screen(3),2))
        gate.approve(evidence)
        assertFalse(gate.consume(g,fields,screen(4,text="Изменено")))
        assertFalse(gate.consume(g,fields,screen(5)))
        gate.approve(evidence)
        assertFalse(gate.consume(g,fields,screen(4,window=2)))
        gate.approve(evidence)
        assertTrue(gate.consume(g,fields,screen(4)))
        assertFalse(gate.consume(g,fields,screen(5)))
    }
    @Test fun approvalExpiresAndCannotUseSameObservation() {
        var clock = 1000L
        val gate = DraftApprovalGate { clock }
        val g = goal()
        val evidence = requireNotNull(VerifiedDraft.verify(g,fields,screen(3),2))
        gate.approve(evidence)
        assertFalse(gate.consume(g,fields,screen(3)))
        gate.approve(evidence)
        clock += 30001
        assertFalse(gate.consume(g,fields,screen(4)))
    }
}
