package dev.nox.local

import org.junit.Assert.*
import org.junit.Test

class ObsidianCommandTest {
    @Test fun acceptsOnlyExactTypedCommands() {
        assertEquals(ObsidianCommand("ready"), ObsidianCommand.parse("""{"op":"ready"}"""))
        for (op in listOf("new_chat", "chat_history", "chat_older")) {
            assertEquals(ObsidianCommand(op), ObsidianCommand.parse("""{"op":"$op"}"""))
            assertNull(ObsidianCommand.parse("""{"op":"$op","id":"arbitrary"}"""))
        }
        assertEquals(ObsidianCommand("send", text="hello", agent=true), ObsidianCommand.parse("""{"op":"send","text":" hello ","agent":true}"""))
        assertEquals(ObsidianCommand("choice", model=2, effort=3), ObsidianCommand.parse("""{"op":"choice","model":2,"effort":3}"""))
        assertNull(ObsidianCommand.parse("""{"op":"page","page":"vision"}"""))
        assertEquals("settings", ObsidianCommand.parse("""{"op":"page","page":"settings"}""")?.text)
        for (op in listOf("pick_image", "perception", "review", "analyze", "remove_image"))
            assertEquals(op, ObsidianCommand.parse("""{"op":"$op"}""")?.op)
    }
    @Test fun replyModalityIsPerRequestAndStrictlyTyped() {
        assertEquals(true, ObsidianCommand.parse("""{"op":"send","text":"hello","agent":false,"voice":true}""")?.voice)
        assertEquals(false, ObsidianCommand.parse("""{"op":"send","text":"hello","agent":false,"voice":false}""")?.voice)
        assertEquals(false, ObsidianCommand.parse("""{"op":"send","text":"hello","agent":false}""")?.voice)
        assertNull(ObsidianCommand.parse("""{"op":"send","text":"hello","agent":false,"voice":"true"}"""))
        assertNull(ObsidianCommand.parse("""{"op":"send","text":"hello","agent":false,"voice":null}"""))
        assertNull(ObsidianCommand.parse("""{"op":"tts"}"""))
    }
    @Test fun branchCommandsAcceptOnlyExactPositiveMessageIds() {
        assertEquals(ObsidianCommand("chat_edit",messageId=123), ObsidianCommand.parse("""{"op":"chat_edit","message":"123"}"""))
        assertEquals(ObsidianCommand("chat_regenerate",messageId=9), ObsidianCommand.parse("""{"op":"chat_regenerate","message":"9"}"""))
        for (bad in listOf("""{"op":"chat_edit","message":1}""", """{"op":"chat_regenerate","message":"0"}""",
            """{"op":"chat_edit","message":"-1"}""", """{"op":"chat_edit","message":"9999999999999999999"}""",
            """{"op":"chat_regenerate","message":"9","agent":true}"""))
            assertNull(ObsidianCommand.parse(bad))
    }
    @Test fun rejectsArbitraryCommandsAndExtraFields() {
        for (raw in listOf("""{"op":"shell","command":"id"}""", """{"op":"launch","package":"com.example"}""", """{"op":"ready","url":"https://example.com"}""", """{"op":"send","text":"hi","agent":true,"permission":"always"}""")) assertNull(raw, ObsidianCommand.parse(raw))
    }
    @Test fun rejectsCoercionAndOutOfBounds() {
        for (raw in listOf("""{"op":"choice","model":"1","effort":0}""", """{"op":"choice","model":3,"effort":0}""", """{"op":"choice","model":0,"effort":4}""", """{"op":"send","text":"hi","agent":"true"}""", """{"op":"send","text":" ","agent":false}""", """{"op":"page","page":"outside"}""", "{")) assertNull(raw, ObsidianCommand.parse(raw))
    }
    @Test fun settingsOperationsAreTypedAndBounded() {
        assertEquals(ObsidianCommand("model_select", model=2), ObsidianCommand.parse("""{"op":"model_select","model":2}"""))
        assertEquals(ObsidianCommand("model_gpu", enabled=true), ObsidianCommand.parse("""{"op":"model_gpu","enabled":true}"""))
        assertEquals(ObsidianCommand("reduce_motion", enabled=false), ObsidianCommand.parse("""{"op":"reduce_motion","enabled":false}"""))
        for (op in listOf("system_assistant","overlay_permission","accessibility_permission","model_load","model_import","model_catalog","model_cancel","benchmark","benchmark_log","benchmark_share","nano_probe")) {
            assertEquals(op, ObsidianCommand.parse("""{"op":"$op"}""")?.op)
            assertNull(ObsidianCommand.parse("""{"op":"$op","package":"com.example"}"""))
        }
        assertNull(ObsidianCommand.parse("""{"op":"settings"}"""))
        assertNull(ObsidianCommand.parse("""{"op":"model_gpu","enabled":"true"}"""))
        assertNull(ObsidianCommand.parse("""{"op":"model_vision","enabled":1}"""))
        assertNull(ObsidianCommand.parse("""{"op":"model_select","model":3}"""))
        assertNull(ObsidianCommand.parse("""{"op":"model_select","model":-1}"""))
        assertNull(ObsidianCommand.parse("""{"op":"model_import","uri":"file:///sdcard/test"}"""))
    }
}
