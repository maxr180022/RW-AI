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
import androidx.compose.ui.text.withStyle
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
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
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
    MORE("Ещё", R.drawable.ic_nav_more),
}

/** Экраны внутри «Ещё». */
private const val M_HUB = -1
private const val M_MEMORY = 0
private const val M_LOG = 1
private const val M_PHONE = 2
private const val M_SETTINGS = 3
private const val M_COMMANDS = 4

/** Команда, подготовленная на другом экране (например, «Ответить» во «Входящих»): попадает в поле ввода агента. */
object AgentPrefill {
    val text = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
}

/** Текст с лёгким Markdown: **жирное** выделяется шрифтом, звёздочки не показываются. */
@Composable
fun MdText(text: String, modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium, color: Color = Color.Unspecified) {
    val annotated = androidx.compose.ui.text.buildAnnotatedString {
        for ((part, bold) in com.rwai.agent.logic.Md.spans(text)) {
            if (bold) withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)) { append(part) } else append(part)
        }
    }
    Text(annotated, modifier = modifier, style = style, color = color)
}

/** Стеклянная карточка с тонкой светящейся рамкой. */
@Composable
fun GlassCard(modifier: Modifier = Modifier, accent: Color? = null, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier.fillMaxWidth().clip(shape)
            .background(Color.White.copy(alpha = 0.055f))
            .border(1.dp, if (accent != null) Brush.linearGradient(listOf(accent.copy(alpha = 0.7f), accent.copy(alpha = 0.2f))) else glowBorder(), shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
        content = content,
    )
}

/** Маленькая кнопка‑«таблетка» для действий в карточках. */
@Composable
fun ActionPill(text: String, accent: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier.clip(shape)
            .then(if (accent) Modifier.background(accentBrush()) else Modifier.background(Color.White.copy(alpha = 0.08f)).border(1.dp, Color.White.copy(alpha = 0.14f), shape))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) { Text(text, style = MaterialTheme.typography.labelMedium, color = Color.White) }
}

@Composable
fun RwRoot(resumeTick: Int, voiceTick: Int = 0) {
    val hasKey by Graph.settings.hasApiKey.collectAsState()
    // Окно подключения показывается, пока нет ключа, и остаётся до нажатия «Начать работу».
    var wizard by rememberSaveable { mutableStateOf(!Graph.settings.hasApiKey.value) }
    var tabRaw by rememberSaveable { mutableIntStateOf(0) }
    val tab = tabRaw.coerceIn(0, Dest.entries.lastIndex)
    var more by rememberSaveable { mutableIntStateOf(M_HUB) }
    var settingsSection by rememberSaveable { mutableIntStateOf(0) }

    if (wizard || !hasKey) {
        SetupScreen(resumeTick = resumeTick, onBegin = { wizard = true }, onFinish = { wizard = false })
        return
    }

    // Жест ассистента, «Поделиться» или кнопки «Ответить» во «Входящих» всегда ведут на вкладку «Агент».
    val shared by IncomingShare.uris.collectAsState()
    val prefill by AgentPrefill.text.collectAsState()
    LaunchedEffect(voiceTick, shared, prefill) {
        if (voiceTick > 0 || shared.isNotEmpty() || prefill != null) tabRaw = Dest.AGENT.ordinal
    }

    // «Назад» из подраздела «Ещё» возвращает в меню, а не закрывает приложение.
    androidx.activity.compose.BackHandler(enabled = tab == Dest.MORE.ordinal && more != M_HUB) { more = M_HUB }

    val subTitle = when (more) {
        M_MEMORY -> "Память"; M_LOG -> "Журнал"; M_PHONE -> "Телефон"; M_SETTINGS -> "Настройки"; M_COMMANDS -> "Команды @"; else -> "Ещё"
    }
    val inSub = tab == Dest.MORE.ordinal && more != M_HUB

    // Боковое меню «Диалоги»: открывается кнопкой с тремя палочками в шапке (и свайпом от левого края) на вкладке «Агент».
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    androidx.activity.compose.BackHandler(enabled = drawerState.isOpen) { drawerScope.launch { drawerState.close() } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = tab == Dest.AGENT.ordinal,
        drawerContent = { DialogsDrawer(onClose = { drawerScope.launch { drawerState.close() } }) },
    ) {
    Scaffold(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.background(MaterialTheme.colorScheme.background).neuralGlow(),
        bottomBar = { RwBottomBar(selected = tab, onSelect = { if (it == Dest.MORE.ordinal && tab == it) more = M_HUB; tabRaw = it }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            AppHeader(
                title = if (tab == Dest.MORE.ordinal) subTitle else Dest.entries[tab].title,
                back = if (inSub) ({ more = M_HUB }) else null,
                onMenu = if (tab == Dest.AGENT.ordinal) ({ drawerScope.launch { drawerState.open() } }) else null,
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (Dest.entries[tab]) {
                    Dest.AGENT -> AssistantScreen(
                        onFixAccess = {
                            settingsSection = 0
                            more = M_SETTINGS
                            tabRaw = Dest.MORE.ordinal
                        },
                        resumeTick = resumeTick,
                        voiceRequest = voiceTick,
                    )
                    Dest.INBOX -> InboxScreen(resumeTick)
                    Dest.MORE -> when (more) {
                        M_MEMORY -> MemoryScreen()
                        M_LOG -> ActivityScreen()
                        M_PHONE -> PhoneScreen(resumeTick)
                        M_SETTINGS -> SettingsHost(resumeTick, settingsSection) { settingsSection = it }
                        M_COMMANDS -> CommandsScreen()
                        else -> MoreHub(resumeTick) { target, section ->
                            if (section >= 0) settingsSection = section
                            more = target
                        }
                    }
                }
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
private fun AppHeader(title: String, back: (() -> Unit)? = null, onMenu: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (onMenu != null) {
            // Три палочки: та же «стеклянная» таблетка, что и подпись раздела справа.
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(50))
                    .border(1.dp, glowBorder(), RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.04f))
                    .clickable(onClick = onMenu)
                    .semantics { contentDescription = "Меню: диалоги и проекты" },
                contentAlignment = Alignment.Center,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(3) {
                        Box(modifier = Modifier.width(16.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(MaterialTheme.colorScheme.onSurface))
                    }
                }
            }
        }
        LogoMark(34.dp)
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
                .then(if (back != null) Modifier.clickable(onClick = back) else Modifier)
                .padding(horizontal = 12.dp, vertical = 5.dp),
        ) {
            Text((if (back != null) "‹  " else "") + title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** Логотип с вырезанным фоном (на тёмном экране, без плашки). */
@Composable
fun LogoMark(size: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.rw_mark),
        contentDescription = "Руся AI",
        modifier = modifier.size(size),
    )
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
