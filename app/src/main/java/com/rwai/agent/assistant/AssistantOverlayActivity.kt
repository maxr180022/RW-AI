package com.rwai.agent.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.rwai.agent.Graph
import com.rwai.agent.MainActivity
import com.rwai.agent.core.ChatRole
import com.rwai.agent.core.Phase
import com.rwai.agent.ui.RwPalette
import com.rwai.agent.ui.RwTheme
import com.rwai.agent.ui.brandBrush
import com.rwai.agent.voice.VoiceInput

/**
 * Панель ассистента поверх текущего экрана (как у Google): открывается жестом вызова ассистента,
 * сама включает микрофон, отправляет сказанное агенту без подтверждения и озвучивает ответ.
 * Приложение целиком при этом не открывается.
 */
class AssistantOverlayActivity : ComponentActivity() {
    private val voice by lazy { VoiceInput(this) }
    private var listening by mutableStateOf(false)
    private var level by mutableFloatStateOf(0f)
    private var heard by mutableStateOf("")
    private var note by mutableStateOf<String?>(null)
    private var submitted by mutableStateOf(false)
    private var baseline = 0

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening() else note = "Без разрешения на микрофон голос не работает. Включите его в настройках приложения."
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RwTheme {
                OverlayUi()
            }
        }
        startListening()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        startListening()
    }

    override fun onDestroy() {
        voice.stop()
        super.onDestroy()
    }

    private fun startListening() {
        if (!Graph.settings.hasApiKey.value) {
            note = "Сначала введите ключ DeepSeek в приложении RW AI."
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        Graph.controller.rolloverIfNewDay()
        Graph.speaker.stop()
        listening = true
        note = null
        heard = ""
        submitted = false
        voice.start(
            languageTag = Graph.settings.current.language,
            onLevel = { level = it },
            onPartial = { heard = it },
            onResult = { text, _ ->
                listening = false
                level = 0f
                heard = text
                baseline = Graph.controller.state.value.chat.size
                submitted = true
                Graph.controller.submit(text, speak = true) // без подтверждения: владелец сказал — агент выполняет
            },
            onFail = { msg ->
                listening = false
                level = 0f
                note = msg
            },
        )
    }

    private fun close(stopSpeech: Boolean) {
        if (stopSpeech) Graph.speaker.stop()
        voice.stop()
        finish()
    }

    @Composable
    private fun OverlayUi() {
        val state by Graph.controller.state.collectAsState()
        var visible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { visible = true }
        val scrim by animateFloatAsState(if (visible) 0.35f else 0f, tween(350), label = "scrim")
        val none = remember { MutableInteractionSource() }

        val running = state.phase == Phase.RUNNING
        val answer = if (submitted) state.chat.drop(baseline).lastOrNull { it.role != ChatRole.USER }?.text else null
        val statusText = when {
            listening -> "Слушаю…"
            state.phase == Phase.WAITING_CONFIRMATION -> "Нужно ваше подтверждение"
            state.phase == Phase.WAITING_INPUT -> "Жду ваш ответ — нажмите на шар"
            running -> state.status
            answer != null -> "Готово"
            else -> "Нажмите на шар и говорите"
        }

        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim))
                .clickable(interactionSource = none, indication = null) { close(true) },
        ) {
            AnimatedVisibility(
                visible = visible,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(tween(420, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(300)),
                exit = slideOutVertically(tween(250)) { it } + fadeOut(tween(200)),
            ) {
                Surface(
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    modifier = Modifier.fillMaxWidth().clickable(interactionSource = none, indication = null) { },
                ) {
                    Column {
                        Box(Modifier.fillMaxWidth().height(4.dp).background(brandBrush()))
                        Column(
                            modifier = Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Orb(listening = listening, level = level, working = running) { if (!listening) startListening() }
                            Text(statusText, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                            if (heard.isNotBlank()) {
                                Text(
                                    "«$heard»", style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                                )
                            }
                            state.confirmation?.let { req ->
                                Text(req.title, style = MaterialTheme.typography.titleSmall)
                                Text(req.details, style = MaterialTheme.typography.bodySmall, maxLines = 6)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { Graph.controller.resolveConfirmation(true) }) { Text("Разрешить") }
                                    OutlinedButton(onClick = { Graph.controller.resolveConfirmation(false) }) { Text("Отклонить") }
                                }
                            }
                            if (!answer.isNullOrBlank()) {
                                Box(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                                    Text(answer, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                            note?.let { Text(it, color = RwPalette.Danger, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center) }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (running) TextButton(onClick = { Graph.controller.stop() }) { Text("Стоп") }
                                TextButton(onClick = {
                                    startActivity(Intent(this@AssistantOverlayActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    close(false)
                                }) { Text("Открыть приложение") }
                                TextButton(onClick = { close(true) }) { Text("Закрыть") }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Живой шар: пульсирует в такт голосу, вращается пока агент работает, «дышит» в покое. */
@Composable
private fun Orb(listening: Boolean, level: Float, working: Boolean, onClick: () -> Unit) {
    val inf = rememberInfiniteTransition(label = "orb")
    val breathe by inf.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe",
    )
    val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "spin")
    val lv by animateFloatAsState(level, tween(90), label = "lv")
    val none = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier.size(150.dp).clickable(interactionSource = none, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val base = size.minDimension * 0.27f
            val amp = when {
                listening -> 0.15f + lv * 0.85f
                working -> 0.35f
                else -> breathe * 0.25f
            }
            for (i in 3 downTo 1) {
                drawCircle(
                    color = RwPalette.GradientStart.copy(alpha = 0.09f * (4 - i) * (0.5f + amp)),
                    radius = base * (1f + 0.27f * i * (0.5f + amp)),
                    center = c,
                )
            }
            rotate(if (working) spin else 0f, pivot = c) {
                drawCircle(
                    brush = Brush.sweepGradient(listOf(RwPalette.GradientStart, RwPalette.GradientEnd, RwPalette.GradientStart), center = c),
                    radius = base,
                    center = c,
                )
            }
            drawCircle(Color.White.copy(alpha = 0.20f), radius = base * 0.5f, center = c - Offset(base * 0.22f, base * 0.28f))
        }
    }
}
