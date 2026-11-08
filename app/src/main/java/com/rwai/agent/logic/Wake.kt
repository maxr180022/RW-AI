package com.rwai.agent.logic

/** Условия, при которых микрофон слушает «Окей, <имя>». Режимы: off, charging, charging_screen, always. */
object WakeGate {
    fun shouldListen(mode: String, charging: Boolean, screenOn: Boolean): Boolean = when (mode) {
        "always" -> true
        "charging" -> charging
        "charging_screen" -> charging || screenOn
        else -> false
    }

    fun pauseReason(mode: String): String = when (mode) {
        "charging" -> "жду зарядку"
        "charging_screen" -> "жду зарядку или включённый экран"
        else -> "выключено"
    }
}

/**
 * Разбор распознанной фразы. Просыпаемся на «Руся», «Окей Руся», «Привет Руся» (с допуском на одну ошибку
 * распознавания, если перед именем есть «окей/привет»). Одиночное имя принимается только точно — меньше случайных срабатываний.
 */
object WakePhrase {
    private val prefixes = setOf("окей", "океи", "окэй", "оке", "ок", "okay", "ok", "okey", "привет", "приветик", "здравствуй", "хей", "эй", "hey", "hi")
    private val punctuation = Regex("[^\\p{L}\\p{N}\\s]")
    private val spaces = Regex("\\s+")

    fun normalize(text: String): String =
        text.lowercase().replace('ё', 'е').replace(punctuation, " ").replace(spaces, " ").trim()

    fun matches(text: String, name: String = "руся"): Boolean {
        val target = normalize(name).replace(" ", "")
        if (target.isEmpty()) return false
        val tokens = normalize(text).split(" ").filter { it.isNotEmpty() }
        // Просто имя: точное слово.
        if (tokens.any { it == target }) return true
        for (i in tokens.indices) {
            if (tokens[i] !in prefixes) continue
            val a = tokens.getOrNull(i + 1) ?: continue
            if (close(a, target)) return true
            val b = tokens.getOrNull(i + 2)
            if (b != null && close(a + b, target)) return true
        }
        return false
    }

    private fun close(a: String, b: String): Boolean = levenshtein(a, b) <= 1

    internal fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }
}

data class PhoneSnapshot(
    val batteryPct: Int,
    val tempC: Float,
    val charging: Boolean,
    /** PowerManager.THERMAL_STATUS_*: 0 — норма … 6 — выключение. */
    val thermalStatus: Int,
    val wakeEnabled: Boolean,
    val wakeMode: String,
    val listenMinutesToday: Int,
    /** % заряда в час при выключенном экране за время прослушивания; null — мало данных. */
    val screenOffDrainPerHour: Float?,
)

data class Tip(val warning: Boolean, val text: String)

/** Локальные советы по использованию телефона: считаются на телефоне, без отправки данных. */
object PhoneAdvisor {
    fun tips(s: PhoneSnapshot): List<Tip> {
        val out = mutableListOf<Tip>()
        if (s.tempC >= 42f || s.thermalStatus >= 3) {
            out += Tip(true, "Телефон сильно нагрет (${"%.1f".format(s.tempC)} °C). Закройте тяжёлые приложения и игры, снимите чехол, не заряжайте на солнце и под подушкой, дайте ему остыть.")
        } else if (s.tempC >= 38f || s.thermalStatus == 2) {
            out += Tip(true, "Телефон тёплый (${"%.1f".format(s.tempC)} °C)." + if (s.charging) " Во время зарядки лучше не нагружать его играми и видео." else " Проверьте, какие приложения работают в фоне.")
        }
        if (s.wakeEnabled && s.wakeMode == "always" && s.batteryPct <= 20 && !s.charging) {
            out += Tip(true, "Заряд ${s.batteryPct}%, а «Окей, Руся» слушает всегда. Переключите на «Только на зарядке», чтобы сэкономить батарею.")
        }
        val drain = s.screenOffDrainPerHour
        if (s.wakeEnabled && drain != null && s.wakeMode != "charging") {
            if (drain >= 4f) out += Tip(true, "При выключенном экране уходит ${"%.1f".format(drain)}% в час — это много. Попробуйте режим «Зарядка или экран включён», а на ночь отключайте «Окей, Руся».")
            else if (drain >= 2f) out += Tip(false, "При выключенном экране уходит ${"%.1f".format(drain)}% в час. Для сравнения выключите «Окей, Руся» на одну ночь и посмотрите разницу.")
        }
        if (s.wakeEnabled && s.listenMinutesToday > 0) {
            out += Tip(false, "Сегодня микрофон слушал ${s.listenMinutesToday} мин.")
        }
        if (s.charging && s.batteryPct >= 90) out += Tip(false, "Батарее полезнее держать заряд между 20% и 80%: по возможности отключайте зарядку раньше.")
        if (!s.charging && s.batteryPct in 1..15) out += Tip(true, "Заряд ${s.batteryPct}% — включите режим энергосбережения или поставьте на зарядку.")
        if (out.isEmpty()) out += Tip(false, "Всё в порядке: телефон не перегрет, заряд в норме.")
        return out
    }
}
