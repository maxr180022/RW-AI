package com.rwai.agent.logic

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Один замер: температура батареи в момент [t] (мс). Пишется в фоне при каждом заметном изменении. */
data class HeatSample(
    val t: Long, val tempC: Float, val pct: Int, val charging: Boolean, val screenOn: Boolean, val ma: Int = -1, val mv: Int = -1,
    /** Тепловой статус системы Android: 0 нет ограничений, 1 лёгкое, 2 умеренное, 3 сильное и выше; -1 нет данных. */
    val thermal: Int = -1,
    /** Прогноз системы: насколько близко к порогу сильного ограничения мощности, % (100 = порог); -1 нет данных. */
    val headroom: Int = -1,
    /** Яркость экрана 0–255; -1 нет данных. */
    val brightness: Int = -1,
    /** Сеть: 0 нет, 1 Wi-Fi, 2 мобильная, 3 прочее; -1 нет данных. */
    val net: Int = -1,
) {
    /** Мощность по датчику батареи (ток × напряжение), Вт; -1 — нет данных. Это сторона батареи, не розетки. */
    val watts: Float get() = if (ma > 0 && mv > 0) ma / 1000f * (mv / 1000f) else -1f
}

/** Скорость зарядки: медленная до 10 Вт, средняя 10–20 Вт, быстрая от 20 Вт (мощность по датчику батареи). */
enum class ChargeClass { UNKNOWN, SLOW, MEDIUM, FAST }

/** Класс зарядки по мощности (Вт, -1 нет данных); если мощности нет, по току батареи (мА). */
fun chargeClassOf(watts: Float, ma: Int): ChargeClass = when {
    watts > 0f -> if (watts < 10f) ChargeClass.SLOW else if (watts < 20f) ChargeClass.MEDIUM else ChargeClass.FAST
    ma > 0 -> if (ma < 2000) ChargeClass.SLOW else if (ma < 3500) ChargeClass.MEDIUM else ChargeClass.FAST
    else -> ChargeClass.UNKNOWN
}

/** Период активности: приложение на экране, служба переднего плана или работа локальной модели Руси. */
data class ActivityInterval(val label: String, val type: String, val start: Long, val end: Long, val kind: String? = null)

enum class HeatVerdict { LIKELY, POSSIBLE, UNLIKELY }

/** Подозреваемый в одном нагреве: что работало, сколько в нагреве и сколько без нагрева. */
data class EpisodeSuspect(
    val label: String,
    val type: String,
    val kind: String?,
    val minInEpisode: Int,
    /** Сколько минут то же самое работало вне нагревов за весь записанный период. */
    val minCool: Int,
    val firstStart: Long,
    val lastEnd: Long,
    val verdict: HeatVerdict,
)

/** Один нагрев: [riseStart] — когда температура пошла вверх, [runStart]…[runEnd] — когда была выше порога. */
data class HeatEpisode(
    val riseStart: Long,
    val runStart: Long,
    val runEnd: Long,
    val peakT: Long,
    val peakTemp: Float,
    val baseTemp: Float,
    val chargingAtRise: Boolean,
    val screenOnAtRise: Boolean,
    val suspects: List<EpisodeSuspect>,
    /** Нагрев отмечен вручную кнопкой «Нагрелся сейчас». */
    val marked: Boolean = false,
    /** Во время нагрева шла быстрая зарядка (от 20 Вт): она и есть главная причина. */
    val fastCharge: Boolean = false,
    /** Наибольший тепловой статус Android за нагрев (-1 нет данных) и наибольшая близость к порогу, %. */
    val maxThermal: Int = -1,
    val peakHeadroom: Int = -1,
)

/**
 * История нагрева: по записанным температурам находит нагревы (выше [hot] °C), определяет, когда начался рост, и сопоставляет
 * его с тем, что тогда работало. Главное отличие от «что греет сейчас»: для каждого подозреваемого считается, работал ли он
 * столько же без нагрева (например, музыка в Android Auto весь вечер при нормальной температуре, значит, вряд ли она).
 */
object HeatTimeline {
    private const val MIN = 60_000L

