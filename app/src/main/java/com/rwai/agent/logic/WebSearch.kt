package com.rwai.agent.logic

data class SearchHit(val title: String, val url: String, val snippet: String)

/** Разбор ответов поисковиков и веб-страниц. Чистая логика без Android. */
object WebText {
    private val tag = Regex("<[^>]+>")
    private val spaces = Regex("[ \\t\\u00A0]+")
    private val blankLines = Regex("\\n\\s*\\n+")
    private val dropBlocks = Regex("(?is)<(script|style|noscript|svg|head|template)[^>]*>.*?</\\1>")
    private val breaks = Regex("(?i)<\\s*(br|/p|/div|/li|/tr|/h[1-6]|/section|/article)[^>]*>")
    private val numeric = Regex("&#(x?[0-9a-fA-F]+);")

    fun decodeEntities(s: String): String {
        var t = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&laquo;", "«").replace("&raquo;", "»")
            .replace("&mdash;", "—").replace("&ndash;", "–").replace("&hellip;", "…")
        t = numeric.replace(t) { m ->
            val v = m.groupValues[1]
            val code = if (v.startsWith("x") || v.startsWith("X")) v.drop(1).toIntOrNull(16) else v.toIntOrNull()
            if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
        }
        return t
    }

    fun stripTags(s: String): String = decodeEntities(tag.replace(s, "")).replace(spaces, " ").trim()

    /** Видимый текст страницы, ограниченный [max] символами. */
    fun htmlToText(html: String, max: Int = 6000): String {
        var t = dropBlocks.replace(html, " ")
        t = breaks.replace(t, "\n")
        t = tag.replace(t, " ")
        t = decodeEntities(t).replace(spaces, " ")
        t = t.lines().joinToString("\n") { it.trim() }.replace(blankLines, "\n\n").trim()
        return if (t.length > max) t.take(max) + "…" else t
    }
}

object SearchParser {
    private val resultLink = Regex("(?is)<a[^>]*class=\"[^\"]*result__a[^\"]*\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>")
    private val resultSnippet = Regex("(?is)<(?:a|div|td)[^>]*class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</(?:a|div|td)>")
    private val liteLink = Regex("(?is)<a[^>]*class=['\"]result-link['\"][^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>")
    private val liteSnippet = Regex("(?is)<td[^>]*class=['\"]result-snippet['\"][^>]*>(.*?)</td>")

    /** Ссылки DuckDuckGo вида //duckduckgo.com/l/?uddg=<url-encoded>&... → реальный адрес. */
    fun decodeDdgUrl(href: String): String? {
        val h = WebText.decodeEntities(href)
        val real = if (h.contains("uddg=")) {
            val enc = h.substringAfter("uddg=").substringBefore('&')
            try { java.net.URLDecoder.decode(enc, "UTF-8") } catch (_: Exception) { return null }
        } else h
        val full = if (real.startsWith("//")) "https:$real" else real
        return if (full.startsWith("http://") || full.startsWith("https://")) full else null
    }

    fun parseDdg(html: String, limit: Int = 8): List<SearchHit> =
        collect(html, resultLink, resultSnippet, limit).ifEmpty { collect(html, liteLink, liteSnippet, limit) }

    private fun collect(html: String, link: Regex, snip: Regex, limit: Int): List<SearchHit> {
        val links = link.findAll(html).toList()
        val out = ArrayList<SearchHit>()
        for ((i, m) in links.withIndex()) {
            val url = decodeDdgUrl(m.groupValues[1]) ?: continue
            if (url.contains("duckduckgo.com/y.js")) continue // реклама
            val end = if (i + 1 < links.size) links[i + 1].range.first else html.length
            val block = html.substring(m.range.last, minOf(end, html.length).coerceAtLeast(m.range.last))
            val snippet = snip.find(block)?.groupValues?.get(1)?.let { WebText.stripTags(it) }.orEmpty()
            out += SearchHit(WebText.stripTags(m.groupValues[2]).take(200), url, snippet.take(400))
            if (out.size >= limit) break
        }
        return out
    }

    fun format(hits: List<SearchHit>): String =
        hits.mapIndexed { i, h -> "${i + 1}. ${h.title}\n   ${h.url}\n   ${h.snippet}" }.joinToString("\n")
}

/** Защита от обращений к локальной сети и банковским адресам при чтении страниц. */
object FetchGuard {
    fun isPrivateHostName(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".internal") || h.endsWith(".lan")) return true
        val parts = h.split('.')
        if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) {
            val a = parts[0].toInt(); val b = parts[1].toInt()
            return a == 10 || a == 127 || a == 0 || (a == 169 && b == 254) || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 100 && b in 64..127)
        }
        return h == "::1" || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80")
    }

    /** null — адрес допустим, иначе причина отказа. */
    fun check(url: String): String? {
        val u = try { java.net.URI(url.trim()) } catch (_: Exception) { return "Некорректная ссылка." }
        val scheme = u.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return "Разрешены только http/https-ссылки."
        val host = u.host ?: return "В ссылке нет адреса сайта."
        if (isPrivateHostName(host)) return "Адреса локальной сети и самого телефона недоступны."
        if (BankGuard.isBankingUrl(url)) return BankGuard.REFUSAL
        return null
    }
}
