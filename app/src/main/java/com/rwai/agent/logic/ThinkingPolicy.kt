package com.rwai.agent.logic

/**
 * Когда у агента включать рассуждение. Оно дорогое по токенам и нужно на планировании и при ошибках, а не на механических шагах («нажать», «прокрутить»).
 * Включено на первых [FIRST_STEPS] запросах задачи и на любом запросе сразу после ошибки, отказа, тайм-аута, зацикливания или перед завершением.
 */
object ThinkingPolicy {
    const val FIRST_STEPS = 3

    fun shouldThink(profileThinking: Boolean, callNumber: Int, lastUserMessage: String): Boolean {
        if (!profileThinking) return false
        if (callNumber <= FIRST_STEPS) return true
        val l = lastUserMessage
        return l.startsWith("ОШИБКА ФОРМАТА") || l.contains("status=FAILED") || l.contains("status=DENIED") || l.contains("status=TIMEOUT") ||
            l.startsWith("Это же действие уже повторялось") || l.startsWith("Прежде чем завершить")
    }
}
