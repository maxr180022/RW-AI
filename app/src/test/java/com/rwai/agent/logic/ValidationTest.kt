package com.rwai.agent.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidationTest {
    @Test fun endpointAcceptsHttpsOnly() {
        assertEquals("https://api.deepseek.com", Endpoint.normalize(" https://api.deepseek.com/ "))
        assertEquals("https://proxy.example.org:8443/v1", Endpoint.normalize("https://proxy.example.org:8443/v1"))
        assertNull(Endpoint.normalize("http://api.deepseek.com"))
        assertNull(Endpoint.normalize("https://user:pass@evil.com"))
        assertNull(Endpoint.normalize("https://localhost"))
        assertNull(Endpoint.normalize("https://a b.com"))
        assertNull(Endpoint.normalize("ftp://x.com"))
        assertNull(Endpoint.normalize(""))
        assertNull(Endpoint.normalize("https://..com"))
    }

    @Test fun masksOneTimeCodes() {
        assertFalse(SensitiveMasker.mask("Ваш код подтверждения: 482913").contains("482913"))
        assertFalse(SensitiveMasker.mask("Your verification code is 123456").contains("123456"))
        assertFalse(SensitiveMasker.mask("Tu código es 7788").contains("7788"))
        assertFalse(SensitiveMasker.mask("482 913 — ваш код для входа").contains("482 913"))
        assertFalse(SensitiveMasker.mask("123456 is your Telegram code").contains("123456"))
    }

    @Test fun doesNotMangleOrdinaryNumbers() {
        val t = "Встреча в 10:00, аренда 260 долларов, дом 15"
        assertEquals(t, SensitiveMasker.mask(t))
    }

    @Test fun labelEscalationIsDetected() {
        assertTrue(LabelClassifier.isEscalation("OK", "Оплатить заказ"))
        assertTrue(LabelClassifier.isEscalation("Далее", "Send"))
        assertTrue(LabelClassifier.isEscalation("Продолжить", "Я не робот"))
        assertFalse(LabelClassifier.isEscalation("Отправить", "Отправить"))
        assertFalse(LabelClassifier.isEscalation("Send", "Send message"))
        assertFalse(LabelClassifier.isEscalation("Входящие", "Входящие (3)"))
    }

    @Test fun ftsQueryIsSafeAndPrefixBased() {
        assertEquals("арен* кварти*", FtsQuery.build("Аренда, КВАРТИРЫ!"))
        assertEquals("арен*", FtsQuery.build("аренду"))
        assertEquals("rent*", FtsQuery.build("Rent"))
        assertEquals("a* or* 1* 1*", FtsQuery.build("a\" OR 1=1 --"))
        assertEquals("name*", FtsQuery.build("name:*"))
        assertNull(FtsQuery.build("!!! ???"))
        assertEquals(8, FtsQuery.build("a b c d e f g h i j k")!!.split(" ").size)
    }
}
