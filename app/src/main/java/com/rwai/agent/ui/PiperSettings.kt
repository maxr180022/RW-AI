package com.rwai.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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

/** Голоса Piper: включить, скачать по языкам, выбрать, проверить, удалить. Работают без интернета, голоса скачиваются один раз. */
@Composable
fun PiperSettings() {
    val ctx = LocalContext.current
    val status by PiperStore.status.collectAsState()
    var enabled by remember { mutableStateOf(PiperStore.enabled(ctx)) }
    var tick by remember { mutableIntStateOf(0) } // перерисовка после выбора голоса
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { PiperStore.refresh(ctx) }

    SwitchRow(
        "Озвучка Piper (без интернета)",
        "Естественные голоса прямо на телефоне вместо системного голоса Google. Голос каждого языка скачивается один раз, около 64 МБ. Пока голос не скачан или что-то не вышло, говорит системный голос.",
        enabled,
    ) { v -> PiperStore.setEnabled(ctx, v); enabled = v }
    note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

    PiperCatalog.LANGUAGES.forEach { (lang, name) ->
        Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
        PiperCatalog.forLanguage(lang).forEach { v ->
            val st = status[v.id]
            val installed = st?.state == PiperStore.State.READY || PiperStore.isInstalled(ctx, v)
            val picked = PiperStore.chosen(ctx, lang)?.id == v.id && tick >= 0
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(v.title + (if (installed && picked) " · выбран" else ""), style = MaterialTheme.typography.bodyLarge)
                Text("Лицензия: ${v.license} · ${DownloadMath.human(v.size)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    st?.state == PiperStore.State.DOWNLOADING -> {
                        LinearProgressIndicator(progress = { DownloadMath.percent(st.done, st.total) / 100f }, modifier = Modifier.fillMaxWidth())
                        Text("Скачивание: ${DownloadMath.percent(st.done, st.total)} %. Держите приложение открытым.", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { PiperStore.stop(ctx, v) }) { Text("Остановить") }
                    }
                    st?.state == PiperStore.State.VERIFYING -> Text("Проверяю файл…", style = MaterialTheme.typography.bodySmall)
                    st?.state == PiperStore.State.EXTRACTING -> Text("Распаковываю…", style = MaterialTheme.typography.bodySmall)
                    installed -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!picked) Button(onClick = { PiperStore.choose(ctx, v); tick++ }) { Text("Выбрать") }
                        OutlinedButton(onClick = {
                            PiperStore.choose(ctx, v); tick++
                            if (!PiperTts.speak(ctx, sample(lang), lang)) note = "Не удалось озвучить."
                        }) { Text("Проверить") }
                        OutlinedButton(onClick = { PiperTts.stop(); PiperStore.delete(ctx, v); tick++ }) { Text("Удалить") }
                    }
                    else -> {
                        st?.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        Button(onClick = { note = PiperStore.download(ctx, v) }) { Text("Скачать (${DownloadMath.human(v.size)})") }
                    }
                }
            }
        }
    }
}
