package com.rwai.agent.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** Строка «Не отвечено»: кто ждёт ответа, что новое, как отмечается ответ. */
class PendingRepliesTest {
    private val uy = TimeZone.getTimeZone("America/Montevideo")
    private fun at(h: Int, min: Int) = Calendar.getInstance(uy).apply { clear(); set(2026, 10, 8, h, min) }.timeInMillis

    private fun msg(body: String, sender: String, ts: Long, pkg: String = "com.instagram.android", app: String = "Instagram") =
        Msg(ts, pkg, app, sender, null, body, group = false, hidden = false)

    private fun threads() = MessageTriage.analyze(
        listOf(
            msg("Привет", "taisiialook", at(1, 5)), msg("Ты тут?", "taisiialook", at(1, 17)),
            msg("Позвони пожалуйста", "Ира", at(23, 40), "org.telegram.messenger", "Telegram"),
        ),
    ).threads

    @Test fun everyoneWhoWroteAndWasNotAnsweredIsPending() {
        val p = PendingReplies.pending(threads(), emptyMap(), seenAt = Long.MAX_VALUE)
        assertEquals(setOf("taisiialook", "Ира"), p.map { it.contact }.toSet())
        assertEquals(2, p.first { it.contact == "taisiialook" }.count)
        assertEquals(at(1, 17), p.first { it.contact == "taisiialook" }.lastTs)
    }

    @Test fun answeredThreadDisappearsAndNewMessageBringsItBack() {
        val replied = mapOf(PendingReplies.key("taisiialook") to at(2, 0))
        val after = PendingReplies.pending(threads(), replied, 0L)
        assertEquals(listOf("Ира"), after.map { it.contact })
        // после ответа она написала ещё — снова неотвечено, и только новое сообщение считается
        val more = threads().map { t ->
            if (t.contact == "taisiialook") t.copy(messages = t.messages + msg("Алло?", "taisiialook", at(3, 0))) else t
        }
        val again = PendingReplies.pending(more, replied, 0L).first { it.contact == "taisiialook" }
        assertEquals(1, again.count)
        assertEquals(at(3, 0), again.lastTs)
    }

    @Test fun contactMatchingIgnoresCaseAndSpaces() {
        val replied = mapOf(PendingReplies.key("  TAISIIALOOK ") to at(5, 0))
        assertFalse(PendingReplies.pending(threads(), replied, 0L).any { it.contact == "taisiialook" })
    }

    @Test fun newOnesComeFirstThenUrgentThenFresher() {
        val seen = at(23, 0) // «Ира» написала после последнего просмотра, taisiialook — до
        val p = PendingReplies.pending(threads(), emptyMap(), seen)
        assertEquals("Ира", p.first().contact)
        assertTrue(p.first().isNew)
        assertFalse(p.first { it.contact == "taisiialook" }.isNew)
    }

    @Test fun repliedAtStorageRoundTripsAndKeepsTheNewest() {
        var raw = ""
        raw = PendingReplies.mark(raw, "taisiialook", 100)
        raw = PendingReplies.mark(raw, "Ира", 200)
        raw = PendingReplies.mark(raw, "TAISIIALOOK", 50) // более ранний ответ не затирает более поздний
        assertEquals(mapOf("taisiialook" to 100L, "ира" to 200L), PendingReplies.parse(raw))
        raw = PendingReplies.unmark(raw, "Ира")
        assertEquals(mapOf("taisiialook" to 100L), PendingReplies.parse(raw))
        assertEquals("", PendingReplies.mark("", "   ", 1)) // пустое имя не записывается
    }

    @Test fun namesWithSeparatorsSurviveStorage() {
        val raw = PendingReplies.mark("", "Иван=Петров: офис", 777)
        assertEquals(mapOf("иван=петров: офис" to 777L), PendingReplies.parse(raw))
    }

    @Test fun storageIsCappedKeepingTheNewest() {
        var raw = ""
        repeat(PendingReplies.STORE_CAP + 40) { raw = PendingReplies.mark(raw, "c$it", it.toLong() + 1) }
        val m = PendingReplies.parse(raw)
        assertEquals(PendingReplies.STORE_CAP, m.size)
        assertTrue(m.containsKey("c${PendingReplies.STORE_CAP + 39}"))
        assertFalse(m.containsKey("c0"))
    }

    @Test fun lineAndTitleAreReadable() {
        val p = PendingReplies.pending(threads(), emptyMap(), 0L).first { it.contact == "taisiialook" }
        assertEquals("taisiialook · Instagram · 08.11 01:17", PendingReplies.line(p, uy))
        assertEquals("Не отвечено: 3", PendingReplies.title(3))
    }
}
