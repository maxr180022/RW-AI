package com.rwai.agent.logic

/**
 * Локальная модель на телефоне: что скачиваем и как считаем загрузку. Чистая логика без Android, чтобы её можно было проверить тестами.
 * Модель — Gemma 4 E4B от Google в формате LiteRT-LM (лицензия Apache-2.0), это самая большая версия, которая помещается в телефон.
 */
object LocalModelSpec {
    const val TITLE = "Gemma 4 E4B (Google, Apache-2.0)"
    const val FILE_NAME = "gemma-4-E4B-it.litertlm"
    const val URL = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm"
    const val SIZE_BYTES = 3_659_530_240L
    const val SHA256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0"

    /** Запас места сверх размера модели: распаковка кэша движка и ход загрузки. */
    const val SPACE_MARGIN_BYTES = 400L * 1024 * 1024
}

enum class StartDecision { OK, NEED_WIFI, NO_SPACE }

object DownloadMath {
    /** С какого байта продолжать: частичный файл больше ожидаемого или неполный — начинаем заново. */
    fun resumeFrom(partialBytes: Long, total: Long): Long = if (partialBytes in 1 until total) partialBytes else 0L

    /** Заголовок Range для докачки; null — качаем с нуля. */
    fun rangeHeader(resumeFrom: Long): String? = if (resumeFrom > 0) "bytes=$resumeFrom-" else null

    fun percent(done: Long, total: Long): Int = if (total <= 0) 0 else (done.coerceIn(0, total) * 100 / total).toInt()

    /** Размер для людей: «3,4 ГБ», «850 МБ». */
    fun human(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024 * 1024)
        if (gb >= 1.0) return String.format(java.util.Locale.forLanguageTag("ru"), "%.1f ГБ", gb)
        return (bytes / (1024 * 1024)).toString() + " МБ"
    }

    /** Можно ли начинать: только по безлимитной сети (Wi-Fi), если так выбрано, и только если хватает места. */
    fun canStart(wifiOnly: Boolean, unmeteredNetwork: Boolean, freeBytes: Long, alreadyHave: Long, total: Long): StartDecision {
        if (wifiOnly && !unmeteredNetwork) return StartDecision.NEED_WIFI
        val need = (total - alreadyHave).coerceAtLeast(0) + LocalModelSpec.SPACE_MARGIN_BYTES
        return if (freeBytes < need) StartDecision.NO_SPACE else StartDecision.OK
    }

    /** Ответ сервера на запрос с Range: 206 — продолжение принято, 200 — сервер отдаёт файл целиком (начинаем с нуля). */
    fun serverAcceptedResume(httpCode: Int): Boolean = httpCode == 206
}

/** Подпись состояния загрузки для экрана. */
object LocalModelText {
    fun hint(state: String, done: Long, total: Long): String = when (state) {
        "NONE" -> "Не скачана. Скачивается один раз (${DownloadMath.human(total)}), дальше работает без интернета и без токенов DeepSeek. Лежит в закрытой папке приложения: случайно не удалится, обновления приложения её не трогают."
        "PARTIAL" -> "Скачано ${DownloadMath.human(done)} из ${DownloadMath.human(total)}. Можно докачать."
        "DOWNLOADING" -> "Скачано ${DownloadMath.human(done)} из ${DownloadMath.human(total)} (${DownloadMath.percent(done, total)} %). Держите приложение открытым и Wi-Fi включённым."
        "VERIFYING" -> "Проверяю целостность файла…"
        "READY" -> "Готова к работе (${DownloadMath.human(total)})."
        else -> ""
    }
}
