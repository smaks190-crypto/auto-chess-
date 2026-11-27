package dev.nox.local

import org.junit.Assert.*
import org.junit.Test

class ObsidianCommandTest {
    @Test fun acceptsOnlyExactTypedCommands() {
        assertEquals(ObsidianCommand("ready"), ObsidianCommand.parse("""{"op":"ready"}"""))
        assertEquals(ObsidianCommand("send", text="hello", agent=true), ObsidianCommand.parse("""{"op":"send","text":" hello ","agent":true}"""))
        assertEquals(ObsidianCommand("choice", model=2, effort=3), ObsidianCommand.parse("""{"op":"choice","model":2,"effort":3}"""))
        assertEquals("vision", ObsidianCommand.parse("""{"op":"page","page":"vision"}""")?.text)
    }
    @Test fun rejectsArbitraryCommandsAndExtraFields() {
        for (raw in listOf("""{"op":"shell","command":"id"}""", """{"op":"launch","package":"com.example"}""", """{"op":"ready","url":"https://example.com"}""", """{"op":"send","text":"hi","agent":true,"permission":"always"}""")) assertNull(raw, ObsidianCommand.parse(raw))
    }
    @Test fun rejectsCoercionAndOutOfBounds() {
        for (raw in listOf("""{"op":"choice","model":"1","effort":0}""", """{"op":"choice","model":3,"effort":0}""", """{"op":"choice","model":0,"effort":4}""", """{"op":"send","text":"hi","agent":"true"}""", """{"op":"send","text":" ","agent":false}""", """{"op":"page","page":"outside"}""", "{")) assertNull(raw, ObsidianCommand.parse(raw))
    }
}
