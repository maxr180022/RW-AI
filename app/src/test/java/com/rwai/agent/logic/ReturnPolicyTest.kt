package com.rwai.agent.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class ReturnPolicyTest {
    private fun d(mode: String = "origin", ask: Boolean = false, spoken: Boolean = false, origin: String? = "com.whatsapp", cur: String? = "com.google.android.gm", bank: Boolean = false) =
        ReturnPolicy.decide(mode, ask, spoken, origin, cur, "com.rwai.agent", "com.sec.launcher", bank)

    @Test fun returnsToOriginApp() = assertEquals(ReturnAction.ORIGIN_APP, d())
    @Test fun alreadyThere() = assertEquals(ReturnAction.NONE, d(cur = "com.whatsapp"))
    @Test fun originHomeGoesHome() = assertEquals(ReturnAction.HOME, d(origin = "com.sec.launcher"))
    @Test fun originUnknownGoesHome() = assertEquals(ReturnAction.HOME, d(origin = null))
    @Test fun bankingOriginGoesHome() = assertEquals(ReturnAction.HOME, d(origin = "com.bank", bank = true))
    @Test fun originOwnAppReopensIt() = assertEquals(ReturnAction.OWN_APP, d(origin = "com.rwai.agent"))
    @Test fun overlayStaysOnScreen() = assertEquals(ReturnAction.NONE, d(cur = "com.rwai.agent"))
    @Test fun questionOpensAppUnlessSpoken() {
        assertEquals(ReturnAction.OWN_APP, d(ask = true))
        assertEquals(ReturnAction.ORIGIN_APP, d(ask = true, spoken = true))
    }
    @Test fun fixedModes() {
        assertEquals(ReturnAction.HOME, d(mode = "home"))
        assertEquals(ReturnAction.OWN_APP, d(mode = "app"))
        assertEquals(ReturnAction.NONE, d(mode = "stay"))
    }
}
