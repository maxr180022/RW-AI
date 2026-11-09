package com.rwai.agent.phone

import android.content.Context
import com.rwai.agent.local.LocalLlm
import com.rwai.agent.logic.HeatTimeline
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Текстовая выгрузка замеров нагрева для разбора: сырые замеры (температура, заряд, ток, экран), периоды работы приложений,
 * найденные нагревы, рейтинг виновников и отметки «Нагрелся сейчас». Названия приложений есть, личных данных и ключей нет.
 */
object HeatExport {
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    private fun t(ts: Long) = fmt.format(Date(ts))

    fun build(ctx: Context): String {
        val now = System.currentTimeMillis()
        val from = now - 48L * 3_600_000L
        val samples = HeatLog.read(ctx, from)
        val intervals = PhoneScan.activityIntervals(ctx, from, now) + LocalLlm.busyIntervals(from)
        val marks = HeatLog.marks(ctx, from)
        val sb = StringBuilder()
        sb.append("Выгрузка замеров нагрева Руся AI, ").append(t(now)).append(", окно 48 часов\n")
        sb.append("Замеров: ").append(samples.size).append(", периодов активности: ").append(intervals.size)
            .append(", отметок «нагрелся»: ").append(marks.size).append(", доступ к статистике: ").append(PhoneScan.hasUsageAccess(ctx)).append('\n')

        sb.append("\n=== Нагревы (выше 37,5 °C и отметки) ===\n")
        val eps = HeatTimeline.episodes(samples, intervals, marks = marks)
        if (eps.isEmpty()) sb.append("нет\n")
        eps.forEach { e ->
            sb.append(HeatTimeline.title(e)).append('\n')
            e.suspects.take(10).forEach { sb.append("   - ").append(HeatTimeline.line(it)).append('\n') }
        }

        sb.append("\n=== Рейтинг: ток и температура с приложением и без него ===\n")
        val cs = HeatTimeline.culprits(samples, intervals)
        if (cs.isEmpty()) sb.append("мало замеров не на зарядке\n")
        cs.take(25).forEach { sb.append(HeatTimeline.culpritLine(it)).append('\n') }

        sb.append("\n=== Отметки «Нагрелся сейчас» ===\n")
        if (marks.isEmpty()) sb.append("нет\n") else marks.forEach { sb.append(t(it)).append('\n') }

        sb.append("\n=== Замеры: время, температура °C, заряд %, зарядка, экран, ток мА, напряжение мВ, мощность Вт (батарея) ===\n")
        samples.forEach {
            sb.append(t(it.t)).append(',').append(it.tempC).append(',').append(it.pct).append(',')
                .append(if (it.charging) "зарядка" else "-").append(',').append(if (it.screenOn) "экран" else "-").append(',').append(it.ma).append(',').append(it.mv).append(',').append(if (it.watts > 0) "%.1f".format(it.watts) else "-").append('\n')
        }

        sb.append("\n=== Периоды работы (от 1 минуты, до 900 записей): с, по, приложение, тип ===\n")
        intervals.filter { it.end - it.start >= 60_000L }.sortedBy { it.start }.takeLast(900).forEach {
            sb.append(t(it.start)).append(" - ").append(t(it.end)).append(", ").append(it.label).append(", ").append(it.type)
                .append(it.kind?.let { k -> " ($k)" } ?: "").append('\n')
        }
        return sb.toString()
    }

    fun write(ctx: Context): File {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles { f -> f.name.startsWith("rwai-heat-") }?.forEach { it.delete() }
        val f = File(dir, "rwai-heat-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt")
        f.writeText(build(ctx))
        return f
    }
}
