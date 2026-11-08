package com.rwai.agent.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedProfileTest {
    @Test fun requestHistoryIsShorterThanBefore() {
        assertTrue(SpeedProfile.HISTORY_CHARS < 60_000)
        assertEquals(SpeedProfile.HISTORY_CHARS, AgentLoopConfig().historyCharBudget)
    }
}
