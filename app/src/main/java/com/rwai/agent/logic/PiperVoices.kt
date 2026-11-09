package com.rwai.agent.logic

/**
 * Голоса Piper для озвучки без интернета. В каталог попали только голоса, чьи наборы данных разрешают коммерческое использование
 * (CC0, общественное достояние, Unlicense): приложение планируется продавать. Голоса с лицензией «только некоммерческое»
 * (Ruslan, Ryan, HiFi-Captain) и с неизвестной лицензией (Irina) в каталог не вошли намеренно.
 * Пакеты голосов лежат в релизе tts-models проекта sherpa-onnx, контрольные суммы взяты из самого релиза.
 */
data class PiperVoice(
    val id: String,
    val lang: String,
    val title: String,
    val archive: String,
    val size: Long,
    val sha256: String,
    val license: String,
)

object PiperCatalog {
    private const val BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"

    val ALL = listOf(
        PiperVoice("ru_dmitri", "ru", "Дмитрий (мужской)", "vits-piper-ru_RU-dmitri-medium.tar.bz2", 67_188_551L, "c86d0803737de13d441923ff3b3f309482fab8d7af3ec85949942809eb9a3660", "CC0"),
        PiperVoice("en_joe", "en", "Joe (мужской)", "vits-piper-en_US-joe-medium.tar.bz2", 67_169_394L, "261b903a1fb2ce621e2b5b14233cd41223c3b9f9590f896c11daee4df22d9bd3", "CC0"),
        PiperVoice("en_john", "en", "John (мужской)", "vits-piper-en_US-john-medium.tar.bz2", 67_249_181L, "d90362cfa48e429d3096f644785184446145b6e38216f2eaf4c0601b8aad5a36", "общественное достояние"),
        PiperVoice("en_norman", "en", "Norman (мужской)", "vits-piper-en_US-norman-medium.tar.bz2", 67_203_672L, "1f32065d480abe9abc7c7f91442125d0b34c1cc065d1e600466cac408eabf3b8", "общественное достояние"),
        PiperVoice("en_ljspeech", "en", "LJ Speech (женский)", "vits-piper-en_US-ljspeech-medium.tar.bz2", 67_169_893L, "3dfb4b759d8be032a4903a9538d128b0fda2a06ab1de6cbc2d93a97e2dd83dba", "общественное достояние"),
        PiperVoice("es_ald", "es", "Ald, Мексика", "vits-piper-es_MX-ald-medium.tar.bz2", 67_196_497L, "c60cf7bc853eb01a875fba0756523fecf9d9f46c763b19785d223fc981fb4fb6", "Unlicense"),
        PiperVoice("es_davefx", "es", "DaveFX, Испания", "vits-piper-es_ES-davefx-medium.tar.bz2", 67_184_952L, "a3f6beb54a9cb893279f72978a22f807a4d9fc9c7848157b524d5cc7b7f58b22", "CC0"),
        PiperVoice("de_thorsten", "de", "Thorsten", "vits-piper-de_DE-thorsten-medium.tar.bz2", 67_214_254L, "50487d9c95fdf2191f31d2588569381063ba1591dcd4c7d4bdd30f12b2191714", "CC0"),
    )

    /** Постоянный голос Руси: русский, мужской. Скачивается сам по Wi-Fi и не удаляется кнопкой. */
    const val PERMANENT_ID = "ru_dmitri"

    val LANGUAGES = listOf("ru" to "Русский", "en" to "English", "es" to "Español", "de" to "Deutsch")

    /** «ru-RU» → «ru», «es-UY» → «es». */
    fun langOf(tag: String): String = tag.substringBefore('-').substringBefore('_').lowercase()

    fun forLanguage(lang: String): List<PiperVoice> = ALL.filter { it.lang == langOf(lang) }

    fun byId(id: String): PiperVoice? = ALL.firstOrNull { it.id == id }

    fun defaultFor(lang: String): PiperVoice? = forLanguage(lang).firstOrNull()

    fun url(v: PiperVoice): String = BASE + v.archive

    /** Лицензии, которые разрешают коммерческое использование. Остальное в каталог попасть не должно (проверяется тестом). */
    val COMMERCIAL_OK = setOf("CC0", "общественное достояние", "Unlicense")
}

/** Нарезка текста на предложения для потокового озвучивания: первая фраза звучит сразу, не дожидаясь всего текста. */
object SpeechChunks {
    fun split(text: String, maxLen: Int = 220): List<String> {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return emptyList()
        val sentences = Regex("(?<=[.!?…])\\s+").split(clean)
        val out = ArrayList<String>()
        for (s in sentences) {
            var rest = s.trim()
            while (rest.length > maxLen) {
                val cut = rest.lastIndexOfAny(charArrayOf(',', ';', ':', ' '), maxLen)
                val at = if (cut > maxLen / 2) cut + 1 else maxLen
                out += rest.substring(0, at).trim()
                rest = rest.substring(at).trim()
            }
            if (rest.isNotEmpty()) out += rest
        }
        return out
    }
}
