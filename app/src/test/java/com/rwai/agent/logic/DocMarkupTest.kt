package com.rwai.agent.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocMarkupTest {
    @Test fun statementLayoutBlocks() {
        val r = DocMarkup.parse(
            ">> В Тверской районный суд г. Москвы\n>> От: Иванова И. И.\n\n== ЗАЯВЛЕНИЕ\nПрошу рассмотреть дело.\n- копия паспорта\n|| Дата: 08.11.2026 | Подпись: ________",
        )
        val b = r.blocks
        assertTrue(b[0] is DocBlock.Head); assertTrue(b[1] is DocBlock.Head)
        assertTrue(b[2] is DocBlock.HalfGap)
        assertEquals(DocBlock.Title("ЗАЯВЛЕНИЕ"), b[3])
        assertEquals(DocBlock.Para("Прошу рассмотреть дело."), b[4])
        assertEquals(DocBlock.Item("копия паспорта"), b[5])
        assertEquals(DocBlock.Row("Дата: 08.11.2026", "Подпись: ________"), b[6])
        assertTrue(r.warnings.isEmpty())
    }

    @Test fun escapedNewlinesAreRestored() {
        val r = DocMarkup.parse("== Заголовок\\nТекст абзаца")
        assertEquals(2, r.blocks.size)
    }

    @Test fun emptyDocumentWarns() {
        assertTrue(DocMarkup.parse("\n\n").warnings.isNotEmpty())
    }

    @Test fun longTextIsCut() {
        val r = DocMarkup.parse("а".repeat(DocMarkup.MAX_CHARS + 500))
        assertTrue(r.warnings.any { it.contains("обрезан") })
    }

    @Test fun fileNameIsSafe() {
        assertEquals("Заявление.pdf", DocMarkup.fileName("../../etc/Заявление.pdf", "pdf", "doc"))
        assertEquals("doc.pdf", DocMarkup.fileName("   ", "pdf", "doc"))
        assertEquals("a_b.png", DocMarkup.fileName("a<b", "png", "x"))
    }

    @Test fun colorsParse() {
        assertEquals(0xFF112233.toInt(), ShapeMarkup.color("#123"))
        assertEquals(0xFF0B1220.toInt(), ShapeMarkup.color("#0B1220"))
        assertEquals(0xFFE53935.toInt(), ShapeMarkup.color("красный"))
        assertNull(ShapeMarkup.color("#12"))
        assertNull(ShapeMarkup.color("nonsense"))
    }

    @Test fun shapesParse() {
        val r = ShapeMarkup.parse(
            "canvas 1080 1350\nbg #0B1220 #7B5CFA\nrect 10 10 80 20 #fff fill 3\ncircle 50 50 10 red line\n" +
                "arrow 10 10 50 50 blue 0.5\ntext 50 30 6 white center bold | Руся AI\npage 2\nmark highlight yellow | Прошу рассмотреть",
        )
        assertTrue(r.errors.toString(), r.errors.isEmpty())
        assertEquals(8, r.shapes.size)
        assertEquals(Shape.Canvas(1080, 1350), r.shapes[0])
        val t = r.shapes[5] as Shape.Text
        assertEquals("Руся AI", t.text); assertEquals("center", t.align); assertTrue(t.bold)
        assertEquals(Shape.Page(2), r.shapes[6])
        assertNotNull((r.shapes[7] as Shape.Phrase).phrase)
    }

    @Test fun badShapesReportErrors() {
        val r = ShapeMarkup.parse("canvas 10 10\nrect 1 2\ncircle 1 2 3 nocolor\nfoo 1\ntext 1 1 5 red")
        assertEquals(5, r.errors.size)
        assertTrue(r.shapes.isEmpty())
    }

    @Test fun shapeLimit() {
        val r = ShapeMarkup.parse((1..450).joinToString("\n") { "circle 50 50 1 red" })
        assertEquals(ShapeMarkup.MAX_SHAPES, r.shapes.size)
        assertTrue(r.errors.isNotEmpty())
    }
}

class PhraseFinderTest {
    private fun line(page: Int, text: String, top: Float) =
        LineBox(page, text, FloatArray(text.length + 1) { it * 10f }, top, top + 16f, 595f, 842f)

    @Test fun findsInsideOneLine() {
        val r = PhraseFinder.find(listOf(line(1, "Прошу рассмотреть дело ", 100f)), "рассмотреть")
        assertEquals(1, r.size)
        assertEquals(60f, r[0].x1, 0.01f); assertEquals(170f, r[0].x2, 0.01f)
    }

    @Test fun findsAcrossLinesAndPages() {
        val ls = listOf(line(1, "Прошу взыскать с ответчика ", 700f), line(2, "сумму долга в размере ", 60f))
        val r = PhraseFinder.find(ls, "С ответчика сумму долга")
        assertEquals(2, r.size)
        assertEquals(1, r[0].page); assertEquals(2, r[1].page)
        assertEquals(0f, r[1].x1, 0.01f); assertEquals(110f, r[1].x2, 0.01f)
    }

    @Test fun missingPhraseGivesEmpty() {
        assertTrue(PhraseFinder.find(listOf(line(1, "один два", 10f)), "три").isEmpty())
        assertTrue(PhraseFinder.find(emptyList(), "x").isEmpty())
    }
}

class DigestViewTest {
    private val sample = """
- **taisiialook (Instagram):** отметила вас в истории; ссорится с Натин
- **maxr180022 (Gmail, пересказ с англ.):** сборка APK упала
- **Ждут ответа:** по сути никто
- **Срочно:** сломанная сборка APK — её надо чинить.
- **Договорённости (дата, время, сумма):** не указаны
""".trimIndent()

    @Test fun parsesIntoCardsWithoutStars() {
        val r = DigestView.parse(sample)
        assertEquals(4, r.size) // «не указаны» убрано
        assertEquals(DigestKind.PERSON, r[0].kind)
        assertEquals("taisiialook" to "Instagram", r[0].person())
        assertEquals("maxr180022" to "Gmail", r[1].person())
        assertEquals(DigestKind.WAITING, r[2].kind)
        assertEquals(DigestKind.URGENT, r[3].kind)
        assertTrue(r.none { it.body.contains("*") || (it.title ?: "").contains("*") })
    }

    @Test fun continuationLinesJoin() {
        val r = DigestView.parse("- **Иван (Telegram):** первая часть\nпродолжение")
        assertEquals(1, r.size)
        assertEquals("первая часть продолжение", r[0].body)
    }

    @Test fun mdSpansAndStrip() {
        assertEquals(listOf("Итог: " to true, "готово" to false), Md.spans("**Итог: **готово").map { it.first.let { s -> if (s == "Итог: ") s else s } to it.second })
        assertEquals("• пункт жирный", Md.strip("- пункт **жирный**"))
        assertEquals("Заголовок", Md.strip("## Заголовок"))
    }
}
