package com.rwai.agent.logic

/** Готовит текст ответа к озвучиванию: без разметки и ссылок, ограниченной длины. */
object Speakable {
    private val url = Regex("https?://\\S+")
    private val markup = Regex("[*_`#>|~]+")
    private val bullet = Regex("(?m)^\\s*[-•]\\s+")
    private val spaces = Regex("\\s+")

    fun clean(text: String, maxChars: Int = 700): String {
        val t = text.replace(url, "ссылка").replace(bullet, "").replace(markup, "").replace(spaces, " ").trim()
        if (t.length <= maxChars) return t
        val cut = t.take(maxChars)
        val end = cut.lastIndexOfAny(charArrayOf('.', '!', '?'))
        return if (end > maxChars / 2) cut.substring(0, end + 1) else cut
    }
}
