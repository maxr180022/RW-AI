package com.rwai.agent.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.rwai.agent.Graph

/** Сводка сообщений, автоперевод, ключ поиска Brave и мост к Claude Code. */
@Composable
fun ExtrasSettings() {
    val settings by Graph.settings.state.collectAsState()
    val secrets = Graph.secretStore

    SectionTitle("Голос агента")
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var voices by remember { mutableStateOf(Graph.speaker.voices(settings.language)) }
    Text(
        "Голос берётся из синтезатора речи телефона. Выберите понравившийся (нажатие — прослушать). Более живые голоса можно скачать в настройках синтеза речи (Google).",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { voices = Graph.speaker.voices(settings.language) }) { Text("Обновить список") }
        OutlinedButton(onClick = {
            try {
                ctx.startActivity(android.content.Intent("com.android.settings.TTS_SETTINGS").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                try { ctx.startActivity(android.content.Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
            }
        }) { Text("Скачать голоса") }
    }
    if (voices.isEmpty()) Text("Нет установленных голосов для этого языка. Нажмите «Скачать голоса».", style = MaterialTheme.typography.bodyMedium)
    Row(modifier = Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = settings.voiceName.isBlank(), onClick = { Graph.settings.update { it.copy(voiceName = "") } }, label = { Text("Авто") })
        voices.forEach { v ->
            FilterChip(selected = settings.voiceName == v.name, onClick = {
                Graph.settings.update { it.copy(voiceName = v.name) }
                Graph.speaker.speak("Привет! Я Руся, твой агент.", settings.language)
            }, label = { Text(v.label) })
        }
    }

    SectionTitle("Сводка и перевод сообщений")
    SwitchRow(
        "Сводка «кому ответить»",
        "После долгого простоя экрана и разблокировки покажет, кто ждёт ответа. Реклама, рассылки и шумные группы отфильтровываются. Нужен доступ к уведомлениям.",
        settings.digestEnabled,
    ) { v -> Graph.settings.update { it.copy(digestEnabled = v) } }
    if (settings.digestEnabled) {
        Text("Показывать, если экран был выключен не меньше:", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(2, 4, 8).forEach { h ->
                FilterChip(selected = settings.digestGapHours == h, onClick = { Graph.settings.update { it.copy(digestGapHours = h) } }, label = { Text("$h ч") })
            }
        }
    }
    SwitchRow(
        "Автоперевод сообщений",
        "Сообщения на испанском и английском переводятся на язык приложения и показываются во «Входящих». Для перевода текст отправляется в DeepSeek.",
        settings.autoTranslate,
    ) { v -> Graph.settings.update { it.copy(autoTranslate = v) } }
    Text(
        "Голосовые сообщения в уведомлении приходят без текста, поэтому Руся AI их не расшифровывает. В WhatsApp включите «Расшифровка голосовых сообщений» (Настройки → Чаты) — тогда текст появится сам.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    SectionTitle("Поиск в интернете")
    var hasBrave by remember { mutableStateOf(secrets.has("brave_search_key")) }
    var braveInput by remember { mutableStateOf("") }
    Text(
        if (hasBrave) "Ключ Brave Search сохранён: поиск идёт через официальный API." else "Без ключа поиск идёт через DuckDuckGo и Wikipedia. Ключ Brave Search (бесплатный тариф) делает поиск стабильнее.",
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(
        value = braveInput, onValueChange = { braveInput = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        label = { Text("Ключ Brave Search (необязательно)") }, visualTransformation = PasswordVisualTransformation(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = braveInput.trim().length >= 10, onClick = { secrets.put("brave_search_key", braveInput.trim()); braveInput = ""; hasBrave = true }) { Text("Сохранить") }
        OutlinedButton(enabled = hasBrave, onClick = { secrets.remove("brave_search_key"); hasBrave = false }) { Text("Удалить") }
    }

    SectionTitle("Мост к Claude Code (компьютер)")
    var hasToken by remember { mutableStateOf(secrets.has("bridge_token")) }
    var tokenInput by remember { mutableStateOf("") }
    var urlInput by remember(settings.bridgeUrl) { mutableStateOf(settings.bridgeUrl) }
    Text(
        "Агент сможет передавать сложные задачи Claude Code на вашем компьютере (по вашей подписке). Каждый вызов подтверждаете вы. " +
            "Запустите на компьютере bridge/rw_bridge.py (инструкция в docs/USER_GUIDE_RU.md). Используйте адрес из частной сети или Tailscale, не открывайте мост в интернет.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = urlInput, onValueChange = { urlInput = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        label = { Text("Адрес моста, например http://192.168.1.20:8765") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )
    OutlinedTextField(
        value = tokenInput, onValueChange = { tokenInput = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        label = { Text(if (hasToken) "Заменить токен моста" else "Токен моста") }, visualTransformation = PasswordVisualTransformation(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            enabled = (urlInput.startsWith("http://") || urlInput.startsWith("https://") || urlInput.isBlank()),
            onClick = {
                Graph.settings.update { it.copy(bridgeUrl = urlInput.trim()) }
                if (tokenInput.trim().length >= 16) { secrets.put("bridge_token", tokenInput.trim()); tokenInput = ""; hasToken = true }
            },
        ) { Text("Сохранить") }
        OutlinedButton(enabled = hasToken || settings.bridgeUrl.isNotBlank(), onClick = {
            secrets.remove("bridge_token"); hasToken = false; Graph.settings.update { it.copy(bridgeUrl = "") }; urlInput = ""
        }) { Text("Отключить") }
    }
}
