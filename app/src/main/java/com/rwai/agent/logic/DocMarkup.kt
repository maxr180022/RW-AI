package com.rwai.agent.logic

/**
 * Простой построчный язык разметки документов. Модель пишет текст, а оформление (шапка справа, заголовок по центру,
 * абзацный отступ, подпись) делает приложение. Так не нужно передавать вложенный JSON.
 *
 *  >> текст        строка «шапки» (блок в правой половине листа, текст выровнен влево) — «В суд…», «От…»
 *  == текст        заголовок по центру, жирный, крупный («ЗАЯВЛЕНИЕ»)
 *  =~ текст        строка по центру, обычная
 *  # текст         жирный подзаголовок слева
 *  << текст        строка по правому краю
 *  ** текст        жирный абзац
 *  - текст         пункт списка (с тире)
 *  || слева | справа   в одной строке: слева и справа («Дата» … «Подпись»)
 *  ---             горизонтальная линия
 *  ~~              пустая строка
 *  ++              новая страница
 *  всё остальное   абзац: выравнивание по ширине, красная строка 1,25 см
 *  пустая строка   небольшой промежуток
 */
sealed interface DocBlock {
    data class Head(val text: String) : DocBlock
    data class Title(val text: String) : DocBlock
    data class Center(val text: String) : DocBlock
    data class Sub(val text: String) : DocBlock
    data class Right(val text: String) : DocBlock
    data class Para(val text: String, val bold: Boolean = false) : DocBlock
    data class Item(val text: String) : DocBlock
    data class Row(val left: String, val right: String) : DocBlock
    data object Rule : DocBlock
    data object Gap : DocBlock
    data object HalfGap : DocBlock
    data object PageBreak : DocBlock
}

data class DocParse(val blocks: List<DocBlock>, val warnings: List<String>)

object DocMarkup {
    const val MAX_CHARS = 30_000
    const val MAX_BLOCKS = 600

    fun parse(raw: String): DocParse {
        val warnings = mutableListOf<String>()
        var src = raw.replace("\r\n", "\n").replace("\r", "\n")
        // Модель иногда присылает «\n» буквами — превращаем в настоящие переводы строк.
        if (!src.contains('\n') && src.contains("\\n")) src = src.replace("\\n", "\n")
        if (src.length > MAX_CHARS) {
            warnings += "Текст обрезан до $MAX_CHARS символов."
            src = src.take(MAX_CHARS)
        }
        val out = mutableListOf<DocBlock>()
        for (lineRaw in src.split('\n')) {
            if (out.size >= MAX_BLOCKS) { warnings += "Слишком много строк, остальное отброшено."; break }
            val line = lineRaw.trimEnd()
            val t = line.trim()
            when {
                t.isEmpty() -> if (out.isNotEmpty() && out.last() !is DocBlock.HalfGap && out.last() !is DocBlock.Gap) out += DocBlock.HalfGap
                t == "---" -> out += DocBlock.Rule
                t == "~~" -> out += DocBlock.Gap
                t == "++" -> out += DocBlock.PageBreak
                t.startsWith(">>") -> out += DocBlock.Head(t.drop(2).trim())
                t.startsWith("==") -> out += DocBlock.Title(t.drop(2).trim())
                t.startsWith("=~") -> out += DocBlock.Center(t.drop(2).trim())
                t.startsWith("<<") -> out += DocBlock.Right(t.drop(2).trim())
                t.startsWith("**") -> out += DocBlock.Para(t.drop(2).trim(), bold = true)
                t.startsWith("||") -> {
                    val parts = t.drop(2).split('|', limit = 2)
                    out += DocBlock.Row(parts[0].trim(), parts.getOrElse(1) { "" }.trim())
                }
                t.startsWith("# ") -> out += DocBlock.Sub(t.drop(2).trim())
                t.startsWith("- ") || t.startsWith("– ") || t.startsWith("— ") -> out += DocBlock.Item(t.drop(2).trim())
                else -> out += DocBlock.Para(t)
            }
        }
        while (out.isNotEmpty() && (out.last() is DocBlock.HalfGap)) out.removeAt(out.lastIndex)
        if (out.none { it !is DocBlock.HalfGap && it !is DocBlock.Gap && it !is DocBlock.PageBreak && it !is DocBlock.Rule }) {
            warnings += "В документе нет текста."
        }
        return DocParse(out, warnings)
    }

