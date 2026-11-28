package dev.nox.local

import org.junit.Assert.*
import org.junit.Test

class AgentProtocolTest {
    @Test fun structuredLaunch() {
        assertEquals(AgentDecision.Tool("launch", "org.telegram.messenger"),
            AgentProtocol.parse("""{"tool":"launch","arg":"org.telegram.messenger"}"""))
    }
    @Test fun typedArguments() {
        assertEquals(AgentDecision.Tool("type", "12", "Привет!"),
            AgentProtocol.parse("""{"tool":"type","arg":"12","text":"Привет!"}"""))
    }
    @Test fun normalAnswer() {
        assertEquals(AgentDecision.Finish("Привет"), AgentProtocol.parse("""{"finish":"Привет"}"""))
    }
    @Test fun unknownToolCannotExecute() {
        assertThrows(IllegalStateException::class.java) {
            AgentProtocol.parse("""{"tool":"shell","arg":"anything"}""")
        }
    }
    @Test fun extraArgumentsAndInvalidTargetsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentProtocol.parse("""{"tool":"click","arg":"1","approved":true}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentProtocol.parse("""{"tool":"click","arg":"1234"}""")
        }
    }
    @Test fun acceptsNumericNodeIdsAndOmittedEmptyArguments() {
        assertEquals(AgentDecision.Tool("click", "12"),
            AgentProtocol.parse("""{"tool":"click","arg":12}"""))
        assertEquals(AgentDecision.Tool("observe", ""),
            AgentProtocol.parse("""{"tool":"observe"}"""))
    }
    @Test fun invalidNodeIdHasActionableDiagnostic() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            AgentProtocol.parse("""{"tool":"click","arg":0}""")
        }
        assertTrue(e.message.orEmpty().contains("ID"))
    }
    @Test fun clickWithStructuredExpectation() {
        assertEquals(AgentDecision.Tool("click", "2", expect = ExpectedNode("hint", "Search chats")),
            AgentProtocol.parse("""{"tool":"click","arg":"2","expect":{"field":"hint","value":"Search chats"}}"""))
    }
    @Test fun malformedAndExtraExpectationFieldsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentProtocol.parse("""{"tool":"click","arg":"2","expect":{"field":"anything","value":"x"}}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentProtocol.parse("""{"tool":"click","arg":"2","expect":{"field":"hint","value":"x","approved":true}}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentProtocol.parse("""{"tool":"launch","arg":"com.example","expect":{"field":"hint","value":"x"}}""")
        }
    }
    @Test fun trailingTextRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentProtocol.parse("""{"tool":"home","arg":""} extra""")
        }
    }
}
