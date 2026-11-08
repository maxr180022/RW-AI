package com.rwai.agent.logic

/** Базовый уровень риска инструмента. Итоговое решение о подтверждении принимает [ConfirmationPolicy]. */
enum class Risk { SAFE, SENSITIVE, DANGEROUS }

enum class ArgType { STRING, INT, BOOL }

data class ArgSpec(
    val name: String,
    val type: ArgType,
    val description: String,
    val required: Boolean = true,
    val maxLen: Int = 2000,
    val allowed: List<String>? = null,
    val min: Long? = null,
    val max: Long? = null,
)

data class ToolSpec(
    val name: String,
    val description: String,
    val args: List<ArgSpec> = emptyList(),
    val risk: Risk = Risk.SAFE,
    val timeoutMs: Long = 15_000,
    /** Результат содержит данные из внешних источников (экран, уведомления, память) и должен считаться недоверенным. */
    val untrustedOutput: Boolean = false,
)

data class ToolCall(val tool: String, val args: Map<String, Any?> = emptyMap()) {
    fun str(name: String): String? = args[name] as? String
    fun int(name: String): Int? = (args[name] as? Long)?.toInt()
    fun bool(name: String): Boolean? = args[name] as? Boolean

    /** Каноническая запись для журнала и обнаружения повторов (без секретов — их сюда не передают). */
    fun canonical(): String = tool + Json.stringify(args)
}

enum class ToolStatus { OK, FAILED, UNAVAILABLE, TIMEOUT, DENIED, DECLINED }

data class ToolResult(
    val status: ToolStatus,
    val message: String,
    /** true — message содержит недоверенные данные (текст с экрана/уведомления/память) */
    val untrusted: Boolean = false,
) {
    companion object {
        fun ok(message: String, untrusted: Boolean = false) = ToolResult(ToolStatus.OK, message, untrusted)
        fun failed(message: String) = ToolResult(ToolStatus.FAILED, message)
        fun unavailable(message: String) = ToolResult(ToolStatus.UNAVAILABLE, message)
    }
}

/** Закрытый список инструментов. Модель не может вызвать ничего, чего нет в этом каталоге. */
object ToolCatalog {
    val all: List<ToolSpec> = listOf(
        ToolSpec(
            name = "launch_app",
            description = "Открыть установленное приложение по названию (Gmail, WhatsApp, Telegram, Instagram, Chrome, Настройки, Календарь, Контакты...).",
            args = listOf(ArgSpec("app", ArgType.STRING, "Название приложения или имя пакета", maxLen = 120)),
            timeoutMs = 10_000,
        ),
        ToolSpec(
            name = "open_url",
            description = "Открыть http/https-ссылку в браузере.",
            args = listOf(ArgSpec("url", ArgType.STRING, "Адрес, начинающийся с https:// или http://", maxLen = 1000)),
        ),
        ToolSpec(
            name = "inspect_screen",
            description = "Получить список видимых элементов текущего экрана (текст, описание, кликабельность). Вызывай после каждого перехода, чтобы проверить результат.",
            timeoutMs = 8_000,
            untrustedOutput = true,
        ),
        ToolSpec(
            name = "find_text",
            description = "Найти на текущем экране элементы, содержащие текст.",
            args = listOf(ArgSpec("query", ArgType.STRING, "Искомый текст", maxLen = 200)),
            timeoutMs = 8_000,
            untrustedOutput = true,
        ),
        ToolSpec(
            name = "tap_text",
            description = "Нажать на элемент экрана с указанным текстом или описанием. Если совпадений несколько, используй index (0 — лучшее совпадение).",
            args = listOf(
                ArgSpec("text", ArgType.STRING, "Текст или описание элемента", maxLen = 200),
                ArgSpec("index", ArgType.INT, "Номер совпадения, по умолчанию 0", required = false, min = 0, max = 20),
            ),
            timeoutMs = 8_000,
        ),
        ToolSpec(
            name = "scroll",
            description = "Прокрутить текущий экран.",
            args = listOf(ArgSpec("direction", ArgType.STRING, "up или down", allowed = listOf("up", "down"))),
            timeoutMs = 6_000,
        ),
        ToolSpec(
            name = "input_text",
            description = "Ввести текст в активное (или единственное) поле ввода на экране. Не отправляет сообщение.",
            args = listOf(ArgSpec("text", ArgType.STRING, "Вводимый текст", maxLen = 4000)),
            timeoutMs = 8_000,
        ),
        ToolSpec(name = "press_back", description = "Нажать системную кнопку «Назад».", timeoutMs = 5_000),
        ToolSpec(
            name = "wait",
            description = "Подождать, пока обновится экран.",
            args = listOf(ArgSpec("ms", ArgType.INT, "Миллисекунды (200–5000)", min = 200, max = 5000)),
            timeoutMs = 7_000,
        ),
        ToolSpec(
            name = "read_notifications",
            description = "Прочитать последние сохранённые уведомления (источник, время, отправитель, текст).",
            args = listOf(
                ArgSpec("limit", ArgType.INT, "Сколько записей (1–50)", required = false, min = 1, max = 50),
                ArgSpec("app", ArgType.STRING, "Фильтр по названию приложения", required = false, maxLen = 120),
            ),
            untrustedOutput = true,
        ),
        ToolSpec(
            name = "search_memory",
            description = "Полнотекстовый поиск по локальной памяти (сохранённые уведомления и выдержки переписок).",
            args = listOf(
                ArgSpec("query", ArgType.STRING, "Слова для поиска", maxLen = 200),
                ArgSpec("app", ArgType.STRING, "Фильтр по приложению", required = false, maxLen = 120),
                ArgSpec("limit", ArgType.INT, "Сколько записей (1–30)", required = false, min = 1, max = 30),
            ),
            untrustedOutput = true,
        ),
        ToolSpec(
            name = "save_excerpt",
            description = "Сохранить выдержку из прочитанной переписки в локальную память для последующего поиска.",
            args = listOf(
                ArgSpec("title", ArgType.STRING, "Краткий заголовок", maxLen = 200),
                ArgSpec("text", ArgType.STRING, "Текст выдержки", maxLen = 4000),
                ArgSpec("app", ArgType.STRING, "Источник (приложение)", required = false, maxLen = 120),
                ArgSpec("contact", ArgType.STRING, "Собеседник", required = false, maxLen = 120),
            ),
        ),
        ToolSpec(
            name = "show_draft",
            description = "Показать пользователю черновик ответа (ничего не отправляет). Вызывай перед вводом и отправкой текста.",
            args = listOf(
                ArgSpec("recipient", ArgType.STRING, "Кому", maxLen = 200),
                ArgSpec("app", ArgType.STRING, "В каком приложении", maxLen = 120),
                ArgSpec("text", ArgType.STRING, "Полный текст черновика", maxLen = 4000),
            ),
        ),
        ToolSpec(
            name = "speak",
            description = "Озвучить короткий текст вслух.",
            args = listOf(ArgSpec("text", ArgType.STRING, "Что произнести", maxLen = 600)),
        ),
    )

