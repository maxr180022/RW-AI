package com.rwai.agent.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeatTimelineTest {
    private val min = 60_000L
    private val t0 = 1_000_000_000L

    /** Температура по минутам: [points] — (минута, °C), между ними линейно, замер раз в 5 минут. */
    private fun samples(vararg points: Pair<Int, Float>): List<HeatSample> {
        val out = ArrayList<HeatSample>()
        for (m in points.first().first..points.last().first step 5) {
            val hi = points.indexOfFirst { it.first >= m }
            val lo = if (hi <= 0) 0 else hi - 1
            val (m1, v1) = points[lo]; val (m2, v2) = points[hi]
            val v = if (m2 == m1) v2 else v1 + (v2 - v1) * (m - m1) / (m2 - m1)
            out += HeatSample(t0 + m * min, v, 50, false, true)
        }
        return out
    }

    private fun iv(label: String, type: String, from: Int, to: Int, kind: String? = null) =
        ActivityInterval(label, type, t0 + from * min, t0 + to * min, kind)

    @Test fun noHeatNoEpisodes() {
        assertTrue(HeatTimeline.episodes(samples(0 to 33f, 120 to 35f), emptyList()).isEmpty())
    }

    @Test fun riseStartIsTheColdPointBeforeCrossingThreshold() {
        val e = HeatTimeline.episodes(samples(0 to 34f, 60 to 34f, 90 to 40f, 150 to 40f, 200 to 34f), emptyList()).single()
        assertEquals(t0 + 60 * min, e.riseStart)
        assertEquals(40f, e.peakTemp, 0.01f)
    }

    @Test fun suspectDuringRiseIsLikelyButAllDayMusicIsNot() {
        val s = samples(0 to 34f, 180 to 34f, 210 to 40f, 270 to 40f, 330 to 34f)
        val ivs = listOf(
            iv("Android Auto", "служба", 0, 170),   // весь вечер без нагрева
            iv("Игра", "экран", 185, 260, kind = "игра"), // только в нагрев
        )
        val e = HeatTimeline.episodes(s, ivs).single()
        val game = e.suspects.first { it.label == "Игра" }
        val auto = e.suspects.firstOrNull { it.label == "Android Auto" }
        assertEquals(HeatVerdict.LIKELY, game.verdict)
        assertEquals("Игра", e.suspects.first().label)
        assertTrue(auto == null || auto.verdict == HeatVerdict.UNLIKELY)
    }

    @Test fun longActivityWithoutHeatMakesSuspectUnlikely() {
        val s = samples(0 to 34f, 480 to 34f, 510 to 40f, 570 to 40f, 640 to 34f)
        // музыка играла и до нагрева (8 ч), и в нагрев
        val e = HeatTimeline.episodes(s, listOf(iv("Музыка", "служба", 0, 570))).single()
        assertEquals(HeatVerdict.UNLIKELY, e.suspects.single().verdict)
        assertTrue(HeatTimeline.line(e.suspects.single()).contains("вряд ли"))
    }

    @Test fun tooShortActivityIsIgnored() {
        val s = samples(0 to 34f, 60 to 34f, 90 to 40f, 150 to 40f, 200 to 34f)
        val e = HeatTimeline.episodes(s, listOf(iv("Мелочь", "экран", 100, 101))).single()
        assertTrue(e.suspects.isEmpty())
    }
}
