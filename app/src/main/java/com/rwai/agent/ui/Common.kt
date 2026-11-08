package com.rwai.agent.ui

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.rwai.agent.Graph
import com.rwai.agent.R
import com.rwai.agent.core.IncomingShare
import com.rwai.agent.accessibility.RwAccessibilityService

fun isNotificationAccessEnabled(ctx: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

fun isAccessibilityEnabled(ctx: Context): Boolean {
    // Служба подключена прямо сейчас — самый надёжный признак (разные прошивки по-разному пишут список включённых служб).
    if (RwAccessibilityService.instance != null) return true
    val enabled = try {
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    } catch (_: Exception) { null } ?: return false
    val expected = ComponentName(ctx, RwAccessibilityService::class.java)
    return enabled.split(':').any {
        ComponentName.unflattenFromString(it) == expected ||
            (it.startsWith(ctx.packageName + "/") && it.endsWith("RwAccessibilityService"))
    }
}

/** Руся AI выбран цифровым помощником по умолчанию (роль «Помощник» или выбранная служба голосового взаимодействия). */
fun isDefaultAssistant(ctx: Context): Boolean {
    try {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val rm = ctx.getSystemService(android.app.role.RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_ASSISTANT) && rm.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT)) return true
        }
    } catch (_: Exception) { }
    return listOf("assistant", "voice_interaction_service").any { key ->
        try { Settings.Secure.getString(ctx.contentResolver, key)?.contains(ctx.packageName + "/") == true } catch (_: Exception) { false }
    }
}

private enum class Dest(val title: String, val icon: Int) {
    AGENT("Агент", R.drawable.ic_nav_chat),
    INBOX("Входящие", R.drawable.ic_nav_inbox),
    MEMORY("Память", R.drawable.ic_nav_memory),
    LOG("Журнал", R.drawable.ic_nav_log),
    PHONE("Телефон", R.drawable.ic_nav_phone),
    SETTINGS("Ещё", R.drawable.ic_nav_more),
}

@Composable
fun RwRoot(resumeTick: Int, voiceTick: Int = 0) {
    val hasKey by Graph.settings.hasApiKey.collectAsState()
    // Окно подключения показывается, пока нет ключа, и остаётся до нажатия «Начать работу».
    var wizard by rememberSaveable { mutableStateOf(!Graph.settings.hasApiKey.value) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var settingsSection by rememberSaveable { mutableIntStateOf(0) }

    if (wizard || !hasKey) {
        SetupScreen(resumeTick = resumeTick, onBegin = { wizard = true }, onFinish = { wizard = false })
        return
    }

    // Жест ассистента или «Поделиться» из Галереи всегда ведут на вкладку «Агент».
    val shared by IncomingShare.uris.collectAsState()
    LaunchedEffect(voiceTick, shared) {
        if (voiceTick > 0 || shared.isNotEmpty()) tab = Dest.AGENT.ordinal
    }

    Scaffold(
        containerColor = Color.Transparent,
        modifier = Modifier.background(MaterialTheme.colorScheme.background).neuralGlow(),
        bottomBar = { RwBottomBar(selected = tab, onSelect = { tab = it }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            AppHeader(Dest.entries[tab].title)
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (Dest.entries[tab]) {
                    Dest.AGENT -> AssistantScreen(
                        onFixAccess = {
                            settingsSection = 0
                            tab = Dest.SETTINGS.ordinal
                        },
                        resumeTick = resumeTick,
                        voiceRequest = voiceTick,
                    )
                    Dest.INBOX -> InboxScreen(resumeTick)
                    Dest.MEMORY -> MemoryScreen()
                    Dest.LOG -> ActivityScreen()
                    Dest.PHONE -> PhoneScreen(resumeTick)
                    Dest.SETTINGS -> SettingsHost(resumeTick, settingsSection) { settingsSection = it }
                }
            }
        }
    }
}

/** Фон «нейросети»: два мягких радиальных свечения (синее сверху‑слева, розовое снизу‑справа). */
private fun Modifier.neuralGlow(): Modifier = this.drawBehind {
    drawRect(
        Brush.radialGradient(
            colors = listOf(RwPalette.Blue.copy(alpha = 0.20f), Color.Transparent),
            center = Offset(size.width * 0.1f, size.height * 0.02f),
            radius = size.width * 0.95f,
        ),
    )
    drawRect(
        Brush.radialGradient(
            colors = listOf(RwPalette.Pink.copy(alpha = 0.13f), Color.Transparent),
            center = Offset(size.width * 0.95f, size.height * 0.98f),
            radius = size.width * 0.9f,
        ),
    )
}

/** Компактная нижняя панель: 52 dp, маленькие иконки, подпись 10 sp, активная вкладка — градиентная «таблетка». */
@Composable
private fun RwBottomBar(selected: Int, onSelect: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().background(Color(0xE6070B18)).navigationBarsPadding()) {
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(glowBorder()))
        Row(
            modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Dest.entries.forEachIndexed { i, d ->
                val on = selected == i
                val tint by animateColorAsState(
                    if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    tween(180), label = "tint",
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(i) }
                        .padding(vertical = 5.dp, horizontal = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .height(24.dp)
                            .width(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .then(if (on) Modifier.background(accentBrush()) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter = painterResource(d.icon),
                            contentDescription = d.title,
                            colorFilter = ColorFilter.tint(tint),
                            modifier = Modifier.size(17.dp),
                        )
                    }
                    Text(
                        d.title,
                        style = MaterialTheme.typography.labelSmall,
                        color = tint,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AppHeader(title: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BrandMark(32.dp)
        Text(
            "Руся AI",
            style = MaterialTheme.typography.titleLarge.copy(brush = brandBrush()),
        )
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .border(1.dp, glowBorder(), RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.04f))
                .padding(horizontal = 12.dp, vertical = 5.dp),
        ) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** Логотип приложения на белой скруглённой плашке. */
@Composable
fun BrandMark(size: Dp) {
    Box(
        modifier = Modifier.size(size).clip(RoundedCornerShape(size / 3.2f)).background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.rw_logo),
            contentDescription = "Руся AI",
            modifier = Modifier.size(size),
        )
    }
}

@Composable
fun SectionTitle(text: String) {
    Row(modifier = Modifier.padding(top = 20.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.width(3.dp).height(16.dp).clip(RoundedCornerShape(2.dp)).background(accentBrush()))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun SwitchRow(title: String, description: String?, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
