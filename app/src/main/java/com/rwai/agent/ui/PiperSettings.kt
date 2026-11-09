package com.rwai.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.rwai.agent.local.PiperStore
import com.rwai.agent.local.PiperTts
import com.rwai.agent.logic.DownloadMath
import com.rwai.agent.logic.PiperCatalog
import com.rwai.agent.logic.Speakable

private fun sample(lang: String): String = when (lang) {
    "ru" -> Speakable.clean("Привет! Я Руся AI. Меня создал Maxim Ricco вместе с командой RusWeo. Чем могу помочь?")
    "es" -> "¡Hola! Soy Rusya, tu agente del teléfono. ¿En qué puedo ayudarte?"
    "de" -> "Hallo! Ich bin Rusja, dein Telefon-Agent. Wie kann ich helfen?"
    else -> "Hello! I am Rusya, your phone agent. How can I help you?"
}

/**
 * Голоса Piper: по одному постоянному голосу на язык (русский Дмитрий, English Joe, Español DaveFX, Deutsch Thorsten).
 * Скачиваются сами по Wi-Fi при запуске приложения и не удаляются. Для продажи они войдут в установочный пакет.
 */
@Composable
fun PiperSettings() {
    val ctx = LocalContext.current
    val status by PiperStore.status.collectAsState()
    val info by PiperTts.info.collectAsState()
    var enabled by remember { mutableStateOf(PiperStore.enabled(ctx)) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { PiperStore.refresh(ctx); PiperStore.ensureDefault(ctx) }

    SwitchRow(
        "Озвучка Piper (без интернета)",
        "Естественные голоса прямо на телефоне вместо системного голоса Google. Голоса постоянные, они скачиваются сами по Wi-Fi при запуске приложения (около 64 МБ каждый). Пока голос не скачан или что-то не вышло, говорит системный голос.",
        enabled,
    ) { v -> PiperStore.setEnabled(ctx, v); enabled = v; if (v) PiperStore.ensureDefault(ctx) }
    if (info.isNotBlank()) {
        Text("Состояние Piper: $info", style = MaterialTheme.typography.bodyMedium)
    } else {
        Text("Нажмите «Проверить» у скачанного голоса: здесь появится, заработал ли Piper.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

    PiperCatalog.LANGUAGES.forEach { (lang, name) ->
        val v = PiperCatalog.defaultFor(lang) ?: return@forEach
        val st = status[v.id]
        val installed = st?.state == PiperStore.State.READY || PiperStore.isInstalled(ctx, v)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 6.dp)) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            Text(v.title + " · постоянный голос", style = MaterialTheme.typography.bodyLarge)
            Text("Лицензия: ${v.license} · ${DownloadMath.human(v.size)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when {
                st?.state == PiperStore.State.DOWNLOADING -> {
                    LinearProgressIndicator(progress = { DownloadMath.percent(st.done, st.total) / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("Скачивание: ${DownloadMath.percent(st.done, st.total)} %. Держите приложение открытым.", style = MaterialTheme.typography.bodySmall)
                }
                st?.state == PiperStore.State.VERIFYING -> Text("Проверяю файл…", style = MaterialTheme.typography.bodySmall)
                st?.state == PiperStore.State.EXTRACTING -> Text("Распаковываю…", style = MaterialTheme.typography.bodySmall)
                installed -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("✓ Установлен", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    ActionPill("Проверить") {
                        note = null
                        // Проверка идёт самим Piper, без запасного голоса: так видно, работает он или нет.
                        if (!PiperTts.speak(ctx, sample(lang), lang)) note = "Не удалось начать озвучку."
                    }
                }
                else -> {
                    st?.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    Text("Ещё не скачан: скачается сам по Wi-Fi.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ActionPill("Скачать сейчас (${DownloadMath.human(v.size)})", accent = true) { note = PiperStore.download(ctx, v) }
                }
            }
        }
    }
}