    fun episodes(
        samplesIn: List<HeatSample>, intervalsIn: List<ActivityInterval>,
        hot: Float = 37.5f, lookBackMin: Int = 90, preMin: Int = 10, marks: List<Long> = emptyList(),
    ): List<HeatEpisode> {
        val samples = samplesIn.sortedBy { it.t }
        if (samples.size < 2) return emptyList()

        // 1. Участки выше порога (с гистерезисом в 1 °C, чтобы дрожание около порога не рвало один нагрев на части).
        val runs = ArrayList<Pair<Int, Int>>()
        var start = -1
        for (idx in samples.indices) {
            val s = samples[idx]
            if (start < 0 && s.tempC >= hot) start = idx
            else if (start >= 0 && s.tempC < hot - 1f) { runs += start to (idx - 1); start = -1 }
        }
        if (start >= 0) runs += start to samples.lastIndex
        if (runs.isEmpty() && marks.isEmpty()) return emptyList()

        // 2. Начало роста: самый холодный замер за [lookBackMin] до того, как температура перешла порог.
        data class Draft(val riseIdx: Int, val a: Int, val b: Int, val peakIdx: Int)
        val drafts = runs.map { (a, b) ->
            val from = samples[a].t - lookBackMin * MIN
            var rise = a
            for (k in a downTo 0) {
                if (samples[k].t < from) break
                if (samples[k].tempC < samples[rise].tempC) rise = k
            }
            val peak = (a..b).maxByOrNull { samples[it].tempC }!!
            Draft(rise, a, b, peak)
        }
        val hotWindows = drafts.map { samples[it.riseIdx].t to samples[it.b].t } + marks.map { (it - 30 * MIN) to it }
        val coverage = samples.first().t to samples.last().t

        // 3. Периоды активности: склеить пересекающиеся у одного приложения и обрезать по времени записи температур.
        val merged = mergeIntervals(intervalsIn, coverage)
        val byKey = merged.groupBy { it.label to it.type }
        val coolMin = HashMap<Pair<String, String>, Int>()
        for ((key, list) in byKey) {
            var cool = 0L
            for (iv in list) cool += (iv.end - iv.start) - hotWindows.sumOf { overlap(iv.start, iv.end, it.first, it.second) }
            coolMin[key] = (cool / MIN).toInt().coerceAtLeast(0)
        }

        // 4. Подозреваемые для каждого нагрева. Зарядка оценивается по мощности: быстрая (от 20 Вт) греет сама, медленная не причина.
        fun chargeSuspect(winStart: Long, winEnd: Long): EpisodeSuspect? {
            val run = samples.filter { it.charging && it.t in winStart..winEnd }
            if (run.isEmpty()) return null
            val first = run.first()
            val last = run.last()
            val maxMa = run.maxOf { it.ma }
            val watts = run.map { it.watts }.filter { it > 0f }.sorted()
            val amps = run.map { it.ma }.filter { it > 0 }.sorted()
            val medW = if (watts.isEmpty()) -1f else watts[watts.size / 2]
            val maxW = if (watts.isEmpty()) -1f else watts.last()
            val cls = chargeClassOf(medW, if (amps.isEmpty()) -1 else amps[amps.size / 2])
            val word = when (cls) {
                ChargeClass.FAST -> "быстрая"; ChargeClass.MEDIUM -> "средняя"; ChargeClass.SLOW -> "медленная"; ChargeClass.UNKNOWN -> "скорость неизвестна"
            }
            val label = "Зарядка $word" + (if (medW > 0) ", обычно ${"%.0f".format(medW)} Вт" else "") +
                (if (maxW > medW + 3f) ", пик ${"%.0f".format(maxW)} Вт" else "") +
                (if (maxMa > 0) ", ток до ${"%.1f".format(maxMa / 1000f)} А" else "") +
                (if (first.pct >= 0 && last.pct >= 0) ", заряд ${first.pct}→${last.pct}%" else "")
            val verdict = when (cls) {
                ChargeClass.FAST -> HeatVerdict.LIKELY; ChargeClass.SLOW -> HeatVerdict.UNLIKELY; else -> HeatVerdict.POSSIBLE
            }
            return EpisodeSuspect(label, "зарядка", null, ((last.t - first.t) / MIN).toInt().coerceAtLeast(1), 0, first.t, last.t, verdict)
        }
        fun suspectsIn(winStart: Long, winEnd: Long): List<EpisodeSuspect> {
            val suspects = ArrayList<EpisodeSuspect>()
            for ((key, list) in byKey) {
                val inEp = list.sumOf { overlap(it.start, it.end, winStart, winEnd) }
                val m = (inEp / MIN).toInt()
                if (m < 3) continue
                val cool = coolMin[key] ?: 0
                val verdict = if (cool >= 30 && cool >= 3 * m) HeatVerdict.UNLIKELY else HeatVerdict.LIKELY
                val inside = list.filter { overlap(it.start, it.end, winStart, winEnd) > 0 }
                suspects += EpisodeSuspect(
                    key.first, key.second, list.firstNotNullOfOrNull { it.kind }, m, cool,
                    inside.minOf { it.start }.coerceAtLeast(winStart), inside.maxOf { it.end }.coerceAtMost(winEnd), verdict,
                )
            }
            val ranked = suspects.sortedWith(
                compareBy<EpisodeSuspect> { it.verdict }
                    .thenByDescending { it.minInEpisode * (if (it.kind in setOf("игра", "видео", "карты")) 2 else 1) },
            )
            val charge = chargeSuspect(winStart, winEnd)
            return if (charge != null && charge.verdict != HeatVerdict.UNLIKELY) listOf(charge) + ranked else ranked + listOfNotNull(charge)
        }
        fun thermalIn(a: Long, b: Long): Pair<Int, Int> {
            val w = samples.filter { it.t in a..b }
            return (w.maxOfOrNull { it.thermal } ?: -1) to (w.maxOfOrNull { it.headroom } ?: -1)
        }
        fun isFast(s: List<EpisodeSuspect>) = s.any { it.type == "зарядка" && it.verdict == HeatVerdict.LIKELY }
        val result = ArrayList<HeatEpisode>()
        for (d in drafts) {
            val rise = samples[d.riseIdx]
            val sus = suspectsIn(rise.t - preMin * MIN, samples[d.b].t)
            val (th, hr) = thermalIn(rise.t, samples[d.b].t)
            result += HeatEpisode(
                riseStart = rise.t, runStart = samples[d.a].t, runEnd = samples[d.b].t, peakT = samples[d.peakIdx].t,
                peakTemp = samples[d.peakIdx].tempC, baseTemp = rise.tempC,
                chargingAtRise = rise.charging, screenOnAtRise = rise.screenOn, suspects = sus,
                fastCharge = isFast(sus), maxThermal = th, peakHeadroom = hr,
            )
        }
        // Нагревы, которые владелец отметил кнопкой: окно в 30 минут до отметки.
        for (mk in marks) {
            if (result.any { mk >= it.riseStart && mk <= it.runEnd + 30 * MIN }) continue
            val ws = mk - 30 * MIN
            val near = samples.filter { it.t in ws..mk }
            if (near.isEmpty()) continue
            val base = near.minByOrNull { it.tempC }!!
            val peak = near.maxByOrNull { it.tempC }!!
            val sus = suspectsIn(ws, mk)
            val (th, hr) = thermalIn(ws, mk)
            result += HeatEpisode(
                riseStart = ws, runStart = mk, runEnd = mk, peakT = peak.t, peakTemp = peak.tempC, baseTemp = base.tempC,
                chargingAtRise = near.first().charging, screenOnAtRise = near.first().screenOn, suspects = sus, marked = true,
                fastCharge = isFast(sus), maxThermal = th, peakHeadroom = hr,
            )
        }
        return result.sortedBy { it.riseStart }
    }

