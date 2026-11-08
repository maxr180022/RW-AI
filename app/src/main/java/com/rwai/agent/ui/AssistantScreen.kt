package com.rwai.agent.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.rwai.agent.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.activity.result.PickVisualMediaRequest
import com.rwai.agent.core.AttachedImage
import com.rwai.agent.core.IncomingShare
import com.rwai.agent.core.PhotoEncoder
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.core.content.ContextCompat
import com.rwai.agent.Graph
import com.rwai.agent.core.ChatEntry
import com.rwai.agent.core.ChatRole
import com.rwai.agent.core.Phase
import com.rwai.agent.voice.VoiceInput

@Composable
fun AssistantScreen(onFixAccess: () -> Unit, resumeTick: Int = 0, voiceRequest: Int = 0) {
    val ctx = LocalContext.current
    val state by Graph.controller.state.collectAsState()
    val a11yOn = remember(resumeTick) { isAccessibilityEnabled(ctx) }
    val settings by Graph.settings.state.collectAsState()
    var input by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(0f) }
    var voiceNote by remember { mutableStateOf<String?>(null) }
    val voice = remember { VoiceInput(ctx) }
    DisposableEffect(Unit) { onDispose { voice.stop() } }

    val scope = rememberCoroutineScope()
    var photos by remember { mutableStateOf<List<AttachedImage>>(emptyList()) }
    var photoNote by remember { mutableStateOf<String?>(null) }
    val addUris: (List<android.net.Uri>) -> Unit = { uris ->
        scope.launch {
            var failed = 0
            val added = ArrayList<AttachedImage>()
            for (u in uris) {
                if (photos.size + added.size >= PhotoEncoder.MAX_PHOTOS) break
                val img = PhotoEncoder.encode(ctx, u)
                if (img != null) added += img else failed++
            }
            photos = photos + added
            photoNote = when {
                failed > 0 -> "Не удалось прочитать фото: $failed."
                uris.size > added.size -> "Можно прикрепить не больше ${PhotoEncoder.MAX_PHOTOS} фото."
                else -> null
            }
        }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(PhotoEncoder.MAX_PHOTOS)) { uris ->
        if (uris.isNotEmpty()) addUris(uris)
    }
    // Съёмка прямо с камеры: системное приложение «Камера» сохраняет кадр во временный файл (разрешение CAMERA не нужно).
    var cameraUri by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = cameraUri?.let { android.net.Uri.parse(it) }
        cameraUri = null
        if (ok && u != null) {
            addUris(listOf(u))
        } else {
            photoNote = if (ok) null else "Съёмка отменена."
        }
    }
    val takePhoto: () -> Unit = {
        try {
            val dir = java.io.File(ctx.cacheDir, "camera").apply { mkdirs() }
            dir.listFiles()?.forEach { f -> if (System.currentTimeMillis() - f.lastModified() > 3_600_000L) f.delete() }
            val f = java.io.File.createTempFile("shot_", ".jpg", dir)
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            cameraUri = uri.toString()
            cameraLauncher.launch(uri)
        } catch (e: Exception) {
            photoNote = "Не удалось открыть камеру: " + (e.message ?: e.javaClass.simpleName)
        }
    }
    val shared by IncomingShare.uris.collectAsState()
    LaunchedEffect(shared) {
        if (shared.isNotEmpty()) {
            val list = shared
            IncomingShare.uris.value = emptyList()
            addUris(list)
        }
    }

    val prefill by AgentPrefill.text.collectAsState()
    LaunchedEffect(prefill) {
        prefill?.let { input = it; AgentPrefill.text.value = null }
    }

    val listState = rememberLazyListState()
    val extraItems = 3 // шаги, подтверждение, черновик
    LaunchedEffect(state.chat.size, state.confirmation, state.steps.size, state.draft) {
        listState.animateScrollToItem(state.chat.size + extraItems - 1)
    }

    val startListening: () -> Unit = {
        listening = true
        voiceNote = null
        voice.start(
            languageTag = settings.language,
            onLevel = { level = it },
            onPartial = { input = it },
            onResult = { text, confidence ->
                listening = false
                level = 0f
                input = text
                if (settings.voiceAutoRun && (confidence == null || confidence >= 0.6f)) {
                    Graph.controller.submit(text)
                    input = ""
                    voiceNote = null
                } else {
                    voiceNote = "Проверьте распознанный текст и нажмите «Отправить»."
                }
            },
            onFail = { msg ->
                listening = false
                level = 0f
                voiceNote = msg
            },
        )
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening() else voiceNote = "Без разрешения на микрофон голосовой ввод недоступен."
    }

    LaunchedEffect(voiceRequest) {
        if (voiceRequest > 0 && !listening) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startListening()
            else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val busy = state.phase == Phase.RUNNING || state.phase == Phase.WAITING_CONFIRMATION
                Box(
                    modifier = Modifier.size(10.dp).clip(CircleShape).background(
                        if (busy) MaterialTheme.colorScheme.secondary else RwPalette.Success,
                    ),
                )
                Text(
                    "${state.status} · ${settings.mode.title}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (state.phase == Phase.RUNNING || state.phase == Phase.WAITING_CONFIRMATION) {
                OutlinedButton(onClick = { Graph.controller.stop() }) { Text("Стоп") }
            } else if (state.chat.isNotEmpty()) {
                TextButton(onClick = { Graph.controller.clearChat() }) { Text("Очистить") }
            }
        }

        if (!a11yOn) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "Управление экраном выключено: агент не видит другие приложения и не может в них нажимать.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = onFixAccess) { Text("Включить") }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.chat.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Box(
                                modifier = Modifier.size(210.dp).drawBehind {
                                    drawCircle(
                                        Brush.radialGradient(
                                            listOf(RwPalette.Violet.copy(alpha = 0.42f), RwPalette.Pink.copy(alpha = 0.16f), Color.Transparent),
                                        ),
                                    )
                                },
                            )
                            LogoMark(150.dp)
                        }
                        Text("Чем помочь?", style = MaterialTheme.typography.headlineMedium.copy(brush = brandBrush()))
                        Text(
                            "Напишите или скажите команду. Вот что можно попробовать:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        listOf(
                            "@почта что нового?",
                            "Есть у меня непрочитанные сообщения?",
                            "Найди в памяти сообщения про аренду",
                            "Составь заявление в суд в PDF",
                        ).forEach { example ->
                            Box(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                                    .background(Color.White.copy(alpha = 0.05f))
                                    .border(1.dp, glowBorder(), RoundedCornerShape(16.dp))
                                    .clickable { input = example }
                                    .padding(horizontal = 14.dp, vertical = 11.dp),
                            ) { Text(example, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
            }
            items(state.chat) { entry -> ChatBubble(entry) }

            item {
                if (state.phase == Phase.RUNNING) {
                    Card(
                        modifier = Modifier.fillMaxWidth().border(1.dp, glowBorder(), MaterialTheme.shapes.medium),
                        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f)),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(state.status, style = MaterialTheme.typography.titleSmall)
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                            state.steps.takeLast(6).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
            item {
                state.confirmation?.let { req ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (req.dangerous) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(req.title, style = MaterialTheme.typography.titleMedium)
                            Text(req.details)
                            Text(req.reason, style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { Graph.controller.resolveConfirmation(false) }) { Text("Отмена") }
                                Button(
                                    onClick = { Graph.controller.resolveConfirmation(true) },
                                    colors = if (req.dangerous) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
                                ) { Text("Подтвердить") }
                            }
                        }
                    }
                }
            }
            item {
                state.draft?.let { d ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Черновик · ${d.app} → ${d.recipient}", style = MaterialTheme.typography.titleSmall)
                            Text(d.text)
                        }
                    }
                }
            }
        }

        voiceNote?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }

        if (photos.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                photos.forEachIndexed { i, p ->
                    Box {
                        Image(
                            bitmap = p.thumb.asImageBitmap(),
                            contentDescription = "Фото ${i + 1}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)),
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { photos = photos.filterIndexed { j, _ -> j != i } },
                            contentAlignment = Alignment.Center,
                        ) { Text("✕", fontSize = 11.sp) }
                    }
                }
                Text(
                    "Фото будут отправлены в DeepSeek для анализа.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        photoNote?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }

        val canSend = (input.isNotBlank() || photos.isNotEmpty()) && state.phase != Phase.RUNNING && state.phase != Phase.WAITING_CONFIRMATION
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 8.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(Color(0xFF0E1426).copy(alpha = 0.92f))
                .border(1.dp, glowBorder(), RoundedCornerShape(26.dp))
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            val suggestions = remember(input) { com.rwai.agent.logic.Mentions.suggestions(input, Graph.settings.current.customCommands) }
            if (suggestions.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    suggestions.forEach { c -> ActionPill("@" + c.primary + " · " + c.title) { input = com.rwai.agent.logic.Mentions.complete(input, c) } }
                }
            }
            TextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        if (listening) "Слушаю… говорите" else if (state.phase == Phase.WAITING_INPUT) "Ваш ответ…" else "Команда Русе…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                textStyle = MaterialTheme.typography.bodyLarge,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                maxLines = 5,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ComposerIcon(R.drawable.ic_attach, "Прикрепить фото", enabled = photos.size < PhotoEncoder.MAX_PHOTOS) {
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                ComposerIcon(R.drawable.ic_camera, "Сфотографировать", enabled = photos.size < PhotoEncoder.MAX_PHOTOS) { takePhoto() }
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                val pulse by animateFloatAsState(if (listening) 1f + level * 0.9f else 1f, label = "micPulse")
                val breath by rememberInfiniteTransition(label = "micBreath").animateFloat(
                    initialValue = 1f,
                    targetValue = 1.18f,
                    animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
                    label = "micBreathValue",
                )
                Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    if (listening) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .graphicsLayer {
                                    val sc = pulse * breath
                                    scaleX = sc
                                    scaleY = sc
                                    alpha = 0.35f
                                }
                                .clip(CircleShape)
                                .background(RwPalette.Pink),
                        )
                    }
                    ComposerIcon(
                        if (listening) R.drawable.ic_stop else R.drawable.ic_mic,
                        if (listening) "Остановить запись" else "Голос",
                        filled = listening,
                    ) {
                        if (listening) {
                            voice.stop()
                            listening = false
                            level = 0f
                        } else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            startListening()
                        } else {
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .then(if (canSend) Modifier.background(brandBrush()) else Modifier.background(Color.White.copy(alpha = 0.08f)))
                        .clickable(enabled = canSend) {
                            Graph.controller.submit(input, photos)
                            input = ""
                            voiceNote = null
                            photos = emptyList()
                            photoNote = null
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_send),
                        contentDescription = "Отправить",
                        colorFilter = ColorFilter.tint(if (canSend) Color.White else Color.White.copy(alpha = 0.35f)),
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }
    }
}

