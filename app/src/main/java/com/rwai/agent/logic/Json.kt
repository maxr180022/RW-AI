package com.rwai.agent.logic

/**
 * Минимальный JSON-парсер/сериализатор на чистом Kotlin (без зависимостей от Android и сторонних библиотек).
 * Нужен, чтобы вся логика разбора ответов модели проверялась обычными JVM-тестами.
 *
 * Типы значений: Map<String, Any?>, List<Any?>, String, Long, Double, Boolean, null.
 */
class JsonParseException(message: String) : Exception(message)

object Json {
    private const val MAX_DEPTH = 64

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.readValue(0)
        p.skipWs()
        if (!p.atEnd()) throw JsonParseException("Лишние данные после JSON в позиции ${p.pos}")
        return v
    }

    fun stringify(value: Any?): String {
        val sb = StringBuilder()
        write(sb, value)
        return sb.toString()
    }

    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        writeString(sb, s)
        return sb.toString()
    }

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> writeString(sb, v)
            is Boolean -> sb.append(v.toString())
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Double -> if (v.isNaN() || v.isInfinite()) sb.append("null") else sb.append(v.toString())
            is Float -> if (v.isNaN() || v.isInfinite()) sb.append("null") else sb.append(v.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k.toString())
                    sb.append(':')
                    write(sb, value)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, item)
                }
                sb.append(']')
            }
            else -> writeString(sb, v.toString())
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u").append(c.code.toString(16).padStart(4, '0')) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(private val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun readValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw JsonParseException("Слишком глубокая вложенность JSON")
            skipWs()
            if (atEnd()) throw JsonParseException("Неожиданный конец JSON")
            return when (val c = s[pos]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else throw JsonParseException("Неожиданный символ '$c' в позиции $pos")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, pos)) throw JsonParseException("Ожидалось '$word' в позиции $pos")
            pos += word.length
            return value
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            pos++ // {
            skipWs()
            if (!atEnd() && s[pos] == '}') { pos++; return map }
            while (true) {
                skipWs()
                if (atEnd() || s[pos] != '"') throw JsonParseException("Ожидалось имя поля в позиции $pos")
                val key = readString()
                skipWs()
                if (atEnd() || s[pos] != ':') throw JsonParseException("Ожидалось ':' в позиции $pos")
                pos++
                map[key] = readValue(depth + 1)
                skipWs()
                if (atEnd()) throw JsonParseException("Неожиданный конец объекта")
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return map }
                    else -> throw JsonParseException("Ожидалось ',' или '}' в позиции $pos")
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            val list = ArrayList<Any?>()
            pos++ // [
            skipWs()
            if (!atEnd() && s[pos] == ']') { pos++; return list }
            while (true) {
                list.add(readValue(depth + 1))
                skipWs()
                if (atEnd()) throw JsonParseException("Неожиданный конец массива")
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return list }
                    else -> throw JsonParseException("Ожидалось ',' или ']' в позиции $pos")
                }
            }
        }

        private fun readString(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonParseException("Незакрытая строка")
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) throw JsonParseException("Незавершённая escape-последовательность")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw JsonParseException("Некорректный \\u")
                                val hex = s.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16) ?: throw JsonParseException("Некорректный \\u$hex")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> throw JsonParseException("Неизвестная escape-последовательность \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            while (!atEnd() && s[pos].isDigit()) pos++
            var isDouble = false
            if (!atEnd() && s[pos] == '.') {
                isDouble = true
                pos++
                while (!atEnd() && s[pos].isDigit()) pos++
            }
            if (!atEnd() && (s[pos] == 'e' || s[pos] == 'E')) {
                isDouble = true
                pos++
                if (!atEnd() && (s[pos] == '+' || s[pos] == '-')) pos++
                while (!atEnd() && s[pos].isDigit()) pos++
            }
            val token = s.substring(start, pos)
            return if (isDouble) token.toDoubleOrNull() ?: throw JsonParseException("Некорректное число '$token'")
            else token.toLongOrNull() ?: token.toDoubleOrNull() ?: throw JsonParseException("Некорректное число '$token'")
        }
    }
}

@Suppress("UNCHECKED_CAST")
fun Any?.asJsonMap(): Map<String, Any?>? = this as? Map<String, Any?>

fun Any?.asJsonList(): List<Any?>? = this as? List<Any?>
