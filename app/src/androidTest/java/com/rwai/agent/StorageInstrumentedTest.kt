package com.rwai.agent

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rwai.agent.data.RwDb
import com.rwai.agent.security.SecretStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Инструментальные тесты: выполняются на эмуляторе/телефоне командой ./gradlew connectedDebugAndroidTest.
 * В версии 0.1.0 они написаны, но НЕ запускались (в среде разработки не было Android-устройства).
 */
@RunWith(AndroidJUnit4::class)
class StorageInstrumentedTest {
    private lateinit var ctx: Context
    private lateinit var db: RwDb
    private lateinit var secrets: SecretStore

    @Before fun setUp() {
        ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.deleteDatabase("rwai.db")
        db = RwDb(ctx)
        secrets = SecretStore(ctx)
        secrets.remove("test_secret")
    }

    @After fun tearDown() {
        db.close()
        secrets.remove("test_secret")
        ctx.deleteDatabase("rwai.db")
    }

    @Test fun secretRoundTripAndRemoval() {
        assertNull(secrets.get("test_secret"))
        secrets.put("test_secret", "sk-тест-1234567890")
        assertEquals("sk-тест-1234567890", secrets.get("test_secret"))
        // В SharedPreferences не должно быть открытого текста
        val raw = ctx.getSharedPreferences("rwai_secrets", Context.MODE_PRIVATE).getString("test_secret", "")!!
        assertFalse(raw.contains("1234567890"))
        secrets.remove("test_secret")
        assertNull(secrets.get("test_secret"))
    }

    @Test fun notificationDedupeAndRussianSearch() {
        val t = System.currentTimeMillis()
        assertTrue(db.insertNotification(t, "com.whatsapp", "WhatsApp", "Сергей", "Сергей", "Договорились по аренде квартиры, 260 долларов", false, "k1"))
        assertFalse(db.insertNotification(t, "com.whatsapp", "WhatsApp", "Сергей", "Сергей", "Договорились по аренде квартиры, 260 долларов", false, "k1"))
        assertEquals(1, db.notificationCount())
        assertEquals(1, db.searchMemory("аренду").size)      // морфология: «аренду» ↔ «аренде»
        assertEquals(1, db.searchMemory("АРЕНДЕ").size)      // регистр кириллицы
        assertEquals(0, db.searchMemory("гараж").size)
        assertEquals(1, db.searchMemory("аренда", "whatsapp").size)
        assertEquals(0, db.searchMemory("аренда", "telegram").size)
    }

    @Test fun hiddenNotificationsAreNotIndexed() {
        db.insertNotification(1L, "com.bank", "Bank", null, null, null, true, "h1")
        assertEquals(1, db.notificationCount())
        assertEquals(0, db.memoryCount())
    }

    @Test fun clearAndPurge() {
        val now = System.currentTimeMillis()
        db.insertNotification(now - 10 * 86_400_000L, "p", "A", null, "t", "старое сообщение", false, "o")
        db.insertNotification(now, "p", "A", null, "t", "новое сообщение", false, "n")
        db.purgeOlderThan(now - 5 * 86_400_000L)
        assertEquals(1, db.notificationCount())
        assertEquals(1, db.searchMemory("сообщение").size)
        db.clearMemory(); db.clearNotifications()
        assertEquals(0, db.memoryCount())
    }

    @Test fun activityLogRoundTrip() {
        db.logActivity(1L, "tool", "launch_app", "Gmail", "ok")
        val rows = db.recentActivity()
        assertEquals(1, rows.size)
        assertEquals("launch_app", rows[0].tool)
        db.clearActivity()
        assertTrue(db.recentActivity().isEmpty())
    }
}
