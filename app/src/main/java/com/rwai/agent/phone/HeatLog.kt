package com.rwai.agent.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import com.rwai.agent.logic.HeatSample
import java.io.File

/**
 * Журнал температуры батареи: запись при каждом заметном изменении (не чаще раза в 3 минуты) плюс при открытии вкладки «Телефон».
 * Хранится в файле приложения на самом телефоне, двое суток, никуда не отправляется. Нужен, чтобы потом сопоставить «когда пошёл нагрев»
 * с тем, что в это время работало (это Android хранит сам, а температуру нет).
 */
object HeatLog {
    private const val FILE = "heat_log.csv"
    private const val KEEP_MS = 48L * 3_600_000L
    private const val MIN_GAP_MS = 3 * 60_000L
    private val lock = Any()
    @Volatile private var started = false
    @Volatile private var lastT = 0L
    @Volatile private var lastTemp = Float.NaN

    private fun file(c: Context) = File(c.applicationContext.filesDir, FILE)

    /** Подписка на изменения батареи на всё время жизни процесса (его держат службы уведомлений и специальных возможностей). */
    fun start(c: Context) {
        if (started) return
        started = true
        val app = c.applicationContext
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { record(app, intent) }
        }
        try {
            app.registerReceiver(r, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { record(app, it) }
        } catch (_: Throwable) { started = false }
    }

    fun record(c: Context, battery: Intent? = null, force: Boolean = false) {
        try {
            val i = battery ?: c.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
            val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
            if (temp <= 0f) return
            val now = System.currentTimeMillis()
            val changed = lastTemp.isNaN() || kotlin.math.abs(temp - lastTemp) >= 0.5f
            if (!force && now - lastT < MIN_GAP_MS && !(changed && now - lastT > 30_000L)) return
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || plugged != 0
            val screen = (c.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
            lastT = now
            lastTemp = temp
            synchronized(lock) {
                val f = file(c)
                f.appendText("$now,$temp,${if (level < 0) -1 else level * 100 / scale},${if (charging) 1 else 0},${if (screen) 1 else 0}\n")
                if (f.length() > 200_000L) trim(f, now)
            }
        } catch (_: Throwable) {
            // журнал не должен ломать приложение
        }
    }

    private fun trim(f: File, now: Long) {
        val keep = f.readLines().filter { (it.substringBefore(',').toLongOrNull() ?: 0L) >= now - KEEP_MS }
        f.writeText(keep.joinToString("\n") + "\n")
    }

    fun read(c: Context, sinceMs: Long = System.currentTimeMillis() - 24L * 3_600_000L): List<HeatSample> = synchronized(lock) {
        try {
            val f = file(c)
            if (!f.exists()) emptyList() else f.readLines().mapNotNull { line ->
                val p = line.split(',')
                if (p.size < 5) return@mapNotNull null
                val t = p[0].toLongOrNull() ?: return@mapNotNull null
                if (t < sinceMs) return@mapNotNull null
                HeatSample(t, p[1].toFloatOrNull() ?: return@mapNotNull null, p[2].toIntOrNull() ?: -1, p[3] == "1", p[4] == "1")
            }
        } catch (_: Throwable) { emptyList() }
    }
}
