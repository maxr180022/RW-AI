package com.rwai.agent.logic

enum class DigestKind { URGENT, WAITING, DEAL, PERSON, NOTE }

data class DigestItem(val title: String?, val body: String, val kind: DigestKind) {
    /** Для карточки человека: «taisiialook (Instagram, пересказ)» → ("taisiialook", "Instagram"). */
    fun person(): Pair<String, String?>? {
        val t = title ?: return null
        if (kind != DigestKind.PERSON) return null
        DigestView.appAndPerson(t)?.let { return it.first to it.second }
        val i = t.indexOf('(')
        if (i <= 0) return null
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
            appAndPerson(title) != null -> DigestKind.PERSON
            else -> DigestKind.NOTE
        }
    }

    private val messengers = setOf(
        "instagram", "telegram", "whatsapp", "viber", "signal", "messenger", "facebook", "vk", "вк", "sms", "смс", "gmail", "почта", "mail", "outlook",
    )

    /** Заголовок вида «Instagram, taisiialook» — приложение и человек: (имя, приложение). Просто «Telegram» без имени — не человек. */
    fun appAndPerson(title: String?): Pair<String, String>? {
        val t = title ?: return null
        val i = t.indexOf(',')
        if (i <= 0) return null
        val app = t.substring(0, i).trim()
        val name = t.substring(i + 1).trim()
        return if (name.isNotEmpty() && app.lowercase() in messengers) name to app else null
    }

    /** Карточки, на которые нужно ответить: срочное, «ждут ответа» и личные сообщения. Остальные (заметки, договорённости) — только информация. */
    fun needsReply(kind: DigestKind): Boolean = kind == DigestKind.URGENT || kind == DigestKind.WAITING || kind == DigestKind.PERSON

    /** Ключ карточки для отметки «отвечено»: у новой сводки карточки новые, у прежней — те же. */
    fun cardKey(text: String, index: Int, item: DigestItem): String =
        Integer.toHexString(text.hashCode()) + ":" + index + ":" + Integer.toHexString(((item.title ?: "") + item.body).hashCode())

    /**
     * Команда владельца, которая видна в чате: его указание и к какой карточке оно относится.
     * Текст самой карточки (он пришёл из чужих сообщений) сюда не попадает: он передаётся модели отдельно как недоверенные данные.
     * null — указание пустое или для этого типа карточки ответа нет.
     */
    fun replyTask(item: DigestItem, instruction: String): String? {
        val ins = instruction.trim()
        if (ins.isEmpty()) return null
        val who = item.person()
        return when (item.kind) {
            DigestKind.PERSON ->
                if (who != null) "Ответь ${who.first}" + (who.second?.let { " в $it" } ?: "") + ": $ins"
                else "Ответь по сообщению из «Входящих»: $ins"
            DigestKind.WAITING -> "По сообщениям, которые ждут ответа: $ins"
            DigestKind.URGENT -> "Разберись со срочным сообщением: $ins"
            DigestKind.NOTE -> "По заметке из «Входящих»: $ins"
            else -> null
        }
    }

    /** Просьба «предложи, как ответить»: агент готовит черновик или варианты и ничего не отправляет. Видна в чате; текст карточки передаётся отдельно. */
    fun suggestTask(item: DigestItem): String? {
        val who = item.person()
        return when (item.kind) {
            DigestKind.PERSON ->
                if (who != null) "Предложи, как ответить ${who.first}" + (who.second?.let { " в $it" } ?: "") + ": покажи черновик, не отправляй."
                else "Предложи, как ответить на это сообщение: покажи черновик, не отправляй."
            DigestKind.WAITING -> "Кому нужно ответить сейчас? Подготовь короткие ответы и покажи черновики, не отправляй."
            DigestKind.URGENT -> "Помоги разобраться со срочным сообщением и предложи, что делать. Ничего не нажимай и не отправляй без моего подтверждения."
            DigestKind.NOTE -> "Объясни, что это за заметка и что мне с ней сделать. Предложи варианты, ничего не отправляй."
            else -> null
        }
    }

    /** Текст карточки, который уходит модели вместе с указанием (как справка, не как команда). */
    fun contextFor(item: DigestItem): String = ((item.title?.let { "$it: " } ?: "") + item.body).take(400)

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

/** Какие карточки «Входящих» владелец уже отработал (ответил или поручил агенту). Хранится строкой «ключ на строку», самые старые выпадают. */
object AnsweredCards {
    const val CAP = 300

    fun parse(raw: String): List<String> = raw.split('\n').filter { it.isNotBlank() }

    fun has(raw: String, key: String): Boolean = key in parse(raw)

    fun add(raw: String, key: String): String = (parse(raw).filter { it != key } + key).takeLast(CAP).joinToString("\n")

    fun remove(raw: String, key: String): String = parse(raw).filter { it != key }.joinToString("\n")
}
