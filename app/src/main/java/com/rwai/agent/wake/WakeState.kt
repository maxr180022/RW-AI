package com.rwai.agent.wake

import android.content.Context
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Общее состояние слушателя «Окей, Руся». */
object WakeState {
    private val changes = LinkedBlockingQueue<Unit>()

    /** Панель ассистента открыта (или идёт задача): микрофон нужен ей, слушатель освобождает его. */
    @Volatile var paused = false
        private set

    /** Слушатель прямо сейчас держит микрофон. */
    @Volatile var holdingMic = false

    @Volatile var overlayVisible = false

    fun setPaused(value: Boolean) {
        paused = value
        poke()
    }

    fun poke() { changes.offer(Unit) }

    /** Ждёт изменения условий (зарядка, экран, режим, пауза) не дольше [ms]. */
    fun awaitChange(ms: Long) {
        try { changes.poll(ms, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
        changes.clear()
    }
}

/** Накопители статистики для вкладки «Телефон»: только числа на телефоне. */
object WakeStats {
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("rwai_wake_stats", Context.MODE_PRIVATE)

    private fun day(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    fun addListen(c: Context, seconds: Long) {
        val p = prefs(c)
        val today = day()
        val base = if (p.getString("day", null) == today) p.getLong("listen_sec", 0L) else 0L
        p.edit().putString("day", today).putLong("listen_sec", base + seconds).apply()
    }

    fun listenMinutesToday(c: Context): Int {
        val p = prefs(c)
        return if (p.getString("day", null) == day()) (p.getLong("listen_sec", 0L) / 60).toInt() else 0
    }

    fun addDrain(c: Context, pct: Float, seconds: Long) {
        val p = prefs(c)
        p.edit().putFloat("drain_pct", p.getFloat("drain_pct", 0f) + pct).putLong("drain_sec", p.getLong("drain_sec", 0L) + seconds).apply()
    }

    /** % в час при выключенном экране за время прослушивания (null, пока замеров меньше часа или меньше 1%). */
    fun drainPerHour(c: Context): Float? {
        val p = prefs(c)
        val sec = p.getLong("drain_sec", 0L)
        val pct = p.getFloat("drain_pct", 0f)
        return if (sec >= 3600 && pct >= 1f) pct / (sec / 3600f) else null
    }

    fun measuredHours(c: Context): Float = prefs(c).getLong("drain_sec", 0L) / 3600f

    fun resetDrain(c: Context) { prefs(c).edit().remove("drain_pct").remove("drain_sec").apply() }
}
