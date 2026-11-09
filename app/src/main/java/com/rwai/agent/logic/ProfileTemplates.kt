package com.rwai.agent.logic

/**
 * Личные данные владельца (профиль) хранятся только на телефоне. Облачная модель видит лишь названия полей:
 * в команде она пишет {{profile.email}}, а настоящее значение подставляется на телефоне при вводе.
 * Обратная замена не даёт значениям уйти к модели с экрана: на выходе вместо значения снова стоит плейсхолдер.
 */
object ProfileTemplates {
    private val token = Regex("\\{\\{\\s*profile\\.([a-z0-9_]{1,40})\\s*\\}\\}", RegexOption.IGNORE_CASE)
    private val keyRegex = Regex("[a-z][a-z0-9_]{0,39}")

    data class Expanded(val text: String, val missing: List<String>)

    fun hasPlaceholders(text: String): Boolean = token.containsMatchIn(text)

    fun keysIn(text: String): List<String> = token.findAll(text).map { it.groupValues[1].lowercase() }.distinct().toList()

    fun expand(text: String, values: Map<String, String>): Expanded {
        val missing = mutableListOf<String>()
        val out = token.replace(text) { m ->
            val key = m.groupValues[1].lowercase()
            val v = values[key]
            if (v.isNullOrEmpty()) { missing += key; m.value } else v
        }
        return Expanded(out, missing.distinct())
    }

    /** Заменяет значения профиля (от 4 символов) на плейсхолдеры — перед тем, как текст уходит модели или в журнал. */
    /** Имена создателей приложения: это не личные данные владельца, модель видит их как есть (иначе в памяти диалога появляются заглушки вместо имени). */
    private val publicNames = Regex("Maxim Ricco|Максим Рикко|Максим Рико|RusWeo|РусВео", RegexOption.IGNORE_CASE)
    private val holdToken = Regex("\uE000(\\d+)\uE001")

    fun redact(text: String, values: Map<String, String>): String {
        val hold = ArrayList<String>()
        var s = publicNames.replace(text) { m -> hold.add(m.value); "\uE000" + (hold.size - 1) + "\uE001" }
        values.entries.filter { it.value.length >= 4 }.sortedByDescending { it.value.length }.forEach { (k, v) ->
            s = s.replace(v, "{{profile.$k}}")
        }
        if (hold.isEmpty()) return s
        return holdToken.replace(s) { m -> hold[m.groupValues[1].toInt()] }
    }

    fun isValidKey(key: String): Boolean = keyRegex.matches(key)

    /** Номера банковских карт в профиль не принимаются (13–19 цифр, проверка Луна). */
    fun looksLikeCard(value: String): Boolean {
        if (value.any { it.isLetter() }) return false
        val digits = value.filter { it.isDigit() }
        if (digits.length !in 13..19) return false
        var sum = 0
        var alt = false
        for (i in digits.length - 1 downTo 0) {
            var n = digits[i] - '0'
            if (alt) { n *= 2; if (n > 9) n -= 9 }
            sum += n
            alt = !alt
        }
        return sum % 10 == 0
    }
}
