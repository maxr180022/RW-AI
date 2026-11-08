package com.rwai.agent.logic

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Диалоги и проекты агента: чистая логика без Android (проверяется JVM-тестами).
 *
 * Идея: чат не растёт бесконечно. Каждый диалог подписан датой, у него есть «память» — сжатая выжимка старых реплик плюс несколько последних
 * реплик дословно. Всё это уходит модели вместе с новой задачей. Когда память заполнена на [DialogRules.COMPACT_PERCENT] %, старое
 * автоматически сжимается в выжимку (без вызова модели, значит бесплатно), и диалог продолжается. Новый день или новый диалог начинается
 * с выжимки предыдущего диалога ТОГО ЖЕ проекта: память разных проектов не смешивается.
 */

/** Реплика для контекста модели: только владелец и агент (служебные сообщения и ошибки в память не идут). */
data class DialogTurn(val fromOwner: Boolean, val text: String, val ts: Long)

/** Результат сжатия: новая выжимка и оставшиеся дословно реплики. */
data class Compacted(val summary: String, val turns: List<DialogTurn>, val folded: Int)

enum class FillLevel { OK, WARN, FULL }

object DialogRules {
    /**
     * Сколько «памяти диалога» (выжимка + реплики + заметка проекта) отправляется модели вместе с задачей, в токенах.
     * Предел задан защитой: UntrustedContent пропускает блок не длиннее 6000 знаков (дальше режет конец, то есть самые свежие реплики),
     * поэтому память должна помещаться в ≈5800 знаков ≈ 2300 токенов. Худший случай после сжатия — около 4500 знаков.
     */
    const val BUDGET_TOKENS = 2_300
    const val WARN_PERCENT = 70
    const val COMPACT_PERCENT = 80
    const val FULL_PERCENT = 90

    /** Сколько последних реплик остаётся дословно после сжатия (2 обмена «владелец → агент»). */
    const val KEEP_LAST_TURNS = 4
    const val SUMMARY_CAP = 1_500
    const val CARRY_CAP = 1_200

    /** Длина строки выжимки: задача → результат (как у прежней «скользящей памяти»). */
    const val LINE_TASK = 80
    const val LINE_ANSWER = 160

    /** Реплика в контексте модели обрезается, чтобы одна длинная выдача не съела весь бюджет. */
    const val TURN_CAP_OWNER = 400
    const val TURN_CAP_AGENT = 700

    const val NOTE_CAP = 600
    const val NAME_CAP = 40
    const val MAX_PROJECTS = 20

    /** Страховка размера базы: больше сообщений в одном диалоге не хранится (самые старые удаляются). */
    const val MAX_MESSAGES_PER_DIALOG = 500

    /** Для русского текста ≈ 2,5 знака на токен; запас на служебные символы — по 4 токена на реплику. */
    private const val CHARS_PER_TOKEN = 2.5
    private const val TURN_OVERHEAD = 4

    fun estimateTokens(text: String): Int = if (text.isEmpty()) 0 else Math.ceil(text.length / CHARS_PER_TOKEN).toInt()

    private fun capped(t: DialogTurn): String = t.text.take(if (t.fromOwner) TURN_CAP_OWNER else TURN_CAP_AGENT)

    /** Размер памяти диалога в токенах: именно столько уходит модели вместе с задачей. */
    fun contextTokens(summary: String, turns: List<DialogTurn>, projectNote: String = ""): Int =
        estimateTokens(summary) + estimateTokens(projectNote.take(NOTE_CAP)) + turns.sumOf { estimateTokens(capped(it)) + TURN_OVERHEAD }

    fun fillPercent(summary: String, turns: List<DialogTurn>, projectNote: String = ""): Int =
        (contextTokens(summary, turns, projectNote) * 100L / BUDGET_TOKENS).toInt().coerceIn(0, 100)

    fun level(percent: Int): FillLevel = when {
        percent >= FULL_PERCENT -> FillLevel.FULL
        percent >= WARN_PERCENT -> FillLevel.WARN
        else -> FillLevel.OK
    }

    fun needsCompaction(percent: Int): Boolean = percent >= COMPACT_PERCENT

    private fun oneLine(s: String, n: Int): String = s.replace(Regex("\\s+"), " ").trim().take(n)

