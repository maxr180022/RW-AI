package com.rwai.agent.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rwai.agent.audio.CallRouter
import com.rwai.agent.logic.DeviceKind
import com.rwai.agent.logic.RouteFallback

/**
 * «Звук в машине»: звонки идут на наушники, если они надеты, иначе на динамик телефона, и не идут на звук машины.
 * Один переключатель, выбор запасного вывода, список сопряжённых Bluetooth-устройств (кто «машина», кто «наушники») и журнал событий,
 * чтобы по результату в машине было видно, что приложение просило и что система сделала.
 */
@Composable
fun CarAudioSettings() {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(CallRouter.enabled(ctx)) }
    var fallback by remember { mutableStateOf(CallRouter.fallback(ctx)) }
    var granted by remember { mutableStateOf(CallRouter.hasBluetoothPermission(ctx)) }
    var tick by remember { mutableIntStateOf(0) }
    val events by CallRouter.events.collectAsState()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        tick++
    }
    LaunchedEffect(Unit) { granted = CallRouter.hasBluetoothPermission(ctx) }

    SettingsGroup("Главное") {
        if (!CallRouter.supported()) {
            Text("Для этой функции нужен Android 12 или новее. На этом телефоне она недоступна.", style = MaterialTheme.typography.bodyMedium)
        }
        SwitchRow(
            "Звонки не на машину",
            "Пока идёт голосовой звонок (сотовый или через мессенджер): звук на наушники, если они надеты, иначе на динамик телефона. На звук машины не выводится никогда.",
            enabled && CallRouter.supported(),
        ) { v ->
            if (CallRouter.supported()) {
                CallRouter.setEnabled(ctx, v)
                enabled = v
                if (v && !granted) launcher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        Text("Если наушников нет, звук идёт:", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RouteFallback.entries.forEach { f ->
                FilterChip(selected = fallback == f, onClick = { CallRouter.setFallback(ctx, f); fallback = f }, label = { Text(f.title) })
            }
        }
        if (!granted && CallRouter.supported()) {
            Text("Нужно разрешение на Bluetooth: без него приложение не отличит наушники от машины.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            ActionPill("Разрешить Bluetooth", accent = true) { launcher.launch(Manifest.permission.BLUETOOTH_CONNECT) }
        }
        Text(
            "Важно: Android может не позволять обычному приложению менять звук обычного сотового звонка. Ниже в журнале видно, что приложение попросило и что система сделала на самом деле.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    SettingsGroup("Устройства Bluetooth") {
        val list = remember(granted, tick) { CallRouter.paired(ctx) }
        if (!granted) {
            Text("Разрешите Bluetooth, чтобы увидеть сопряжённые устройства.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (list.isEmpty()) {
            Text("Сопряжённых устройств нет.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        list.forEach { d ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                Text(d.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Определено: " + d.kind.title + if (d.manual) " (вы поправили)" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ActionPill("Это машина", accent = d.kind == DeviceKind.CAR) { CallRouter.setOverride(ctx, d.address, DeviceKind.CAR); tick++ }
                    ActionPill("Это наушники", accent = d.kind == DeviceKind.HEADSET) { CallRouter.setOverride(ctx, d.address, DeviceKind.HEADSET); tick++ }
                    if (d.manual) ActionPill("Сбросить") { CallRouter.setOverride(ctx, d.address, null); tick++ }
                }
            }
        }
    }

    SettingsGroup("Что происходило со звуком звонков") {
        if (events.isEmpty()) {
            Text("Пока пусто. Совершите звонок, пока включена настройка.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        events.forEach { e ->
            Text(CallRouter.stamp(e.ts) + "  " + e.text, style = MaterialTheme.typography.bodySmall)
        }
    }
}
