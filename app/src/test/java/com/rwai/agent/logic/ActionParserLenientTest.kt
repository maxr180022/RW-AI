package com.rwai.agent.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionParserLenientTest {
    private fun act(raw: String): ToolCall = (ActionParser.parse(raw) as AgentDecision.Act).call

    @Test fun flattenedToolAndArgsAreAccepted() {
        val c = act("""{"thought":"нажимаю","tool":"tap_text","args":{"text":"Фонарик"}}""")
        assertEquals("tap_text", c.tool)
        assertEquals("Фонарик", c.str("text"))
    }

    @Test fun actionGivenAsAStringIsAccepted() {
        assertEquals("inspect_screen", act("""{"action":"inspect_screen"}""").tool)
        assertEquals("scroll", act("""{"action":"scroll","args":{"direction":"down"}}""").tool)
    }

    @Test fun functionCallStyleWithStringArgumentsIsAccepted() {
        val c = act("""{"name":"scroll","arguments":"{\"direction\":\"down\"}"}""")
        assertEquals("scroll", c.tool)
        assertEquals("down", c.str("direction"))
    }

    @Test fun strictFormsAndRefusalsStillWork() {
        assertEquals("press_back", act("""{"thought":"t","action":{"tool":"press_back","args":{}}}""").tool)
        assertTrue(ActionParser.parse("""{"finish":{"success":true,"summary":"Готово"}}""") is AgentDecision.Finish)
        assertTrue(ActionParser.parse("""{"hello":1}""") is AgentDecision.Invalid)
        assertTrue(ActionParser.parse("просто текст") is AgentDecision.Invalid)
    }

    @Test fun unknownToolsAreStillRejectedByTheCatalog() {
        assertTrue(ActionParser.parse("""{"tool":"format_phone","args":{}}""") is AgentDecision.Invalid)
        assertTrue(ActionParser.parse("""{"name":"tap_text","arguments":"не json"}""") is AgentDecision.Invalid)
    }
}
