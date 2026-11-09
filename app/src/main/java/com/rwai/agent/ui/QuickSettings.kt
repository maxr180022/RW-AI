package com.rwai.agent.ui

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rwai.agent.Graph
import com.rwai.agent.core.LightLlm
import com.rwai.agent.core.QuickRouter
import com.rwai.agent.local.LocalModelStore
import java.text.SimpleDateFormat
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale

private fun contactsGranted(ctx: Context): Boolean =
    androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
        androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED

private fun dndGranted(ctx: Context): Boolean =
    (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).isNotificationPolicyAccessGranted

/** «Быстрые действия»: простые команды телефон выполняет сам, без DeepSeek. Здесь переключатели, доступ и журнал решений. */
@Composable
fun QuickSettings() {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(QuickRouter.enabled(ctx)) }
    var local by remember { mutableStateOf(QuickRouter.localEnabled(ctx)) }
    var dnd by remember { mutableStateOf(dndGranted(ctx)) }
    var bg by remember { mutableStateOf(LightLlm.backgroundEnabled(ctx)) }
    var write by remember { mutableStateOf(Settings.System.canWrite(ctx)) }
    var contactsOk by remember { mutableStateOf(contactsGranted(ctx)) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var auditNote by remember { mutableStateOf<String?>(null) }
    val contactsLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { contactsOk = contactsGranted(ctx) }
    var tick by remember { mutableIntStateOf(0) }
    val log = remember(tick) { try { Graph.db.recentRouteLog(10) } catch (_: Exception) { emptyList() } }
    val counts = remember(tick) { try { Graph.db.routeCounts() } catch (_: Exception) { 0 to 0 } }
    val fmt = remember { SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()) }

    SettingsGroup("Главное") {
        SwitchRow(
            "Быстрые действия без DeepSeek",
            "Простые команды выполняются прямо на телефоне за долю секунды, без токенов и без хождения по экранам: фонарик, громкость музыки, «Не беспокоить», будильник, таймер, открыть приложение, заряд, пауза и следующий трек. Если не вышло, команда уходит агенту.",
            enabled,
        ) { v -> QuickRouter.setEnabled(ctx, v); enabled = v }
        SwitchRow(
            "Нечёткие фразы разбирает локальная модель",
            "Например, «сделай потише» или «разбуди меня в семь». Работает, если скачана локальная модель (Параметры → Локальная модель). Иначе такие фразы идут агенту.",
            local,
        ) { v -> QuickRouter.setLocalEnabled(ctx, v); local = v }
        Text(
            if (LocalModelStore.isReady(ctx)) "Локальная модель скачана." else "Локальная модель не скачана: работают только точные фразы.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SwitchRow(
            "Фоновые тексты на локальной модели",
            "Перевод входящих, сводки, сводка уведомлений и черновики ответов («Предложить ответ» во «Входящих») пишет локальная модель: без токенов, без интернета и без хождения по экранам. Если модель не скачана или ответила не по формату, работает DeepSeek.",
            bg,
        ) { v -> LightLlm.setBackground(ctx, v); bg = v }
        val st = LightLlm.stats(ctx)
        Text("Фоновых текстов: локально ${st.first} · через DeepSeek ${st.second}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    SettingsGroup("Примеры точных фраз") {
        Text(
            "«Включи фонарик», «выключи фонарик»\n«Поставь будильник на 7:30», «таймер на 10 минут»\n«Громкость на 40», «громче», «тише»\n«Выключи режим не беспокоить»\n«Какой заряд», «пауза», «следующий трек»\n«Открой Telegram», «позвони Саше»\n«Какая погода», «который час», «что у меня в календаре»\n«Яркость на 50», «ярче», «темнее»\n«Включи вибрацию», «беззвучный режим», «включи звук»\n«Включи песню …»",
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    SettingsGroup("Доступ") {
        Text(
            "«Не беспокоить»: " + if (dnd) "доступ дан ✓" else "нужен доступ, чтобы включать и выключать режим",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!dnd) ActionPill("Дать доступ", accent = true) {
            try { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
        }
        ActionPill("Проверить доступ") { dnd = dndGranted(ctx); write = Settings.System.canWrite(ctx); contactsOk = contactsGranted(ctx); tick++ }
        Text("Контакты и звонки («Позвони Саше»): " + if (contactsOk) "доступ дан ✓" else "нужен доступ", style = MaterialTheme.typography.bodyMedium)
        if (!contactsOk) ActionPill("Дать доступ к контактам и звонкам", accent = true) {
            contactsLauncher.launch(arrayOf(android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.CALL_PHONE))
        }
        Text("Яркость: " + if (write) "доступ дан ✓" else "нужен доступ к изменению системных настроек", style = MaterialTheme.typography.bodyMedium)
        if (!write) ActionPill("Дать доступ к яркости", accent = true) {
            try { ctx.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
        }
        Text(
            "Будильник и таймер работают без особого доступа.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    SettingsGroup("Журнал решений") {
        Text("Выполнено быстро: ${counts.first} · не вышло или отдано агенту: ${counts.second}", style = MaterialTheme.typography.bodyMedium)
        if (log.isEmpty()) Text("Пока пусто.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        log.forEach { r ->
            Text(
                fmt.format(Date(r.ts)) + "  " + (if (r.ok) "✓ " else "✗ ") + r.action + " · " + (if (r.source == "rule") "правило" else "локальная модель") + "\n" + r.message + (r.audit?.let { " · проверка: $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        ActionPill("Обновить") { tick++ }
        Text("DeepSeek сам проверяет журнал (раз в несколько часов, когда накопится 15 записей): верно ли выбрано действие. Это нужно, чтобы потом улучшать локальную модель.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
            ActionPill("Проверить сейчас", compact = true) {
                auditNote = "Проверяю…"
                scope.launch { auditNote = try { com.rwai.agent.core.RouteAudit.run(ctx) } catch (e: Exception) { "Не вышло: " + (e.message ?: e.javaClass.simpleName) }; tick++ }
            }
            ActionPill("Выгрузить для обучения", compact = true) {
                scope.launch {
                    auditNote = try {
                        val f = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.rwai.agent.core.RouteAudit.exportJsonl(ctx) }
                        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Выгрузить журнал").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        "Файл готов: " + f.name
                    } catch (e: Exception) { "Не вышло: " + (e.message ?: e.javaClass.simpleName) }
                }
            }
        }
        auditNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
