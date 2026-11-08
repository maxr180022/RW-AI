package com.rwai.agent.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** Дата и время уведомлений: сводка и пересказы агента должны показывать, когда это пришло. */
class NotificationTimesTest {
    private val uy = TimeZone.getTimeZone("America/Montevideo")
    private fun at(h: Int, min: Int) = Calendar.getInstance(uy).apply { clear(); set(2026, 10, 8, h, min) }.timeInMillis

    private fun msg(body: String, sender: String, ts: Long) =
        Msg(ts, "com.instagram.android", "Instagram", sender, null, body, group = false, hidden = false)

    private fun result() = MessageTriage.analyze(
        listOf(msg("Привет", "taisiialook", at(1, 5)), msg("Ты тут?", "taisiialook", at(1, 17)), msg("Позвони", "Ира", at(23, 40))),
    )

    @Test fun digestDataGivesTheModelTheTimeOfTheLastMessage() {
        val data = MessageTriage.digestData(result().threads, tz = uy)
        assertTrue(data, data.contains("taisiialook") && data.contains("последнее 08.11 01:17"))
        assertTrue(data, data.contains("Ира") && data.contains("последнее 08.11 23:40"))
        // время берётся у ПОСЛЕДНЕГО сообщения, а не у первого
        assertFalse(data, data.contains("08.11 01:05"))
    }

    @Test fun localSummaryShowsTheTimeToo() {
        val s = MessageTriage.localSummary(result(), tz = uy)
        assertTrue(s, s.contains("taisiialook (2, 08.11 01:17)"))
        assertTrue(s, s.contains("Ира (1, 08.11 23:40)"))
    }

    @Test fun systemPromptAsksForDateAndTimeInEveryItem() {
        val p = SystemPrompt.build()
        assertTrue(p.contains("указывай дату и время (дд.мм чч:мм)"))
    }
}
