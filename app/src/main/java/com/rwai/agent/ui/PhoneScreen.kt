package com.rwai.agent.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.rwai.agent.Graph
import com.rwai.agent.local.LocalLlm
import com.rwai.agent.logic.AppActivity
import com.rwai.agent.logic.HeatInput
import com.rwai.agent.logic.HeatReport
import com.rwai.agent.logic.PhoneSnapshot
import com.rwai.agent.phone.PhoneScan
import com.rwai.agent.wake.WakeState
import com.rwai.agent.wake.WakeModel
import com.rwai.agent.wake.WakeStats
import com.rwai.agent.wake.WakeWordService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class BatteryInfo(val pct: Int, val tempC: Float, val charging: Boolean)

private fun readBattery(c: Context): BatteryInfo {
    val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = (i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100).coerceAtLeast(1)
    val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val plugged = i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
    val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL || plugged != 0
    return BatteryInfo(if (level < 0) -1 else level * 100 / scale, temp, charging)
}

/** Ток батареи в мА по модулю (знак у разных телефонов разный); null — датчика нет. Значения в мкА или мА определяем по величине. */
private fun batteryCurrentMa(c: Context): Int? = try {
    val raw = kotlin.math.abs((c.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))
    if (raw == 0L || raw == Long.MIN_VALUE) null else (if (raw > 20_000) raw / 1000 else raw).toInt()
} catch (_: Exception) { null }

