package com.rwai.agent.logic

enum class DigestKind { URGENT, WAITING, DEAL, PERSON, NOTE }

data class DigestItem(val title: String?, val body: String, val kind: DigestKind) {
    /** Для карточки человека: «taisiialook (Instagram, пересказ)» → ("taisiialook", "Instagram"). */
    fun person(): Pair<String, String?>? {
        val t = title ?: return null
        val i = t.indexOf('(')
        if (kind != DigestKind.PERSON || i <= 0) return null
        val name = t.substring(0, i).trim()
        val app = t.substring(i + 1).substringBefore(')').substringBefore(',').trim().ifEmpty { null }
        return if (name.isEmpty()) null else name to app
    }
}

/** Разбор сводки модели («- **Имя (Приложение):** текст») в карточки без звёздочек. */
object DigestView {
    private val bullet = Regex("^\\s*(?:[-*•]\\s+)?\\*\\*(.+?)\\*\\*\\s*:?\\s*(.*)$")
    private val bulletPlain = Regex("^\\s*[-*•]\\s+(.*)$")
    private val empty = Regex("(?iu)^(нет|не указаны|не указано|ничего|нет данных|—|-)[.!]?$")

    fun kindOf(title: String?): DigestKind {
        val t = (title ?: "").lowercase().replace('ё', 'е')
        return when {
            "срочно" in t -> DigestKind.URGENT
            "ждут ответа" in t || "ждет ответа" in t || "ожидают" in t -> DigestKind.WAITING
            "договор" in t -> DigestKind.DEAL
            '(' in t -> DigestKind.PERSON
            else -> DigestKind.NOTE
        }
    }

    fun parse(text: String): List<DigestItem> {
        data class Acc(var title: String?, val body: StringBuilder)
        val acc = mutableListOf<Acc>()
        for (raw in text.replace("\r", "").split('\n')) {
            val line = raw.trimEnd()
            if (line.isBlank()) continue
            val m = bullet.find(line)
            if (m != null) {
                acc += Acc(m.groupValues[1].trim().trimEnd(':').trim(), StringBuilder(Md.strip(m.groupValues[2].trim())))
                continue
            }
            val b = bulletPlain.find(line)
            if (b != null || acc.isEmpty()) {
                acc += Acc(null, StringBuilder(Md.strip((b?.groupValues?.get(1) ?: line).trim())))
            } else {
                if (acc.last().body.isNotEmpty()) acc.last().body.append(' ')
                acc.last().body.append(Md.strip(line.trim()))
            }
        }
        return acc.map { DigestItem(it.title, it.body.toString().trim(), kindOf(it.title)) }
            .filter { it.body.isNotEmpty() || it.title != null }
            .filterNot { it.title != null && empty.matches(it.body) }
    }
}

/** Лёгкая разметка Markdown: **жирный**, `код`, заголовки #, маркеры списка. */
object Md {
    /** Убирает звёздочки, обратные кавычки и «#». */
    fun strip(s: String): String = spans(s).joinToString("") { it.first }

    /** Разбивает строку на куски (текст, жирный?). Маркеры «- » в начале строк заменяются на «• ». */
    fun spans(raw: String): List<Pair<String, Boolean>> {
        val text = raw.lines().joinToString("\n") { l ->
            val t = l.trimStart()
            when {
                t.startsWith("- ") || t.startsWith("* ") && !t.startsWith("**") -> "• " + t.drop(2)
                t.startsWith("#") -> t.trimStart('#').trim()
                else -> l
            }
        }.replace("`", "")
        val out = mutableListOf<Pair<String, Boolean>>()
        var i = 0
        var bold = false
        val sb = StringBuilder()
        while (i < text.length) {
            if (text.startsWith("**", i)) {
                if (sb.isNotEmpty()) { out += sb.toString() to bold; sb.setLength(0) }
                bold = !bold
                i += 2
            } else { sb.append(text[i]); i++ }
        }
        if (sb.isNotEmpty()) out += sb.toString() to bold
        return out
    }
}
