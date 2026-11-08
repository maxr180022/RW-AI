package com.rwai.agent.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.rwai.agent.Graph
import com.rwai.agent.data.ActivityRow
import com.rwai.agent.data.MemoryRow
import com.rwai.agent.data.NotificationRow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val dateFmt = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())

@Composable
fun InboxScreen(resumeTick: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<NotificationRow>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var summary by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val enabled = remember(resumeTick) { isNotificationAccessEnabled(ctx) }

    LaunchedEffect(refresh, resumeTick) {
        rows = withContext(Dispatchers.IO) { Graph.db.recentNotifications(150) }
    }

    LazyColumn(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                if (!enabled) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Доступ к уведомлениям выключен. Без него «Входящие» остаются пустыми.")
                            Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                                Text("Открыть настройки доступа")
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy && rows.any { !it.hidden }, onClick = {
                        busy = true
                        scope.launch {
                            summary = try {
                                withContext(Dispatchers.IO) {
                                    com.rwai.agent.digest.DigestManager.translatePending()
                                    com.rwai.agent.digest.DigestManager.build(System.currentTimeMillis() - 24 * 3_600_000L)
                                } ?: "Новых сообщений нет."
                            } catch (e: Exception) {
                                "Не удалось собрать сводку: " + (e.message ?: "ошибка")
                            }
                            refresh++
                            busy = false
                        }
                    }) { Text("Кто ждёт ответа") }
                    OutlinedButton(onClick = { refresh++ }) { Text("Обновить") }
                }
                if (busy) CircularProgressIndicator()
                if (summary == null) {
                    com.rwai.agent.digest.DigestManager.lastDigest()?.let { (ts, t) ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("Последняя сводка · " + dateFmt.format(Date(ts)), style = MaterialTheme.typography.labelMedium)
                                Text(t)
                            }
                        }
                    }
                }
                summary?.let {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Text(it, modifier = Modifier.padding(12.dp))
                    }
                }
                Text(
                    "Показано ${rows.size} уведомлений. Приложения скрывают часть содержимого — RW AI показывает только то, что отдала система.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(rows) { r -> NotificationItem(r) }
    }
}

@Composable
private fun NotificationItem(r: NotificationRow) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text("${r.app} · ${dateFmt.format(Date(r.ts))}", style = MaterialTheme.typography.labelMedium)
            val who = r.sender ?: r.title
            if (!who.isNullOrBlank()) Text(who, style = MaterialTheme.typography.titleSmall)
            Text(if (r.hidden) "[содержимое скрыто приложением]" else (r.body ?: ""), style = MaterialTheme.typography.bodyMedium)
            if (!r.tr.isNullOrBlank()) Text("↳ " + r.tr, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun MemoryScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MemoryRow>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var version by remember { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(submitted, version) {
        withContext(Dispatchers.IO) {
            total = Graph.db.memoryCount()
            results = if (submitted.isBlank()) emptyList() else Graph.db.searchMemory(submitted, null, 30)
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Локальная память: $total записей (сохранённые уведомления, выдержки переписок и итоги задач). Данные хранятся только на этом телефоне.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.weight(1f), label = { Text("Поиск по словам") }, singleLine = true)
            Button(onClick = { submitted = query.trim() }) { Text("Найти") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = total > 0, onClick = {
                scope.launch {
                    val json = withContext(Dispatchers.IO) { Graph.db.exportMemoryJson() }
                    val file = withContext(Dispatchers.IO) {
                        File(ctx.cacheDir, "exports").apply { mkdirs() }.let { File(it, "rwai-memory.json").apply { writeText(json) } }
                    }
                    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    ctx.startActivity(Intent.createChooser(send, "Экспорт памяти").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }) { Text("Экспорт") }
            OutlinedButton(enabled = total > 0, onClick = { confirmClear = true }) { Text("Очистить память") }
        }
        if (submitted.isNotBlank() && results.isEmpty()) {
            Text("Ничего не найдено. Память содержит только то, что RW AI реально получил (уведомления и сохранённые выдержки).", style = MaterialTheme.typography.bodyMedium)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(results) { m ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("${m.app ?: m.source} · ${dateFmt.format(Date(m.ts))}" + (m.contact?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelMedium)
                        Text(m.title, style = MaterialTheme.typography.titleSmall)
                        Text(m.body.take(600))
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистить память?") },
            text = { Text("Будут удалены все сохранённые уведомления, выдержки и итоги задач. Это действие нельзя отменить.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        withContext(Dispatchers.IO) { Graph.db.clearMemory(); Graph.db.clearNotifications() }
                        version++
                    }
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } },
        )
    }
}

@Composable
fun ActivityScreen() {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<ActivityRow>>(emptyList()) }
    var version by remember { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(version) { rows = withContext(Dispatchers.IO) { Graph.db.recentActivity(300) } }

    val appCtx = androidx.compose.ui.platform.LocalContext.current
    var crash by remember(version) { mutableStateOf(com.rwai.agent.core.CrashLog.last(appCtx)) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        crash?.let { text ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.padding(top = 8.dp)) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Последний сбой приложения (сохранён только на телефоне)", style = MaterialTheme.typography.titleSmall)
                    Text(text.take(1200), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { com.rwai.agent.core.CrashLog.clear(appCtx); crash = null }) { Text("Скрыть") }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            OutlinedButton(onClick = { version++ }) { Text("Обновить") }
            OutlinedButton(enabled = rows.isNotEmpty(), onClick = { confirmClear = true }) { Text("Очистить журнал") }
        }
        if (rows.isEmpty()) Text("Журнал пуст. Здесь появятся все шаги агента, подтверждения и ошибки.", style = MaterialTheme.typography.bodyMedium)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(rows) { i, r ->
                Column {
                    if (i == 0 || rows[i - 1].taskId != r.taskId) {
                        HorizontalDivider(modifier = Modifier.padding(top = 6.dp))
                        Text("Задача от " + dateFmt.format(Date(r.taskId)), style = MaterialTheme.typography.labelLarge)
                    }
                    Text(
                        "${dateFmt.format(Date(r.ts))}  ${r.tool ?: r.kind} — ${r.status}\n${r.detail.take(260)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистить журнал?") },
            text = { Text("Журнал действий будет удалён безвозвратно.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch { withContext(Dispatchers.IO) { Graph.db.clearActivity() }; version++ }
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } },
        )
    }
}
