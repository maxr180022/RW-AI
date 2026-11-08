package com.rwai.agent.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryCompactionTest {
    private fun screen(n: Int) = ChatMessage.user("РЕЗУЛЬТАТ inspect_screen: status=OK\n" + "экран$n ".repeat(200))
    private fun other(tool: String) = ChatMessage.user("РЕЗУЛЬТАТ $tool: status=OK\n" + "данные ".repeat(200))

    @Test fun olderScreensAreShrunkAndLatestTwoKept() {
        val h = listOf(
            ChatMessage.user("ЗАДАЧА ВЛАДЕЛЬЦА: открой почту"),
            screen(1), ChatMessage.assistant("{\"action\":1}"), screen(2), other("read_notifications"), screen(3), screen(4),
        )
        val r = HistoryCompaction.shrinkOldScreens(h)
        assertEquals(h.size, r.size)
        assertEquals(HistoryCompaction.STUB, r[1].content)
        assertEquals(HistoryCompaction.STUB, r[3].content)
        assertSame(h[5], r[5])
        assertSame(h[6], r[6])
        assertSame(h[0], r[0])
        assertSame(h[4], r[4]) // результаты других инструментов остаются: в них найденные факты
        assertSame(h[2], r[2])
    }

    @Test fun twoOrFewerScreensAreLeftAlone() {
        val h = listOf(ChatMessage.user("ЗАДАЧА"), screen(1), screen(2))
        assertSame(h, HistoryCompaction.shrinkOldScreens(h))
    }

    @Test fun shortOldScreenIsNotWorthReplacing() {
        val tiny = ChatMessage.user("РЕЗУЛЬТАТ inspect_screen: status=OK\nпусто")
        val h = listOf(tiny, screen(1), screen(2))
        assertSame(h, HistoryCompaction.shrinkOldScreens(h))
    }

    @Test fun modelAnswersAndTaskAreNeverTouched() {
        val a = ChatMessage.assistant("РЕЗУЛЬТАТ inspect_screen: " + "x".repeat(900))
        val h = listOf(a, screen(1), screen(2), screen(3))
        val r = HistoryCompaction.shrinkOldScreens(h)
        assertSame(a, r[0])
        assertEquals(HistoryCompaction.STUB, r[1].content)
    }

    @Test fun usageLinesAreSortedAndShowCacheShare() {
        val lines = UsageView.lines(
            listOf(
                UsageLine("translate", 1200, 1000, 0),
                UsageLine("agent", 52340, 50000, 30000),
                UsageLine("digest", 0, 0, 0),
            ),
        )
        assertEquals(listOf("Агент (шаги задач): 52 340 · кэш 60 %", "Автоперевод сообщений: 1 200"), lines)
        assertTrue(UsageView.lines(emptyList()).isEmpty())
        assertEquals("12", UsageView.group(12))
        assertEquals("1 234 567", UsageView.group(1234567))
    }
}
