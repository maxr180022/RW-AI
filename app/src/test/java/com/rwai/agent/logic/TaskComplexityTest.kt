package com.rwai.agent.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Автовыбор модели: простое — быстро и легко, сложное и всё неясное — точно. */
class TaskComplexityTest {
    private fun c(t: String, photos: Boolean = false) = TaskComplexity.classify(t, photos)

    @Test fun clearLookupsAreSimple() {
        listOf(
            "Открой Gmail", "Что нового в почте?", "@почта что нового?", "Покажи уведомления", "Какая погода?", "Включи музыку",
            "Сколько заряд батареи", "Найди в памяти сообщения про аренду", "Есть у меня непрочитанные сообщения?", "Громкость на максимум",
        ).forEach { assertEquals(it, Complexity.SIMPLE, c(it)) }
    }

    @Test fun repliesSendingDocumentsLegalAndMoneyAreComplex() {
        listOf(
            "Составь заявление в суд в PDF", "Ответь Ивану в Telegram: буду в пять", "Отправь сообщение taisiialook в Instagram ровно этим текстом",
            "Разберись со срочным сообщением: проверь аккаунт", "Перепиши черновик короче", "Предложи, как ответить", "Подготовь претензию по договору",
            "Оплати счёт", "Добавь в календарь встречу в пятницу", "Почему не пришло письмо", "Переведи это на испанский",
        ).forEach { assertEquals(it, Complexity.COMPLEX, c(it)) }
    }

    @Test fun lookupWithAnActionIsComplex() {
        // начинается как поиск, но дальше действие с последствиями
        assertEquals(Complexity.COMPLEX, c("Найди письмо от банка и перешли его Ивану"))
        assertEquals(Complexity.COMPLEX, c("Открой почту, найди письмо, ответь на него"))
        assertEquals(Complexity.COMPLEX, c("Открой Gmail, открой Telegram, открой Instagram"))
    }

    @Test fun photosLongTextAndUnknownPhrasesAreComplex() {
        assertEquals(Complexity.COMPLEX, c("Покажи, что на фото", photos = true))
        assertEquals(Complexity.COMPLEX, c("Покажи " + "очень ".repeat(40) + "подробно"))
        assertEquals(Complexity.COMPLEX, c("Сделай что-нибудь полезное")) // неясно — точная модель
        assertEquals(Complexity.COMPLEX, c(""))
    }

    @Test fun wordBoundariesWorkWithCyrillic() {
        // «иск» и «суд» как отдельные слова — сложное; внутри других слов — нет
        assertEquals(Complexity.COMPLEX, c("Подай иск"))
        assertEquals(Complexity.SIMPLE, c("Открой камеру"))
        assertEquals(Complexity.SIMPLE, c("Покажи, какой риск у приложений")) // «риск» не «иск»
    }

    @Test fun simpleTaskGetsLightModelWithoutThinking() {
        val p = TaskComplexity.profile(Complexity.SIMPLE, true, "deepseek-v4-pro", true, "deepseek-flash")
        assertEquals(ModelProfile("deepseek-flash", false, Complexity.SIMPLE), p)
    }

    @Test fun complexTaskGetsChosenModelWithChosenThinking() {
        assertEquals(ModelProfile("deepseek-v4-pro", true, Complexity.COMPLEX), TaskComplexity.profile(Complexity.COMPLEX, true, "deepseek-v4-pro", true, "deepseek-flash"))
        // владелец отключил рассуждение сам — это уважается и для сложных
        assertFalse(TaskComplexity.profile(Complexity.COMPLEX, true, "deepseek-flash", false, "deepseek-flash").thinking)
    }

    @Test fun withAutoOffEverythingGoesToTheChosenModelAsBefore() {
        val p = TaskComplexity.profile(Complexity.SIMPLE, false, "deepseek-v4-pro", true, "deepseek-flash")
        assertEquals(ModelProfile("deepseek-v4-pro", true, Complexity.SIMPLE), p)
    }

    @Test fun escalationOnlyForEarlyFailureOfALightRun() {
        val light = ModelProfile("deepseek-flash", false, Complexity.SIMPLE)
        val strong = ModelProfile("deepseek-v4-pro", true, Complexity.COMPLEX)
        assertTrue(TaskComplexity.shouldEscalate(light, true, failed = true, steps = 0))
        assertTrue(TaskComplexity.shouldEscalate(light, true, failed = true, steps = 2))
        assertFalse(TaskComplexity.shouldEscalate(light, true, failed = true, steps = 3)) // уже что-то делал: повтор мог бы продублировать действия
        assertFalse(TaskComplexity.shouldEscalate(light, true, failed = false, steps = 0))
        assertFalse(TaskComplexity.shouldEscalate(strong, true, failed = true, steps = 0))
        assertFalse(TaskComplexity.shouldEscalate(light, false, failed = true, steps = 0))
    }
}
