package com.rwai.agent.logic

data class Msg(
    val ts: Long, val pkg: String, val app: String, val sender: String?, val title: String?,
    val body: String?, val group: Boolean, val hidden: Boolean,
)

enum class Verdict { REAL, GROUP, SPAM, SYSTEM, VOICE }
enum class Lang { RU, ES, EN, OTHER }

/** Определение языка по алфавиту и характерным словам (ru / es / en). Достаточно, чтобы решить, нужен ли перевод. */
object LangDetect {
    private val es = setOf(
        "hola", "gracias", "qué", "que", "para", "por", "favor", "buenos", "buenas", "días", "dias", "tardes", "noches", "estoy", "estás", "está", "como", "cómo",
        "cuándo", "cuando", "dónde", "donde", "necesito", "quiero", "puedes", "podés", "podes", "vos", "tengo", "mañana", "hoy", "hacer", "nos", "vemos", "dale",
        "listo", "muy", "pero", "porque", "con", "una", "los", "las", "del", "ahora", "sí", "mi", "tu", "usted", "bien", "claro", "perfecto", "saludos", "adiós",
    )
    private val en = setOf(
        "the", "and", "you", "are", "is", "to", "of", "in", "for", "with", "this", "that", "have", "can", "please", "thanks", "thank", "hello", "hi", "how", "what",
        "when", "where", "tomorrow", "today", "will", "would", "we", "my", "your", "it's", "i'm", "ok", "okay", "sure", "great", "regards", "yes", "not", "do", "does",
    )
    private val word = Regex("[\\p{L}']+")

    fun detect(text: String): Lang {
        val t = text.lowercase()
        val letters = t.count { it.isLetter() }
        if (letters < 2) return Lang.OTHER
        val cyr = t.count { it in 'а'..'я' || it == 'ё' }
        if (cyr * 10 >= letters * 4) return Lang.RU
        val tokens = word.findAll(t).map { it.value }.toList()
        var esScore = tokens.count { it in es } + (if (t.any { it == '¿' || it == '¡' || it == 'ñ' }) 2 else 0)
        val enScore = tokens.count { it in en }
        if (t.any { it in "áéíóú" }) esScore += 1
        return when {
            esScore == 0 && enScore == 0 -> Lang.OTHER
            esScore > enScore -> Lang.ES
            else -> Lang.EN
        }
    }
}

