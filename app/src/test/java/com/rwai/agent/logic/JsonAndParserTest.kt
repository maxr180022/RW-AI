package com.rwai.agent.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTest {
    @Test fun parsesNestedStructures() {
        val v = Json.parse("""{"a":[1,2.5,"x",true,null],"b":{"c":"d\nЖ"}}""").asJsonMap()!!
        assertEquals(listOf(1L, 2.5, "x", true, null), v["a"])
        assertEquals("d\nЖ", v["b"].asJsonMap()!!["c"])
    }

    @Test fun roundTrip() {
        val src = mapOf("k" to listOf("а\"б", 1L, false, null), "n" to mapOf("x" to 2.0))
        assertEquals(src, Json.parse(Json.stringify(src)))
    }

    @Test fun escapesControlCharacters() {
        assertEquals("\"a\\u0001b\"", Json.quote("a\u0001b"))
    }

    @Test(expected = JsonParseException::class) fun rejectsTrailingData() { Json.parse("{} x") }

    @Test(expected = JsonParseException::class) fun rejectsUnterminatedString() { Json.parse("{\"a\":\"b") }

    @Test(expected = JsonParseException::class) fun rejectsDeepNesting() { Json.parse("[".repeat(200) + "]".repeat(200)) }
}

class ActionParserTest {
    @Test fun parsesAction() {
        val d = ActionParser.parse("""{"thought":"открою почту","action":{"tool":"launch_app","args":{"app":"Gmail"}}}""")
        d as AgentDecision.Act
        assertEquals("launch_app", d.call.tool)
        assertEquals("Gmail", d.call.str("app"))
    }

    @Test fun parsesActionInsideCodeFenceWithChatter() {
        val d = ActionParser.parse("Конечно!\n```json\n{\"action\":{\"tool\":\"press_back\"}}\n```\nГотово.")
        assertTrue(d is AgentDecision.Act)
    }

    @Test fun ignoresReasoningBlock() {
        val d = ActionParser.parse("<think>вариант {\"ask\":\"x\"}</think>{\"finish\":{\"success\":true,\"summary\":\"ok\"}}")
        assertTrue(d is AgentDecision.Finish)
    }

    @Test fun parsesFinishAndAsk() {
        assertEquals(AgentDecision.Finish("готово", false), ActionParser.parse("""{"finish":{"success":false,"summary":"готово"}}"""))
        assertEquals(AgentDecision.Ask("Кому?"), ActionParser.parse("""{"ask":"Кому?"}"""))
    }

    @Test fun rejectsUnknownTool() {
        val d = ActionParser.parse("""{"action":{"tool":"run_shell","args":{"cmd":"rm -rf /"}}}""")
        assertTrue(d is AgentDecision.Invalid)
        assertTrue((d as AgentDecision.Invalid).reason.contains("run_shell"))
    }

    @Test fun rejectsMissingAndExtraArgs() {
        assertTrue(ActionParser.parse("""{"action":{"tool":"launch_app","args":{}}}""") is AgentDecision.Invalid)
        assertTrue(ActionParser.parse("""{"action":{"tool":"launch_app","args":{"app":"x","evil":1}}}""") is AgentDecision.Invalid)
    }

    @Test fun rejectsBadEnumAndRange() {
        assertTrue(ActionParser.parse("""{"action":{"tool":"scroll","args":{"direction":"left"}}}""") is AgentDecision.Invalid)
        assertTrue(ActionParser.parse("""{"action":{"tool":"wait","args":{"ms":999999}}}""") is AgentDecision.Invalid)
    }

    @Test fun coercesNumericStringsAndIntegralDoubles() {
        val a = ActionParser.parse("""{"action":{"tool":"wait","args":{"ms":"500"}}}""") as AgentDecision.Act
        assertEquals(500, a.call.int("ms"))
        val b = ActionParser.parse("""{"action":{"tool":"wait","args":{"ms":500.0}}}""") as AgentDecision.Act
        assertEquals(500, b.call.int("ms"))
    }

    @Test fun rejectsMultipleKindsAndGarbage() {
        assertTrue(ActionParser.parse("""{"ask":"a","finish":{"success":true,"summary":"s"}}""") is AgentDecision.Invalid)
        assertTrue(ActionParser.parse("просто текст") is AgentDecision.Invalid)
        assertTrue(ActionParser.parse("{ не json }") is AgentDecision.Invalid)
    }

    @Test fun rejectsTooLongText() {
        val long = "a".repeat(5000)
        assertTrue(ActionParser.parse("""{"action":{"tool":"input_text","args":{"text":"$long"}}}""") is AgentDecision.Invalid)
    }

    @Test fun braceInsideStringDoesNotBreakScan() {
        val d = ActionParser.parse("""{"action":{"tool":"input_text","args":{"text":"привет } мир { !"}}}""")
        d as AgentDecision.Act
        assertEquals("привет } мир { !", d.call.str("text"))
    }

    @Test fun catalogHasUniqueNames() {
        assertEquals(ToolCatalog.all.size, ToolCatalog.all.map { it.name }.toSet().size)
        assertNull(ToolCatalog.find("nonexistent"))
    }

    @Test fun promptListsEveryTool() {
        val p = SystemPrompt.build()
        ToolCatalog.all.forEach { assertTrue("нет ${it.name}", p.contains(it.name)) }
        assertFalse(p.contains("run_shell"))
    }
}