    /** Приложение и средний ток/температура, пока оно работало и пока нет (замеры не на зарядке). */
    data class Culprit(
        val label: String, val type: String, val kind: String?,
        val activeSamples: Int, val inactiveSamples: Int,
        val maActive: Int, val maInactive: Int, val tempActive: Float, val tempInactive: Float,
    ) {
        val extraMa get() = maActive - maInactive
        val extraTemp get() = tempActive - tempInactive
        val guilty get() = extraMa >= 80 || extraTemp >= 1.0f
    }

    /**
     * Статистика по всем замерам: для каждого приложения средний ток и температура, пока оно работало, против тех же замеров без него.
     * Фоновые службы и модель сравниваются по замерам с выключенным экраном, приложения на экране: по замерам с включённым.
     * Это совпадение во времени, а не прямой замер процессора (его Android не отдаёт), но за несколько суток общий виновник выделяется.
     */
    fun culprits(samplesIn: List<HeatSample>, intervalsIn: List<ActivityInterval>, minSide: Int = 4): List<Culprit> {
        val samples = samplesIn.filter { !it.charging && it.ma > 0 }.sortedBy { it.t }
        if (samples.size < 2 * minSide) return emptyList()
        val merged = mergeIntervals(intervalsIn, samples.first().t to samples.last().t)
        val out = ArrayList<Culprit>()
        for ((key, list) in merged.groupBy { it.label to it.type }) {
            val pool = samples.filter { if (key.second == "экран") it.screenOn else !it.screenOn }
            val (active, inactive) = pool.partition { s -> list.any { s.t >= it.start && s.t <= it.end } }
            if (active.size < minSide || inactive.size < minSide) continue
            out += Culprit(
                key.first, key.second, list.firstNotNullOfOrNull { it.kind }, active.size, inactive.size,
                active.map { it.ma }.average().toInt(), inactive.map { it.ma }.average().toInt(),
                active.map { it.tempC }.average().toFloat(), inactive.map { it.tempC }.average().toFloat(),
            )
        }
        return out.sortedWith(compareByDescending<Culprit> { it.extraMa }.thenByDescending { it.extraTemp })
    }

