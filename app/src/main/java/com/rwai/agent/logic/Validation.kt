package com.rwai.agent.logic

/** Проверка пользовательского адреса API: только https, без логина/пароля в адресе, без пробелов. */
object Endpoint {
    fun normalize(input: String): String? {
        val s = input.trim().trimEnd('/')
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        val m = Regex("^https://([A-Za-z0-9.-]+)(:[0-9]{1,5})?(/[A-Za-z0-9._~/%-]*)?$").matchEntire(s) ?: return null
        val host = m.groupValues[1]
        if (!host.contains('.') || host.startsWith('.') || host.endsWith('.') || host.contains("..")) return null
        return s
    }
}

/** Маскирование одноразовых кодов, PIN и паролей в тексте уведомлений перед сохранением. */
object SensitiveMasker {
    private val keyword = Regex(
        "(code|pin|otp|password|passcode|код|пин|парол|контрольн|подтвержден|c[oó]digo|contrase[nñ]a|clave)" +
            "([^0-9\\n]{0,30})([0-9][0-9 \\-]{2,10}[0-9])",
        RegexOption.IGNORE_CASE,
    )
    private val trailing = Regex("([0-9][0-9 \\-]{2,10}[0-9])([^0-9\\n]{0,25})(is your|— ваш|ваш код|es tu|your (verification|login|security)? ?code)", RegexOption.IGNORE_CASE)

    fun mask(text: String): String {
        var s = keyword.replace(text) { m -> m.groupValues[1] + m.groupValues[2] + "••••" }
        s = trailing.replace(s) { m -> "••••" + m.groupValues[2] + m.groupValues[3] }
        return s
    }
}

enum class LabelRisk { NORMAL, SEND, DANGEROUS, USER_ONLY }

/** Классификация подписи элемента — единая для политики и для исполнителя (проверка фактически найденного элемента). */
object LabelClassifier {
    fun classify(label: String): LabelRisk = when {
        ConfirmationPolicy.isUserOnlyLabel(label) -> LabelRisk.USER_ONLY
        ConfirmationPolicy.isDangerousLabel(label) -> LabelRisk.DANGEROUS
        ConfirmationPolicy.isSendLabel(label) -> LabelRisk.SEND
        else -> LabelRisk.NORMAL
    }

    /**
     * Нажатие допустимо, только если фактически найденный элемент не опаснее запроса модели:
     * иначе модель могла «протащить» кнопку отправки/оплаты под безобидным запросом.
     */
    fun isEscalation(query: String, actualLabel: String): Boolean = classify(actualLabel).ordinal > classify(query).ordinal
}

/**
 * Безопасное построение запроса FTS: только токены из букв/цифр в нижнем регистре (операторы FTS пишутся
 * заглавными, поэтому не срабатывают) и префиксный поиск. Для русских слов окончания грубо отбрасываются
 * («аренда» → «арен*» найдёт «аренду», «аренды»). Это поиск по словам, а не семантический поиск.
 */
object FtsQuery {
    private val cyrillic = Regex("^[а-яё]+$")

    internal fun stem(token: String): String = when {
        !cyrillic.matches(token) -> token
        token.length >= 6 -> token.dropLast(2)
        token.length == 5 -> token.dropLast(1)
        else -> token
    }

    fun build(q: String): String? {
        val tokens = Regex("[\\p{L}\\p{N}]+").findAll(UiMatcher.normalize(q)).map { stem(it.value) }.take(8).toList()
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { "$it*" }
    }
}