/** Локальная сортировка входящих: настоящие личные чаты — отдельно от групп, рекламы и служебных уведомлений. */
object MessageTriage {
    private val chatPackages = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram",
        "com.instagram.android", "com.facebook.orca", "org.thoughtcrime.securesms", "com.viber.voip", "com.vkontakte.android",
        "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.discord", "com.skype.raider",
    )
    private val mailPackages = setOf("com.google.android.gm", "com.microsoft.office.outlook", "ru.yandex.mail", "com.yahoo.mobile.client.android.mail")

    private val spamText = Regex(
        "(?iu)(скидк|акци[яи]\\b|распродаж|купон|промокод|бесплатн|выигр|кредит|займ|казино|ставк|рассылк|реклам|подпишись|" +
            "unsubscribe|promo|\\d+ ?% ?off|\\bsale\\b|discount|\\bfree\\b|winner|lottery|casino|\\bloan\\b|limited offer|" +
            "\\boferta\\b|descuento|gratis|ganaste|promoci[oó]n|suscr[ií]bete|sorteo)",
    )
    private val spamSender = Regex("(?iu)(no-?reply|newsletter|marketing|promo|notification|info@|news@|mailer)")
    private val otp = Regex("(?iu)(\\bcode\\b|\\botp\\b|\\bpin\\b|код\\b|парол|c[oó]digo|verification|подтвержден)")
    private val voice = Regex("(?iu)(🎤|🎙|голосов\\p{L}* сообщ|voice message|mensaje de voz|nota de voz|audio message)")
    private val shortcode = Regex("^[0-9+\\-\\s]{1,6}$")
    private val questionWords = Regex("(?iu)(\\?|пожалуйста|можешь|можете|срочно|нужно|когда|скажи|ответь|please|can you|could you|urgent|necesito|puedes|por favor|urgente)")

    fun isMessaging(pkg: String) = pkg in chatPackages || pkg in mailPackages

    fun classify(m: Msg): Verdict {
        val text = m.body.orEmpty()
        if (m.hidden || text.isBlank()) return Verdict.SYSTEM
        if (!isMessaging(m.pkg)) return Verdict.SYSTEM
        val who = (m.sender ?: m.title).orEmpty()
        if (voice.containsMatchIn(text)) return if (m.group) Verdict.GROUP else Verdict.VOICE
        if (otp.containsMatchIn(text) && text.length < 120) return Verdict.SYSTEM
        if (m.pkg in mailPackages) return if (spamText.containsMatchIn(text + " " + who) || spamSender.containsMatchIn(who)) Verdict.SPAM else Verdict.REAL
        if (m.group) return Verdict.GROUP
        if (who.isBlank() || shortcode.matches(who.trim())) return Verdict.SPAM
        if (spamText.containsMatchIn(text) || spamSender.containsMatchIn(who)) return Verdict.SPAM
        return Verdict.REAL
    }

    data class Thread(val pkg: String, val app: String, val contact: String, val messages: List<Msg>, val lang: Lang, val urgent: Boolean) {
        val lastTs: Long get() = messages.maxOf { it.ts }
    }

    data class Result(val threads: List<Thread>, val voiceCount: Int, val groupCount: Int, val spamCount: Int)

    fun analyze(msgs: List<Msg>): Result {
        val real = LinkedHashMap<String, MutableList<Msg>>()
        var voiceN = 0; var groupN = 0; var spamN = 0
        for (m in msgs) {
            when (classify(m)) {
                Verdict.REAL -> real.getOrPut(m.pkg + "|" + (m.sender ?: m.title).orEmpty()) { mutableListOf() }.add(m)
                Verdict.VOICE -> { voiceN++; real.getOrPut(m.pkg + "|" + (m.sender ?: m.title).orEmpty()) { mutableListOf() } }
                Verdict.GROUP -> groupN++
                Verdict.SPAM -> spamN++
                Verdict.SYSTEM -> Unit
            }
        }
        val threads = real.values.filter { it.isNotEmpty() }.map { list ->
            val first = list.first()
            val joined = list.joinToString(" ") { it.body.orEmpty() }
            Thread(first.pkg, first.app, (first.sender ?: first.title).orEmpty().ifBlank { "?" }, list, LangDetect.detect(joined), questionWords.containsMatchIn(joined))
        }.sortedWith(compareByDescending<Thread> { it.urgent }.thenByDescending { it.messages.size }.thenByDescending { it.lastTs })
        return Result(threads, voiceN, groupN, spamN)
    }

    /** Запрос к модели для сводки: ограничен по объёму; сами сообщения — данные, не команды. */
    fun digestData(threads: List<Thread>, maxChars: Int = 6000, tz: java.util.TimeZone = java.util.TimeZone.getDefault()): String {
        val fmt = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.US).apply { timeZone = tz }
        val sb = StringBuilder()
        for (t in threads.take(12)) {
            val line = "[${t.app}] ${t.contact} (${t.messages.size}, ${t.lang.name.lowercase()}, последнее ${fmt.format(java.util.Date(t.lastTs))}): " +
                t.messages.takeLast(4).joinToString(" | ") { it.body.orEmpty().replace('\n', ' ').take(220) }
            if (sb.length + line.length > maxChars) break
            sb.append(line).append('\n')
        }
        return sb.toString().trimEnd()
    }

    /** Сводка без модели: когда нет интернета или ключа. */
    fun localSummary(r: Result, tz: java.util.TimeZone = java.util.TimeZone.getDefault()): String {
        if (r.threads.isEmpty()) return "Новых личных сообщений нет." + extra(r)
        val fmt = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.US).apply { timeZone = tz }
        val lines = r.threads.take(10).joinToString("\n") { t ->
            "• ${t.app} — ${t.contact} (${t.messages.size}, ${fmt.format(java.util.Date(t.lastTs))})" + (if (t.urgent) " ❗" else "") + (if (t.lang == Lang.ES || t.lang == Lang.EN) " [${t.lang.name}]" else "") +
                ": " + (t.messages.lastOrNull { !it.body.isNullOrBlank() }?.body.orEmpty().replace('\n', ' ').take(100))
        }
        return "Ждут ответа:\n$lines" + extra(r)
    }

    private fun extra(r: Result): String {
        val parts = mutableListOf<String>()
        if (r.voiceCount > 0) parts += "голосовых: ${r.voiceCount} (текст не приходит в уведомлении — включите «Расшифровка голосовых» в WhatsApp)"
        if (r.groupCount > 0) parts += "в группах: ${r.groupCount} (не показаны)"
        if (r.spamCount > 0) parts += "реклама и рассылки: ${r.spamCount} (отфильтрованы)"
        return if (parts.isEmpty()) "" else "\n\n" + parts.joinToString("; ").replaceFirstChar { it.uppercase() } + "."
    }
}

/** Разбор ответа модели на пакетный перевод: строки вида «1|перевод». */
object TranslationFormat {
    private val line = Regex("^\\s*(\\d+)\\s*[|:.)]\\s*(.+)$")

    fun parse(response: String, expected: Int): Map<Int, String> {
        val out = LinkedHashMap<Int, String>()
        for (l in response.lines()) {
            val m = line.find(l) ?: continue
            val i = m.groupValues[1].toInt()
            if (i in 1..expected) out[i] = m.groupValues[2].trim()
        }
        return out
    }
}