/** Круглая кнопка 40 dp с иконкой: «стеклянная» или залитая градиентом (запись). */
@Composable
private fun ComposerIcon(icon: Int, desc: String, enabled: Boolean = true, filled: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (filled) Brush.linearGradient(listOf(RwPalette.Pink, RwPalette.Violet)) else Brush.linearGradient(listOf(Color.White.copy(alpha = 0.07f), Color.White.copy(alpha = 0.07f))))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = desc,
            colorFilter = ColorFilter.tint(Color.White.copy(alpha = if (enabled) 0.95f else 0.3f)),
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ChatBubble(entry: ChatEntry) {
    val scheme = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val isUser = entry.role == ChatRole.USER
    val label = when (entry.role) {
        ChatRole.USER -> null
        ChatRole.AGENT -> "Руся AI"
        ChatRole.SYSTEM -> "Система"
        ChatRole.ERROR -> "Ошибка"
    }
    val fg = when (entry.role) {
        ChatRole.ERROR -> Color(0xFFFFB4C8)
        ChatRole.SYSTEM -> scheme.onSurfaceVariant
        else -> Color.White
    }
    val shape = if (isUser) RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp) else RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
    val base = when {
        isUser -> Modifier.clip(shape).background(accentBrush())
        entry.role == ChatRole.ERROR -> Modifier.clip(shape).background(Color(0x33FF2D55)).border(1.dp, Color(0x66FF2D55), shape)
        else -> Modifier.clip(shape).background(Color.White.copy(alpha = 0.055f)).border(1.dp, glowBorder(), shape)
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Column(
            modifier = Modifier.widthIn(max = 340.dp).then(base).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (label != null) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall.copy(brush = if (entry.role == ChatRole.AGENT) brandBrush() else Brush.linearGradient(listOf(fg.copy(alpha = 0.7f), fg.copy(alpha = 0.7f)))),
                )
            }
            if (entry.thumbs.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    entry.thumbs.forEach { t ->
                        Image(
                            bitmap = t.asImageBitmap(),
                            contentDescription = "Фото",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(88.dp).clip(RoundedCornerShape(12.dp)),
                        )
                    }
                }
            }
            MdText(entry.text, color = fg, style = MaterialTheme.typography.bodyMedium)
            entry.files.forEach { f -> FileCard(f, ctx) }
        }
    }
}

