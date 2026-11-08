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
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.rwai.agent.Graph
import com.rwai.agent.MainActivity
import com.rwai.agent.R
import com.rwai.agent.core.ChatRole
import com.rwai.agent.core.Phase
import com.rwai.agent.ui.RwPalette
import com.rwai.agent.ui.RwTheme
import com.rwai.agent.ui.brandBrush
import com.rwai.agent.voice.VoiceInput
import com.rwai.agent.wake.WakeState

/**
 * Панель ассистента поверх текущего экрана (как у Google): открывается жестом вызова ассистента,
 * сама включает микрофон, отправляет сказанное агенту без подтверждения и озвучивает ответ.
 * Приложение целиком при этом не открывается.
 */
class AssistantOverlayActivity : ComponentActivity() {
    companion object {
        const val EXTRA_FROM_WAKE = "com.rwai.agent.extra.FROM_WAKE"
    }

    private val voice by lazy { VoiceInput(this) }
    private var listening by mutableStateOf(false)
    private var level by mutableFloatStateOf(0f)
    private var heard by mutableStateOf("")
    private var note by mutableStateOf<String?>(null)
    private var submitted by mutableStateOf(false)
    private var shown by mutableStateOf(false)
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private var baseline = 0

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening() else note = "Без разрешения на микрофон голос не работает. Включите его в настройках приложения."
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        WakeState.overlayVisible = true
        // Освобождаем микрофон у слушателя «Окей, Руся», пока панель на экране.
        val micWasHeld = WakeState.holdingMic
        WakeState.setPaused(true)
        setContent {
            RwTheme {
                OverlayUi()
            }
        }
        // Анимация панели идёт плавно сама по себе: тяжёлый запуск микрофона и распознавателя — только после неё.
        shown = true
        ui.postDelayed({ if (!isFinishing) startListening() }, if (micWasHeld) 650L else 520L)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        startListening()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        WakeState.overlayVisible = false
        WakeState.setPaused(false)
        voice.stop()
        super.onDestroy()
    }

    private fun startListening() {
        if (!Graph.settings.hasApiKey.value) {
            note = "Сначала введите ключ DeepSeek в приложении Руся AI."
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
            onLevel = { level = level * 0.55f + it * 0.45f },
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
        shown = false
        ui.postDelayed({ finish() }, 240)
    }

    @Composable
    private fun OverlayUi() {
        val state by Graph.controller.state.collectAsState()
        val scrim by animateFloatAsState(if (shown) 0.32f else 0f, tween(300), label = "scrim")
        val none = remember { MutableInteractionSource() }

        val running = state.phase == Phase.RUNNING
        val speaking by Graph.speaker.speaking.collectAsState()
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
                visible = shown,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(tween(240)),
                exit = slideOutVertically(tween(220, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(180)),
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
                            modifier = Modifier.fillMaxWidth().animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))
                                .navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Orb(listening = listening, level = { level }, working = running, speaking = speaking) { if (!listening) startListening() }
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

private val GlowBlue = Color(0xFF2B6BFF)
private val GlowPink = Color(0xFFFF2D87)

/**
 * Знак Руся AI в центре. Свечение вокруг него — сверху синее, снизу красное (цвета знака): пульсирует в такт голосу,
 * вращающееся кольцо — пока агент работает, мягкое «дыхание» в покое.
 */
@Composable
private fun Orb(listening: Boolean, level: () -> Float, working: Boolean, speaking: Boolean, onClick: () -> Unit) {
    val inf = rememberInfiniteTransition(label = "orb")
    // Значения читаются только при отрисовке (внутри draw/graphicsLayer): панель не перекомпонуется на каждом кадре.
    val breathe = inf.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe",
    )
    val flicker = inf.animateFloat(
        0f, 1f, infiniteRepeatable(tween(420, easing = LinearEasing), RepeatMode.Reverse), label = "flicker",
    )
    val spin = inf.animateFloat(0f, 360f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "spin")
    val none = remember { MutableInteractionSource() }
    fun amp(): Float = when { // 0..1 — «сила» свечения
        listening -> 0.2f + level().coerceIn(0f, 1f) * 0.8f
        speaking -> 0.45f + flicker.value * 0.45f
        working -> 0.35f + breathe.value * 0.2f
        else -> breathe.value * 0.3f
    }
    Box(
        modifier = Modifier.size(190.dp).clickable(interactionSource = none, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val amp = amp()
            val c = center
            val base = size.minDimension * 0.30f
            for (i in 3 downTo 1) {
                val r = base * (1f + 0.30f * i * (0.5f + amp))
                val a = 0.16f * (4 - i) * (0.4f + amp)
                drawCircle(
                    brush = Brush.verticalGradient(
                        listOf(GlowBlue.copy(alpha = a.coerceAtMost(0.6f)), GlowPink.copy(alpha = a.coerceAtMost(0.6f))),
                        startY = c.y - r, endY = c.y + r,
                    ),
                    radius = r,
                    center = c,
                )
            }
            if (working) {
                rotate(spin.value, pivot = c) {
                    drawCircle(
                        brush = Brush.sweepGradient(listOf(Color.Transparent, GlowBlue, GlowPink, Color.Transparent), center = c),
                        radius = base * 1.25f,
                        center = c,
                        style = Stroke(width = 5.dp.toPx()),
                    )
                }
            }
        }
        Image(
            painter = painterResource(R.drawable.rw_mark),
            contentDescription = "Руся AI",
            modifier = Modifier.size(104.dp).graphicsLayer {
                val a = amp()
                val sc = 1f + 0.14f * a
                scaleX = sc; scaleY = sc
                alpha = 0.72f + 0.28f * a
            },
        )
    }
}
