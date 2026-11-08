package com.rwai.agent.logic

import kotlin.math.roundToInt

data class GeoPlace(val name: String, val lat: Double, val lon: Double)

/** Разбор ответов Open-Meteo и компактный русский текст прогноза (минимум токенов для модели). */
object WeatherFormat {
    fun describe(code: Int): String = when (code) {
        0 -> "ясно"
        1 -> "в основном ясно"
        2 -> "переменная облачность"
        3 -> "пасмурно"
        45, 48 -> "туман"
        51, 53, 55 -> "морось"
        56, 57 -> "ледяная морось"
        61 -> "небольшой дождь"
        63 -> "дождь"
        65 -> "сильный дождь"
        66, 67 -> "ледяной дождь"
        71 -> "небольшой снег"
        73 -> "снег"
        75 -> "сильный снегопад"
        77 -> "снежная крупа"
        80 -> "небольшой ливень"
        81 -> "ливень"
        82 -> "сильный ливень"
        85, 86 -> "снегопад"
        95 -> "гроза"
        96, 99 -> "гроза с градом"
        else -> "погода неизвестна"
    }

    fun windDir(deg: Double): String {
        val names = listOf("северный", "северо-восточный", "восточный", "юго-восточный", "южный", "юго-западный", "западный", "северо-западный")
        return names[(((deg % 360 + 360) % 360) / 45.0).roundToInt() % 8]
    }

    private fun temp(v: Double?): String = if (v == null) "?" else (v.roundToInt().let { if (it > 0) "+$it" else "$it" } + "°")
    private fun parse(t: String): Any? = try { Json.parse(t) } catch (_: Exception) { null }
    private fun num(v: Any?): Double? = (v as? Number)?.toDouble()?.takeIf { it.isFinite() }

    fun parseGeocode(json: String): GeoPlace? {
        val r = parse(json).asJsonMap()?.get("results").asJsonList()?.firstOrNull().asJsonMap() ?: return null
        val lat = num(r["latitude"]) ?: return null
        val lon = num(r["longitude"]) ?: return null
        val name = (r["name"] as? String)?.take(60) ?: return null
        val country = (r["country"] as? String)?.take(40)
        return GeoPlace(if (country.isNullOrBlank() || country == name) name else "$name, $country", lat, lon)
    }

    /** Формат: текущая погода + ближайшие дни (до 3). Пустая строка — ответ неразборчив. */
    fun format(place: String, json: String): String {
        val root = parse(json).asJsonMap() ?: return ""
        val cur = root["current"].asJsonMap() ?: return ""
        val sb = StringBuilder()
        val code = (num(cur["weather_code"]) ?: -1.0).toInt()
        sb.append(place).append(": сейчас ").append(temp(num(cur["temperature_2m"])))
        num(cur["apparent_temperature"])?.let { sb.append(" (ощущается ").append(temp(it)).append(')') }
        sb.append(", ").append(describe(code))
        num(cur["wind_speed_10m"])?.let { w ->
            sb.append(", ветер ").append(w.roundToInt()).append(" м/с")
            num(cur["wind_direction_10m"])?.let { sb.append(' ').append(windDir(it)) }
        }
        num(cur["relative_humidity_2m"])?.let { sb.append(", влажность ").append(it.roundToInt()).append('%') }
        val d = root["daily"].asJsonMap()
        val days = d?.get("time").asJsonList().orEmpty()
        val labels = listOf("Сегодня", "Завтра", "Послезавтра")
        for (i in 0 until minOf(3, days.size)) {
            val hi = num(d?.get("temperature_2m_max").asJsonList()?.getOrNull(i))
            val lo = num(d?.get("temperature_2m_min").asJsonList()?.getOrNull(i))
            val c = (num(d?.get("weather_code").asJsonList()?.getOrNull(i)) ?: -1.0).toInt()
            val p = num(d?.get("precipitation_probability_max").asJsonList()?.getOrNull(i))
            sb.append('\n').append(labels[i]).append(" (").append(days[i].toString().takeLast(5)).append("): ")
                .append(temp(lo)).append("…").append(temp(hi)).append(", ").append(describe(c))
            if (p != null && p >= 20) sb.append(", осадки ").append(p.roundToInt()).append('%')
        }
        return sb.toString()
    }
}
