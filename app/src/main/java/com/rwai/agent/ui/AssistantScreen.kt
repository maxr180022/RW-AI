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
import androidx.compose.material3.AssistChip
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
                        BrandMark(72.dp)
                        Text("Чем помочь?", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Напишите или скажите команду. Вот что можно попробовать:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        listOf(
                            "Покажи последние уведомления",
                            "Есть у меня непрочитанные сообщения?",
                            "Найди в памяти сообщения про аренду",
                            "Подготовь ответ на испанском, но пока не отправляй",
                        ).forEach { example ->
                            AssistChip(
                                onClick = { input = example },
                                label = { Text(example) },
                                shape = MaterialTheme.shapes.large,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
            items(state.chat) { entry -> ChatBubble(entry) }

            item {
                if (state.phase == Phase.RUNNING) {
                    Card(modifier = Modifier.fillMaxWidth()) {
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

        Column(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp),
                shape = RoundedCornerShape(26.dp),
                placeholder = { Text(if (listening) "Слушаю… говорите" else if (state.phase == Phase.WAITING_INPUT) "Ваш ответ…" else "Команда агенту…") },
                maxLines = 5,
            )
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalIconButton(
                onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                modifier = Modifier.size(44.dp),
                enabled = photos.size < PhotoEncoder.MAX_PHOTOS,
            ) { Text("📎", fontSize = 18.sp) }
            FilledTonalIconButton(
                onClick = { takePhoto() },
                modifier = Modifier.size(44.dp),
                enabled = photos.size < PhotoEncoder.MAX_PHOTOS,
            ) { Text("📷", fontSize = 18.sp) }
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            val pulse by animateFloatAsState(if (listening) 1f + level * 0.9f else 1f, label = "micPulse")
            val breath by rememberInfiniteTransition(label = "micBreath").animateFloat(
                initialValue = 1f,
                targetValue = 1.18f,
                animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
                label = "micBreathValue",
            )
            Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                if (listening) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .graphicsLayer {
                                val sc = pulse * breath
                                scaleX = sc
                                scaleY = sc
                                alpha = 0.35f
                            }
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error),
                    )
                }
                FilledTonalIconButton(
                    onClick = {
                        if (listening) {
                            voice.stop()
                            listening = false
                            level = 0f
                        } else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            startListening()
                        } else {
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    modifier = Modifier.size(44.dp),
                    colors = if (listening) IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.error)
                    else IconButtonDefaults.filledTonalIconButtonColors(),
                ) { Text(if (listening) "⏹" else "🎤", fontSize = 18.sp) }
            }
            FilledIconButton(
                enabled = (input.isNotBlank() || photos.isNotEmpty()) && state.phase != Phase.RUNNING && state.phase != Phase.WAITING_CONFIRMATION,
                onClick = {
                    Graph.controller.submit(input, photos)
                    input = ""
                    voiceNote = null
                    photos = emptyList()
                    photoNote = null
                },
                modifier = Modifier.size(44.dp),
            ) { Text("↑", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        }
        }
    }
}

@Composable
private fun ChatBubble(entry: ChatEntry) {
    val scheme = MaterialTheme.colorScheme
    val isUser = entry.role == ChatRole.USER
    val (bg, fg, label) = when (entry.role) {
        ChatRole.USER -> Triple(scheme.primary, scheme.onPrimary, "Вы")
        ChatRole.AGENT -> Triple(scheme.surface, scheme.onSurface, "Руся AI")
        ChatRole.SYSTEM -> Triple(scheme.surfaceVariant, scheme.onSurfaceVariant, "Система")
        ChatRole.ERROR -> Triple(scheme.errorContainer, scheme.onErrorContainer, "Ошибка")
    }
    val shape = if (isUser) RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp) else RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Column(
            modifier = Modifier.widthIn(max = 330.dp).clip(shape).background(bg).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.7f))
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
            Text(entry.text, color = fg)
        }
    }
}
