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
        assertTrue(p.contains("RW Agent")); assertTrue(p.contains("Эр Вэ Агент")); assertTrue(p.contains("RusWeo")); assertTrue(p.contains("мужского рода"))
    }

    @Test fun promptListsOnlyKeysNotValues() {
        val p = SystemPrompt.build("русский", listOf("email", "phone"), "Живу в Монтевидео")
        assertTrue(p.contains("{{profile.ключ}}")); assertTrue(p.contains("email, phone")); assertTrue(p.contains("Монтевидео"))
    }

    @Test fun nameIsSpelledForRussianSpeech() {
        assertEquals("Я Эр Вэ Агент, меня создала RusWeo", Speakable.clean("Я RW Agent, меня создала RusWeo"))
        assertEquals("Эр Вэ Агент готов", Speakable.clean("RW AI готов"))
        assertEquals("RW Agent ready", Speakable.clean("RW Agent ready", russian = false))
    }

    @Test fun profileInputNeedsConfirmationOncePerApp() {
        val policy = ConfirmationPolicy(AutonomyMode.OPERATOR, false)
        val call = ToolCall("input_text", mapOf("text" to "{{profile.email}}"))
        val ctx = ActionContext(currentApp = "com.site")
        assertTrue(policy.evaluate(call, ctx) is PolicyDecision.Confirm)
        policy.noteApproved(call, ctx)
        assertTrue(policy.evaluate(call, ctx) is PolicyDecision.Allow)
        assertTrue(policy.evaluate(call, ActionContext(currentApp = "com.other")) is PolicyDecision.Confirm)
        assertTrue(policy.evaluate(ToolCall("input_text", mapOf("text" to "привет")), ctx) is PolicyDecision.Allow)
    }
}

class WakeTest {
    @Test fun wakePhraseMatches() {
        assertTrue(WakePhrase.matches("окей руся"))
        assertTrue(WakePhrase.matches("Окей, Руся!"))
        assertTrue(WakePhrase.matches("ок русь я включи музыку"))
        assertTrue(WakePhrase.matches("okay русся"))
        assertTrue(WakePhrase.matches("океи руси"))
    }

    @Test fun wakePhraseRejects() {
        assertFalse(WakePhrase.matches("окей гугл"))
        assertFalse(WakePhrase.matches("привет руся")) // без «окей» не просыпаемся: меньше случайных срабатываний
        assertFalse(WakePhrase.matches("окей русский язык"))
        assertFalse(WakePhrase.matches(""))
    }

    @Test fun customName() {
        assertTrue(WakePhrase.matches("окей агент", "агент"))
        assertFalse(WakePhrase.matches("окей руся", "агент"))
    }

    @Test fun gateModes() {
        assertTrue(WakeGate.shouldListen("always", false, false))
        assertFalse(WakeGate.shouldListen("charging", false, true))
        assertTrue(WakeGate.shouldListen("charging", true, false))
        assertTrue(WakeGate.shouldListen("charging_screen", false, true))
        assertFalse(WakeGate.shouldListen("charging_screen", false, false))
        assertFalse(WakeGate.shouldListen("off", true, true))
    }

    private fun snap(temp: Float = 30f, pct: Int = 60, charging: Boolean = false, mode: String = "always", wake: Boolean = true, drain: Float? = null, thermal: Int = 0) =
        PhoneSnapshot(pct, temp, charging, thermal, wake, mode, 10, drain)

    @Test fun advisorWarnsOnHeatAndDrain() {
        assertTrue(PhoneAdvisor.tips(snap(temp = 43f)).any { it.warning && it.text.contains("нагрет") })
        assertTrue(PhoneAdvisor.tips(snap(drain = 5f)).any { it.warning && it.text.contains("много") })
        assertTrue(PhoneAdvisor.tips(snap(pct = 15)).any { it.warning && it.text.contains("Только на зарядке") })
        assertTrue(PhoneAdvisor.tips(snap(wake = false)).any { it.text.contains("Всё в порядке") })
    }
}
