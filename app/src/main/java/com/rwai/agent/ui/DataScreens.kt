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
import com.rwai.agent.core.Phase
import com.rwai.agent.digest.AnsweredStore
import com.rwai.agent.logic.AnsweredCards
import com.rwai.agent.logic.DigestKind
import com.rwai.agent.logic.DigestView
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
                val digestText: Pair<Long?, String>? = summary?.let { null to it }
                    ?: com.rwai.agent.digest.DigestManager.lastDigest()?.let { (ts, t) -> ts to t }
                digestText?.let { (ts, t) -> DigestCards(ts, t) }
                Text(
                    "Показано ${rows.size} уведомлений. Приложения скрывают часть содержимого — Руся AI показывает только то, что отдала система.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(rows) { r -> NotificationItem(r) }
    }
}

@Composable
private fun DigestCards(ts: Long?, text: String) {
    val items = remember(text) { DigestView.parse(text) }
    var answered by remember { mutableStateOf(AnsweredStore.raw()) }
    var composing by remember(text) { mutableStateOf<String?>(null) } // ключ карточки, у которой открыто поле «что сделать»
    var draft by remember(text) { mutableStateOf("") }
    var hint by remember(text) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Сводка" + (ts?.let { " · " + dateFmt.format(Date(it)) } ?: ""),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (items.isEmpty()) {
            GlassCard { MdText(text) }
        }
        items.forEachIndexed { index, it0 ->
            val key = DigestView.cardKey(text, index, it0)
            val needs = DigestView.needsReply(it0.kind)
            val done = needs && AnsweredCards.has(answered, key)
            // Цвета только из палитры приложения: красный — ждёт ответа, зелёный — уже отработано, остальное — просто информация.
            val (accent, tag) = when {
                done -> RwPalette.Success to "Отвечено"
                it0.kind == DigestKind.URGENT -> RwPalette.Danger to "Срочно · не отвечено"
                it0.kind == DigestKind.WAITING -> RwPalette.Danger to "Ждут ответа · не отвечено"
                it0.kind == DigestKind.PERSON -> RwPalette.Danger to "Сообщение · не отвечено"
                it0.kind == DigestKind.DEAL -> RwPalette.Blue to "Договорённости"
                else -> RwPalette.Violet to "Заметка"
            }
            GlassCard(accent = accent) {
                Text(tag.uppercase(), style = MaterialTheme.typography.labelSmall, color = accent)
                val kindLabel = when (it0.kind) {
                    DigestKind.URGENT -> "Срочно"; DigestKind.WAITING -> "Ждут ответа"; DigestKind.PERSON -> "Сообщение"; DigestKind.DEAL -> "Договорённости"; else -> "Заметка"
                }
                val head = it0.title?.takeIf { t -> t.isNotBlank() && !t.equals(kindLabel, true) }
                if (head != null) Text(head, style = MaterialTheme.typography.titleSmall)
                if (it0.body.isNotBlank()) MdText(it0.body, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (done) 0.62f else 0.88f))
                if (needs) {
                    // Кнопка остаётся всегда, и после ответа: можно ответить ещё раз или вернуть карточку в «не отвечено».
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        ActionPill(if (it0.kind == DigestKind.URGENT) "Разобраться" else "Ответить", accent = !done) {
                            composing = if (composing == key) null else key
                            hint = null
                        }
                        if (done) ActionPill("Не отвечено") { answered = AnsweredStore.unmark(key) }
                    }
                    if (composing == key) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it.take(500) },
                            label = { Text("Что сделать?") },
                            minLines = 2,
                            maxLines = 5,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ActionPill("Отправить", accent = true) {
                                val task = DigestView.replyTask(it0, draft)
                                when {
                                    task == null -> hint = "Напишите, что нужно сделать."
                                    !Graph.settings.hasApiKey.value -> hint = "Сначала введите API-ключ DeepSeek в настройках."
                                    Graph.controller.state.value.phase != Phase.IDLE -> hint = "Агент занят: дождитесь окончания задачи или нажмите «Стоп» на вкладке «Агент»."
                                    else -> {
                                        Graph.controller.submit(task, attach = DigestView.contextFor(it0))
                                        answered = AnsweredStore.mark(key)
                                        composing = null
                                        draft = ""
                                        hint = null
                                        AgentPrefill.text.value = "" // переключает на вкладку «Агент», чтобы видеть работу
                                    }
                                }
                            }
                            ActionPill("Отмена") { composing = null; hint = null }
                        }
                        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                } else if (it0.kind == DigestKind.DEAL) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        ActionPill("В календарь", accent = true) { AgentPrefill.text.value = "Добавь в календарь: ${it0.body.take(200)}" }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationItem(r: NotificationRow) {
    val ctx = LocalContext.current
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                r.app.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = RwPalette.Blue,
                modifier = Modifier.weight(1f),
            )
            Text(dateFmt.format(Date(r.ts)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val who = r.sender ?: r.title
        if (!who.isNullOrBlank()) Text(who, style = MaterialTheme.typography.titleSmall)
        Text(if (r.hidden) "[содержимое скрыто приложением]" else (r.body ?: ""), style = MaterialTheme.typography.bodyMedium)
        if (!r.tr.isNullOrBlank()) Text("↳ " + r.tr, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        if (!r.hidden && !who.isNullOrBlank()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                ActionPill("Ответить", accent = true) { AgentPrefill.text.value = "Ответь $who в ${r.app}: " }
                ActionPill("Открыть") {
                    val i = ctx.packageManager.getLaunchIntentForPackage(r.pkg)
                    if (i != null) ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
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
            Text("Ничего не найдено. Память содержит только то, что Руся AI реально получил (уведомления и сохранённые выдержки).", style = MaterialTheme.typography.bodyMedium)
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
