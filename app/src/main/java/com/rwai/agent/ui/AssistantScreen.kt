package com.rwai.agent.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.rwai.agent.Graph
import com.rwai.agent.core.ChatEntry
import com.rwai.agent.core.ChatRole
import com.rwai.agent.core.Phase
import com.rwai.agent.voice.VoiceInput

@Composable
fun AssistantScreen() {
    val ctx = LocalContext.current
    val state by Graph.controller.state.collectAsState()
    val hasKey by Graph.settings.hasApiKey.collectAsState()
    val settings by Graph.settings.state.collectAsState()
    var input by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var voiceNote by remember { mutableStateOf<String?>(null) }
    val voice = remember { VoiceInput(ctx) }
    DisposableEffect(Unit) { onDispose { voice.stop() } }

    val listState = rememberLazyListState()
    val extraItems = 3 // шаги, подтверждение, черновик
    LaunchedEffect(state.chat.size, state.confirmation, state.steps.size, state.draft) {
        listState.animateScrollToItem(state.chat.size + extraItems - 1)
    }

    val startListening: () -> Unit = {
        listening = true
        voiceNote = null
        voice.start(
            languageTag = settings.language,
            onPartial = { input = it },
            onResult = { text, confidence ->
                listening = false
                input = text
                if (settings.voiceAutoRun && (confidence == null || confidence >= 0.6f)) {
                    Graph.controller.submit(text)
                    input = ""
                } else {
                    voiceNote = "Проверьте распознанный текст и нажмите «Отправить»."
                }
            },
            onFail = { msg ->
                listening = false
                voiceNote = msg
            },
        )
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening() else voiceNote = "Без разрешения на микрофон голосовой ввод недоступен."
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Статус: ${state.status}  ·  режим: ${settings.mode.title}",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            if (state.phase == Phase.RUNNING || state.phase == Phase.WAITING_CONFIRMATION) {
                OutlinedButton(onClick = { Graph.controller.stop() }) { Text("Стоп") }
            } else if (state.chat.isNotEmpty()) {
                OutlinedButton(onClick = { Graph.controller.clearChat() }) { Text("Очистить") }
            }
        }

        if (!hasKey) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                Text(
                    "Чтобы агент заработал, введите ваш API-ключ DeepSeek: вкладка «Настройки» → «DeepSeek».",
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.chat.isEmpty()) {
                item {
                    Text(
                        "Примеры команд:\n• «Открой Gmail»\n• «Покажи последние уведомления»\n• «Найди в памяти сообщения про аренду»\n• «Подготовь ответ на испанском, но пока не отправляй»",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.chat) { entry -> ChatBubble(entry) }

            item {
                if (state.phase == Phase.RUNNING) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(state.status, style = MaterialTheme.typography.titleSmall)
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                            state.steps.takeLast(6).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
            item {
                state.confirmation?.let { req ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (req.dangerous) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(req.title, style = MaterialTheme.typography.titleMedium)
                            Text(req.details)
                            Text(req.reason, style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { Graph.controller.resolveConfirmation(false) }) { Text("Отмена") }
                                Button(
                                    onClick = { Graph.controller.resolveConfirmation(true) },
                                    colors = if (req.dangerous) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
                                ) { Text("Подтвердить") }
                            }
                        }
                    }
                }
            }
            item {
                state.draft?.let { d ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Черновик · ${d.app} → ${d.recipient}", style = MaterialTheme.typography.titleSmall)
                            Text(d.text)
                        }
                    }
                }
            }
        }

        voiceNote?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (state.phase == Phase.WAITING_INPUT) "Ваш ответ…" else "Команда агенту…") },
                maxLines = 4,
            )
            FilledTonalButton(onClick = {
                if (listening) {
                    voice.stop()
                    listening = false
                } else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    startListening()
                } else {
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }) { Text(if (listening) "Слушаю…" else "Голос") }
            Button(
                enabled = input.isNotBlank() && state.phase != Phase.RUNNING && state.phase != Phase.WAITING_CONFIRMATION,
                onClick = {
                    Graph.controller.submit(input)
                    input = ""
                },
            ) { Text("Отправить") }
        }
    }
}

@Composable
private fun ChatBubble(entry: ChatEntry) {
    val scheme = MaterialTheme.colorScheme
    val (bg, label) = when (entry.role) {
        ChatRole.USER -> scheme.primaryContainer to "Вы"
        ChatRole.AGENT -> scheme.secondaryContainer to "RW AI"
        ChatRole.SYSTEM -> scheme.surfaceVariant to "Система"
        ChatRole.ERROR -> scheme.errorContainer to "Ошибка"
    }
    Card(colors = CardDefaults.cardColors(containerColor = bg), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(entry.text)
        }
    }
}
