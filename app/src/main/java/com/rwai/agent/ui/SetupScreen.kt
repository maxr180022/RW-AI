package com.rwai.agent.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.rwai.agent.Graph
import kotlinx.coroutines.launch

private const val DEEPSEEK_KEYS_URL = "https://platform.deepseek.com/api_keys"

/**
 * Первый запуск: понятное подключение DeepSeek в три шага.
 * Пользователю нужно только получить ключ, вставить его и нажать одну кнопку.
 */
@Composable
fun SetupScreen(resumeTick: Int, onBegin: () -> Unit, onFinish: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var keyInput by rememberSaveable { mutableStateOf("") }
    var showKey by rememberSaveable { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var connected by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showA11y by remember { mutableStateOf(false) }

    val cleanKey = keyInput.trim().removePrefix("Bearer ").trim()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())) {
            // Шапка с градиентом
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(brandBrush())
                    .padding(24.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    BrandMark(52.dp)
                    Text("Руся AI", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        if (connected) "Готово! Осталось включить доступ к экрану." else "Ваш личный ИИ-оператор смартфона. Подключим его за минуту.",
                        color = Color.White.copy(alpha = 0.92f),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (!connected) {
                    StepCard(number = "1", title = "Получите ключ DeepSeek") {
                        Text(
                            "Ключ — это как пароль, по которому Руся AI обращается к нейросети DeepSeek. Его создают один раз в вашем аккаунте DeepSeek.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text("Что сделать:", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "1) Нажмите кнопку ниже и войдите (или зарегистрируйтесь) на сайте DeepSeek.\n" +
                                "2) Если сайт попросит, пополните баланс аккаунта на небольшую сумму: запросы к нейросети оплачиваются с него.\n" +
                                "3) На странице «API keys» нажмите «Create new API key», дайте любое имя.\n" +
                                "4) Нажмите на значок копирования рядом с ключом и вернитесь сюда. Ключ показывается только один раз.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(
                            onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DEEPSEEK_KEYS_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                        ) { Text("Открыть сайт DeepSeek") }
                    }

                    StepCard(number = "2", title = "Вставьте ключ сюда") {
                        OutlinedTextField(
                            value = keyInput,
                            onValueChange = { keyInput = it; error = null },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("sk-…") },
                            singleLine = true,
                            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                            isError = error != null,
                            enabled = !connecting,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { clipboard.getText()?.text?.let { keyInput = it; error = null } },
                                enabled = !connecting,
                            ) { Text("Вставить из буфера") }
                            TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Скрыть" else "Показать") }
                        }
                        Text(
                            "Ключ хранится только на этом телефоне в зашифрованном виде и уходит только на сервер DeepSeek.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    StepCard(number = "3", title = "Подключите") {
                        Button(
                            onClick = {
                                scope.launch {
                                    connecting = true
                                    error = null
                                    onBegin()
                                    Graph.settings.saveApiKey(cleanKey)
                                    val r = Graph.brain.validate()
                                    if (r.ok) {
                                        connected = true
                                        if (Graph.settings.current.quickReply) com.rwai.agent.core.AgentNotifications.showQuick(Graph.appContext())
                                    } else {
                                        Graph.settings.deleteApiKey()
                                        error = r.message
                                    }
                                    connecting = false
                                }
                            },
                            enabled = cleanKey.length >= 10 && !connecting,
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                        ) {
                            if (connecting) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.onPrimary)
                                Text("  Проверяю ключ…")
                            } else {
                                Text("Подключить", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        error?.let {
                            Text("Не получилось: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Проверьте, что ключ скопирован целиком и интернет работает, затем нажмите «Подключить» ещё раз.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("✅", fontSize = 26.sp)
                            Column {
                                Text("DeepSeek подключён", style = MaterialTheme.typography.titleMedium)
                                Text("Ключ принят, соединение работает.", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    Text("Теперь дайте Руся AI доступ", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
                    Text(
                        "Каждое разрешение вы включаете сами в настройках Android и можете отозвать в любой момент. Без доступа к экрану агент сможет только отвечать и читать уведомления.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SetupPermissions(resumeTick = resumeTick, onOpenA11y = { showA11y = true })
                    Button(onClick = onFinish, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                        Text("Начать работу", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        "Разрешения можно включить и позже: вкладка «Ещё» → «Доступ».",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showA11y) AccessibilityHelpDialog(onDismiss = { showA11y = false })
}

@Composable
private fun StepCard(number: String, title: String, content: @Composable () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier.size(30.dp).clip(CircleShape).background(brandBrush()),
                    contentAlignment = Alignment.Center,
                ) { Text(number, color = Color.White, fontWeight = FontWeight.Bold) }
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

@Composable
private fun SetupPermissions(resumeTick: Int, onOpenA11y: () -> Unit) {
    val ctx = LocalContext.current
    val a11y = remember(resumeTick) { isAccessibilityEnabled(ctx) }
    val notif = remember(resumeTick) { isNotificationAccessEnabled(ctx) }
    val mic = remember(resumeTick) { ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    PermissionRow("Управление экраном", "Чтобы агент открывал приложения и нажимал кнопки", a11y, "Включить", onOpenA11y)
    PermissionRow(
        "Доступ к уведомлениям", "Чтобы собирать входящие сообщения в одном месте", notif, "Включить",
        { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
    )
    PermissionRow("Микрофон", "Для голосовых команд (по кнопке)", mic, "Разрешить", { micLauncher.launch(Manifest.permission.RECORD_AUDIO) })
}

@Composable
private fun PermissionRow(title: String, subtitle: String, enabled: Boolean, actionLabel: String, onAction: () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (enabled) "✅" else "⭕", fontSize = 22.sp)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!enabled) Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** Пояснение перед включением службы «Специальные возможности» (общее для мастера и настроек). */
@Composable
fun AccessibilityHelpDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Включение управления экраном") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Что сможет Руся AI: видеть текст и кнопки на экране, нажимать их, прокручивать, вводить текст, нажимать «Назад» — только во время вашей задачи.")
                Text("Чего не будет: чтения паролей, обхода блокировки экрана, биометрии и CAPTCHA, скрытой слежки. Отправка, оплата и удаление — только с вашего подтверждения.")
                Text("Если Android пишет «Ограниченная настройка»: нажмите «Параметры приложения», затем ⋮ (три точки справа сверху) → «Разрешить ограниченные настройки», вернитесь и включите службу Руся AI в списке «Установленные приложения».")
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("Открыть настройки") }
        },
        dismissButton = {
            TextButton(onClick = {
                onDismiss()
                ctx.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }) { Text("Параметры приложения") }
        },
    )
}
