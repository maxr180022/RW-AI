package com.rwai.agent.logic

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Один замер: температура батареи в момент [t] (мс). Пишется в фоне при каждом заметном изменении. */
data class HeatSample(val t: Long, val tempC: Float, val pct: Int, val charging: Boolean, val screenOn: Boolean)

/** Период активности: приложение на экране, служба переднего плана или работа локальной модели Руси. */
data class ActivityInterval(val label: String, val type: String, val start: Long, val end: Long, val kind: String? = null)

enum class Verdict { LIKELY, UNLIKELY }

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
    val verdict: Verdict,
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
        hot: Float = 37.5f, lookBackMin: Int = 90, preMin: Int = 10,
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
        if (runs.isEmpty()) return emptyList()

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
        val hotWindows = drafts.map { samples[it.riseIdx].t to samples[it.b].t }
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

        // 4. Подозреваемые для каждого нагрева.
        return drafts.map { d ->
            val rise = samples[d.riseIdx]
            val winStart = rise.t - preMin * MIN
            val winEnd = samples[d.b].t
            val suspects = ArrayList<EpisodeSuspect>()
            for ((key, list) in byKey) {
                val inEp = list.sumOf { overlap(it.start, it.end, winStart, winEnd) }
                val m = (inEp / MIN).toInt()
                if (m < 3) continue
                val cool = coolMin[key] ?: 0
                val verdict = if (cool >= 30 && cool >= 3 * m) Verdict.UNLIKELY else Verdict.LIKELY
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
            HeatEpisode(
                riseStart = rise.t, runStart = samples[d.a].t, runEnd = winEnd, peakT = samples[d.peakIdx].t,
                peakTemp = samples[d.peakIdx].tempC, baseTemp = rise.tempC,
                chargingAtRise = rise.charging, screenOnAtRise = rise.screenOn, suspects = ranked,
            )
        }
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
    fun title(e: HeatEpisode): String =
        "В ${hm(e.riseStart)} пошёл рост: ${"%.1f".format(e.baseTemp)} → ${"%.1f".format(e.peakTemp)} °C к ${hm(e.peakT)}" +
            (if (e.chargingAtRise) ", на зарядке" else "") + (if (e.screenOnAtRise) ", экран включён" else ", экран выключен")

    fun line(s: EpisodeSuspect): String {
        val what = when (s.type) {
            "служба" -> "работал в фоне (служба)"
            "модель" -> "считала локальная модель"
            else -> "был на экране"
        }
        val name = s.label + (s.kind?.let { " ($it)" } ?: "")
        val v = if (s.verdict == Verdict.UNLIKELY) "вряд ли причина: без нагрева это же работало ${minutesText(s.minCool)}"
        else if (s.minCool < s.minInEpisode / 2) "вероятная причина: почти не работало, пока телефон был прохладным"
        else "возможная причина"
        return "$name: $what ${hm(s.firstStart)}–${hm(s.lastEnd)} (${minutesText(s.minInEpisode)}); $v"
    }
}
