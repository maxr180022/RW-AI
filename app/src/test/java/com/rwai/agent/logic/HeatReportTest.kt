package com.rwai.agent.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeatReportTest {
    private fun input(
        temp: Float = 39.6f, charging: Boolean = false, screenOn: Boolean = false, ma: Int? = null,
        apps: List<AppActivity> = emptyList(), localMin: Int = 0, mic: Boolean = false, usage: Boolean = true,
    ) = HeatInput(
        tempC = temp, thermalStatus = 2, charging = charging, screenOn = screenOn, currentMa = ma, apps = apps, ownPackage = "com.rwai.agent",
        ownLocalModelMin = localMin, ownLocalModelBackend = "процессор", ownMicHeld = mic, usageAccess = usage,
    )

    private fun app(pkg: String, fg: Int = 0, svc: Int = 0, now: Boolean = false, ago: Int? = null, kind: String? = null) =
        AppActivity(pkg, pkg.uppercase(), fg, svc, now, ago, kind)

    @Test fun gameOnScreenIsTopCause() {
        val c = HeatReport.analyze(input(apps = listOf(app("chat", fg = 10, ago = 5), app("game", fg = 25, ago = 12, kind = "игра"))))
        assertEquals("GAME (игра)", c.first().title)
        assertTrue(c.any { it.title == "CHAT" })
    }

    @Test fun localModelIsNamedAsOwnCause() {
        val c = HeatReport.analyze(input(localMin = 12))
        assertEquals("Руся: локальная модель", c.single().title)
        assertTrue(c.single().detail.contains("12 мин"))
    }

    @Test fun runningServiceShownEvenWithoutScreenTime() {
        val c = HeatReport.analyze(input(apps = listOf(app("music", svc = 40, now = true))))
        assertTrue(c.single().title.contains("работает в фоне"))
        assertTrue(c.single().detail.contains("сейчас"))
    }

    @Test fun highIdleCurrentIsReportedOnlyOffCharger() {
        assertTrue(HeatReport.analyze(input(ma = 600)).single().title.contains("600 мА"))
        assertTrue(HeatReport.analyze(input(ma = 600, charging = true)).isEmpty())
        assertTrue(HeatReport.analyze(input(ma = 100)).isEmpty())
    }

    @Test fun ownAppIsNotListedAsForeignApp() {
        assertTrue(HeatReport.analyze(input(apps = listOf(app("com.rwai.agent", fg = 30)))).isEmpty())
    }

    @Test fun noCausesIsStatedHonestlyWithoutAdvice() {
        val i = input()
        val h = HeatReport.headline(i, HeatReport.analyze(i))
        assertTrue(h.contains("не вижу"))
        assertTrue(HeatReport.headline(input(usage = false), emptyList()).contains("Доступа к статистике"))
    }
}
