package com.rwai.agent.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.rwai.agent.Graph
import com.rwai.agent.logic.AutonomyMode
import com.rwai.agent.logic.Endpoint
import com.rwai.agent.settings.Settings as AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


/** «Ещё → Настройки»: один список разделов (доступ, параметры, профиль, подключения). [section] выбирает, какой раздел открыть сразу (0 доступ, 2 профиль, 3 подключения, иначе список). */
@Composable
fun SettingsHost(resumeTick: Int, section: Int, onSection: (Int) -> Unit) {
    SettingsScreen(resumeTick, section)
}

@Composable
fun PermissionsScreen(resumeTick: Int) {
    val ctx = LocalContext.current
    var showA11yInfo by remember { mutableStateOf(false) }
    val notif = remember(resumeTick) { isNotificationAccessEnabled(ctx) }
    val a11y = remember(resumeTick) { isAccessibilityEnabled(ctx) }
    val mic = remember(resumeTick) { ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
    val post = remember(resumeTick) {
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val postLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Руся AI не получает права скрытно: каждое разрешение вы включаете сами в системных настройках Android и можете отозвать в любой момент.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )

        PermissionCard(
            title = "Доступ к уведомлениям",
            enabled = notif,
            why = "Нужен, чтобы сохранять приходящие уведомления (отправитель, время, текст) для раздела «Входящие» и поиска. Скрытое приложением содержимое не извлекается.",
            actionLabel = "Открыть настройки доступа",
            onAction = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
        )
        PermissionCard(
            title = "Специальные возможности (управление интерфейсом)",
            enabled = a11y,
            why = "Нужны, чтобы по вашей команде читать видимый текст на экране, нажимать кнопки, прокручивать и вводить текст в других приложениях. Пароли, CAPTCHA, биометрия и экран блокировки не обрабатываются.",
            actionLabel = "Прочитать и включить",
            onAction = { showA11yInfo = true },
        )
        PermissionCard(
            title = "Микрофон",
            enabled = mic,
            why = "Нужен только для кнопки «Голос» (push-to-talk). Постоянного прослушивания нет.",
            actionLabel = "Разрешить",
            onAction = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        )
        PermissionCard(
            title = "Показ уведомлений Руся AI",
            enabled = post,
            why = "Нужен для запросов подтверждения и кнопки «Стоп», когда агент работает в другом приложении.",
            actionLabel = "Разрешить",
            onAction = {
                if (Build.VERSION.SDK_INT >= 33) postLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
        )
        Card {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val isAssistant = remember(resumeTick) { isDefaultAssistant(ctx) }
                Text("Ассистент по умолчанию" + if (isAssistant) " ✅" else "", style = MaterialTheme.typography.titleSmall)
                if (isAssistant) Text("Руся AI уже выбран ассистентом: долгое нажатие кнопки вызова открывает его панель.", style = MaterialTheme.typography.bodySmall)
                else Text(
                    "Чтобы долгое нажатие кнопки «Домой» или боковой кнопки открывало Руся AI вместо Google: нажмите кнопку ниже и выберите Руся AI в пункте «Цифровой помощник». " +
                        "На Samsung также проверьте: Настройки → Дополнительные функции → Боковая кнопка → Нажатие и удержание → «Открыть цифровой помощник».",
                    style = MaterialTheme.typography.bodySmall,
                )
                val pickAssistant = {
                    try {
                        ctx.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: Exception) {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                if (isAssistant) OutlinedButton(onClick = pickAssistant) { Text("Сменить ассистента") }
                else Button(onClick = pickAssistant) { Text("Выбрать ассистента") }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("🔒 Банки закрыты для агента", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Руся AI не открывает банковские и платёжные приложения и сайты, не читает их экран и не сохраняет их уведомления. Это правило нельзя отключить.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Card {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Экономия заряда (по желанию)", style = MaterialTheme.typography.titleSmall)
                Text("Если Samsung «усыпляет» Руся AI и уведомления перестают сохраняться: Настройки → Приложения → Руся AI → Батарея → «Без ограничений».", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                    Text("Открыть настройки батареи")
                }
            }
        }
    }

    if (showA11yInfo) AccessibilityHelpDialog(onDismiss = { showA11yInfo = false })
}

@Composable
private fun PermissionCard(title: String, enabled: Boolean, why: String, actionLabel: String, onAction: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(if (enabled) "Состояние: включено" else "Состояние: выключено", style = MaterialTheme.typography.labelLarge)
            Text(why, style = MaterialTheme.typography.bodySmall)
            if (!enabled) Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}
