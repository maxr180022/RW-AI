package com.rwai.agent.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.rwai.agent.Graph
import com.rwai.agent.R
import com.rwai.agent.logic.Mentions

private class Tile(val title: String, val sub: String, val icon: Int, val target: Int, val section: Int = -1)

/** Меню «Ещё»: плитки разделов вместо вкладок внизу. target: 0 память, 1 журнал, 2 телефон, 3 настройки (section), 4 команды. */
@Composable
fun MoreHub(resumeTick: Int, open: (target: Int, section: Int) -> Unit) {
    val tiles = listOf(
        Tile("Память", "Что запомнила Руся", R.drawable.ic_nav_memory, 0),
        Tile("Журнал", "Все действия агента", R.drawable.ic_nav_log, 1),
        Tile("Телефон", "Заряд, нагрев, проверка", R.drawable.ic_nav_phone, 2),
        Tile("Команды @", "@почта, @ютуб, свои", R.drawable.ic_nav_chat, 4),
        Tile("Подключения", "Почта, YouTube, календарь", R.drawable.ic_nav_inbox, 3, 3),
        Tile("Профиль", "Данные для форм и PDF", R.drawable.ic_nav_more, 3, 2),
        Tile("Параметры", "Голос, режимы, сводка", R.drawable.ic_nav_more, 3, 1),
        Tile("Доступ", "Разрешения Android", R.drawable.ic_nav_more, 3, 0),
    )
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val version = remember { try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (_: Exception) { "" } }
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { t -> TileView(t, Modifier.weight(1f)) { open(t.target, t.section) } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Text(
            "Руся AI · версия " + version + "\nСоздатели: Maxim Ricco и команда RusWeo",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, start = 4.dp),
        )
    }
}

@Composable
private fun TileView(t: Tile, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier.clip(shape)
            .background(Color.White.copy(alpha = 0.055f))
            .border(1.dp, glowBorder(), shape)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(accentBrush()),
            contentAlignment = Alignment.Center,
        ) {
            Image(painter = painterResource(t.icon), contentDescription = t.title, colorFilter = ColorFilter.tint(Color.White), modifier = Modifier.size(22.dp))
        }
        Text(t.title, style = MaterialTheme.typography.titleMedium)
        Text(t.sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Список @-команд и редактор своих. */
@Composable
fun CommandsScreen() {
    var custom by remember { mutableStateOf(Mentions.parseCustom(Graph.settings.current.customCommands)) }
    var name by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    val nameOk = name.trim().removePrefix("@").matches(Regex("[\\p{L}\\p{N}_]{2,24}"))
    fun save(list: List<com.rwai.agent.logic.MentionCmd>) {
        custom = list
        Graph.settings.update { it.copy(customCommands = Mentions.serializeCustom(list)) }
    }
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Напишите @ в строке команды — появятся подсказки. Команда сразу говорит Русе, каким источником пользоваться, поэтому она не ищет путь сама и тратит меньше токенов. Пример: «@почта есть письма от Ивана?»",
            style = MaterialTheme.typography.bodyMedium,
        )
        SectionTitle("Встроенные")
        Mentions.builtin.forEach { c ->
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("@" + c.primary, style = MaterialTheme.typography.titleSmall, color = RwPalette.Blue)
                    Text(c.aliases.joinToString("  ") { "@$it" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(c.directive, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
            }
        }
        SectionTitle("Свои команды")
        custom.forEach { c ->
            GlassCard(accent = RwPalette.Violet) {
                Text("@" + c.primary, style = MaterialTheme.typography.titleSmall, color = RwPalette.Violet)
                Text(c.directive, style = MaterialTheme.typography.bodySmall)
                Row(modifier = Modifier.padding(top = 4.dp)) { ActionPill("Удалить") { save(custom.filterNot { it.primary == c.primary }) } }
            }
        }
        GlassCard {
            Text("Новая команда", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(value = name, onValueChange = { name = it.take(24) }, label = { Text("Имя, например работа") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = text, onValueChange = { text = it.take(400) },
                label = { Text("Что делать, например: открой Slack и прочитай канал") },
                modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4,
            )
            Row(modifier = Modifier.padding(top = 4.dp)) {
                ActionPill("Добавить", accent = nameOk && text.isNotBlank()) {
                    if (nameOk && text.isNotBlank()) {
                        val n = name.trim().removePrefix("@").lowercase()
                        save(custom.filterNot { it.primary == n } + com.rwai.agent.logic.MentionCmd(n, emptyList(), n, text.trim(), builtin = false))
                        name = ""; text = ""
                    }
                }
            }
            Text("Свои инструкции добавляются к команде для модели, лимит 400 символов. Имена встроенных команд занять нельзя: они сработают первыми.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
    }
}
