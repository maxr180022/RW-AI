package com.rwai.agent.ui

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.rwai.agent.Graph
import com.rwai.agent.R
import com.rwai.agent.core.IncomingShare
import com.rwai.agent.accessibility.RwAccessibilityService

fun isNotificationAccessEnabled(ctx: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

fun isAccessibilityEnabled(ctx: Context): Boolean {
    val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    val expected = ComponentName(ctx, RwAccessibilityService::class.java)
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
}

private enum class Dest(val title: String, val icon: String) {
    AGENT("Агент", "💬"),
    INBOX("Входящие", "📥"),
    MEMORY("Память", "🧠"),
    LOG("Журнал", "📜"),
    PHONE("Телефон", "🔋"),
    SETTINGS("Ещё", "⚙️"),
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
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Dest.entries.forEachIndexed { i, d ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Text(d.icon, fontSize = 20.sp) },
                        label = { Text(d.title, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
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

@Composable
private fun AppHeader(title: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BrandMark(36.dp)
        Column {
            Text("RW AI", style = MaterialTheme.typography.titleMedium)
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            contentDescription = "RW AI",
            modifier = Modifier.size(size),
        )
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp))
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
