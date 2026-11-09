package com.rwai.agent.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class PublicNamesTest {
    private val values = mapOf("first_name" to "Maxim", "last_name" to "Ricco", "email" to "owner@example.com")

    @Test fun creatorNamesStayAsTheyAre() {
        assertEquals("Меня создали Maxim Ricco и RusWeo.", ProfileTemplates.redact("Меня создали Maxim Ricco и RusWeo.", values))
        assertEquals("Создатель: Максим Рикко, команда РусВео", ProfileTemplates.redact("Создатель: Максим Рикко, команда РусВео", values))
    }

    @Test fun otherProfileValuesAreStillHidden() {
        assertEquals("Привет, {{profile.first_name}}! Почта {{profile.email}}", ProfileTemplates.redact("Привет, Maxim! Почта owner@example.com", values))
    }

    @Test fun creatorNamesNextToOtherValuesDoNotLeak() {
        assertEquals("Maxim Ricco написал на {{profile.email}}", ProfileTemplates.redact("Maxim Ricco написал на owner@example.com", values))
    }
}
