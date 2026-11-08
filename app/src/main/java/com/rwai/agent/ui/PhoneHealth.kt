package com.rwai.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rwai.agent.logic.AppFacts
import com.rwai.agent.logic.Finding
import com.rwai.agent.logic.PhoneAudit
import com.rwai.agent.phone.PhoneScan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Проверка приложений на признаки риска и список того, что сегодня дольше всего работало на экране. Всё считается на телефоне. */
@Composable
fun PhoneHealthCard(resumeTick: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var findings by remember { mutableStateOf<List<Finding>?>(null) }
    var top by remember { mutableStateOf<List<AppFacts>>(emptyList()) }
    val usageOk = remember(resumeTick) { PhoneScan.hasUsageAccess(ctx) }

    Card {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Проверка приложений", style = MaterialTheme.typography.titleMedium)
            Text(
                "Показывает приложения с подозрительными признаками (установка вне магазина, чтение экрана и уведомлений, права администратора) и то, что сегодня дольше всего работало. " +
                    "Это не антивирус: код приложений не проверяется, вирусных баз нет. Ничего не отправляется в интернет.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        val apps = withContext(Dispatchers.Default) { PhoneScan.scan(ctx) }
                        findings = PhoneAudit.analyze(apps, ctx.packageName)
                        top = PhoneAudit.topUsage(apps.filter { it.pkg != ctx.packageName })
                        busy = false
                    }
                }) { Text("Проверить") }
                if (!usageOk) OutlinedButton(onClick = { ctx.startActivity(PhoneScan.usageSettingsIntent()) }) { Text("Доступ к статистике") }
            }
            if (busy) CircularProgressIndicator()
            findings?.let { list ->
                Text(PhoneAudit.summary(list), fontWeight = FontWeight.SemiBold)
                list.take(15).forEach { f ->
                    Text((if (f.level >= 3) "🔴 " else if (f.level == 2) "🟠 " else "• ") + f.app + ": " + f.text, style = MaterialTheme.typography.bodyMedium)
                }
                if (usageOk) {
                    if (top.isEmpty()) Text("Сегодня данных об использовании пока нет.", style = MaterialTheme.typography.bodySmall)
                    else {
                        Text("Дольше всего на экране сегодня (они чаще всего греют и разряжают телефон):", style = MaterialTheme.typography.labelLarge)
                        top.forEach { Text("• ${it.label} — ${it.foregroundMinutes} мин") }
                    }
                } else {
                    Text("Чтобы видеть, какие приложения дольше всего работали, дайте «Доступ к статистике» (разрешение выдаётся в системных настройках).", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
