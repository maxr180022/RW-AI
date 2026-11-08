package com.rwai.agent.notifications

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.rwai.agent.logic.PendingReplies

/** Поле «Ответить» из уведомления мессенджера: приложение, имена, под которыми разговор виден, и само действие. */
data class ReplyHandle(val pkg: String, val app: String, val keys: Set<String>, val action: Notification.Action, val ts: Long)

/**
 * Ответ без перелистывания экранов. Пока у разговора есть уведомление с полем «Ответить» (WhatsApp, Telegram, Instagram и другие),
 * текст можно отправить прямо в него, не открывая приложение. Действия хранятся только в памяти процесса и только сутки.
 * Пользуется этим только кнопка владельца с его же текстом; агент сам таким путём не отправляет.
 */
object ReplyHandles {
    private const val MAX = 40
    private const val MAX_AGE_MS = 24 * 3_600_000L
    private val list = ArrayList<ReplyHandle>()

    @Synchronized
    fun put(h: ReplyHandle) {
        if (h.keys.isEmpty()) return
        list.removeAll { it.pkg == h.pkg && it.keys.any { k -> k in h.keys } }
        list.add(0, h)
        while (list.size > MAX) list.removeAt(list.size - 1)
    }

    /** Строго: имя и приложение должны совпасть, иначе «Иван» из Telegram получил бы ответ, предназначенный «Ивану» из WhatsApp. */
    @Synchronized
    fun find(recipient: String, app: String, now: Long = System.currentTimeMillis()): ReplyHandle? {
        val k = PendingReplies.key(recipient)
        if (k.isEmpty()) return null
        val a = app.trim()
        return list.firstOrNull { h ->
            now - h.ts < MAX_AGE_MS && k in h.keys &&
                (a.isEmpty() || h.app.equals(a, ignoreCase = true) || h.app.contains(a, ignoreCase = true) || a.contains(h.app, ignoreCase = true))
        }
    }

    fun canReply(recipient: String, app: String): Boolean = find(recipient, app) != null

    /** true — приложение приняло ответ. Если уведомление уже убрано и действие не работает, возвращает false: тогда остаётся путь через агента. */
    fun send(context: Context, h: ReplyHandle, text: String): Boolean {
        val inputs = h.action.remoteInputs
        if (inputs.isNullOrEmpty() || text.isBlank()) return false
        val intent = Intent()
        val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
        return try {
            RemoteInput.addResultsToIntent(inputs, intent, results)
            h.action.actionIntent.send(context, 0, intent)
            true
        } catch (_: PendingIntent.CanceledException) {
            false
        } catch (_: Exception) {
            false
        }
    }
}