    fun culpritLine(c: Culprit): String {
        val what = when (c.type) { "служба" -> "в фоне"; "модель" -> "считает модель"; else -> "на экране" }
        val sign = { v: Int -> if (v >= 0) "+$v" else "$v" }
        val verdict = if (c.guilty) "сажает батарею и греет: кандидат на ограничение или удаление" else "заметного влияния нет"
        return "${c.label}${c.kind?.let { " ($it)" } ?: ""}, $what: ток ${c.maActive} мА против ${c.maInactive} мА без него (${sign(c.extraMa)} мА), " +
            "температура ${if (c.extraTemp >= 0) "+" else ""}${"%.1f".format(c.extraTemp)} °C; $verdict (замеров ${c.activeSamples} и ${c.inactiveSamples})"
    }

    private fun overlap(a1: Long, a2: Long, b1: Long, b2: Long): Long = (minOf(a2, b2) - maxOf(a1, b1)).coerceAtLeast(0)

    private fun mergeIntervals(list: List<ActivityInterval>, cover: Pair<Long, Long>): List<ActivityInterval> {
        val out = ArrayList<ActivityInterval>()
        for ((_, group) in list.groupBy { it.label to it.type }) {
            var cur: ActivityInterval? = null
            for (iv in group.sortedBy { it.start }) {
                // Приложение не бывает на экране больше 6 часов подряд: это обрывок журнала Android, а не работа.
                if (iv.type == "экран" && iv.end - iv.start > 6 * 3_600_000L) continue
                val c = ActivityInterval(iv.label, iv.type, maxOf(iv.start, cover.first), minOf(iv.end, cover.second), iv.kind)
                if (c.end <= c.start) continue
                val p = cur
                if (p != null && c.start <= p.end) cur = p.copy(end = maxOf(p.end, c.end))
                else { if (p != null) out += p; cur = c }
            }
            cur?.let { out += it }
        }
        return out
    }

    private val thermalNames = listOf("нет ограничений", "лёгкое", "умеренное", "сильное", "критическое", "аварийное", "выключение")

