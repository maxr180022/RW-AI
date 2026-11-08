package com.rwai.agent.logic

enum class AutonomyMode(val title: String) {
    /** Читает, ищет, анализирует, готовит черновики. Любое отправляющее действие — только с подтверждением. */
    ASSISTANT("Ассистент"),

    /** Самостоятельно выполняет низкорисковые действия; обычные ответы — по правилу автоотправки. */
    OPERATOR("Оператор"),

    /** Многошаговые задачи с контролем результата. Опасные действия всё равно требуют подтверждения. */
    AUTONOMOUS("Автономный");

    companion object {
        fun fromName(name: String?): AutonomyMode = entries.firstOrNull { it.name == name } ?: ASSISTANT
    }
}

sealed interface PolicyDecision {
    data object Allow : PolicyDecision
    data class Confirm(val title: String, val details: String, val reason: String, val dangerous: Boolean) : PolicyDecision
    data class Deny(val reason: String) : PolicyDecision
}

/** Контекст, нужный политике, чтобы показать пользователю точный текст и адресата перед отправкой. */
data class ActionContext(
    val currentApp: String? = null,
    val lastTypedText: String? = null,
    val lastDraftRecipient: String? = null,
)

/**
 * Матрица подтверждений (ТЗ, раздел 7). Политика работает по тексту элемента, который нажимает агент,
 * поэтому решение не зависит от того, что модель сама заявила о своём действии.
 */