private fun thermalStatus(c: Context): Int =
    if (Build.VERSION.SDK_INT >= 29) (c.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus else 0

private fun thermalText(s: Int): String = when (s) {
    0 -> "в норме"; 1 -> "слегка тёплый"; 2 -> "тёплый (возможно замедление)"; 3 -> "перегрев"; 4 -> "сильный перегрев"; else -> "критический"
}

/** Вкладка «Телефон»: голосовой вызов «Окей, Руся» с режимами, состояние батареи и локальные советы. */
@Composable
fun PhoneScreen(resumeTick: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by Graph.settings.state.collectAsState()
    val progress by WakeModel.progress.collectAsState()
    var modelReady by remember(resumeTick) { mutableStateOf(WakeModel.ready(ctx)) }
    var battery by remember { mutableStateOf(readBattery(ctx)) }
    var stats by remember { mutableStateOf(0 to (null as Float?)) }
    var message by remember { mutableStateOf<String?>(null) }
    var heatApps by remember { mutableStateOf<List<AppActivity>>(emptyList()) }
    val usageOk = remember(resumeTick) { PhoneScan.hasUsageAccess(ctx) }

    // Обновляем показания, пока вкладка открыта (экран включён).
    LaunchedEffect(resumeTick) {
        while (true) {
            battery = readBattery(ctx)
            stats = WakeStats.listenMinutesToday(ctx) to WakeStats.drainPerHour(ctx)
            heatApps = withContext(Dispatchers.Default) { PhoneScan.recentActivity(ctx) }
            delay(5_000)
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            Graph.settings.update { it.copy(wakeEnabled = true) }
            WakeWordService.sync(ctx)
        } else message = "Без разрешения на микрофон «Окей, Руся» работать не может."
    }

    fun setEnabled(on: Boolean) {
        message = null
        if (!on) {
            Graph.settings.update { it.copy(wakeEnabled = false) }
            WakeWordService.sync(ctx)
            return
        }
        if (!modelReady) { message = "Сначала скачайте голосовую модель."; return }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            Graph.settings.update { it.copy(wakeEnabled = true) }
            WakeWordService.sync(ctx)
        }
    }

    val snapshot = PhoneSnapshot(
        batteryPct = battery.pct, tempC = battery.tempC, charging = battery.charging, thermalStatus = thermalStatus(ctx),
        wakeEnabled = settings.wakeEnabled, wakeMode = settings.wakeMode, listenMinutesToday = stats.first, screenOffDrainPerHour = stats.second,
    )

    val heatInput = HeatInput(
        tempC = battery.tempC, thermalStatus = snapshot.thermalStatus, charging = battery.charging,
        screenOn = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive,
        currentMa = batteryCurrentMa(ctx), apps = heatApps, ownPackage = ctx.packageName,
        ownLocalModelMin = (LocalLlm.busyMs(3_600_000L) / 60_000L).toInt(), ownLocalModelBackend = LocalLlm.backend(),
        ownMicHeld = WakeState.holdingMic, usageAccess = usageOk,
    )
    val causes = HeatReport.analyze(heatInput)

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ---- Окей, Руся ----
        Card(modifier = Modifier.padding(top = 8.dp)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("«Окей, Руся»", style = MaterialTheme.typography.titleMedium)
                        Text("Агент просыпается от вашего голоса без нажатия кнопки", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = settings.wakeEnabled, onCheckedChange = { setEnabled(it) })
                }
                if (!modelReady) {
                    Text(
                        "Нужна голосовая модель (≈45 МБ, русская). Она скачивается один раз и хранится только на телефоне; распознавание идёт офлайн, звук никуда не отправляется. Лучше качать по Wi‑Fi.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (progress >= 0) {
                        LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                        Text("Загрузка… $progress%", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Button(onClick = {
                            message = null
                            scope.launch {
                                WakeModel.download(ctx).fold(
                                    onSuccess = { modelReady = true; message = "Модель скачана. Теперь включите переключатель." },
                                    onFailure = { message = "Не удалось скачать модель: " + (it.message ?: it.javaClass.simpleName) },
                                )
                            }
                        }) { Text("Скачать голосовую модель") }
                    }
                } else {
                    Text("Когда слушать:", style = MaterialTheme.typography.labelLarge)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(
                            "charging" to "Только на зарядке",
                            "charging_screen" to "Зарядка или экран включён",
                            "always" to "Всегда",
                        ).forEach { (v, label) ->
                            FilterChip(
                                selected = settings.wakeMode == v,
                                onClick = { Graph.settings.update { it.copy(wakeMode = v) }; WakeWordService.sync(ctx) },
                                label = { Text(label) },
                            )
                        }
                    }
                    Text(
                        "Вне выбранного режима микрофон и модель полностью выключены. В режиме «Всегда» микрофон занят постоянно: Android показывает зелёную точку, а батарея расходуется заметнее. " +
                            "Звук обрабатывается только когда есть голос. После перезагрузки телефона откройте приложение один раз, чтобы слушатель включился.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = {
                        Graph.settings.update { it.copy(wakeEnabled = false) }
                        WakeWordService.sync(ctx)
                        WakeModel.delete(ctx)
                        modelReady = false
                    }) { Text("Удалить модель") }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
            }
        }

        // ---- Состояние ----
        Card {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Состояние телефона", style = MaterialTheme.typography.titleMedium)
                Text("Заряд: ${if (battery.pct >= 0) battery.pct.toString() + "%" else "—"}" + if (battery.charging) " · заряжается" else "")
                Text("Температура батареи: ${"%.1f".format(battery.tempC)} °C · ${thermalText(snapshot.thermalStatus)}")
                if (settings.wakeEnabled) {
                    Text("«Окей, Руся» слушал сегодня: ${stats.first} мин")
                    val d = stats.second
                    Text(
                        if (d != null) "Расход при выключенном экране: ≈${"%.1f".format(d)}% в час (замеров ${"%.1f".format(WakeStats.measuredHours(ctx))} ч)"
                        else "Расход при выключенном экране: пока мало данных (нужен час замеров с выключенным экраном не на зарядке)",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        // ---- Почему телефон тёплый: только факты, без общих советов ----
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Что греет телефон", style = MaterialTheme.typography.titleMedium)
                Text(HeatReport.headline(heatInput, causes), fontWeight = FontWeight.SemiBold)
                causes.take(8).forEach { cause ->
                    Text("• " + cause.title, fontWeight = FontWeight.SemiBold)
                    Text(cause.detail, style = MaterialTheme.typography.bodyMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!usageOk) OutlinedButton(onClick = { ctx.startActivity(PhoneScan.usageSettingsIntent()) }) { Text("Доступ к статистике") }
                    OutlinedButton(onClick = { battery = readBattery(ctx); scope.launch { heatApps = withContext(Dispatchers.Default) { PhoneScan.recentActivity(ctx) } } }) { Text("Обновить") }
                }
                Text("Всё считается на телефоне, ничего не отправляется.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        PhoneHealthCard(resumeTick)
        OutlinedButton(onClick = { WakeStats.resetDrain(ctx); stats = stats.first to null }, modifier = Modifier.padding(bottom = 16.dp)) {
            Text("Сбросить замеры расхода")
        }
    }
}
