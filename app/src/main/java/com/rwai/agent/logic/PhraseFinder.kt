package com.rwai.agent.logic

/** Строка страницы PDF, созданного приложением: текст, X‑координаты каждого символа (pt) и вертикальные границы. */
class LineBox(
    val page: Int,
    text: String,
    /** xs[i] — левая граница i‑го символа, xs[text.length] — правая граница строки. */
    val xs: FloatArray,
    val top: Float,
    val bottom: Float,
    val pageW: Float,
    val pageH: Float,
) {
    val text: String = text
}

data class Segment(val page: Int, val x1: Float, val x2: Float, val top: Float, val bottom: Float, val pageW: Float, val pageH: Float)

object PhraseFinder {
    const val MAX_HITS = 10

    /** Находит фразу (без учёта регистра и переносов строк) и возвращает отрезки по каждой затронутой строке. */
    fun find(lines: List<LineBox>, phrase: String): List<Segment> {
        val needle = phrase.trim().replace(Regex("\\s+"), " ").map { it.lowercaseChar() }.joinToString("")
        if (needle.isEmpty() || lines.isEmpty()) return emptyList()
        val sb = StringBuilder()
        val lineOf = ArrayList<Int>()
        val offOf = ArrayList<Int>()
        for ((li, l) in lines.withIndex()) {
            if (li > 0) { sb.append(' '); lineOf += -1; offOf += 0 }
            val t = l.text.trim { it == ' ' || it == '\n' }
            val lead = l.text.indexOfFirst { it != ' ' && it != '\n' }.coerceAtLeast(0)
            for ((i, c) in t.withIndex()) {
                sb.append(c.lowercaseChar()); lineOf += li; offOf += lead + i
            }
        }
        val hay = sb.toString()
        val out = mutableListOf<Segment>()
        var from = 0
        var hits = 0
        while (hits < MAX_HITS) {
            val s = hay.indexOf(needle, from)
            if (s < 0) break
            val e = s + needle.length // не включительно
            var i = s
            while (i < e) {
                val li = lineOf[i]
                if (li < 0) { i++; continue }
                var j = i
                while (j + 1 < e && lineOf[j + 1] == li) j++
                val l = lines[li]
                val a = offOf[i]
                val b = (offOf[j] + 1).coerceAtMost(l.xs.size - 1)
                out += Segment(l.page, l.xs[a.coerceAtMost(l.xs.size - 1)], l.xs[b], l.top, l.bottom, l.pageW, l.pageH)
                i = j + 1
            }
            hits++
            from = e
        }
        return out
    }
}
