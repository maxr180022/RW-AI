package com.rwai.agent.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Профиль скорости: рассуждение выключается один раз, история запроса короче, выбор владельца потом сохраняется. */
class SpeedProfileTest {
    @Test fun thinkingIsSwitchedOffOnceOnAlreadyConfiguredPhones() {
        assertFalse(SpeedProfile.thinkingAfterMigration(alreadyMigrated = false, saved = true))
        assertFalse(SpeedProfile.thinkingAfterMigration(alreadyMigrated = false, saved = false))
    }

    @Test fun ownersLaterChoiceIsKept() {
        // после миграции владелец включил рассуждение обратно — оно остаётся включённым
        assertTrue(SpeedProfile.thinkingAfterMigration(alreadyMigrated = true, saved = true))
        assertFalse(SpeedProfile.thinkingAfterMigration(alreadyMigrated = true, saved = false))
    }

    @Test fun requestHistoryIsShorterThanBefore() {
        assertTrue(SpeedProfile.HISTORY_CHARS < 60_000)
        assertEquals(SpeedProfile.HISTORY_CHARS, AgentLoopConfig().historyCharBudget)
    }
}
