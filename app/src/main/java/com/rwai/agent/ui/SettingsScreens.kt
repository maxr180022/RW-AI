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


/** Вкладка «Ещё»: два раздела — «Доступ» (разрешения) и «Параметры». */
@Composable
fun SettingsHost(resumeTick: Int, section: Int, onSection: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = section == 0, onClick = { onSection(0) }, label = { Text("Доступ") })
            FilterChip(selected = section == 1, onClick = { onSection(1) }, label = { Text("Параметры") })
            FilterChip(selected = section == 2, onClick = { onSection(2) }, label = { Text("Профиль") })
            FilterChip(selected = section == 3, onClick = { onSection(3) }, label = { Text("Подключения") })
        }
        when (section) {
            0 -> PermissionsScreen(resumeTick)
            1 -> SettingsScreen()
            2 -> ProfileScreen()
            else -> ConnectionsScreen(resumeTick)
        }
    }
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

@Composable
fun SettingsScreen() {
    val scope = rememberCoroutineScope()
    val settings by Graph.settings.state.collectAsState()
    val hasKey by Graph.settings.hasApiKey.collectAsState()
    var keyInput by remember { mutableStateOf("") }
    var checkResult by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var urlInput by remember(settings.baseUrl) { mutableStateOf(settings.baseUrl) }
    var urlError by remember { mutableStateOf<String?>(null) }
    var modelInput by remember(settings.model) { mutableStateOf(settings.model) }
    var confirmWipe by remember { mutableStateOf(false) }
    var confirmDeleteKey by remember { mutableStateOf(false) }
    var tokensToday by remember { mutableStateOf(Graph.settings.tokensUsedToday()) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle("DeepSeek")
        Text(if (hasKey) "API-ключ сохранён (зашифрован в хранилище Android Keystore)." else "API-ключ не задан.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (hasKey) "Заменить API-ключ" else "API-ключ DeepSeek") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = keyInput.trim().length >= 10, onClick = {
                Graph.settings.saveApiKey(keyInput)
                keyInput = ""
                checkResult = "Ключ сохранён. Нажмите «Проверить соединение»."
            }) { Text("Сохранить ключ") }
            OutlinedButton(enabled = hasKey, onClick = { confirmDeleteKey = true }) { Text("Удалить ключ") }
        }
        Text("Ключ вводится только здесь; не отправляйте его никому и не вставляйте в чаты.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        OutlinedTextField(
            value = urlInput,
            onValueChange = { urlInput = it; urlError = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Base URL (только https)") },
            isError = urlError != null,
            supportingText = { urlError?.let { Text(it) } },
            singleLine = true,
        )
        OutlinedTextField(
            value = modelInput,
            onValueChange = { modelInput = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Модель") },
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            AppSettings.KNOWN_MODELS.forEach { m ->
                FilterChip(selected = modelInput == m, onClick = { modelInput = m }, label = { Text(m) })
            }
        }
        SwitchRow("Автовыбор модели", "Простые задачи (открыть, показать, найти) идут на быстрой модели, сложные (ответ человеку, документ, несколько шагов, юридическое, деньги) и всё неясное на выбранной модели с рассуждением. Точность важнее скорости.", settings.autoModel) { v ->
            Graph.settings.update { it.copy(autoModel = v) }
        }
        SwitchRow("Режим рассуждения (thinking)", "Для сложных задач: точнее планирует, но каждый шаг медленнее и расходует больше токенов. При автовыборе простые задачи идут без него.", settings.thinking) { v ->
            Graph.settings.update { it.copy(thinking = v) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val normalized = Endpoint.normalize(urlInput)
                if (normalized == null) {
                    urlError = "Нужен адрес вида https://api.deepseek.com"
                } else if (modelInput.isBlank()) {
                    checkResult = "Укажите название модели."
                } else {
                    Graph.settings.update { it.copy(baseUrl = normalized, model = modelInput.trim()) }
                    checking = true
                    checkResult = "Проверяю…"
                    scope.launch {
                        val r = Graph.brain.validate()
                        checkResult = (if (r.ok) "Успешно: " else "Ошибка: ") + r.message
                        checking = false
                    }
                }
            }, enabled = hasKey && !checking) { Text("Проверить соединение") }
        }
        checkResult?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

        SwitchRow(
            "Быстрая команда в шторке",
            "Постоянное уведомление с полем «Написать Руся AI»: команду можно ввести, не открывая приложение. Работает только на разблокированном телефоне.",
            settings.quickReply,
        ) { v ->
            Graph.settings.update { it.copy(quickReply = v) }
            val appCtx = Graph.appContext()
            if (v) com.rwai.agent.core.AgentNotifications.showQuick(appCtx) else com.rwai.agent.core.AgentNotifications.cancelQuick(appCtx)
        }

        SectionTitle("Автономность")
        AutonomyMode.entries.forEach { m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = settings.mode == m, onClick = { Graph.settings.update { it.copy(mode = m) } })
                Column {
                    Text(m.title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        when (m) {
                            AutonomyMode.ASSISTANT -> "Читает и готовит черновики. Любая отправка — только после вашего подтверждения."
                            AutonomyMode.OPERATOR -> "Выполняет безопасные действия сам. Отправка — по правилу автоотправки ниже."
                            AutonomyMode.AUTONOMOUS -> "Решает многошаговые задачи сам. Оплата, удаление и безопасность — всегда с подтверждением."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        SwitchRow(
            "Автоотправка обычных ответов",
            "Только в режимах «Оператор» и «Автономный», и только если текст не содержит денег, договоров и юридических обязательств. Иначе — подтверждение.",
            settings.autoSend,
        ) { v -> Graph.settings.update { it.copy(autoSend = v) } }

        SectionTitle("После выполнения задачи")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            listOf("origin" to "Туда, откуда вызвал", "home" to "На главный экран", "app" to "В Руся AI", "stay" to "Остаться").forEach { (v, label) ->
                FilterChip(selected = settings.afterTask == v, onClick = { Graph.settings.update { it.copy(afterTask = v) } }, label = { Text(label) })
            }
        }
        Text("Если агент задаёт вопрос текстом, Руся AI открывается. Если вы говорили голосом, вопрос озвучивается без открытия приложения.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        SectionTitle("Язык и голос")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            listOf("ru-RU" to "Русский", "es-UY" to "Español (UY)", "es-ES" to "Español", "en-US" to "English").forEach { (tag, label) ->
                FilterChip(selected = settings.language == tag, onClick = { Graph.settings.update { it.copy(language = tag) } }, label = { Text(label) })
            }
        }
        SwitchRow("Озвучивать итог задачи", null, settings.speakResults) { v -> Graph.settings.update { it.copy(speakResults = v) } }
        SwitchRow("Сразу выполнять уверенно распознанную голосовую команду", "Если распознавание сомнительное, текст попадёт в поле для проверки.", settings.voiceAutoRun) { v ->
            Graph.settings.update { it.copy(voiceAutoRun = v) }
        }

        SectionTitle("Уведомления и приватность")
        SwitchRow("Сохранять уведомления", "Для «Входящих» и поиска. Выключите, чтобы Руся AI ничего не записывал.", settings.captureNotifications) { v ->
            Graph.settings.update { it.copy(captureNotifications = v) }
        }
        SwitchRow("Маскировать коды и пароли в уведомлениях", "Одноразовые коды из SMS/мессенджеров заменяются на ••••.", settings.maskCodes) { v ->
            Graph.settings.update { it.copy(maskCodes = v) }
        }
        var excluded by remember(settings.excludedApps) { mutableStateOf(settings.excludedApps) }
        OutlinedTextField(
            value = excluded,
            onValueChange = { excluded = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Исключённые приложения (имена пакетов через запятую)") },
            supportingText = { Text("Например: com.example.bank, org.example.health") },
        )
        OutlinedButton(onClick = { Graph.settings.update { it.copy(excludedApps = excluded.trim()) } }, enabled = excluded.trim() != settings.excludedApps) { Text("Применить исключения") }
        var retention by remember(settings.retentionDays) { mutableStateOf(settings.retentionDays.toString()) }
        OutlinedTextField(
            value = retention,
            onValueChange = { v ->
                retention = v.filter { it.isDigit() }.take(4)
                retention.toIntOrNull()?.let { d -> Graph.settings.update { it.copy(retentionDays = d) } }
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Срок хранения данных, дней (0 — не удалять)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
        )

        ExtrasSettings()

        SectionTitle("Расход токенов")
        var cap by remember(settings.dailyTokenCap) { mutableStateOf(settings.dailyTokenCap.toString()) }
        Text("Сегодня использовано токенов: $tokensToday", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { tokensToday = Graph.settings.tokensUsedToday() }) { Text("Обновить счётчик") }
        OutlinedTextField(
            value = cap,
            onValueChange = { v ->
                cap = v.filter { it.isDigit() }.take(9)
                cap.toIntOrNull()?.let { n -> Graph.settings.update { it.copy(dailyTokenCap = n) } }
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Дневной лимит токенов (0 — без лимита)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        OutlinedButton(onClick = { confirmWipe = true }, modifier = Modifier.fillMaxWidth()) { Text("Удалить все данные Руся AI (память, уведомления, журнал)") }
        Text("Руся AI 0.4.0", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 12.dp))
    }

    if (confirmDeleteKey) {
        AlertDialog(
            onDismissRequest = { confirmDeleteKey = false },
            title = { Text("Удалить API-ключ?") },
            text = { Text("Агент перестанет работать, пока вы не введёте ключ снова.") },
            confirmButton = { TextButton(onClick = { Graph.settings.deleteApiKey(); confirmDeleteKey = false; checkResult = "Ключ удалён." }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { confirmDeleteKey = false }) { Text("Отмена") } },
        )
    }
    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("Удалить все данные?") },
            text = { Text("Будут безвозвратно удалены сохранённые уведомления, память и журнал действий. API-ключ и настройки останутся.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmWipe = false
                    scope.launch { withContext(Dispatchers.IO) { Graph.db.wipeAll() }; checkResult = "Данные удалены." }
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Отмена") } },
        )
    }
}
