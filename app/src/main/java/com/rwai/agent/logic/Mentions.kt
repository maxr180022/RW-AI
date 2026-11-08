package com.rwai.agent.logic

/** Команда через «@»: быстрый выбор источника («@почта», «@ютуб»), чтобы агент не гадал и не тратил токены на поиск нужного инструмента. */
data class MentionCmd(val primary: String, val aliases: List<String>, val title: String, val directive: String, val builtin: Boolean = true)

data class MentionResult(val text: String, val directives: List<String>, val used: List<String>) {
    /** Текст для модели: указания владельца + сама задача. */
    fun forModel(): String =
        if (directives.isEmpty()) text
        else "УКАЗАНИЯ ВЛАДЕЛЬЦА (команды через @): " + directives.joinToString(" ") + "\nЗАДАЧА: " + text
}

object Mentions {
    val builtin: List<MentionCmd> = listOf(
        MentionCmd("почта", listOf("mail", "gmail", "email", "письма"), "Почта", "Используй только mail_list/mail_read/mail_send, приложения не открывай."),
        MentionCmd("ютуб", listOf("youtube", "yt"), "YouTube", "Используй youtube_search; результат дай списком со ссылками, при просьбе открой через open_url."),
        MentionCmd("музыка", listOf("music", "плеер", "трек"), "Музыка", "Используй media_status, media_control и play_music, без открытия приложений, если плеер уже играет."),
        MentionCmd("календарь", listOf("calendar", "события"), "Календарь", "Используй calendar_events и calendar_add."),
        MentionCmd("телефон", listOf("phone", "батарея", "заряд"), "Телефон", "Используй phone_status; не открывай приложения."),
        MentionCmd("погода", listOf("weather", "прогноз"), "Погода", "Используй weather; без города — по геолокации телефона."),
        MentionCmd("интернет", listOf("web", "поиск", "найди"), "Интернет", "Используй web_search и fetch_page; экран телефона не трогай."),
        MentionCmd("входящие", listOf("inbox", "сообщения", "уведомления"), "Входящие", "Начни с read_notifications и search_memory; приложения открывай, только если данных не хватает."),
        MentionCmd("телеграм", listOf("telegram", "tg"), "Telegram", "Работай через приложение Telegram: открой его, найди чат, читай с экрана; ответы только через show_draft."),
        MentionCmd("ватсап", listOf("whatsapp", "wa", "вотсап"), "WhatsApp", "Работай через приложение WhatsApp: открой его, найди чат, читай с экрана; ответы только через show_draft."),
        MentionCmd("инстаграм", listOf("instagram", "inst", "ig"), "Instagram", "Работай через приложение Instagram: открой его, читай с экрана; ответы только через show_draft."),
        MentionCmd("документ", listOf("pdf", "файл", "doc"), "Документ", "Создай документ инструментами make_pdf, draw_image или annotate_document одним вызовом."),
        MentionCmd("компьютер", listOf("pc", "claude", "комп"), "Компьютер", "Передай задачу через ask_claude_code, описывая её обезличенно."),
    )

    private val token = Regex("(?<![\\p{L}\\p{N}_@.])@([\\p{L}\\p{N}_]{2,24})")

    private fun norm(s: String) = s.lowercase().replace('ё', 'е')

    /** Пользовательские команды хранятся строками «имя=инструкция». */
    fun parseCustom(raw: String): List<MentionCmd> = raw.lines().mapNotNull { l ->
        val i = l.indexOf('=')
        if (i <= 0) return@mapNotNull null
        val name = norm(l.substring(0, i).trim().removePrefix("@"))
        val dir = l.substring(i + 1).trim()
        if (!name.matches(Regex("[\\p{L}\\p{N}_]{2,24}")) || dir.isEmpty()) null else MentionCmd(name, emptyList(), name, dir.take(400), builtin = false)
    }.distinctBy { it.primary }.take(30)

    fun serializeCustom(list: List<MentionCmd>): String = list.joinToString("\n") { it.primary + "=" + it.directive.replace('\n', ' ') }

    fun all(customRaw: String): List<MentionCmd> = builtin + parseCustom(customRaw)

    /** Находит @команды, убирает их из текста и собирает указания. Неизвестные @слова остаются как есть. */
    fun expand(text: String, customRaw: String = ""): MentionResult {
        val cmds = all(customRaw)
        val used = LinkedHashMap<String, MentionCmd>()
        val cleaned = token.replace(text) { m ->
            val w = norm(m.groupValues[1])
            val c = cmds.firstOrNull { w == it.primary || w in it.aliases }
            if (c == null) m.value else { used.putIfAbsent(c.primary, c); "" }
        }.replace(Regex("[ \\t]{2,}"), " ").trim().trimStart(',', ':', ';').trim()
        val finalText = cleaned.ifEmpty { if (used.isNotEmpty()) "Что нового? Кратко." else text }
        return MentionResult(finalText, used.values.map { it.directive }, used.keys.toList())
    }

    /** Подсказки при наборе «@…»: команды, начинающиеся с введённого префикса. */
    fun suggestions(input: String, customRaw: String = ""): List<MentionCmd> {
        val m = Regex("(?:^|\\s)@([\\p{L}\\p{N}_]*)$").find(input) ?: return emptyList()
        val p = norm(m.groupValues[1])
        return all(customRaw).filter { p.isEmpty() || it.primary.startsWith(p) || it.aliases.any { a -> a.startsWith(p) } }.take(8)
    }

    /** Подставляет выбранную подсказку вместо набираемого «@пре». */
    fun complete(input: String, cmd: MentionCmd): String =
        Regex("@([\\p{L}\\p{N}_]*)$").replace(input) { "@" + cmd.primary + " " }
}
