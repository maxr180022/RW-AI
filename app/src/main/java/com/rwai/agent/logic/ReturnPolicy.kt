package com.rwai.agent.logic

enum class ReturnAction { NONE, HOME, OWN_APP, ORIGIN_APP }

/**
 * Куда вернуть владельца после задачи. Режим «origin» — туда, откуда он дал команду
 * (другое приложение, главный экран или сам Руся AI); банковские приложения никогда не открываем сами — вместо них главный экран.
 */
object ReturnPolicy {
    /**
     * Откуда владелец дал команду. Если он сидел на экране самого Руся AI, это и есть «откуда», и после задачи возвращаем его в приложение.
     * Раньше брали «последнее чужое приложение», поэтому команда из приложения возвращала на рабочий стол. Панель ассистента и шторка
     * (поверх другого приложения) по-прежнему возвращают в то приложение, которое было под ними.
     */
    fun originFor(mainScreenOpen: Boolean, own: String, detected: String?): String? = if (mainScreenOpen) own else detected

    fun decide(
        mode: String,
        needsAnswer: Boolean,
        spoken: Boolean,
        origin: String?,
        current: String?,
        own: String,
        launcher: String?,
        originIsBanking: Boolean,
    ): ReturnAction {
        if (current == null || current == own) return ReturnAction.NONE
        val m = if (needsAnswer && !spoken) "app" else mode
        return when (m) {
            "stay" -> ReturnAction.NONE
            "home" -> if (current == launcher) ReturnAction.NONE else ReturnAction.HOME
            "app" -> ReturnAction.OWN_APP
            else -> when {
                origin == null || origin == launcher || originIsBanking ->
                    if (current == launcher) ReturnAction.NONE else ReturnAction.HOME
                origin == own -> ReturnAction.OWN_APP
                current == origin -> ReturnAction.NONE
                else -> ReturnAction.ORIGIN_APP
            }
        }
    }
}