    /** Безопасное имя файла: без путей и служебных символов, с нужным расширением. */
    fun fileName(requested: String?, ext: String, fallback: String): String {
        var base = (requested ?: "").trim().substringAfterLast('/').substringAfterLast('\\')
        base = base.removeSuffix(".$ext").removeSuffix(".${ext.uppercase()}")
        base = base.replace(Regex("[^\\p{L}\\p{N} ._()\\-]"), "_").trim(' ', '.', '_').take(60)
        if (base.isBlank()) base = fallback
        return "$base.$ext"
    }
}

// ----------------------------------------------------------------------------------------------- рисунки и пометки

sealed interface Shape {
    data class Canvas(val w: Int, val h: Int) : Shape
    data class Background(val top: Int, val bottom: Int) : Shape
    data class Rect(val x: Float, val y: Float, val w: Float, val h: Float, val color: Int, val fill: Boolean, val round: Float, val alpha: Float = 1f) : Shape
    data class Circle(val x: Float, val y: Float, val r: Float, val color: Int, val fill: Boolean) : Shape
    data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val color: Int, val width: Float, val arrow: Boolean) : Shape
    data class Text(val x: Float, val y: Float, val size: Float, val color: Int, val align: String, val bold: Boolean, val text: String) : Shape
    data class Page(val n: Int) : Shape
    /** Пометка по тексту (для PDF, созданных приложением): style = highlight | underline | box | strike. */
    data class Phrase(val phrase: String, val style: String, val color: Int) : Shape
}

data class ShapeParse(val shapes: List<Shape>, val errors: List<String>)

/**
 * Построчные команды рисования. Координаты, размеры и толщина — в ПРОЦЕНТАХ ширины/высоты холста (0–100).
 *
 *  canvas 1080 1350
 *  bg #0B1220 [#7B5CFA]
 *  rect x y w h цвет [fill|line] [скругление]
 *  highlight x y w h [цвет]
 *  circle x y r цвет [fill|line]
 *  line x1 y1 x2 y2 цвет [толщина]
 *  arrow x1 y1 x2 y2 цвет [толщина]
 *  text x y размер цвет [left|center|right] [bold] | Текст
 *  page N
 *  mark highlight|underline|box|strike [цвет] | фраза
 */
object ShapeMarkup {
    const val MAX_SHAPES = 400

    private val named = mapOf(
        "black" to 0xFF000000.toInt(), "white" to 0xFFFFFFFF.toInt(), "red" to 0xFFE53935.toInt(), "green" to 0xFF2E7D32.toInt(),
        "blue" to 0xFF1E5BFF.toInt(), "yellow" to 0xFFFFEB3B.toInt(), "orange" to 0xFFFF9800.toInt(), "gray" to 0xFF808080.toInt(),
        "grey" to 0xFF808080.toInt(), "purple" to 0xFF7B3FF2.toInt(), "pink" to 0xFFFF2D87.toInt(), "cyan" to 0xFF00BCD4.toInt(),
        "красный" to 0xFFE53935.toInt(), "зелёный" to 0xFF2E7D32.toInt(), "зеленый" to 0xFF2E7D32.toInt(), "синий" to 0xFF1E5BFF.toInt(),
        "жёлтый" to 0xFFFFEB3B.toInt(), "желтый" to 0xFFFFEB3B.toInt(), "чёрный" to 0xFF000000.toInt(), "черный" to 0xFF000000.toInt(),
        "белый" to 0xFFFFFFFF.toInt(), "серый" to 0xFF808080.toInt(), "оранжевый" to 0xFFFF9800.toInt(), "фиолетовый" to 0xFF7B3FF2.toInt(),
        "розовый" to 0xFFFF2D87.toInt(),
    )

    fun color(s: String?): Int? {
        val v = s?.trim()?.lowercase() ?: return null
        named[v]?.let { return it }
        if (!v.startsWith("#")) return null
        val h = v.drop(1)
        return try {
            when (h.length) {
                3 -> (0xFF000000L or (h.map { "$it$it" }.joinToString("").toLong(16))).toInt()
                6 -> (0xFF000000L or h.toLong(16)).toInt()
                8 -> h.toLong(16).toInt()
                else -> null
            }
        } catch (_: NumberFormatException) { null }
    }

    private fun num(s: String?): Float? = s?.replace(',', '.')?.toFloatOrNull()?.takeIf { it.isFinite() }

