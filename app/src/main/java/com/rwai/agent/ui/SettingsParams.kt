package com.rwai.agent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rwai.agent.Graph
import com.rwai.agent.local.LocalModelStore
import com.rwai.agent.logic.AutonomyMode
import com.rwai.agent.logic.DownloadMath
import com.rwai.agent.logic.Endpoint
import com.rwai.agent.logic.UsageView
import com.rwai.agent.settings.Settings as AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Разделы вкладки «Параметры». Вместо одной длинной ленты настроек: список разделов, каждый открывается отдельной страницей из карточек. */
private enum class Page(val title: String) {
    HUB("Параметры"),
    AI("DeepSeek и модель"),
    AGENT("Поведение агента"),
    VOICE("Язык и голос"),
    CAR("Звук в машине (Bluetooth)"),
    PRIVACY("Сообщения и приватность"),
    WEB("Интернет и компьютер"),
    LOCAL("Локальная модель"),
    TOKENS("Расход токенов"),
}

@Composable
fun SettingsScreen() {
    var pageIdx by rememberSaveable { mutableIntStateOf(0) }
    val page = Page.entries[pageIdx.coerceIn(0, Page.entries.lastIndex)]
    androidx.activity.compose.BackHandler(enabled = page != Page.HUB) { pageIdx = 0 }

    if (page == Page.HUB) {
        SettingsHub { pageIdx = it.ordinal }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "‹  Все параметры",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { pageIdx = 0 }.padding(vertical = 6.dp),
                )
                Text(page.title, style = MaterialTheme.typography.titleLarge)
            }
            when (page) {
                Page.AI -> AiPage()
                Page.AGENT -> AgentPage()
                Page.VOICE -> VoicePage()
                Page.PRIVACY -> PrivacyPage()
                Page.CAR -> CarAudioSettings()
                Page.WEB -> WebPage()
                Page.LOCAL -> SettingsGroup("Модель на телефоне") { LocalModelSettings() }
                Page.TOKENS -> TokensPage()
                Page.HUB -> Unit
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Карточка-группа внутри страницы раздела: заголовок и настройки одной темы. */
@Composable
fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    GlassCard {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

private fun onOff(v: Boolean) = if (v) "вкл" else "выкл"

private fun languageLabel(tag: String) = when (tag) {
    "ru-RU" -> "Русский"; "es-UY" -> "Español (UY)"; "es-ES" -> "Español"; "en-US" -> "English"; else -> tag
}

private fun afterTaskLabel(v: String) = when (v) {
    "origin" -> "туда, откуда вызвал"; "home" -> "на главный экран"; "app" -> "в Руся AI"; "stay" -> "остаться"; else -> v
}

// ------------------------------------------------------------------ список разделов

@Composable
private fun SettingsHub(onOpen: (Page) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by Graph.settings.state.collectAsState()
    val hasKey by Graph.settings.hasApiKey.collectAsState()
    val local by LocalModelStore.status.collectAsState()
    var confirmWipe by remember { mutableStateOf(false) }
    var wipeNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { LocalModelStore.refresh(ctx) }

    fun summary(p: Page): String = when (p) {
        Page.AI -> (if (hasKey) "Ключ сохранён" else "Ключ не задан") + " · " + settings.model + if (settings.autoModel) " · автовыбор модели" else ""
        Page.AGENT -> settings.mode.title + " · после задачи: " + afterTaskLabel(settings.afterTask)
        Page.VOICE -> languageLabel(settings.language) + " · озвучка итога: " + onOff(settings.speakResults)
        Page.CAR -> if (com.rwai.agent.audio.CallRouter.enabled(ctx)) "Включено: звонки не на машину" else "Выключено"
        Page.PRIVACY -> "Уведомления: " + onOff(settings.captureNotifications) + " · сводка: " + onOff(settings.digestEnabled) + " · автоперевод: " + onOff(settings.autoTranslate)
        Page.WEB -> "Поиск: " + (if (Graph.secretStore.has("brave_search_key")) "Brave" else "DuckDuckGo") + " · мост к компьютеру: " + (if (settings.bridgeUrl.isNotBlank()) "задан" else "не задан")
        Page.LOCAL -> when (local.state) {
            LocalModelStore.State.READY -> "Готова, работает без интернета"
            LocalModelStore.State.DOWNLOADING -> "Скачивание: " + DownloadMath.percent(local.done, local.total) + " %"
            LocalModelStore.State.PARTIAL -> "Скачана частично, можно докачать"
            LocalModelStore.State.VERIFYING -> "Проверка файла"
            LocalModelStore.State.ERROR -> "Ошибка загрузки"
            LocalModelStore.State.NONE -> "Не скачана · " + DownloadMath.human(local.total)
        }
        Page.TOKENS -> "Сегодня: " + UsageView.group(Graph.settings.tokensUsedToday())
        Page.HUB -> ""
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        Page.entries.filter { it != Page.HUB }.forEach { p ->
            GlassCard(modifier = Modifier.clickable { onOpen(p) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(p.title, style = MaterialTheme.typography.titleSmall)
                        Text(summary(p), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { confirmWipe = true }, modifier = Modifier.fillMaxWidth()) { Text("Удалить все данные Руся AI") }
        wipeNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        val version = remember { try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (_: Exception) { null } ?: "" }
        Text("Руся AI $version", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("Удалить все данные?") },
            text = { Text("Будут безвозвратно удалены сохранённые уведомления, память и журнал действий. API-ключ и настройки останутся.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmWipe = false
                    scope.launch { withContext(Dispatchers.IO) { Graph.db.wipeAll() }; wipeNote = "Данные удалены." }
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Отмена") } },
        )
    }
}

// ------------------------------------------------------------------ DeepSeek и модель

@Composable
private fun AiPage() {
    val scope = rememberCoroutineScope()
    val settings by Graph.settings.state.collectAsState()
    val hasKey by Graph.settings.hasApiKey.collectAsState()
    var keyInput by remember { mutableStateOf("") }
    var checkResult by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var urlInput by remember(settings.baseUrl) { mutableStateOf(settings.baseUrl) }
    var urlError by remember { mutableStateOf<String?>(null) }
    var modelInput by remember(settings.model) { mutableStateOf(settings.model) }
    var confirmDeleteKey by remember { mutableStateOf(false) }

    SettingsGroup("API-ключ") {
        Text(if (hasKey) "Ключ сохранён (зашифрован в хранилище Android Keystore)." else "Ключ не задан.", style = MaterialTheme.typography.bodyMedium)
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
    }

    SettingsGroup("Подключение") {
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
        checkResult?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }

    SettingsGroup("Как выбирается модель") {
        SwitchRow("Автовыбор модели", "Простые задачи (открыть, показать, найти) идут на быстрой модели, сложные (ответ человеку, документ, несколько шагов, юридическое, деньги) и всё неясное на выбранной модели с рассуждением. Точность важнее скорости.", settings.autoModel) { v ->
            Graph.settings.update { it.copy(autoModel = v) }
        }
        SwitchRow("Режим рассуждения (thinking)", "Для сложных задач: точнее планирует, но каждый шаг медленнее и расходует больше токенов. При автовыборе простые задачи идут без него.", settings.thinking) { v ->
            Graph.settings.update { it.copy(thinking = v) }
        }
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
}

// ------------------------------------------------------------------ Поведение агента

@Composable
private fun AgentPage() {
    val settings by Graph.settings.state.collectAsState()

    SettingsGroup("Автономность") {
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
    }

    SettingsGroup("После выполнения задачи") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            listOf("origin" to "Туда, откуда вызвал", "home" to "На главный экран", "app" to "В Руся AI", "stay" to "Остаться").forEach { (v, label) ->
                FilterChip(selected = settings.afterTask == v, onClick = { Graph.settings.update { it.copy(afterTask = v) } }, label = { Text(label) })
            }
        }
        Text("Если агент задаёт вопрос текстом, Руся AI открывается. Если вы говорили голосом, вопрос озвучивается без открытия приложения.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    SettingsGroup("Быстрая команда") {
        SwitchRow(
            "Команда в шторке",
            "Постоянное уведомление с полем «Написать Руся AI»: команду можно ввести, не открывая приложение. Работает только на разблокированном телефоне.",
            settings.quickReply,
        ) { v ->
            Graph.settings.update { it.copy(quickReply = v) }
            val appCtx = Graph.appContext()
            if (v) com.rwai.agent.core.AgentNotifications.showQuick(appCtx) else com.rwai.agent.core.AgentNotifications.cancelQuick(appCtx)
        }
    }
}

// ------------------------------------------------------------------ Язык и голос

@Composable
private fun VoicePage() {
    val settings by Graph.settings.state.collectAsState()

    SettingsGroup("Язык") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            listOf("ru-RU", "es-UY", "es-ES", "en-US").forEach { tag ->
                FilterChip(selected = settings.language == tag, onClick = { Graph.settings.update { it.copy(language = tag) } }, label = { Text(languageLabel(tag)) })
            }
        }
    }
    SettingsGroup("Озвучивание и голосовые команды") {
        SwitchRow("Озвучивать итог задачи", null, settings.speakResults) { v -> Graph.settings.update { it.copy(speakResults = v) } }
        SwitchRow("Сразу выполнять уверенно распознанную голосовую команду", "Если распознавание сомнительное, текст попадёт в поле для проверки.", settings.voiceAutoRun) { v ->
            Graph.settings.update { it.copy(voiceAutoRun = v) }
        }
    }
    val speechCtx = androidx.compose.ui.platform.LocalContext.current
    var speakFull by remember { mutableStateOf(com.rwai.agent.voice.SpeechPrefs.full(speechCtx)) }
    SettingsGroup("Длина озвучки") {
        SwitchRow("Озвучивать ответ полностью", "Выключено (по умолчанию): Руся озвучивает только первые две фразы ответа, остальное читайте в чате или нажмите динамик у сообщения, чтобы озвучить его целиком.", speakFull) { v ->
            com.rwai.agent.voice.SpeechPrefs.setFull(speechCtx, v); speakFull = v
        }
    }
    SettingsGroup("Голос Piper (офлайн, естественный)") { PiperSettings() }
    SettingsGroup("Системный голос (запасной)") { ExtrasVoice() }
}

// ------------------------------------------------------------------ Сообщения и приватность

@Composable
private fun PrivacyPage() {
    val settings by Graph.settings.state.collectAsState()

    SettingsGroup("Уведомления") {
        SwitchRow("Сохранять уведомления", "Для «Входящих» и поиска. Выключите, чтобы Руся AI ничего не записывал.", settings.captureNotifications) { v ->
            Graph.settings.update { it.copy(captureNotifications = v) }
        }
        SwitchRow("Маскировать коды и пароли в уведомлениях", "Одноразовые коды из SMS/мессенджеров заменяются на ••••.", settings.maskCodes) { v ->
            Graph.settings.update { it.copy(maskCodes = v) }
        }
    }
    SettingsGroup("Сводка «кому ответить»") { ExtrasDigest() }
    SettingsGroup("Автоперевод") { ExtrasTranslate() }
    SettingsGroup("Исключения и хранение") {
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
    }
}

// ------------------------------------------------------------------ Интернет и компьютер

@Composable
private fun WebPage() {
    SettingsGroup("Поиск в интернете") { ExtrasSearch() }
    SettingsGroup("Мост к Claude Code (компьютер)") { ExtrasBridge() }
}

// ------------------------------------------------------------------ Расход токенов

@Composable
private fun TokensPage() {
    val settings by Graph.settings.state.collectAsState()
    var tokensToday by remember { mutableStateOf(Graph.settings.tokensUsedToday()) }
    var usage by remember { mutableStateOf(Graph.settings.usageToday()) }
    var cap by remember(settings.dailyTokenCap) { mutableStateOf(settings.dailyTokenCap.toString()) }

    SettingsGroup("Сегодня") {
        Text("Использовано токенов: ${UsageView.group(tokensToday)}", style = MaterialTheme.typography.bodyMedium)
        val lines = UsageView.lines(usage)
        if (lines.isEmpty()) Text("По источникам пока пусто: появится после первых запросов.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        OutlinedButton(onClick = { tokensToday = Graph.settings.tokensUsedToday(); usage = Graph.settings.usageToday() }) { Text("Обновить счётчик") }
    }
    SettingsGroup("Лимит") {
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
    }
}
