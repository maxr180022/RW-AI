package com.rwai.agent.logic

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Один замер: температура батареи в момент [t] (мс). Пишется в фоне при каждом заметном изменении. */
data class HeatSample(val t: Long, val tempC: Float, val pct: Int, val charging: Boolean, val screenOn: Boolean, val ma: Int = -1)

/** Период активности: приложение на экране, служба переднего плана или работа локальной модели Руси. */
data class ActivityInterval(val label: String, val type: String, val start: Long, val end: Long, val kind: String? = null)

enum class HeatVerdict { LIKELY, UNLIKELY }

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

        // 4. Подозреваемые для каждого нагрева. Зарядка, включённая в это время, идёт первой: быстрая зарядка сама греет батарею.
        fun chargeSuspect(winStart: Long, winEnd: Long): EpisodeSuspect? {
            val idx = samples.indices.firstOrNull { k ->
                val x = samples[k]
                x.charging && x.t in winStart..winEnd && (k == 0 || !samples[k - 1].charging)
            } ?: return null
            val first = samples[idx]
            val run = samples.drop(idx).takeWhile { it.charging && it.t <= winEnd }
            val last = run.last()
            val maxMa = run.maxOf { it.ma }
            val label = "Зарядка" + (if (maxMa > 0) ", ток до ${"%.1f".format(maxMa / 1000f)} А" else "") +
                (if (first.pct >= 0 && last.pct >= 0) ", заряд ${first.pct}→${last.pct}%" else "")
            return EpisodeSuspect(label, "зарядка", null, ((last.t - first.t) / MIN).toInt().coerceAtLeast(1), 0, first.t, last.t, HeatVerdict.LIKELY)
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
            return listOfNotNull(chargeSuspect(winStart, winEnd)) + ranked
        }
        val result = ArrayList<HeatEpisode>()
        for (d in drafts) {
            val rise = samples[d.riseIdx]
            result += HeatEpisode(
                riseStart = rise.t, runStart = samples[d.a].t, runEnd = samples[d.b].t, peakT = samples[d.peakIdx].t,
                peakTemp = samples[d.peakIdx].tempC, baseTemp = rise.tempC,
                chargingAtRise = rise.charging, screenOnAtRise = rise.screenOn, suspects = suspectsIn(rise.t - preMin * MIN, samples[d.b].t),
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
            result += HeatEpisode(
                riseStart = ws, runStart = mk, runEnd = mk, peakT = peak.t, peakTemp = peak.tempC, baseTemp = base.tempC,
                chargingAtRise = near.first().charging, screenOnAtRise = near.first().screenOn, suspects = suspectsIn(ws, mk), marked = true,
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

    fun hm(t: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))

    fun minutesText(m: Int): String = if (m >= 60) "${m / 60} ч ${m % 60} мин" else "$m мин"

    /** Строка про нагрев: «в 18:10 пошёл рост (34,5 → 40,1 °C к 18:45)». */
    fun title(e: HeatEpisode): String = if (e.marked)
        "Вы отметили «нагрелся» в ${hm(e.peakT.coerceAtLeast(e.runEnd))}: за 30 минут до этого ${"%.1f".format(e.baseTemp)} → ${"%.1f".format(e.peakTemp)} °C" else
        "В ${hm(e.riseStart)} пошёл рост: ${"%.1f".format(e.baseTemp)} → ${"%.1f".format(e.peakTemp)} °C к ${hm(e.peakT)}" +
            (if (e.chargingAtRise) ", на зарядке" else "") + (if (e.screenOnAtRise) ", экран включён" else ", экран выключен")

    fun line(s: EpisodeSuspect): String {
        if (s.type == "зарядка") return "${s.label}, с ${hm(s.firstStart)} по ${hm(s.lastEnd)}: сама зарядка греет батарею, особенно быстрая; вероятная причина"
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
