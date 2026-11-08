package com.rwai.agent.logic

/** Готовит текст ответа к озвучиванию: без разметки и ссылок, ограниченной длины. */
object Speakable {
    private val url = Regex("https?://\\S+")
    private val markup = Regex("[*_`#>|~]+")
    private val bullet = Regex("(?m)^\\s*[-•]\\s+")
    private val spaces = Regex("\\s+")

    private val brand = Regex("(?<![\\p{L}\\p{N}])RW[\\s-]?(AI|Agent|Агент)(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    private val ai = Regex("(?<![\\p{L}\\p{N}])AI(?![\\p{L}\\p{N}])")
    private val rw = Regex("(?<![\\p{L}\\p{N}])RW(?![\\p{L}\\p{N}])")

    /** [russian]: русскому синтезатору имя читаем по буквам — «Эр Вэ Агент». */
    fun clean(text: String, maxChars: Int = 700, russian: Boolean = true): String {
        val named = if (russian) text.replace(brand, "Эр Вэ Агент").replace(rw, "Эр Вэ").replace(ai, "Эй Ай") else text
        val t = named.replace(url, "ссылка").replace(bullet, "").replace(markup, "").replace(spaces, " ").trim()
        if (t.length <= maxChars) return t
        val cut = t.take(maxChars)
        val end = cut.lastIndexOfAny(charArrayOf('.', '!', '?'))
        return if (end > maxChars / 2) cut.substring(0, end + 1) else cut
    }
}
