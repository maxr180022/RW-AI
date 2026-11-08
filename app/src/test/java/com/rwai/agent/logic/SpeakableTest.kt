package com.rwai.agent.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakableTest {
    @Test fun stripsMarkupAndLinks() {
        val r = Speakable.clean("**Итог:** письмо от `Ивана`\n- пришло в 9:00\nhttps://example.com/x?y=1")
        assertFalse(r.contains("*")); assertFalse(r.contains("`")); assertFalse(r.contains("http"))
        assertTrue(r.contains("Итог: письмо от Ивана"))
        assertTrue(r.contains("ссылка"))
    }

    @Test fun cutsLongTextAtSentenceEnd() {
        val r = Speakable.clean("Первое предложение. ".repeat(100), 100)
        assertTrue(r.length <= 100); assertTrue(r.endsWith("."))
    }

    @Test fun shortTextUntouched() = assertEquals("Готово", Speakable.clean("Готово"))
}

class IdentityPromptTest {
    @Test fun agentKnowsNameCreatorAndGender() {
        val p = SystemPrompt.build()
        assertTrue(p.contains("Руся")); assertTrue(p.contains("RusWeo")); assertTrue(p.contains("мужского рода"))
    }
}