    fun parse(raw: String): ShapeParse {
        val shapes = mutableListOf<Shape>()
        val errors = mutableListOf<String>()
        var src = raw.replace("\r", "")
        if (!src.contains('\n') && src.contains("\\n")) src = src.replace("\\n", "\n")
        for ((idx, lineRaw) in src.split('\n').withIndex()) {
            val line = lineRaw.trim()
            if (line.isEmpty() || line.startsWith("//")) continue
            if (shapes.size >= MAX_SHAPES) { errors += "Слишком много элементов (максимум $MAX_SHAPES)."; break }
            // Текст идёт после « | », остальное — токены.
            val bar = line.indexOf('|')
            val head = (if (bar >= 0) line.substring(0, bar) else line).trim()
            val tail = if (bar >= 0) line.substring(bar + 1).trim() else ""
            val tk = head.split(Regex("\\s+"))
            val cmd = tk[0].lowercase()
            val err = { m: String -> errors += "Строка ${idx + 1} («${line.take(40)}»): $m" }
            fun n(i: Int) = num(tk.getOrNull(i))
            try {
                when (cmd) {
                    "canvas" -> {
                        val w = n(1)?.toInt(); val h = n(2)?.toInt()
                        if (w == null || h == null || w !in 200..4000 || h !in 200..4000) err("canvas ширина высота, 200–4000") else shapes += Shape.Canvas(w, h)
                    }
                    "bg" -> {
                        val a = color(tk.getOrNull(1)); val b = color(tk.getOrNull(2)) ?: a
                        if (a == null || b == null) err("не понят цвет") else shapes += Shape.Background(a, b)
                    }
                    "rect", "highlight" -> {
                        val x = n(1); val y = n(2); val w = n(3); val h = n(4)
                        if (x == null || y == null || w == null || h == null) { err("нужно x y w h"); continue }
                        if (cmd == "highlight") {
                            val c = color(tk.getOrNull(5)) ?: 0xFFFFEB3B.toInt()
                            shapes += Shape.Rect(x, y, w, h, c, true, 0f, alpha = 0.38f)
                        } else {
                            val c = color(tk.getOrNull(5)); if (c == null) { err("не понят цвет"); continue }
                            val fill = tk.getOrNull(6)?.lowercase() != "line"
                            shapes += Shape.Rect(x, y, w, h, c, fill, n(7) ?: 0f)
                        }
                    }
                    "circle" -> {
                        val x = n(1); val y = n(2); val r = n(3); val c = color(tk.getOrNull(4))
                        if (x == null || y == null || r == null || c == null) err("нужно x y r цвет") else shapes += Shape.Circle(x, y, r, c, tk.getOrNull(5)?.lowercase() != "line")
                    }
                    "line", "arrow" -> {
                        val x1 = n(1); val y1 = n(2); val x2 = n(3); val y2 = n(4); val c = color(tk.getOrNull(5))
                        if (x1 == null || y1 == null || x2 == null || y2 == null || c == null) err("нужно x1 y1 x2 y2 цвет")
                        else shapes += Shape.Line(x1, y1, x2, y2, c, (n(6) ?: 0.4f).coerceIn(0.05f, 10f), cmd == "arrow")
                    }
                    "text" -> {
                        val x = n(1); val y = n(2); val sz = n(3); val c = color(tk.getOrNull(4))
                        if (x == null || y == null || sz == null || c == null || tail.isEmpty()) err("нужно x y размер цвет | текст")
                        else {
                            val rest = tk.drop(5).map { it.lowercase() }
                            shapes += Shape.Text(x, y, sz.coerceIn(0.5f, 40f), c, rest.firstOrNull { it in setOf("left", "center", "right") } ?: "left", "bold" in rest, tail.take(300))
                        }
                    }
                    "page" -> {
                        val p = n(1)?.toInt()
                        if (p == null || p < 1 || p > 200) err("page номер") else shapes += Shape.Page(p)
                    }
                    "mark" -> {
                        val style = tk.getOrNull(1)?.lowercase()
                        if (style !in setOf("highlight", "underline", "box", "strike") || tail.length < 2) err("mark highlight|underline|box|strike [цвет] | фраза")
                        else shapes += Shape.Phrase(tail.take(200), style!!, color(tk.getOrNull(2)) ?: if (style == "highlight") 0xFFFFEB3B.toInt() else 0xFFE53935.toInt())
                    }
                    else -> err("неизвестная команда «$cmd»")
                }
            } catch (_: Exception) { err("не удалось разобрать") }
        }
        return ShapeParse(shapes, errors)
    }
}