class ConfirmationPolicy(
    private val mode: AutonomyMode,
    private val autoSendEnabled: Boolean,
    private val protectedSecrets: () -> Collection<String> = { emptyList() },
) {
    companion object {
        private fun stems(vararg s: String): Regex =
            Regex(s.joinToString("|") { "(?<![\\p{L}\\p{N}])$it" }, RegexOption.IGNORE_CASE)

        /** Действия, которые может выполнить только сам пользователь. */
        private val userOnly = stems(
            "captcha", "i'?m not a robot", "я не робот", "no soy un robot", "recaptcha",
            "fingerprint", "отпечат", "huella", "face unlock", "face id", "биометр", "biometr",
            "verification code", "код подтверждения", "código de verificación", "enter pin", "введите пин", "ingrese pin",
        )

        /** Платежи, удаление, смена паролей и защитных настроек — всегда подтверждение. */
        private val dangerous = stems(
            "pay(?![\\p{L}\\p{N}])", "pay now", "payment", "buy", "purchase", "checkout", "place order", "order now", "subscribe", "donate",
            "send money", "transfer", "wire(?![\\p{L}\\p{N}])",
            "delete", "remove account", "erase", "factory reset", "wipe", "unsend", "clear all", "clear data", "uninstall",
            "change password", "reset password", "remove screen lock", "turn off", "disable",
            "оплат", "купить", "куплю", "заказать", "оформить заказ", "подписаться", "пожертвова",
            "перевод средств", "денежн", "перевести деньг",
            "удалить", "удаление", "стереть", "очистить", "сброс", "сбросить", "удалён", "отключить",
            "сменить пароль", "изменить пароль", "сбросить пароль",
            "pagar", "comprar", "compra", "transferir", "enviar dinero", "eliminar", "borrar", "cambiar contraseña", "desactivar",
        )

        /** Отправка/публикация — подтверждение, если не включена автоотправка и нет финансово-юридического содержания. */
        private val send = stems(
            "send", "post(?![\\p{L}\\p{N}])", "publish", "tweet",
            "отправ", "послать", "опубликовать",
            "enviar", "publicar",
        )

        /** Финансовые и юридические обязательства в тексте сообщения (ТЗ: «Да» для таких сообщений). */
        private val commitment = Regex(
            "(?<![\\p{L}\\p{N}])(" +
                "\\$|usd(?![\\p{L}\\p{N}])|eur(?![\\p{L}\\p{N}])|euros?(?![\\p{L}\\p{N}])|rub(?![\\p{L}\\p{N}])|uyu(?![\\p{L}\\p{N}])|руб(?![\\p{L}\\p{N}])|рубл|долл|евро(?![\\p{L}\\p{N}])|песо(?![\\p{L}\\p{N}])|песос|pesos?(?![\\p{L}\\p{N}])|dólares?(?![\\p{L}\\p{N}])|" +
                "contract|agreement|sign(?![\\p{L}\\p{N}])|signed(?![\\p{L}\\p{N}])|signature|legal|lawsuit|court(?![\\p{L}\\p{N}])|invoice|payment|pay(?![\\p{L}\\p{N}])|iban|swift|" +
                "договор(?!и|ён|ен)|контракт|подпис|суд(а|е|у|ом|ебн\\p{L}*)?(?![\\p{L}])|иск(?![\\p{L}\\p{N}])|юрист|адвокат|счёт|счет(?![\\p{L}\\p{N}])|оплат|аренд|залог|кредит|" +
                "долг(а|у|е|ом|и)?(?![\\p{L}])|" +
                "contrato|firmar|abogado|juicio|alquiler|deuda|factura|pago" +
                ")",
            RegexOption.IGNORE_CASE,
        )

        fun hasCommitment(text: String?): Boolean = text != null && commitment.containsMatchIn(text)
        fun isDangerousLabel(label: String): Boolean = dangerous.containsMatchIn(label)
        fun isSendLabel(label: String): Boolean = send.containsMatchIn(label)
        fun isUserOnlyLabel(label: String): Boolean = userOnly.containsMatchIn(label)
    }

    /** Приложения, в которых владелец уже разрешил ввод данных профиля в этой задаче. */
    private val profileApproved = HashSet<String>()

    fun noteApproved(call: ToolCall, ctx: ActionContext) {
        if (call.tool == "input_text" && ProfileTemplates.hasPlaceholders(call.str("text").orEmpty())) profileApproved += ctx.currentApp.orEmpty()
    }

    fun evaluate(call: ToolCall, ctx: ActionContext): PolicyDecision {
        return when (call.tool) {
            "tap_text" -> evaluateTap(call.str("text").orEmpty(), ctx)
            "input_text" -> {
                val text = call.str("text").orEmpty()
                if (SecretRedactor.containsSecret(text, protectedSecrets())) {
                    PolicyDecision.Deny("Нельзя вводить в приложения ключи и секреты RW AI.")
                } else if (ProfileTemplates.hasPlaceholders(text) && ctx.currentApp.orEmpty() !in profileApproved) {
                    PolicyDecision.Confirm(
                        title = "Ввести ваши личные данные",
                        details = "В приложении «${ctx.currentApp ?: "?"}» будут введены поля профиля: " + ProfileTemplates.keysIn(text).joinToString(", ") +
                            ". Значения берутся на телефоне и модели не показываются.",
                        reason = "Личные данные вводятся только с вашего разрешения (подтверждение действует для этого приложения до конца задачи).",
                        dangerous = false,
                    )
                } else PolicyDecision.Allow
            }
            "show_draft" -> {
                val text = call.str("text").orEmpty()
                if (SecretRedactor.containsSecret(text, protectedSecrets())) PolicyDecision.Deny("Черновик содержит секрет.") else PolicyDecision.Allow
            }
            "speak" -> {
                val text = call.str("text").orEmpty()
                if (SecretRedactor.containsSecret(text, protectedSecrets())) PolicyDecision.Deny("Нельзя озвучивать секреты.") else PolicyDecision.Allow
            }
            "open_url" -> evaluateUrl(call.str("url").orEmpty())
            "fetch_page" -> {
                val url = call.str("url").orEmpty()
                if (BankGuard.isBankingUrl(url)) PolicyDecision.Deny(BankGuard.REFUSAL)
                else if (SecretRedactor.containsSecret(url, protectedSecrets())) PolicyDecision.Deny("Ссылка содержит секрет.")
                else evaluateUrl(url)
            }
            "ask_claude_code" -> {
                val task = call.str("task").orEmpty()
                if (SecretRedactor.containsSecret(task, protectedSecrets())) PolicyDecision.Deny("Задача содержит секрет.")
                else PolicyDecision.Confirm(
                    title = "Передать задачу Claude Code на компьютере",
                    details = task.take(400),
                    reason = "Задача уйдёт на ваш компьютер и выполнится там по вашей подписке Claude Code.",
                    dangerous = false,
                )
            }
            "web_search" -> {
                val q = call.str("query").orEmpty()
                if (SecretRedactor.containsSecret(q, protectedSecrets())) PolicyDecision.Deny("Запрос содержит секрет.") else PolicyDecision.Allow
            }
            else -> PolicyDecision.Allow
        }
    }

    private fun evaluateUrl(url: String): PolicyDecision {
        val lower = url.trim().lowercase()
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
            return PolicyDecision.Deny("Разрешены только http/https-ссылки.")
        }
        // Длинные ссылки с параметрами могут нести утёкшие данные: показываем пользователю.
        if (url.length > 200 || (url.contains('?') && url.contains('@'))) {
            return PolicyDecision.Confirm(
                title = "Открыть ссылку",
                details = url,
                reason = "Длинная ссылка может содержать передаваемые данные.",
                dangerous = false,
            )
        }
        return PolicyDecision.Allow
    }

    private fun evaluateTap(label: String, ctx: ActionContext): PolicyDecision {
        if (isUserOnlyLabel(label)) {
            return PolicyDecision.Deny("«$label» — это вход, CAPTCHA или биометрия. Такое подтверждает только сам пользователь.")
        }
        if (isDangerousLabel(label)) {
            return PolicyDecision.Confirm(
                title = "Опасное действие: «$label»",
                details = describe(label, ctx, includeText = false),
                reason = "Платежи, удаление и смена защитных настроек всегда требуют подтверждения.",
                dangerous = true,
            )
        }
        if (isSendLabel(label)) {
            val typed = ctx.lastTypedText
            val autoAllowed = autoSendEnabled &&
                mode != AutonomyMode.ASSISTANT &&
                !typed.isNullOrBlank() &&
                !hasCommitment(typed)
            if (autoAllowed) return PolicyDecision.Allow
            val reason = when {
                mode == AutonomyMode.ASSISTANT -> "Режим «Ассистент»: отправка только с подтверждения."
                !autoSendEnabled -> "Автоотправка выключена в настройках."
                typed.isNullOrBlank() -> "Не найден введённый текст сообщения — нужна проверка."
                else -> "Сообщение содержит финансовые или юридические обязательства."
            }
            return PolicyDecision.Confirm(
                title = "Отправить сообщение",
                details = describe(label, ctx, includeText = true),
                reason = reason,
                dangerous = false,
            )
        }
        return PolicyDecision.Allow
    }

    private fun describe(label: String, ctx: ActionContext, includeText: Boolean): String = buildString {
        append("Приложение: ").append(ctx.currentApp ?: "не определено").append('\n')
        if (!ctx.lastDraftRecipient.isNullOrBlank()) append("Получатель: ").append(ctx.lastDraftRecipient).append('\n')
        append("Нажимаемый элемент: «").append(label).append("»\n")
        if (includeText) {
            append("Текст:\n").append(ctx.lastTypedText ?: "(текст не вводился)").append('\n')
            append("Последствия: сообщение будет отправлено адресату и его нельзя будет вернуть.")
        } else {
            append("Последствия: действие может быть необратимым (оплата, удаление или смена настроек).")
        }
    }
}
