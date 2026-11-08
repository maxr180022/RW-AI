package com.rwai.agent.digest

import android.content.Context
import com.rwai.agent.Graph
import com.rwai.agent.logic.MessageTriage
import com.rwai.agent.logic.Msg
import com.rwai.agent.logic.PendingReply
import com.rwai.agent.logic.PendingReplies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * Отметки «владелец ответил этому человеку» и момент, когда он в последний раз смотрел «Входящие».
 * Хранится только на телефоне. По ним строка на главном экране показывает, кто ещё ждёт ответа и что нового.
 */
object RepliedStore {
    private val prefs get() = Graph.appContext().getSharedPreferences("rwai_digest", Context.MODE_PRIVATE)

    /** Меняется при каждой отметке: экран пересчитывает строку «Не отвечено». */
    val tick = MutableStateFlow(0L)

    private fun raw(): String = prefs.getString("replied", "").orEmpty()

    fun map(): Map<String, Long> = PendingReplies.parse(raw())

    fun mark(contact: String, ts: Long = System.currentTimeMillis()) {
        if (contact.isBlank()) return
        prefs.edit().putString("replied", PendingReplies.mark(raw(), contact, ts)).apply()
        tick.value = System.nanoTime()
    }

    fun unmark(contact: String) {
        prefs.edit().putString("replied", PendingReplies.unmark(raw(), contact)).apply()
        tick.value = System.nanoTime()
    }

    fun seenAt(): Long = prefs.getLong("inbox_seen", 0L)

    fun markSeen(ts: Long = System.currentTimeMillis()) {
        prefs.edit().putLong("inbox_seen", ts).apply()
        tick.value = System.nanoTime()
    }

    /** Кто ждёт ответа сейчас: личные переписки за сутки без ответа владельца. Без модели и без сети. */
    suspend fun pending(): List<PendingReply> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val rows = Graph.db.notificationsSince(now - PendingReplies.WINDOW_MS, 400)
        val result = MessageTriage.analyze(rows.map { Msg(it.ts, it.pkg, it.app, it.sender, it.title, it.body, it.group, it.hidden) })
        PendingReplies.pending(result.threads, map(), seenAt())
    }
}
