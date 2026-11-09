package com.rwai.agent.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelTest {
    private val total = LocalModelSpec.SIZE_BYTES

    @Test fun resumeOnlyFromAValidPartialFile() {
        assertEquals(0L, DownloadMath.resumeFrom(0, total))
        assertEquals(1_000L, DownloadMath.resumeFrom(1_000, total))
        assertEquals(0L, DownloadMath.resumeFrom(total, total)) // файл уже целый или больше ожидаемого: заново
        assertEquals(0L, DownloadMath.resumeFrom(total + 5, total))
    }

    @Test fun rangeHeaderOnlyWhenResuming() {
        assertNull(DownloadMath.rangeHeader(0))
        assertEquals("bytes=1048576-", DownloadMath.rangeHeader(1_048_576))
    }

    @Test fun onlyPartialContentMeansTheServerAcceptedResume() {
        assertTrue(DownloadMath.serverAcceptedResume(206))
        assertFalse(DownloadMath.serverAcceptedResume(200))
    }

    @Test fun percentIsClampedAndSafe() {
        assertEquals(0, DownloadMath.percent(0, total))
        assertEquals(50, DownloadMath.percent(total / 2, total))
        assertEquals(100, DownloadMath.percent(total * 2, total))
        assertEquals(0, DownloadMath.percent(10, 0))
    }

    @Test fun humanSizes() {
        assertEquals("3,4 ГБ", DownloadMath.human(total))
        assertEquals("850 МБ", DownloadMath.human(850L * 1024 * 1024))
    }

    @Test fun wifiOnlyBlocksMeteredNetworkAndLowSpaceBlocksStart() {
        val gb = 1024L * 1024 * 1024
        assertEquals(StartDecision.NEED_WIFI, DownloadMath.canStart(true, false, 50 * gb, 0, total))
        assertEquals(StartDecision.OK, DownloadMath.canStart(false, false, 50 * gb, 0, total))
        assertEquals(StartDecision.OK, DownloadMath.canStart(true, true, 50 * gb, 0, total))
        assertEquals(StartDecision.NO_SPACE, DownloadMath.canStart(true, true, 3 * gb, 0, total))
        // уже скачанная часть место не требует
        assertEquals(StartDecision.OK, DownloadMath.canStart(true, true, gb, total - 100L * 1024 * 1024, total))
    }

    @Test fun specPointsToTheApacheLicensedFile() {
        assertTrue(LocalModelSpec.URL.startsWith("https://huggingface.co/litert-community/"))
        assertTrue(LocalModelSpec.URL.endsWith(LocalModelSpec.FILE_NAME))
        assertEquals(64, LocalModelSpec.SHA256.length)
    }

    @Test fun hintsMentionSizeAndProgress() {
        assertTrue(LocalModelText.hint("NONE", 0, total).contains("3,4 ГБ"))
        assertTrue(LocalModelText.hint("DOWNLOADING", total / 2, total).contains("50 %"))
        assertEquals("", LocalModelText.hint("ERROR", 0, total))
    }
}