    /** Что про этот нагрев сказала сама система Android (тепловой статус и близость к порогу), если это записано. */
    fun thermalLine(e: HeatEpisode): String? {
        if (e.maxThermal < 0 && e.peakHeadroom < 0) return null
        val st = if (e.maxThermal >= 0) "ограничение мощности: ${thermalNames.getOrElse(e.maxThermal) { "статус ${e.maxThermal}" }}" else null
        val hr = if (e.peakHeadroom >= 0) "близость к порогу сильного ограничения ${e.peakHeadroom}% (100% = порог)" else null
        return "Тепловая защита Android: " + listOfNotNull(st, hr).joinToString("; ")
    }

    /** Итог по всем нагревам: сколько от быстрой зарядки, сколько без неё и что чаще всего работало в остальных. */
    fun summary(eps: List<HeatEpisode>): List<String> {
        if (eps.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        val rest = eps.filter { !it.fastCharge }
        out += "Нагревов: ${eps.size}. При быстрой зарядке: ${eps.size - rest.size}, без неё: ${rest.size}."
        if (rest.isNotEmpty()) {
            val counts = HashMap<String, Int>()
            for (e in rest) e.suspects.filter { it.type != "зарядка" && it.verdict != HeatVerdict.UNLIKELY && it.minInEpisode >= 5 }
                .map { it.label }.distinct().forEach { counts[it] = (counts[it] ?: 0) + 1 }
            val top = counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(6)
            if (top.isNotEmpty()) {
                out += "В нагревах без быстрой зарядки одновременно работали: " + top.joinToString(", ") { "${it.key} (${it.value} из ${rest.size})" } +
                    ". Это совпадение по времени, а не прямой замер процессора: Android не отдаёт приложениям нагрузку других приложений."
            }
        }
        val withTh = eps.filter { it.maxThermal >= 0 }
        out += if (withTh.isEmpty()) "Тепловой статус Android в этих нагревах не записан (появится в новых замерах)."
        else "Система сама ограничивала мощность (умеренно или сильнее) в ${withTh.count { it.maxThermal >= 2 }} из ${withTh.size} нагревов."
        return out
    }

    fun hm(t: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))

    fun minutesText(m: Int): String = if (m >= 60) "${m / 60} ч ${m % 60} мин" else "$m мин"

    /** Строка про нагрев: «в 18:10 пошёл рост (34,5 → 40,1 °C к 18:45)». */
    fun title(e: HeatEpisode): String = if (e.marked)
        "Вы отметили «нагрелся» в ${hm(e.peakT.coerceAtLeast(e.runEnd))}: за 30 минут до этого ${"%.1f".format(e.baseTemp)} → ${"%.1f".format(e.peakTemp)} °C" else
        "В ${hm(e.riseStart)} пошёл рост: ${"%.1f".format(e.baseTemp)} → ${"%.1f".format(e.peakTemp)} °C к ${hm(e.peakT)}" +
            (if (e.chargingAtRise) ", на зарядке" else "") + (if (e.screenOnAtRise) ", экран включён" else ", экран выключен")

    fun line(s: EpisodeSuspect): String {
        if (s.type == "зарядка") return "${s.label}, с ${hm(s.firstStart)} по ${hm(s.lastEnd)}: " + when (s.verdict) {
            HeatVerdict.LIKELY -> "быстрая зарядка сама греет батарею; вероятная причина"
            HeatVerdict.POSSIBLE -> "греет умеренно; возможная причина"
            HeatVerdict.UNLIKELY -> "зарядка медленная, сама так не греет; не причина"
        }
        val what = when (s.type) {
            "служба" -> "работал в фоне (служба)"
            "модель" -> "считала локальная модель"
            else -> "был на экране"
        }
        val name = s.label + (s.kind?.let { " ($it)" } ?: "")
        val v = if (s.verdict == HeatVerdict.UNLIKELY) "вряд ли причина: без нагрева это же работало ${minutesText(s.minCool)}"
        else if (s.minCool < s.minInEpisode / 2) "вероятная причина: почти не работало, пока телефон был прохладным"
        else "возможная причина"
        return "$name: $what ${hm(s.firstStart)}–${hm(s.lastEnd)} (${minutesText(s.minInEpisode)}); $v"
    }
}
