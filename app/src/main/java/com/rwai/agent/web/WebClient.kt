package com.rwai.agent.web

import com.rwai.agent.logic.FetchGuard
import com.rwai.agent.logic.SearchHit
import com.rwai.agent.logic.SearchParser
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder

class WebException(message: String) : Exception(message)

/** Прямые запросы с телефона к поисковикам и сайтам (минуя DeepSeek). Только http/https, без локальной сети и банков. */
object WebClient {
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    private fun checkHost(url: String) {
        FetchGuard.check(url)?.let { throw WebException(it) }
        val host = URL(url).host
        val bad = try {
            InetAddress.getAllByName(host).any { it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress || it.isAnyLocalAddress }
        } catch (_: Exception) { false }
        if (bad) throw WebException("Адреса локальной сети и самого телефона недоступны.")
    }

    /** Возвращает (текст, итоговый адрес). Перенаправления проверяются на каждом шаге. */
    fun request(url: String, post: String? = null, headers: Map<String, String> = emptyMap(), maxBytes: Int = 600_000): Pair<String, String> {
        var current = url
        for (hop in 0..4) {
            checkHost(current)
            val c = URL(current).openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 12_000
                c.readTimeout = 15_000
                c.setRequestProperty("User-Agent", UA)
                c.setRequestProperty("Accept-Language", "ru,en;q=0.8,es;q=0.6")
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                if (post != null && hop == 0) {
                    c.requestMethod = "POST"
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    c.outputStream.use { it.write(post.toByteArray(Charsets.UTF_8)) }
                }
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: throw WebException("Перенаправление без адреса")
                    current = URL(URL(current), loc).toString()
                    continue
                }
                if (code !in 200..299) throw WebException("Сайт ответил кодом $code")
                val type = c.contentType.orEmpty().lowercase()
                if (type.isNotEmpty() && !(type.contains("text") || type.contains("json") || type.contains("xml"))) {
                    throw WebException("Это не текстовая страница ($type)")
                }
                val charset = Regex("charset=([\\w-]+)").find(type)?.groupValues?.get(1)?.let { try { charset(it) } catch (_: Exception) { null } } ?: Charsets.UTF_8
                val buf = ByteArrayOutputStream()
                c.inputStream.use { input ->
                    val chunk = ByteArray(16 * 1024)
                    while (buf.size() < maxBytes) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        buf.write(chunk, 0, n)
                    }
                }
                return buf.toString(charset.name()) to current
            } finally {
                c.disconnect()
            }
        }
        throw WebException("Слишком много перенаправлений")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun search(query: String, braveKey: String?, lang: String): List<SearchHit> {
        if (!braveKey.isNullOrBlank()) {
            try {
                val (body, _) = request(
                    "https://api.search.brave.com/res/v1/web/search?count=8&q=" + enc(query),
                    headers = mapOf("X-Subscription-Token" to braveKey, "Accept" to "application/json"),
                )
                val arr = JSONObject(body).optJSONObject("web")?.optJSONArray("results")
                val hits = ArrayList<SearchHit>()
                if (arr != null) for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    hits += SearchHit(o.optString("title"), o.optString("url"), com.rwai.agent.logic.WebText.stripTags(o.optString("description")).take(400))
                }
                if (hits.isNotEmpty()) return hits
            } catch (_: Exception) { /* пробуем бесплатные источники */ }
        }
        val region = if (lang.startsWith("ru")) "ru-ru" else if (lang.startsWith("es")) "es-es" else "us-en"
        try {
            val (html, _) = request("https://html.duckduckgo.com/html/", post = "q=" + enc(query) + "&kl=" + region)
            val hits = SearchParser.parseDdg(html)
            if (hits.isNotEmpty()) return hits
        } catch (_: Exception) { }
        try {
            val (html, _) = request("https://lite.duckduckgo.com/lite/", post = "q=" + enc(query) + "&kl=" + region)
            val hits = SearchParser.parseDdg(html)
            if (hits.isNotEmpty()) return hits
        } catch (_: Exception) { }
        // Запасной вариант — Википедия (подходит для справочных вопросов)
        val wl = lang.substringBefore('-').ifEmpty { "ru" }
        val (json, _) = request("https://$wl.wikipedia.org/w/api.php?action=query&list=search&utf8=1&format=json&srlimit=6&srsearch=" + enc(query))
        val arr = JSONObject(json).optJSONObject("query")?.optJSONArray("search")
        val hits = ArrayList<SearchHit>()
        if (arr != null) for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val title = o.optString("title")
            hits += SearchHit(title, "https://$wl.wikipedia.org/wiki/" + title.replace(' ', '_'), com.rwai.agent.logic.WebText.stripTags(o.optString("snippet")))
        }
        return hits
    }
}