    private val byName = all.associateBy { it.name }

    fun find(name: String): ToolSpec? = byName[name]

    /**
     * Проверка вызова и нормализация аргументов. Возвращает либо нормализованный [ToolCall], либо текст ошибки.
     */
    fun validate(tool: String, rawArgs: Map<String, Any?>): Pair<ToolCall?, String?> {
        val spec = find(tool) ?: return null to "Неизвестный инструмент '$tool'. Доступны: ${all.joinToString { it.name }}"
        val normalized = LinkedHashMap<String, Any?>()
        for (key in rawArgs.keys) {
            if (spec.args.none { it.name == key }) return null to "Лишний аргумент '$key' у инструмента '$tool'"
        }
        for (a in spec.args) {
            val raw = rawArgs[a.name]
            if (raw == null) {
                if (a.required) return null to "Не указан обязательный аргумент '${a.name}' инструмента '$tool'"
                continue
            }
            when (a.type) {
                ArgType.STRING -> {
                    val s = raw as? String ?: return null to "Аргумент '${a.name}' должен быть строкой"
                    if (s.length > a.maxLen) return null to "Аргумент '${a.name}' длиннее ${a.maxLen} символов"
                    if (a.required && s.isBlank()) return null to "Аргумент '${a.name}' пустой"
                    if (a.allowed != null && s !in a.allowed) return null to "Аргумент '${a.name}' должен быть одним из: ${a.allowed.joinToString()}"
                    normalized[a.name] = s
                }
                ArgType.INT -> {
                    val n: Long = when (raw) {
                        is Long -> raw
                        is Double -> if (raw == Math.floor(raw) && !raw.isInfinite()) raw.toLong() else return null to "Аргумент '${a.name}' должен быть целым числом"
                        is String -> raw.trim().toLongOrNull() ?: return null to "Аргумент '${a.name}' должен быть целым числом"
                        else -> return null to "Аргумент '${a.name}' должен быть целым числом"
                    }
                    if (a.min != null && n < a.min) return null to "Аргумент '${a.name}' меньше ${a.min}"
                    if (a.max != null && n > a.max) return null to "Аргумент '${a.name}' больше ${a.max}"
                    normalized[a.name] = n
                }
                ArgType.BOOL -> {
                    val b = when (raw) {
                        is Boolean -> raw
                        is String -> raw.lowercase().toBooleanStrictOrNull() ?: return null to "Аргумент '${a.name}' должен быть true/false"
                        else -> return null to "Аргумент '${a.name}' должен быть true/false"
                    }
                    normalized[a.name] = b
                }
            }
        }
        return ToolCall(tool, normalized) to null
    }

    /** Описание каталога для системного промпта. */
    fun describeForPrompt(): String = buildString {
        for (t in all) {
            append("- ").append(t.name).append('(')
            append(t.args.joinToString(", ") { a -> a.name + (if (a.required) "" else "?") + ": " + a.type.name.lowercase() })
            append(") — ").append(t.description).append('\n')
        }
    }
}
