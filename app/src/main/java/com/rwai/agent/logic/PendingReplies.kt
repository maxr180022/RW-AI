package com.rwai.agent.logic

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Переписка, где после последнего ответа владельца пришло что-то новое. isNew — пришло после того, как владелец в последний раз смотрел «Входящие». */
data class PendingReply(val app: String, val contact: String, val lastTs: Long, val count: Int, val urgent: Boolean, val isNew: Boolean)

/**
 * «Кому я не ответил»: строка на главном экране. Считается только по уведомлениям на телефоне (без модели, бесплатно).
 * Ответом считается момент, когда владелец ответил (через агента, карточку или «Отправить как есть»): всё, что пришло ДО него, закрыто,
 * всё, что пришло ПОСЛЕ, снова появится как неотвеченное.
 */
object PendingReplies {
    const val WINDOW_MS = 24 * 3_600_000L
    const val STORE_CAP = 200

    fun key(contact: String): String = contact.trim().lowercase()

    /** Неотвеченные и новые сверху; внутри — срочные, затем по времени (свежие выше). */
    fun pending(threads: List<MessageTriage.Thread>, repliedAt: Map<String, Long>, seenAt: Long): List<PendingReply> =
        threads.mapNotNull { t ->
            val replied = repliedAt[key(t.contact)] ?: 0L
            val fresh = t.messages.filter { it.ts > replied }
            if (fresh.isEmpty()) null else {
                val last = fresh.maxOf { it.ts }
                PendingReply(t.app, t.contact, last, fresh.size, t.urgent, last > seenAt)
            }
        }.sortedWith(compareByDescending<PendingReply> { it.isNew }.thenByDescending { it.urgent }.thenByDescending { it.lastTs })

    // Хранение: «ключ<TAB>время» на строку. Табуляция, потому что в имени может быть «=» или «:».
    fun parse(raw: String): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        for (line in raw.split('\n')) {
            val i = line.lastIndexOf('\t')
            if (i <= 0) continue
            val ts = line.substring(i + 1).trim().toLongOrNull() ?: continue
            out[line.substring(0, i)] = ts
        }
        return out
    }

    fun serialize(map: Map<String, Long>): String =
        map.entries.sortedByDescending { it.value }.take(STORE_CAP).joinToString("\n") { it.key + "\t" + it.value }

    fun mark(raw: String, contact: String, ts: Long): String {
        val k = key(contact)
        if (k.isEmpty()) return raw
        val m = parse(raw).toMutableMap()
        m[k] = maxOf(ts, m[k] ?: 0L)
        return serialize(m)
    }

    fun unmark(raw: String, contact: String): String {
        val m = parse(raw).toMutableMap()
        m.remove(key(contact))
        return serialize(m)
    }

    fun stamp(ms: Long, tz: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("dd.MM HH:mm", Locale.US).apply { timeZone = tz }.format(Date(ms))

    fun title(count: Int): String = "Не отвечено: $count"

    fun line(p: PendingReply, tz: TimeZone = TimeZone.getDefault()): String = "${p.contact} · ${p.app} · ${stamp(p.lastTs, tz)}"
}
