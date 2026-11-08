package com.rwai.agent.logic

enum class NotifyKind { RESULT, CONFIRMATION, QUICK_REPLY }

/**
 * Когда слать уведомления. Пока владелец сам сидит в приложении, уведомления с ответами и подтверждениями не нужны:
 * всё то же видно в чате. Когда приложение свёрнуто (например, агент ушёл в другое приложение), уведомления приходят как раньше.
 * Постоянная плашка «Написать Руся AI» — не ответ, а вход в приложение: она остаётся всегда.
 */
object NotifyPolicy {
    fun shouldShow(kind: NotifyKind, appInForeground: Boolean): Boolean = kind == NotifyKind.QUICK_REPLY || !appInForeground
}
