package com.rwai.agent.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileTemplatesTest {
    private val v = mapOf("email" to "max@example.com", "phone" to "+598 99 123 456", "first_name" to "Макс")

    @Test fun expandsKnownAndReportsMissing() {
        val r = ProfileTemplates.expand("Почта {{profile.email}}, адрес {{profile.address}}", v)
        assertEquals("Почта max@example.com, адрес {{profile.address}}", r.text)
        assertEquals(listOf("address"), r.missing)
    }

    @Test fun redactReplacesValuesWithPlaceholders() {
        val r = ProfileTemplates.redact("Поле: max@example.com / +598 99 123 456", v)
        assertEquals("Поле: {{profile.email}} / {{profile.phone}}", r)
    }

    @Test fun roundTrip() {
        val typed = "{{profile.email}}"
        val screen = ProfileTemplates.expand(typed, v).text
        assertEquals(typed, ProfileTemplates.redact(screen, v))
    }

    @Test fun keysAndValidation() {
        assertEquals(listOf("email", "phone"), ProfileTemplates.keysIn("{{profile.email}} {{ profile.PHONE }} {{profile.email}}"))
        assertTrue(ProfileTemplates.isValidKey("work_phone")); assertFalse(ProfileTemplates.isValidKey("1abc")); assertFalse(ProfileTemplates.isValidKey("Имя"))
    }

    @Test fun cardNumbersRejected() {
        assertTrue(ProfileTemplates.looksLikeCard("4111 1111 1111 1111"))
        assertFalse(ProfileTemplates.looksLikeCard("+598 99 123 456")); assertFalse(ProfileTemplates.looksLikeCard("max@example.com"))
    }
}
