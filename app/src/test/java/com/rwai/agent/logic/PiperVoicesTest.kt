package com.rwai.agent.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiperVoicesTest {
    @Test fun everyVoiceIsCommerciallyUsableAndComplete() {
        for (v in PiperCatalog.ALL) {
            assertTrue("лицензия ${v.id}", v.license in PiperCatalog.COMMERCIAL_OK)
            assertEquals(64, v.sha256.length)
            assertTrue(v.size > 1_000_000)
            assertTrue(v.archive.endsWith(".tar.bz2"))
            assertTrue(PiperCatalog.url(v).startsWith("https://github.com/k2-fsa/sherpa-onnx/releases/download/"))
        }
        assertEquals(PiperCatalog.ALL.size, PiperCatalog.ALL.map { it.id }.toSet().size)
    }

    @Test fun nonCommercialVoicesAreNotInTheCatalog() {
        val names = PiperCatalog.ALL.joinToString { it.archive }
        for (banned in listOf("ruslan", "ryan", "irina", "hfc_", "lessac")) assertTrue(banned, !names.contains(banned))
    }

    @Test fun everySupportedLanguageHasAVoice() {
        for ((lang, _) in PiperCatalog.LANGUAGES) assertNotNull(lang, PiperCatalog.defaultFor(lang))
    }

    @Test fun dmitriIsTheOnlyRussianAndTheDefaultVoice() {
        assertEquals(listOf("ru_dmitri"), PiperCatalog.forLanguage("ru-RU").map { it.id })
        assertEquals(PiperCatalog.PERMANENT_ID, PiperCatalog.defaultFor("ru")?.id)
        assertNotNull(PiperCatalog.byId(PiperCatalog.PERMANENT_ID))
    }

    @Test fun englishHasSeveralMaleVoicesAndJoeIsTheDefault() {
        assertEquals("en_joe", PiperCatalog.defaultFor("en-US")?.id)
        assertTrue(PiperCatalog.forLanguage("en").size >= 3)
    }

    @Test fun languageTagsMapToVoiceLanguages() {
        assertEquals("ru", PiperCatalog.langOf("ru-RU"))
        assertEquals("es", PiperCatalog.langOf("es-UY"))
        assertEquals("en", PiperCatalog.langOf("en_US"))
        assertEquals("ru_dmitri", PiperCatalog.defaultFor("ru-RU")?.id)
        assertEquals("es_ald", PiperCatalog.defaultFor("es-UY")?.id)
    }

    @Test fun textIsSplitIntoSentencesAndLongOnesAreCut() {
        assertEquals(listOf("Привет!", "Как дела?", "Хорошо."), SpeechChunks.split("Привет!  Как дела? Хорошо."))
        assertTrue(SpeechChunks.split("").isEmpty())
        val long = "слово, ".repeat(100)
        val parts = SpeechChunks.split(long, 100)
        assertTrue(parts.all { it.length <= 100 })
        assertTrue(parts.size > 4)
    }

    @Test fun namesAreSpokenTheWayTheOwnerWants() {
        assertEquals("Тебя создал Максим Рикко и команда Русвэо.", Speakable.clean("Тебя создал Maxim Ricco и команда RusWeo."))
        assertEquals("Максим Рикко", Speakable.clean("Максим Рикко"))
        assertEquals("команда Русвэо", Speakable.clean("команда РусВео"))
        // для английской и других речей имена не трогаем
        assertEquals("Maxim Ricco", Speakable.clean("Maxim Ricco", russian = false))
    }
}
