package com.rwai.agent.logic

/**
 * Экономия токенов в цикле агента. На каждом шаге модель получает всю историю заново, а самый тяжёлый её кусок — снимки экрана
 * (до 6000 знаков каждый). Устаревший снимок модели не нужен: экран уже изменился, а актуальный есть в последних результатах.
 * Поэтому все снимки, кроме последних [keep], заменяются одной короткой строкой. Остальная история (задача, решения, результаты других
 * инструментов, найденные факты) остаётся как есть.
 */
object HistoryCompaction {
    const val SCREEN_PREFIX = "РЕЗУЛЬТАТ inspect_screen:"
    const val STUB = "РЕЗУЛЬТАТ inspect_screen: [старый снимок экрана опущен: экран уже изменился, актуальный смотри в последних результатах]"
    private const val MIN_LEN_TO_SHRINK = 600

    fun shrinkOldScreens(history: List<ChatMessage>, keep: Int = 2): List<ChatMessage> {
        val screenIdx = history.indices.filter { isScreen(history[it]) }
        if (screenIdx.size <= keep) return history
        val old = screenIdx.dropLast(keep).filter { history[it].content.length >= MIN_LEN_TO_SHRINK }.toSet()
        if (old.isEmpty()) return history
        return history.mapIndexed { i, m -> if (i in old) ChatMessage.user(STUB) else m }
    }

    private fun isScreen(m: ChatMessage) = m.role == "user" && m.content.startsWith(SCREEN_PREFIX)
}

/** Расход токенов одного источника за сутки: [tokens] всего, [prompt] из них на запрос, [cached] из запроса взято из кэша DeepSeek. */
data class UsageLine(val source: String, val tokens: Int, val prompt: Int, val cached: Int)

object UsageView {
    /** Все источники, по которым ведётся счёт, в порядке показа. */
    val SOURCES = listOf("agent", "translate", "digest", "notes", "phone", "other")

    fun label(source: String): String = when (source) {
        "agent" -> "Агент (шаги задач)"
        "translate" -> "Автоперевод сообщений"
        "digest" -> "Сводки сообщений"
        "notes" -> "Сводка уведомлений"
        "phone" -> "Советы по телефону"
        else -> "Прочее"
    }

    fun group(n: Int): String = n.toString().reversed().chunked(3).joinToString(" ").reversed()

    fun line(l: UsageLine): String {
        val cache = if (l.prompt > 0 && l.cached > 0) " · кэш ${(l.cached.toLong() * 100 / l.prompt).coerceIn(0, 100)} %" else ""
        return "${label(l.source)}: ${group(l.tokens)}$cache"
    }

    /** Только источники с расходом, самые затратные первыми. */
    fun lines(all: List<UsageLine>): List<String> = all.filter { it.tokens > 0 }.sortedByDescending { it.tokens }.map { line(it) }
}
