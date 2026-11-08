package com.rwai.agent.logic

/** Платформо-независимое описание элемента экрана (заполняется из AccessibilityNodeInfo). */
data class UiNodeInfo(
    val index: Int,
    val depth: Int,
    val className: String,
    val text: String?,
    val description: String?,
    val viewId: String?,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val password: Boolean,
    val focused: Boolean = false,
) {
    /** Подпись для поиска и показа: текст, иначе content description. Пароли никогда не раскрываются. */
    val label: String
        get() = if (password) "" else (text?.takeIf { it.isNotBlank() } ?: description?.takeIf { it.isNotBlank() } ?: "")
}

object UiMatcher {
    fun normalize(s: String): String = s.lowercase().replace(Regex("\\s+"), " ").trim()

    fun score(node: UiNodeInfo, query: String): Int {
        val q = normalize(query)
        if (q.isEmpty() || node.password) return 0
        val candidates = listOfNotNull(node.text, node.description).map { normalize(it) }.filter { it.isNotEmpty() }
        var best = 0
        for (c in candidates) {
            val s = when {
                c == q -> 100
                c.startsWith(q) -> 80
                Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(q) + "(?![\\p{L}\\p{N}])").containsMatchIn(c) -> 60
                c.contains(q) -> 40
                else -> 0
            }
            if (s > best) best = s
        }
        if (best == 0) return 0
        return best + (if (node.clickable) 10 else 0)
    }

    /** Лучшие совпадения по убыванию оценки; при равенстве — в порядке обхода дерева. */
    fun find(nodes: List<UiNodeInfo>, query: String, limit: Int = 10): List<UiNodeInfo> =
        nodes.map { it to score(it, query) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<UiNodeInfo, Int>> { it.second }.thenBy { it.first.index })
            .take(limit)
            .map { it.first }
}

object UiFormatter {
    fun format(packageName: String?, nodes: List<UiNodeInfo>, maxChars: Int = 6000): String {
        val sb = StringBuilder()
        sb.append("Приложение: ").append(packageName ?: "неизвестно").append('\n')
        var shown = 0
        for (n in nodes) {
            val interesting = n.label.isNotEmpty() || n.clickable || n.editable || n.password
            if (!interesting) continue
            val flags = buildList {
                if (n.clickable) add("click")
                if (n.editable) add("input")
                if (n.scrollable) add("scroll")
                if (n.focused) add("focus")
            }.joinToString(",")
            val text = when {
                n.password -> "[поле пароля скрыто]"
                n.label.isNotEmpty() -> "\"" + n.label.replace("\n", " ").take(200) + "\""
                else -> "(без текста)"
            }
            val line = "[" + n.index + "] " + n.className.substringAfterLast('.') + " " + text + (if (flags.isNotEmpty()) " <$flags>" else "")
            if (sb.length + line.length + 1 > maxChars) {
                sb.append("…(список обрезан, показано ").append(shown).append(" из ").append(nodes.size).append(" элементов)\n")
                break
            }
            sb.append(line).append('\n')
            shown++
        }
        if (shown == 0) sb.append("(видимых элементов нет: экран может быть защищён, пуст или приложение не отдаёт интерфейс)\n")
        return sb.toString().trimEnd()
    }
}

/** Названия приложений → кандидаты имён пакетов (первый установленный будет запущен). */
object AppAliases {
    private val aliases: Map<String, List<String>> = mapOf(
        "gmail" to listOf("com.google.android.gm"),
        "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
        "telegram" to listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram"),
        "instagram" to listOf("com.instagram.android"),
        "chrome" to listOf("com.android.chrome"),
        "браузер" to listOf("com.android.chrome", "com.sec.android.app.sbrowser"),
        "samsung internet" to listOf("com.sec.android.app.sbrowser"),
        "календарь" to listOf("com.google.android.calendar", "com.samsung.android.calendar"),
        "calendar" to listOf("com.google.android.calendar", "com.samsung.android.calendar"),
        "контакты" to listOf("com.google.android.contacts", "com.samsung.android.app.contacts"),
        "contacts" to listOf("com.google.android.contacts", "com.samsung.android.app.contacts"),
        "настройки" to listOf("com.android.settings"),
        "settings" to listOf("com.android.settings"),
        "файлы" to listOf("com.sec.android.app.myfiles", "com.google.android.apps.nbu.files"),
        "files" to listOf("com.sec.android.app.myfiles", "com.google.android.apps.nbu.files"),
        "сообщения" to listOf("com.samsung.android.messaging", "com.google.android.apps.messaging"),
        "messages" to listOf("com.samsung.android.messaging", "com.google.android.apps.messaging"),
        "карты" to listOf("com.google.android.apps.maps"),
        "maps" to listOf("com.google.android.apps.maps"),
        "youtube" to listOf("com.google.android.youtube"),
        "почта" to listOf("com.google.android.gm", "com.samsung.android.email.provider"),
    )

    fun candidates(name: String): List<String> {
        val key = UiMatcher.normalize(name)
        aliases[key]?.let { return it }
        // Имя пакета передано напрямую
        if (Regex("^[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+$").matches(key)) return listOf(key)
        return emptyList()
    }
}
