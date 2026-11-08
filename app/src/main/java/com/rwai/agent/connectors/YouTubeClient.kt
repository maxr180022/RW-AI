package com.rwai.agent.connectors

import com.rwai.agent.logic.Json
import com.rwai.agent.logic.asJsonList
import com.rwai.agent.logic.asJsonMap
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/** Поиск видео через YouTube Data API v3 (нужен бесплатный ключ). Ключ в запросах не логируется. */
object YouTubeClient {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    fun search(key: String, query: String): String {
        val url = "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=6&q=" +
            URLEncoder.encode(query, "UTF-8") + "&key=" + URLEncoder.encode(key, "UTF-8")
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val msg = (Json.parse(text).asJsonMap()?.get("error").asJsonMap()?.get("message") as? String).orEmpty().take(200)
                throw java.io.IOException("YouTube API: ${r.code} $msg")
            }
            val items = Json.parse(text).asJsonMap()?.get("items").asJsonList().orEmpty()
            if (items.isEmpty()) return "Видео не найдено."
            return items.mapNotNull { it.asJsonMap() }.mapIndexedNotNull { i, m ->
                val id = m["id"].asJsonMap()?.get("videoId") as? String ?: return@mapIndexedNotNull null
                val sn = m["snippet"].asJsonMap()
                "${i + 1}. ${(sn?.get("title") as? String).orEmpty()} — ${(sn?.get("channelTitle") as? String).orEmpty()}\n   https://www.youtube.com/watch?v=$id"
            }.joinToString("\n")
        }
    }
}
