package com.rwai.agent.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rwai.agent.local.LocalLlm
import com.rwai.agent.logic.HeatEpisode
import com.rwai.agent.logic.HeatSample
import com.rwai.agent.logic.HeatTimeline
import com.rwai.agent.phone.HeatExport
import com.rwai.agent.phone.HeatLog
import com.rwai.agent.phone.PhoneScan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** История нагрева за сутки: график температуры и для каждого нагрева «когда пошёл рост и что в это время работало». */
@Composable
fun HeatHistoryCard(resumeTick: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var samples by remember { mutableStateOf<List<HeatSample>>(emptyList()) }
    var episodes by remember { mutableStateOf<List<HeatEpisode>>(emptyList()) }
    var culprits by remember { mutableStateOf<List<HeatTimeline.Culprit>>(emptyList()) }
    var shareNote by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    val usageOk = remember(resumeTick) { PhoneScan.hasUsageAccess(ctx) }

    suspend fun load() {
        val now = System.currentTimeMillis()
        val from = now - 24L * 3_600_000L
        withContext(Dispatchers.Default) {
            HeatLog.record(ctx, force = true)
            val s = HeatLog.read(ctx, from)
            val iv = PhoneScan.activityIntervals(ctx, from, now) + LocalLlm.busyIntervals(from)
            samples = s
            episodes = HeatTimeline.episodes(s, iv, marks = HeatLog.marks(ctx, from))
            culprits = HeatTimeline.culprits(s, iv)
        }
        loaded = true
    }
    LaunchedEffect(resumeTick) { load() }

    val line = MaterialTheme.colorScheme.primary
    val hotShade = MaterialTheme.colorScheme.error.copy(alpha = 0.22f)
    val grid = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)

    Card {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("История нагрева (сутки)", style = MaterialTheme.typography.titleMedium)
            if (samples.size < 2) {
                Text(
                    if (loaded) "Температуру Руся начала записывать только что. Поносите телефон как обычно, через несколько часов здесь будет график и разбор каждого нагрева: когда пошёл рост и что в это время работало."
                    else "Загрузка…",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                val t0 = samples.first().t
                val t1 = maxOf(samples.last().t, t0 + 60_000L)
                val lo = minOf(30f, samples.minOf { it.tempC } - 1f)
                val hi = maxOf(40f, samples.maxOf { it.tempC } + 1f)
                Canvas(modifier = Modifier.fillMaxWidth().height(110.dp)) {
                    fun x(t: Long) = size.width * (t - t0).toFloat() / (t1 - t0).toFloat()
                    fun y(v: Float) = size.height * (1f - (v - lo) / (hi - lo))
                    episodes.forEach { e ->
                        val a = x(e.riseStart)
                        drawRect(hotShade, topLeft = Offset(a, 0f), size = Size((x(e.runEnd) - a).coerceAtLeast(2f), size.height))
                    }
                    drawLine(grid, Offset(0f, y(37.5f)), Offset(size.width, y(37.5f)), strokeWidth = 1f)
                    val path = Path()
                    samples.forEachIndexed { i, s -> if (i == 0) path.moveTo(x(s.t), y(s.tempC)) else path.lineTo(x(s.t), y(s.tempC)) }
                    drawPath(path, line, style = Stroke(width = 3f))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(HeatTimeline.hm(t0), style = MaterialTheme.typography.bodySmall)
                    Text("серая линия: 37,5 °C", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(HeatTimeline.hm(t1), style = MaterialTheme.typography.bodySmall)
                }
                if (episodes.isEmpty()) {
                    Text("Нагревов выше 37,5 °C за записанное время не было (макс. ${"%.1f".format(samples.maxOf { it.tempC })} °C).", style = MaterialTheme.typography.bodyMedium)
                }
                episodes.asReversed().forEach { e ->
                    Text(HeatTimeline.title(e), fontWeight = FontWeight.SemiBold)
                    if (e.suspects.isEmpty()) {
                        Text("В это время не вижу ни приложений на экране, ни служб в фоне." + if (usageOk) "" else " Нет «Доступа к статистике».", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        e.suspects.take(6).forEach { Text("• " + HeatTimeline.line(it), style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
            Text("Кто сажает батарею и греет (по замерам)", style = MaterialTheme.typography.titleSmall)
            if (culprits.isEmpty()) {
                Text(
                    "Пока мало замеров: нужны хотя бы сутки обычного использования не на зарядке. Руся записывает ток батареи и температуру и сравнивает, какие приложения работают, когда ток и нагрев растут.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                val bad = culprits.filter { it.guilty }
                Text(
                    if (bad.isEmpty()) "Явных виновников по замерам нет: ни у одного приложения ток и температура заметно не выше, чем без него."
                    else "Кандидаты на ограничение или удаление (${bad.size}), самый вредный сверху:",
                    fontWeight = FontWeight.SemiBold,
                )
                (if (bad.isEmpty()) culprits.take(4) else bad.take(8)).forEach { Text("• " + HeatTimeline.culpritLine(it), style = MaterialTheme.typography.bodyMedium) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scope.launch { withContext(Dispatchers.Default) { HeatLog.addMark(ctx) }; load() } }) { Text("Нагрелся сейчас") }
                OutlinedButton(onClick = {
                    scope.launch {
                        shareNote = try {
                            val file = withContext(Dispatchers.IO) { HeatExport.write(ctx) }
                            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            ctx.startActivity(android.content.Intent.createChooser(send, "Выгрузить замеры").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                            "Файл замеров готов: " + file.name
                        } catch (e: Exception) { "Не удалось выгрузить: " + e.javaClass.simpleName }
                    }
                }) { Text("Выгрузить замеры") }
            }
            shareNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!usageOk) OutlinedButton(onClick = { ctx.startActivity(PhoneScan.usageSettingsIntent()) }) { Text("Доступ к статистике") }
                OutlinedButton(onClick = { scope.launch { load() } }) { Text("Обновить") }
            }
            Text(
                "Температура пишется на самом телефоне (двое суток), что работало — берётся из журнала Android. Ничего не отправляется.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
