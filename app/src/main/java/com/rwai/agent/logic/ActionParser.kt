package com.rwai.agent.logic

/** Решение модели на очередном шаге. */
sealed interface AgentDecision {
    data class Act(val thought: String, val call: ToolCall) : AgentDecision
    data class Finish(val summary: String, val success: Boolean) : AgentDecision
    data class Ask(val question: String) : AgentDecision
    data class Invalid(val reason: String) : AgentDecision
}

/**
 * Разбор ответа модели. Ожидается ровно один JSON-объект одного из видов:
 *  {"thought":"...","action":{"tool":"...","args":{...}}}
 *  {"finish":{"success":true,"summary":"..."}}
 *  {"ask":"вопрос пользователю"}
 * Модель может обернуть JSON в ```-блок или добавить текст вокруг — это допускается, но выполняется только
 * то, что прошло строгую проверку по [ToolCatalog].
 */
object ActionParser {
    fun parse(raw: String): AgentDecision {
        val cleaned = stripReasoning(raw)
        val candidates = jsonObjectCandidates(cleaned)
        if (candidates.isEmpty()) return AgentDecision.Invalid("В ответе нет JSON-объекта.")
        var lastError = "Не удалось разобрать JSON."
        for (c in candidates) {
            val parsed = try {
                Json.parse(c)
            } catch (e: JsonParseException) {
                lastError = "Некорректный JSON: ${e.message}"
                continue
            }
            val obj = parsed.asJsonMap() ?: continue
            return interpret(obj)
        }
        return AgentDecision.Invalid(lastError)
    }

    private fun interpret(raw: Map<String, Any?>): AgentDecision {
        val obj = normalize(raw)
        val kinds = listOf("action", "finish", "ask").filter { obj.containsKey(it) }
        if (kinds.isEmpty()) return AgentDecision.Invalid("Ожидалось поле action, finish или ask.")
        if (kinds.size > 1) return AgentDecision.Invalid("В ответе должно быть только одно из полей action / finish / ask.")
        return when (kinds[0]) {
            "ask" -> {
                val q = (obj["ask"] as? String)?.trim().orEmpty()
                if (q.isEmpty()) AgentDecision.Invalid("Поле ask должно содержать вопрос строкой.") else AgentDecision.Ask(q.take(1000))
            }
            "finish" -> {
                val f = obj["finish"].asJsonMap() ?: return AgentDecision.Invalid("Поле finish должно быть объектом {success, summary}.")
                val summary = (f["summary"] as? String)?.trim().orEmpty()
                if (summary.isEmpty()) return AgentDecision.Invalid("В finish.summary нужен текст отчёта.")
                val success = f["success"] as? Boolean ?: return AgentDecision.Invalid("В finish.success нужно true или false.")
                AgentDecision.Finish(summary.take(4000), success)
            }
            else -> {
                val a = obj["action"].asJsonMap() ?: return AgentDecision.Invalid("Поле action должно быть объектом {tool, args}.")
                val tool = (a["tool"] as? String)?.trim().orEmpty()
                if (tool.isEmpty()) return AgentDecision.Invalid("В action.tool нужно имя инструмента.")
                val rawArgs = when (val x = a["args"]) {
                    null -> emptyMap()
                    else -> x.asJsonMap() ?: return AgentDecision.Invalid("action.args должен быть объектом.")
                }
                val (call, err) = ToolCatalog.validate(tool, rawArgs)
                if (call == null) AgentDecision.Invalid(err ?: "Некорректное действие.")
                else AgentDecision.Act((obj["thought"] as? String).orEmpty().take(1000), call)
            }
        }
    }

    /**
     * Модель часто «сплющивает» ответ: {"tool":"x","args":{}}, {"action":"x","args":{}} или стиль вызова функций ({"name":"x","arguments":"{...}"}).
     * Приводим это к строгому виду {"action":{"tool","args"}}; дальше проверка по каталогу инструментов та же, ничего лишнего выполнить нельзя.
     */
    private fun normalize(o: Map<String, Any?>): Map<String, Any?> {
        if (o.containsKey("finish") || o.containsKey("ask")) return o
        val act = o["action"]
        if (act is Map<*, *>) return o
        val tool = (act as? String) ?: (o["tool"] as? String) ?: (o["tool_name"] as? String) ?: (o["name"] as? String)
        if (tool.isNullOrBlank()) return o
        val rawArgs = o["args"] ?: o["arguments"] ?: o["parameters"] ?: o["input"]
        val args: Any? = if (rawArgs is String) { try { Json.parse(rawArgs) } catch (e: JsonParseException) { null } } else rawArgs
        val drop = setOf("action", "tool", "tool_name", "name", "args", "arguments", "parameters", "input")
        val rest = o.filterKeys { it !in drop }
        return rest + mapOf("action" to mapOf("tool" to tool.trim(), "args" to (args ?: emptyMap<String, Any?>())))
    }

    /** Удаляет блоки рассуждений и ограды кода, чтобы JSON искался в «чистом» тексте. */
    private fun stripReasoning(raw: String): String {
        var s = raw.replace(Regex("<think>.*?</think>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
        s = s.replace(Regex("```[a-zA-Z]*"), "")
        return s.trim()
    }

    /** Все сбалансированные по скобкам фрагменты, начинающиеся с '{' (с учётом строк и экранирования). */
    internal fun jsonObjectCandidates(text: String): List<String> {
        val result = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            if (text[i] == '{') {
                val end = findMatchingBrace(text, i)
                if (end > i) {
                    result.add(text.substring(i, end + 1))
                    i = end + 1
                    continue
                }
            }
            i++
        }
        return result
    }

    private fun findMatchingBrace(text: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (j in start until text.length) {
            val c = text[j]
            if (inString) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) return j }
                }
            }
        }
        return -1
    }
}