/** Карточка созданного файла (PDF, картинка): открыть, поделиться, сохранить в «Загрузки». */
@Composable
private fun FileCard(f: com.rwai.agent.docs.GeneratedFile, ctx: android.content.Context) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier.padding(top = 6.dp).fillMaxWidth().clip(shape)
            .background(Color.White.copy(alpha = 0.06f)).border(1.dp, glowBorder(), shape).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text((if (f.mime.startsWith("image/")) "🖼 " else "📄 ") + f.name, style = MaterialTheme.typography.titleSmall, color = Color.White)
        if (f.savedTo != null) Text("Сохранено: ${f.savedTo}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { com.rwai.agent.docs.DocFiles.open(ctx, f) }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp), modifier = Modifier.height(34.dp)) {
                Text("Открыть", style = MaterialTheme.typography.labelMedium)
            }
            FilledTonalButton(onClick = { com.rwai.agent.docs.DocFiles.share(ctx, f) }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp), modifier = Modifier.height(34.dp)) {
                Text("Поделиться", style = MaterialTheme.typography.labelMedium)
            }
            FilledTonalButton(onClick = { com.rwai.agent.docs.DocFiles.saveToDownloads(ctx, f) }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp), modifier = Modifier.height(34.dp)) {
                Text("Скачать", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
