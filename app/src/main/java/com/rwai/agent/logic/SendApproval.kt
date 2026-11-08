package com.rwai.agent.logic

/** Сообщение, которое владелец уже одобрил кнопкой «Отправить» на карточке черновика: адресат, приложение и точный текст. */
data class ApprovedSend(val recipient: String, val app: String, val text: String)

/**
 * Одобрение отправки кнопкой на карточке черновика.
 * Владелец видит точный текст и адресата и нажимает «Отправить» — это и есть подтверждение, второе окно не нужно.
 * Одобрение узкое: действует один раз, только для задачи, запущенной этой кнопкой, и только если запрос подтверждения
 * на отправку обычного сообщения содержит РОВНО этот текст (и того же адресата). Любое отличие — обычное подтверждение.
 * Платежи, звонки, удаление и прочие опасные действия сюда не попадают никогда.
 */
object SendApproval {
    private const val SEND_TITLE = "Отправить сообщение"
    private val recipientLine = Regex("(?m)^Получатель: (.*)$")

    private fun norm(s: String): String = s.replace(Regex("\\s+"), " ").trim()

    fun matches(req: ConfirmationRequest, approved: ApprovedSend): Boolean {
        if (req.title != SEND_TITLE || req.dangerous) return false
        val want = norm(approved.text)
        if (want.isEmpty()) return false
        val d = req.details
        val from = d.indexOf("Текст:\n")
        val to = d.lastIndexOf("\nПоследствия:")
        if (from < 0 || to < from) return false
        if (norm(d.substring(from + "Текст:\n".length, to)) != want) return false
        val recipient = recipientLine.find(d)?.groupValues?.get(1)?.trim()
        if (recipient != null && approved.recipient.isNotBlank() && !recipient.equals(approved.recipient.trim(), ignoreCase = true)) return false
        return true
    }

    /** Команда агенту для отправки одобренного текста без изменений. Видна владельцу в чате. */
    fun sendTask(approved: ApprovedSend): String {
        val to = approved.recipient.ifBlank { "адресату" } + (if (approved.app.isNotBlank()) " в ${approved.app}" else "")
        return "Отправь сообщение $to ровно этим текстом, без изменений: «${approved.text.trim()}»"
    }

    /** Команда агенту «перепиши черновик»: новый черновик он покажет, ничего не отправляя. */
    fun rewriteTask(approved: ApprovedSend, instruction: String): String? {
        val ins = instruction.trim()
        if (ins.isEmpty()) return null
        val to = approved.recipient.ifBlank { "адресата" } + (if (approved.app.isNotBlank()) " в ${approved.app}" else "")
        return "Перепиши черновик для $to: $ins. Текущий текст: «${approved.text.trim()}». Покажи новый черновик, не отправляй."
    }
}
