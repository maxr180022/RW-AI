package com.rwai.agent.logic

enum class Complexity { SIMPLE, COMPLEX }

/** Какая модель и в каком режиме ведёт задачу. */
data class ModelProfile(val model: String, val thinking: Boolean, val complexity: Complexity)

/**
 * Автовыбор модели (требование владельца): точность прежде всего. Простая задача (открыть, показать, найти, что нового) идёт на лёгкой
 * быстрой модели без рассуждения, а всё остальное, включая любую неясность, на выбранной владельцем модели с рассуждением.
 * Сложной всегда считается: ответ человеку, любая отправка, документ, юридическое, деньги, несколько шагов, фото, длинная формулировка.
 */
object TaskComplexity {
    private const val SIMPLE_MAX_CHARS = 100
    private const val COMPLEX_MIN_CHARS = 160

    // Границы слов: в Java \b не работает с кириллицей, поэтому явные lookaround.
    private const val L = "(?<![\\p{L}])"
    private const val R = "(?![\\p{L}])"

    private val complexWords = Regex(
        "(?iu)(" +
            "составь|заявлен|претензи|договор|юрист|жалоб|напиши|напис|ответь|отправь|перешли|перепиши|переведи|подготов|проанализ|" +
            "разбер|сравни|спланир|запланир|оплат|деньг|банк|календар|удали|настро|реши|подбери|объясни|предложи|черновик|документ|" +
            "письм|e-?mail|затем|потом|после этого|что делать|почему|зачем|" +
            L + "иск" + R + "|" + L + "суд" + R + "|" + L + "pdf" + R +
            ")",
    )

    private val simpleStart = Regex(
        "(?iu)^\\s*(@\\S+\\s+)?(" +
            "открой|запусти|покажи|найди|что\\s+нов|есть\\s+ли|есть\\s+у\\s+меня|сколько|какая\\s+(сегодня\\s+)?погода|который\\s+час|" +
            "включи|выключи|пауза|стоп|следующ|прочитай|зачитай|скажи\\s+(время|дату)|заряд|батаре|громкост" +
            ")",
    )

    fun classify(task: String, hasPhotos: Boolean = false): Complexity {
        val t = task.trim()
        if (hasPhotos || t.length > COMPLEX_MIN_CHARS) return Complexity.COMPLEX
        if (complexWords.containsMatchIn(t)) return Complexity.COMPLEX
        if (t.count { it == ',' || it == ';' } >= 2) return Complexity.COMPLEX // несколько действий в одной фразе
        return if (t.length <= SIMPLE_MAX_CHARS && simpleStart.containsMatchIn(t)) Complexity.SIMPLE else Complexity.COMPLEX
    }

    /**
     * Профиль модели. Автовыбор выключен — как раньше: всё на выбранной модели с выбранным режимом.
     * Включён: простая задача — лёгкая модель без рассуждения, сложная — выбранная модель и выбранный режим рассуждения.
     */
    fun profile(c: Complexity, auto: Boolean, strongModel: String, strongThinking: Boolean, lightModel: String): ModelProfile = when {
        !auto -> ModelProfile(strongModel, strongThinking, c)
        c == Complexity.SIMPLE -> ModelProfile(lightModel, false, c)
        else -> ModelProfile(strongModel, strongThinking, c)
    }

    /** Лёгкая модель не справилась на самом старте (ошибка формата, сбой, ноль-два шага): безопасно повторить на точной. */
    fun shouldEscalate(profile: ModelProfile, auto: Boolean, failed: Boolean, steps: Int): Boolean =
        auto && failed && profile.complexity == Complexity.SIMPLE && steps <= 2
}
