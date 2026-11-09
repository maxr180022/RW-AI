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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rwai.agent.local.LocalLlm
import com.rwai.agent.local.LocalModelStore
import com.rwai.agent.logic.DownloadMath
import com.rwai.agent.logic.LocalModelSpec
import com.rwai.agent.logic.LocalModelText
import kotlinx.coroutines.launch

private const val SAMPLE = "Hola, ¿cómo estás? ¿Podemos vernos mañana a las 5 para hablar del contrato? Si no puedes, avísame y lo movemos al jueves."

/**
 * Раздел «Локальная модель»: скачать один раз, проверить на примере (перевод), удалить. Пока модель нужна только для проверки качества и скорости;
 * агент и перевод сообщений работают как раньше через DeepSeek.
 */
@Composable
fun LocalModelSettings() {
    val ctx = LocalContext.current
    val st by LocalModelStore.status.collectAsState()
    val scope = rememberCoroutineScope()
    var wifiOnly by rememberSaveable { mutableStateOf(true) }
    var note by remember { mutableStateOf<String?>(null) }
    var sample by rememberSaveable { mutableStateOf(SAMPLE) }
    var result by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { LocalModelStore.refresh(ctx) }

    SectionTitle("Локальная модель (без токенов)")
    Text(LocalModelSpec.TITLE, style = MaterialTheme.typography.titleSmall)
    Text(LocalModelText.hint(st.state.name, st.done, st.total), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (st.state == LocalModelStore.State.DOWNLOADING || st.state == LocalModelStore.State.VERIFYING) {
        LinearProgressIndicator(progress = { DownloadMath.percent(st.done, st.total) / 100f }, modifier = Modifier.fillMaxWidth())
    }
    st.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

    when (st.state) {
        LocalModelStore.State.NONE, LocalModelStore.State.PARTIAL, LocalModelStore.State.ERROR -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = wifiOnly, onCheckedChange = { wifiOnly = it })
                Text("Только по Wi-Fi", style = MaterialTheme.typography.bodyMedium)
            }
            Button(onClick = { note = LocalModelStore.start(ctx, wifiOnly) }) {
                Text(if (st.state == LocalModelStore.State.PARTIAL || st.done > 0) "Докачать" else "Скачать модель (${DownloadMath.human(st.total)})")
            }
            if (st.done > 0) OutlinedButton(onClick = { LocalModelStore.delete(ctx); note = null }) { Text("Удалить скачанное") }
        }
        LocalModelStore.State.DOWNLOADING -> OutlinedButton(onClick = { LocalModelStore.stop(ctx) }) { Text("Остановить (можно докачать позже)") }
        LocalModelStore.State.VERIFYING -> Unit
        LocalModelStore.State.READY -> {
            OutlinedTextField(
                value = sample,
                onValueChange = { sample = it.take(1200) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Текст для проверки (испанский или английский)") },
                minLines = 2,
                maxLines = 5,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !testing && sample.isNotBlank(),
                    onClick = {
                        testing = true
                        result = "Запускаю модель…"
                        scope.launch {
                            val t0 = System.currentTimeMillis()
                            result = try {
                                val a = LocalLlm.generate(ctx, "Переведи на русский язык. Верни только перевод, без пояснений.\n\nТекст: " + sample.trim())
                                fun sec(ms: Long) = String.format(java.util.Locale.forLanguageTag("ru"), "%.1f", ms / 1000.0)
                                val load = if (LocalLlm.lastLoadMs > 0) "загрузка модели " + sec(LocalLlm.lastLoadMs) + " с, " else "модель уже в памяти, "
                                a + "

[Локальная модель, " + LocalLlm.backend() + ": " + load + "перевод " + sec(LocalLlm.lastGenMs) + " с]"
                            } catch (e: Exception) {
                                e.message ?: "Ошибка"
                            }
                            testing = false
                        }
                    },
                ) { Text(if (testing) "Считаю…" else "Перевести на русский") }
                OutlinedButton(onClick = { LocalLlm.release(); result = null }) { Text("Выгрузить из памяти") }
            }
            result?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
            OutlinedButton(onClick = { LocalModelStore.delete(ctx); result = null }) { Text("Удалить модель") }
        }
    }
}
