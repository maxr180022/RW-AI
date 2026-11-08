package com.rwai.agent.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class DialogsTest {
    private val uy = TimeZone.getTimeZone("America/Montevideo")
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0): Long =
        Calendar.getInstance(uy).apply { clear(); set(y, m - 1, d, h, min) }.timeInMillis

    private fun turns(n: Int, len: Int = 300): List<DialogTurn> =
        (0 until n).map { DialogTurn(fromOwner = it % 2 == 0, text = (if (it % 2 == 0) "вопрос $it " else "ответ $it ") + "x".repeat(len), ts = 1_000L + it) }

    // ---------- подпись диалога ----------

    @Test fun titleIsJustTheDate() {
        assertEquals("08.11.2026", DialogRules.title(at(2026, 11, 8, 15), emptyList(), uy))
        assertEquals("01.01.2027", DialogRules.title(at(2027, 1, 1, 0, 5), listOf("08.11.2026"), uy))
    }

    @Test fun secondDialogOfTheDayGetsNumber() {
        val t = at(2026, 11, 8, 15)
        assertEquals("08.11.2026 (2)", DialogRules.title(t, listOf("08.11.2026"), uy))
        assertEquals("08.11.2026 (3)", DialogRules.title(t, listOf("08.11.2026", "08.11.2026 (2)"), uy))
        assertEquals("08.11.2026", DialogRules.title(t, listOf("07.11.2026", "08.12.2026"), uy))
    }

    @Test fun newDayIsDecidedByCalendarDayNotBy24Hours() {
        assertFalse(DialogRules.isNewDay(at(2026, 11, 8, 0, 5), at(2026, 11, 8, 23, 55), uy))
        assertTrue(DialogRules.isNewDay(at(2026, 11, 8, 23, 55), at(2026, 11, 9, 0, 5), uy))
        assertTrue(DialogRules.isNewDay(at(2026, 11, 8, 12), at(2026, 12, 8, 12), uy))
    }

    // ---------- заполненность ----------

    @Test fun fillGrowsWithMemoryAndStaysWithin100() {
        assertEquals(0, DialogRules.fillPercent("", emptyList()))
        val small = DialogRules.fillPercent("", turns(4))
        val big = DialogRules.fillPercent("", turns(24))
        assertTrue("small=$small big=$big", small in 1 until big)
        assertEquals(100, DialogRules.fillPercent("x".repeat(100_000), turns(200)))
    }

    @Test fun projectNoteCountsTowardFill() {
        val without = DialogRules.contextTokens("", turns(4))
        val with = DialogRules.contextTokens("", turns(4), projectNote = "n".repeat(500))
        assertTrue(with > without)
    }

    @Test fun levelsMatchThresholds() {
        assertEquals(FillLevel.OK, DialogRules.level(69))
        assertEquals(FillLevel.WARN, DialogRules.level(70))
        assertEquals(FillLevel.WARN, DialogRules.level(89))
        assertEquals(FillLevel.FULL, DialogRules.level(90))
        assertFalse(DialogRules.needsCompaction(79))
        assertTrue(DialogRules.needsCompaction(80))
    }

    // ---------- сжатие ----------

    @Test fun compactionShrinksBelowWarningAndKeepsRecentTurns() {
        val all = turns(40, 500)
        val before = DialogRules.fillPercent("", all)
        assertTrue("до сжатия должно быть заполнено: $before", DialogRules.needsCompaction(before))
        val c = DialogRules.compact("", all)
        val after = DialogRules.fillPercent(c.summary, c.turns)
        assertTrue("после=$after", after < DialogRules.WARN_PERCENT)
        assertTrue(c.folded > 0)
        assertEquals(all.takeLast(c.turns.size), c.turns)
        assertTrue(c.turns.size >= DialogRules.KEEP_LAST_TURNS)
        assertTrue(c.summary.length <= DialogRules.SUMMARY_CAP)
        assertTrue(c.summary.contains("ответ 31"))
    }

    @Test fun compactionNeverCutsAnExchangeInHalf() {
        val all = turns(7) // 0 вопрос, 1 ответ, ... 6 вопрос
        val c = DialogRules.compact("", all)
        // на границе ответ агента: его вопрос остаётся с ним, поэтому первой в хвосте не может быть реплика-«сирота»-ответ
        assertTrue(c.turns.isEmpty() || c.turns.first().fromOwner)
    }

    @Test fun nothingToCompactIsReportedHonestly() {
        val few = turns(4)
        val c = DialogRules.compact("старое", few)
        assertEquals(0, c.folded)
        assertEquals("старое", c.summary)
        assertEquals(few, c.turns)
    }

    @Test fun summaryNeverExceedsCapAndDropsOldestLines() {
        var s = ""
        repeat(60) { i -> s = DialogRules.fold(s, listOf(DialogTurn(true, "задача номер $i " + "я".repeat(80), 0), DialogTurn(false, "итог $i " + "ы".repeat(200), 0))) }
        assertTrue(s.length <= DialogRules.SUMMARY_CAP)
        assertTrue(s.contains("задача номер 59"))
        assertFalse(s.contains("задача номер 0 "))
    }

    @Test fun foldMakesTaskArrowResultLines() {
        val s = DialogRules.fold("", listOf(DialogTurn(true, "Найди письмо от банка", 1), DialogTurn(false, "Письмо найдено, оно про карту.", 2), DialogTurn(true, "Спасибо", 3)))
        assertEquals("• Найди письмо от банка → Письмо найдено, оно про карту.\n• Спасибо", s)
    }

    // ---------- перенос в следующий диалог ----------

    @Test fun carryOverContainsEverythingFromThePreviousDialogButIsCapped() {
        val s = DialogRules.carryOver("• давнее дело → сделано", turns(10, 40))
        assertTrue(s.contains("давнее дело"))
        assertTrue(s.contains("ответ 9"))
        assertTrue(s.length <= DialogRules.CARRY_CAP)
        assertTrue(DialogRules.carryOver("", turns(200, 400)).length <= DialogRules.CARRY_CAP)
    }

    @Test fun emptyMemoryGivesEmptyContext() {
        assertEquals("", DialogRules.buildContext("", emptyList()))
    }

    @Test fun contextHasSummaryAndRecentTurnsAndIsCapped() {
        val ctx = DialogRules.buildContext("• старое → итог", listOf(DialogTurn(true, "привет", 1), DialogTurn(false, "я".repeat(5000), 2)))
        assertTrue(ctx.contains("Кратко о прошлом в этом диалоге:\n• старое → итог"))
        assertTrue(ctx.contains("Владелец: привет"))
        assertTrue(ctx.contains("Агент: " + "я".repeat(DialogRules.TURN_CAP_AGENT)))
        assertFalse(ctx.contains("я".repeat(DialogRules.TURN_CAP_AGENT + 1)))
    }

    @Test fun worstCaseMemoryFitsTheProtectionLimitSoNewestTurnsAreNeverCut() {
        // Защита обрезает вставленный блок на 6000 знаках с конца: память после сжатия обязана помещаться, иначе пропадут самые свежие реплики.
        val huge = (0 until 200).map { DialogTurn(it % 2 == 0, "я".repeat(5_000), it.toLong()) }
        val c = DialogRules.compact("", huge)
        val ctx = DialogRules.buildContext(c.summary, c.turns, "П".repeat(40), "н".repeat(5_000))
        assertTrue("длина=${ctx.length}", ctx.length <= 5_800)
        val wrapped = UntrustedContent.wrap("history", ctx)
        assertFalse(wrapped.contains("обрезано"))
        assertTrue(wrapped.contains("Агент: " + "я".repeat(DialogRules.TURN_CAP_AGENT)))
    }

    @Test fun contextBeforeCompactionStaysWithinLimitToo() {
        // Самая полная память, при которой сжатие ещё не включилось (79 %), тоже не должна обрезаться защитой.
        var turns = emptyList<DialogTurn>()
        var i = 0
        while (DialogRules.fillPercent("", turns) < DialogRules.COMPACT_PERCENT) { turns = turns + DialogTurn(i % 2 == 0, "я".repeat(5_000), i.toLong()); i++ }
        val before = turns.dropLast(1)
        assertTrue(DialogRules.fillPercent("", before) < DialogRules.COMPACT_PERCENT)
        assertTrue(DialogRules.buildContext("", before).length <= 5_800)
    }

    // ---------- проекты: изоляция ----------

    @Test fun projectContextCarriesOnlyItsOwnMemoryAndNote() {
        val ctx = DialogRules.buildContext("• договор аренды → проверен", emptyList(), projectName = "Квартира", projectNote = "Только про аренду")
        assertTrue(ctx.contains("Проект «Квартира»: Только про аренду"))
        assertTrue(ctx.contains("только в рамках этого проекта"))
        assertTrue(ctx.contains("договор аренды"))
        val plain = DialogRules.buildContext("• договор аренды → проверен", emptyList())
        assertFalse(plain.contains("Проект"))
    }

    @Test fun carryOverOfAnotherProjectIsNotMixedIn() {
        // Перенос считается только из памяти конкретного диалога: чужая память в него попасть не может.
        val projectA = DialogRules.carryOver("• секрет проекта А → ок", emptyList())
        val projectB = DialogRules.carryOver("• дело проекта Б → ок", emptyList())
        assertFalse(projectB.contains("секрет проекта А"))
        assertFalse(projectA.contains("дело проекта Б"))
    }

    @Test fun projectNameValidation() {
        assertEquals("Введите название проекта.", DialogRules.validateProjectName("   ", emptyList()))
        assertEquals("Введите название проекта.", DialogRules.validateProjectName("<>", emptyList()))
        assertEquals("Проект с таким названием уже есть.", DialogRules.validateProjectName("квартира", listOf("Квартира")))
        assertNull(DialogRules.validateProjectName("Работа", listOf("Квартира")))
        val many = (1..DialogRules.MAX_PROJECTS).map { "П$it" }
        assertTrue(DialogRules.validateProjectName("Ещё один", many)!!.contains("Слишком много"))
    }

    @Test fun projectNameAndNoteAreCleaned() {
        assertEquals("Моя работа", DialogRules.cleanProjectName("  Моя   <работа>  "))
        assertEquals(DialogRules.NAME_CAP, DialogRules.cleanProjectName("я".repeat(100)).length)
        assertEquals(DialogRules.NOTE_CAP, DialogRules.cleanProjectNote("я".repeat(2000)).length)
        assertFalse(DialogRules.cleanProjectNote("<script>x</script>").contains("<"))
    }
}