    /** Свернуть реплики в строки выжимки: «• задача → результат». Самые старые строки выпадают, когда выжимка длиннее [cap]. */
    fun fold(summary: String, turns: List<DialogTurn>, cap: Int = SUMMARY_CAP): String {
        val lines = ArrayList(summary.split("\n").filter { it.isNotBlank() })
        var i = 0
        while (i < turns.size) {
            val t = turns[i]
            if (t.fromOwner) {
                val next = turns.getOrNull(i + 1)
                if (next != null && !next.fromOwner) {
                    lines += "• " + oneLine(t.text, LINE_TASK) + " → " + oneLine(next.text, LINE_ANSWER)
                    i += 2
                } else {
                    lines += "• " + oneLine(t.text, LINE_TASK)
                    i += 1
                }
            } else {
                lines += "• → " + oneLine(t.text, LINE_ANSWER)
                i += 1
            }
        }
        while (lines.size > 1 && lines.joinToString("\n").length > cap) lines.removeAt(0)
        val out = lines.joinToString("\n")
        return if (out.length > cap) out.takeLast(cap) else out
    }

    /** Сжать: всё, кроме последних [keepLast] реплик, уходит в выжимку. Если сжимать нечего — вернуть как есть (folded = 0). */
    fun compact(summary: String, turns: List<DialogTurn>, keepLast: Int = KEEP_LAST_TURNS): Compacted {
        if (turns.size <= keepLast) return Compacted(summary, turns, 0)
        var cut = turns.size - keepLast
        // Не разрываем обмен: если на границе стоит ответ агента, его вопрос остаётся вместе с ним среди последних реплик.
        if (cut < turns.size && !turns[cut].fromOwner && cut > 0 && turns[cut - 1].fromOwner) cut -= 1
        if (cut <= 0) return Compacted(summary, turns, 0)
        return Compacted(fold(summary, turns.subList(0, cut)), turns.subList(cut, turns.size).toList(), cut)
    }

    /** Выжимка для нового диалога: память прошлого диалога и все его реплики, сжатые целиком. */
    fun carryOver(summary: String, turns: List<DialogTurn>): String = fold(summary, turns, CARRY_CAP)

    /** Текст «памяти» для модели. Пустая строка, если помнить нечего. Вызывающий оборачивает его как недоверенные данные. */
    fun buildContext(summary: String, turns: List<DialogTurn>, projectName: String? = null, projectNote: String = ""): String {
        val parts = ArrayList<String>()
        if (projectName != null) parts += "Проект «$projectName»" + (if (projectNote.isNotBlank()) ": " + projectNote.take(NOTE_CAP) else "") + ". Работай только в рамках этого проекта."
        if (summary.isNotBlank()) parts += "Кратко о прошлом в этом диалоге:\n$summary"
        if (turns.isNotEmpty()) parts += "Последние реплики:\n" + turns.joinToString("\n") { (if (it.fromOwner) "Владелец: " else "Агент: ") + capped(it) }
        return parts.joinToString("\n\n")
    }

    // ---------------- заголовки, дни, проекты ----------------

    private fun dateKey(ts: Long, tz: TimeZone): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }.format(Date(ts))

    /** Подпись диалога — просто дата дд.мм.гггг. Второй и следующий диалог за день: «08.11.2026 (2)». */
    fun title(ts: Long, existingTitles: List<String>, tz: TimeZone = TimeZone.getDefault()): String {
        val base = SimpleDateFormat("dd.MM.yyyy", Locale.US).apply { timeZone = tz }.format(Date(ts))
        val n = existingTitles.count { it == base || it.startsWith("$base (") }
        return if (n == 0) base else "$base (${n + 1})"
    }

    fun isNewDay(lastTs: Long, nowTs: Long, tz: TimeZone = TimeZone.getDefault()): Boolean = dateKey(lastTs, tz) != dateKey(nowTs, tz)

    fun cleanProjectName(raw: String): String = raw.replace("<", "").replace(">", "").replace(Regex("\\s+"), " ").trim().take(NAME_CAP)

    fun cleanProjectNote(raw: String): String = raw.replace("<", "").replace(">", "").trim().take(NOTE_CAP)

    /** Сообщение об ошибке для названия проекта или null, если название подходит. */
    fun validateProjectName(raw: String, existing: List<String>): String? {
        val name = cleanProjectName(raw)
        return when {
            name.isEmpty() -> "Введите название проекта."
            existing.any { it.equals(name, ignoreCase = true) } -> "Проект с таким названием уже есть."
            existing.size >= MAX_PROJECTS -> "Слишком много проектов (не больше $MAX_PROJECTS)."
            else -> null
        }
    }
}
