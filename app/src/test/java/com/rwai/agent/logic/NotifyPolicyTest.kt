package com.rwai.agent.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Уведомления не нужны, пока владелец сам внутри приложения. */
class NotifyPolicyTest {
    @Test fun noResultOrConfirmationPushWhileTheOwnerIsInsideTheApp() {
        assertFalse(NotifyPolicy.shouldShow(NotifyKind.RESULT, appInForeground = true))
        assertFalse(NotifyPolicy.shouldShow(NotifyKind.CONFIRMATION, appInForeground = true))
    }

    @Test fun pushesComeAsBeforeWhenTheAppIsMinimized() {
        assertTrue(NotifyPolicy.shouldShow(NotifyKind.RESULT, appInForeground = false))
        assertTrue(NotifyPolicy.shouldShow(NotifyKind.CONFIRMATION, appInForeground = false))
    }

    @Test fun theQuickReplyShadeEntryIsAlwaysThere() {
        assertTrue(NotifyPolicy.shouldShow(NotifyKind.QUICK_REPLY, appInForeground = true))
        assertTrue(NotifyPolicy.shouldShow(NotifyKind.QUICK_REPLY, appInForeground = false))
    }
}
