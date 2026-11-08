package com.rwai.agent.ui

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.rwai.agent.Graph
import com.rwai.agent.connectors.CalendarClient
import com.rwai.agent.connectors.MailAccount
import com.rwai.agent.connectors.MailClient
import com.rwai.agent.connectors.MailStore
import com.rwai.agent.connectors.MediaControl
import com.rwai.agent.logic.MailPresets
import com.rwai.agent.logic.MailRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun ConnCard(title: String, connected: Boolean, content: @Composable () -> Unit) {
    GlassCard(accent = if (connected) Color(0xFF2ECC8F) else null) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Row(
                modifier = Modifier.clip(RoundedCornerShape(50))
                    .background((if (connected) Color(0xFF2ECC8F) else Color.White).copy(alpha = 0.13f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(if (connected) Color(0xFF2ECC8F) else MaterialTheme.colorScheme.onSurfaceVariant))
                Spacer(Modifier.width(6.dp))
                Text(if (connected) "Подключено" else "Не подключено", style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
        content()
    }
}

/** Подключения: музыка, почта, YouTube, календарь, мессенджеры. Каждое включается здесь, без программирования. */
@Composable
fun ConnectionsScreen(resumeTick: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val secrets = Graph.secretStore

    val notif = remember(resumeTick) { isNotificationAccessEnabled(ctx) }
    val a11y = remember(resumeTick) { isAccessibilityEnabled(ctx) }
    var calRead by remember(resumeTick) { mutableStateOf(CalendarClient.canRead(ctx)) }
    var calWrite by remember(resumeTick) { mutableStateOf(CalendarClient.canWrite(ctx)) }
    var locOk by remember(resumeTick) { mutableStateOf(com.rwai.agent.connectors.LocationFinder.hasPermission(ctx)) }
    val locLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        locOk = com.rwai.agent.connectors.LocationFinder.hasPermission(ctx)
    }
    val calLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        calRead = CalendarClient.canRead(ctx); calWrite = CalendarClient.canWrite(ctx)
    }

    var mailUser by remember { mutableStateOf(MailStore.user()) }
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var imap by remember { mutableStateOf("") }
    var smtp by remember { mutableStateOf("") }
    var mailBusy by remember { mutableStateOf(false) }
    var mailMsg by remember { mutableStateOf<String?>(null) }

    var hasYt by remember { mutableStateOf(secrets.has("youtube_api_key")) }
    var ytKey by remember { mutableStateOf("") }
    var nowPlaying by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Подключите сервисы, и агент будет работать с ними напрямую, а не бродить по приложениям. Все пароли и ключи хранятся зашифрованно на телефоне. Отправка писем и запись в календарь — только с вашего подтверждения.",
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp),
        )

        // ---- Музыка ----
        ConnCard("Музыка (любой плеер)", notif) {
            Text("YouTube Music, Spotify и другие: что играет, пауза, следующий трек, «включи …». Отдельный вход не нужен — достаточно доступа к уведомлениям.", style = MaterialTheme.typography.bodySmall)
            if (!notif) {
                Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Включить доступ к уведомлениям") }
            } else {
                OutlinedButton(onClick = { nowPlaying = MediaControl.status(ctx) }) { Text("Что играет сейчас?") }
                nowPlaying?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }

        // ---- Почта ----
        ConnCard("Почта (Gmail, Яндекс, Mail.ru, iCloud…)", mailUser != null) {
            if (mailUser != null) {
                Text("Подключён ящик: $mailUser. Агент читает и ищет письма (письма от банков скрыты) и отправляет только после вашего подтверждения.", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { MailStore.clear(); mailUser = null; mailMsg = "Почта отключена, данные входа удалены." }) { Text("Отключить почту") }
            } else {
                OutlinedTextField(
                    value = email, onValueChange = { email = it.trim() }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Адрес почты") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                )
                if (email.contains('@')) Text(MailPresets.passwordHelp(email), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = pass, onValueChange = { pass = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Пароль приложения") }, visualTransformation = PasswordVisualTransformation(),
                )
                if (email.contains('@') && MailPresets.forAddress(email) == null) {
                    Text("Для этого почтового сервиса укажите серверы (например imap.company.com и smtp.company.com:465):", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = imap, onValueChange = { imap = it.trim() }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("IMAP-сервер") })
                    OutlinedTextField(value = smtp, onValueChange = { smtp = it.trim() }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("SMTP-сервер") })
                }
                Button(enabled = !mailBusy && MailRules.validAddress(email) && pass.length >= 8, onClick = {
                    mailBusy = true; mailMsg = "Проверяю вход…"
                    scope.launch {
                        val server = MailStore.resolve(email, imap, smtp)
                        if (server == null) {
                            mailMsg = "Укажите IMAP- и SMTP-серверы."
                        } else {
                            val err = withContext(Dispatchers.IO) { MailClient.test(MailAccount(email, pass.trim(), server)) }
                            if (err == null) {
                                MailStore.save(email, pass, imap, smtp)
                                mailUser = email; pass = ""; mailMsg = "Готово: почта подключена."
                            } else mailMsg = err
                        }
                        mailBusy = false
                    }
                }) { Text("Подключить") }
                if (mailBusy) CircularProgressIndicator()
            }
            mailMsg?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
        }

        // ---- YouTube ----
        ConnCard("YouTube", true) {
            Text(
                if (hasYt) "Ключ YouTube API сохранён: агент получает список видео со ссылками."
                else "Без ключа агент открывает поиск в приложении YouTube. С бесплатным ключом YouTube Data API v3 (console.cloud.google.com → APIs → YouTube Data API v3 → Credentials) он видит список результатов и выбирает нужный ролик.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = ytKey, onValueChange = { ytKey = it.trim() }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Ключ YouTube API (необязательно)") }, visualTransformation = PasswordVisualTransformation(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = ytKey.length >= 20, onClick = { secrets.put("youtube_api_key", ytKey); ytKey = ""; hasYt = true }) { Text("Сохранить") }
                OutlinedButton(enabled = hasYt, onClick = { secrets.remove("youtube_api_key"); hasYt = false }) { Text("Удалить ключ") }
            }
        }

        // ---- Календарь ----
        ConnCard("Календарь", calRead && calWrite) {
            Text("Агент видит события на ближайшие дни и добавляет новые (с подтверждением) прямо в календарь телефона, без открытия приложения.", style = MaterialTheme.typography.bodySmall)
            if (!(calRead && calWrite)) {
                Button(onClick = { calLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) }) { Text("Разрешить доступ к календарю") }
            }
        }

        // ---- Погода ----
        ConnCard("Погода", locOk) {
            Text(
                "Спросите «какая погода?» — Руся определит город по примерной геолокации телефона и ответит без открытия приложений. Наружу уходят только координаты, округлённые до ~1 км, и только к бесплатному сервису Open-Meteo; модели DeepSeek они не передаются. Можно и без разрешения: «погода в Перми».",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!locOk) Button(onClick = { locLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }) { Text("Разрешить примерную геолокацию") }
        }

        // ---- WhatsApp / Telegram ----
        ConnCard("WhatsApp, Telegram, Instagram", notif && a11y) {
            Text(
                "Для личных переписок у этих сервисов нет открытого способа чтения, поэтому агент работает так: новые сообщения видит через уведомления (вкладка «Входящие», сводка, перевод), а старую переписку читает с экрана, когда открывает чат. " +
                    "Нужны «Доступ к уведомлениям» и «Специальные возможности» (Ещё → Доступ).",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Уведомления: " + (if (notif) "✅" else "❌") + "   Управление экраном: " + (if (a11y) "✅" else "❌"), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
