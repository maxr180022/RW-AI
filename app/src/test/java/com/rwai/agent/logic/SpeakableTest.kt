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
        assertTrue(p.contains("Руся AI")); assertTrue(p.contains("Руся Эй Ай")); assertTrue(p.contains("RusWeo")); assertTrue(p.contains("мужского рода"))
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
        assertTrue(WakePhrase.matches("Руся"))
        assertTrue(WakePhrase.matches("Руся, включи музыку"))
        assertTrue(WakePhrase.matches("Привет, Руся!"))
        assertTrue(WakePhrase.matches("привет русь я"))
    }

    @Test fun wakePhraseRejects() {
        assertFalse(WakePhrase.matches("окей гугл"))
        assertFalse(WakePhrase.matches("привет русский"))
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

class TriageTest {
    private fun m(body: String, sender: String? = "Ана", pkg: String = "com.whatsapp", group: Boolean = false, hidden: Boolean = false, ts: Long = 1) =
        Msg(ts, pkg, "WhatsApp", sender, sender, body, group, hidden)

    @Test fun classifiesChats() {
        assertEquals(Verdict.REAL, MessageTriage.classify(m("Hola, ¿puedes llamarme?")))
        assertEquals(Verdict.GROUP, MessageTriage.classify(m("всем привет", group = true)))
        assertEquals(Verdict.SPAM, MessageTriage.classify(m("Скидка 50% только сегодня!", sender = "Shop")))
        assertEquals(Verdict.SPAM, MessageTriage.classify(m("hello", sender = "12345")))
        assertEquals(Verdict.SYSTEM, MessageTriage.classify(m("Your code is 123456")))
        assertEquals(Verdict.SYSTEM, MessageTriage.classify(m("Привет", pkg = "com.android.vending")))
        assertEquals(Verdict.VOICE, MessageTriage.classify(m("🎤 Voice message (0:12)")))
        assertEquals(Verdict.SPAM, MessageTriage.classify(m("Big sale", sender = "newsletter@shop.com", pkg = "com.google.android.gm")))
        assertEquals(Verdict.REAL, MessageTriage.classify(m("Встреча в 10", sender = "boss@corp.com", pkg = "com.google.android.gm")))
    }

    @Test fun analyzeGroupsThreadsAndSorts() {
        val r = MessageTriage.analyze(
            listOf(
                m("Hola", sender = "Ana", ts = 1), m("¿Puedes ayudarme?", sender = "Ana", ts = 2), m("ok", sender = "Bob", ts = 3),
                m("spam sale 70% off", sender = "Promo"), m("в группе", group = true), m("🎤 Voice message", sender = "Carl"),
            ),
        )
        assertEquals("Ana", r.threads.first().contact)
        assertTrue(r.threads.first().urgent)
        assertEquals(1, r.groupCount); assertEquals(1, r.spamCount); assertEquals(1, r.voiceCount)
        assertTrue(MessageTriage.localSummary(r).contains("Ana"))
    }

    @Test fun detectsLanguages() {
        assertEquals(Lang.RU, LangDetect.detect("Привет, как дела?"))
        assertEquals(Lang.ES, LangDetect.detect("Hola, ¿cómo estás? Necesito ayuda por favor"))
        assertEquals(Lang.EN, LangDetect.detect("Hello, can you please call me tomorrow?"))
        assertEquals(Lang.OTHER, LangDetect.detect("123 456"))
    }

    @Test fun parsesTranslations() {
        val r = TranslationFormat.parse("1|Привет\n2) Как дела?\nмусор\n9|лишнее", 2)
        assertEquals(mapOf(1 to "Привет", 2 to "Как дела?"), r)
    }

    @Test fun webParsing() {
        val html = """<div class="result"><a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa%3Fx%3D1&amp;rut=zz">Пример &amp; тест</a>
            <a class="result__snippet" href="x">Описание <b>страницы</b></a></div>
            <div class="result"><a class="result__a" href="https://other.org/">Другой</a><a class="result__snippet" href="y">Второй</a></div>"""
        val hits = SearchParser.parseDdg(html)
        assertEquals(2, hits.size)
        assertEquals("https://example.com/a?x=1", hits[0].url); assertEquals("Пример & тест", hits[0].title); assertEquals("Описание страницы", hits[0].snippet)
        assertEquals("https://other.org/", hits[1].url)
        assertTrue(WebText.htmlToText("<html><head><title>x</title></head><body><script>var a=1</script><p>Привет&nbsp;мир</p><div>Строка&amp;2</div></body></html>").contains("Привет мир"))
    }

    @Test fun fetchGuardBlocksLocalAndBanks() {
        assertTrue(FetchGuard.check("http://192.168.0.1/admin") != null)
        assertTrue(FetchGuard.check("http://localhost:8080") != null)
        assertTrue(FetchGuard.check("ftp://example.com") != null)
        assertTrue(FetchGuard.check("https://www.sberbank.ru/") != null)
        assertEquals(null, FetchGuard.check("https://en.wikipedia.org/wiki/Montevideo"))
    }
}

class PhoneAuditTest {
    private fun app(pkg: String, installer: String? = "com.android.vending", system: Boolean = false, perms: Set<String> = emptySet(),
                    a11y: Boolean = false, nl: Boolean = false, admin: Boolean = false, min: Int? = null) =
        AppFacts(pkg, pkg, installer, system, perms, a11y, nl, admin, min)

    @Test fun flagsSideloadedWithDangerousAccess() {
        val f = PhoneAudit.analyze(
            listOf(
                app("evil", installer = null, a11y = true), app("good"), app("sys", installer = null, system = true, a11y = true),
                app("mine", installer = null, a11y = true), app("reader", nl = true), app("side", installer = "com.some.browser"),
                app("sms", installer = null, perms = setOf("android.permission.READ_SMS")),
            ),
            ownPackage = "mine",
        )
        assertEquals(3, f.first { it.app == "evil" }.level)
        assertEquals(3, f.first { it.app == "sms" }.level)
        assertEquals(1, f.first { it.app == "reader" }.level)
        assertEquals(1, f.first { it.app == "side" }.level)
        assertTrue(f.none { it.app == "good" || it.app == "mine" || it.app == "sys" })
        assertTrue(PhoneAudit.summary(f).contains("серьёзных"))
    }

    @Test fun trustedAccessibilityIsMidLevelAndEmptyIsClean() {
        val f = PhoneAudit.analyze(listOf(app("talk", a11y = true)), "x")
        assertEquals(2, f.single().level)
        assertEquals("Подозрительных признаков не найдено.", PhoneAudit.summary(emptyList()))
    }

    @Test fun topUsageSortsAndSkipsZero() {
        val t = PhoneAudit.topUsage(listOf(app("a", min = 5), app("b", min = 90), app("c", min = 0), app("d")), 2)
        assertEquals(listOf("b", "a"), t.map { it.pkg })
    }
}

class SafetyAndSpeechTest {
    @Test fun dialerServiceCodesAreDenied() {
        for (c in listOf("*#0228#", "*#*#4636#*#*", "*#06#", "##4636##", "*123#", " *#0228# ")) {
            assertTrue(c, DialCodes.isServiceCode(c))
            val d = ConfirmationPolicy(AutonomyMode.AUTONOMOUS, true).evaluate(ToolCall("input_text", mapOf("text" to c)), ActionContext(currentApp = "Телефон"))
            assertTrue(c, d is PolicyDecision.Deny)
        }
        for (c in listOf("+59899123456", "Привет, как дела?", "Заказ #12", "Скидка 5%", "12:30")) assertTrue(c, !DialCodes.isServiceCode(c))
    }

    @Test fun phoneStatusToolIsSafeAndNamed() {
        val spec = ToolCatalog.find("phone_status")!!
        assertEquals(Risk.SAFE, spec.risk)
        assertTrue(SystemPrompt.build().contains("phone_status"))
        assertTrue(SystemPrompt.build().contains("Maxim Ricco"))
    }

    @Test fun callButtonNeedsConfirmation() {
        val d = ConfirmationPolicy(AutonomyMode.AUTONOMOUS, true).evaluate(ToolCall("tap_text", mapOf("text" to "Вызов")), ActionContext(currentApp = "Телефон"))
        assertTrue(d is PolicyDecision.Confirm)
    }

    @Test fun aiIsSpelledInRussian() {
        assertEquals("Руся Эй Ай готова", Speakable.clean("Руся AI готова"))
    }
}

class ConnectorsTest {
    @Test fun mailPresetsByDomain() {
        assertEquals("imap.gmail.com", MailPresets.forAddress("max@gmail.com")!!.imapHost)
        assertEquals("smtp.yandex.ru", MailPresets.forAddress("a@ya.ru")!!.smtpHost)
        assertTrue(MailPresets.forAddress("x@icloud.com")!!.smtpStartTls)
        assertEquals(null, MailPresets.forAddress("x@company.org"))
        assertTrue(MailPresets.passwordHelp("a@gmail.com").contains("apppasswords"))
    }

    @Test fun mailRules() {
        assertTrue(MailRules.validAddress("max.ricco+x@gmail.com")); assertTrue(!MailRules.validAddress("не адрес")); assertTrue(!MailRules.validAddress("a@b"))
        assertEquals("a@b.com", MailRules.extractAddress("Имя <a@b.com>"))
        assertTrue(MailRules.isBankSender("Сбер <info@sberbank.ru>"))
        assertTrue(!MailRules.isBankSender("Ана <ana@gmail.com>"))
        val out = MailRules.format(listOf(MailItem(5, "Сбер <x@sberbank.ru>", "Выписка", 0, true, "секрет"), MailItem(6, "Ана <ana@gmail.com>", "Привет", 0, true, "как дела")), { "дата" })
        assertTrue(out.contains("скрыто")); assertTrue(!out.contains("Выписка")); assertTrue(out.contains("НОВОЕ") && out.contains("Привет"))
    }

    @Test fun mailSendAlwaysConfirmedAndValidated() {
        val p = ConfirmationPolicy(AutonomyMode.AUTONOMOUS, true)
        val ctx = ActionContext(currentApp = null)
        fun call(to: String) = ToolCall("mail_send", mapOf("to" to to, "subject" to "Тема", "body" to "Текст"))
        assertTrue(p.evaluate(call("ana@gmail.com"), ctx) is PolicyDecision.Confirm)
        assertTrue(p.evaluate(call("bad"), ctx) is PolicyDecision.Deny)
        assertTrue(p.evaluate(call("info@sberbank.ru"), ctx) is PolicyDecision.Deny)
    }

    @Test fun calendarParsingAndConfirm() {
        val z = java.time.ZoneId.of("UTC")
        assertEquals(java.time.Instant.parse("2026-11-09T15:00:00Z").toEpochMilli(), CalendarArgs.parseStart("2026-11-09T15:00", z))
        assertEquals(java.time.Instant.parse("2026-11-09T15:00:00Z").toEpochMilli(), CalendarArgs.parseStart("2026-11-09 15:00", z))
        assertEquals(null, CalendarArgs.parseStart("завтра", z))
        val p = ConfirmationPolicy(AutonomyMode.AUTONOMOUS, true)
        assertTrue(p.evaluate(ToolCall("calendar_add", mapOf("title" to "Встреча", "start" to "2026-11-09T15:00")), ActionContext(null)) is PolicyDecision.Confirm)
        assertTrue(p.evaluate(ToolCall("calendar_add", mapOf("title" to "Встреча", "start" to "завтра")), ActionContext(null)) is PolicyDecision.Deny)
    }

    @Test fun newToolsValidate() {
        assertTrue(ToolCatalog.validate("media_control", mapOf("action" to "next")).first != null)
        assertTrue(ToolCatalog.validate("media_control", mapOf("action" to "explode")).first == null)
        assertTrue(ToolCatalog.validate("mail_list", mapOf("limit" to 5L, "unread_only" to true)).first != null)
        assertTrue(ToolCatalog.validate("mail_list", emptyMap()).first != null)
        assertTrue(ToolCatalog.validate("mail_read", mapOf("id" to 42L)).first != null)
        assertTrue(ToolCatalog.validate("play_music", mapOf("query" to "Queen")).first != null)
    }
}
