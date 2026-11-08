package com.rwai.agent.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rwai.agent.logic.PendingReply
import com.rwai.agent.logic.PendingReplies
import kotlinx.coroutines.delay

/**
 * Строка «Не отвечено: N» на главном экране: сколько человек ждут ответа, а рядом по очереди меняются они сами
 * (кто, в каком приложении, когда написал; новые помечены). Только неотвеченные и новые. Нажатие открывает «Входящие».
 * Две строки: «Не отвечено: N · кто · где · когда» и текст сообщения. Только на главной (внутри проекта строки нет). Нет неотвеченных — строки нет совсем. Оформление из общих элементов приложения.
 */
@Composable
fun PendingStrip(items: List<PendingReply>, onOpen: () -> Unit) {
    if (items.isEmpty()) return
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(items.size) {
        index = 0
        while (items.size > 1) {
            delay(4_000)
            index = (index + 1) % items.size
        }
    }
    val current = items[index.coerceIn(0, items.lastIndex)]
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(shape).border(1.dp, glowBorder(), shape)
            .background(Color.White.copy(alpha = 0.04f)).clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Ровно две строки. Первая: «Не отвечено: N · кто · где · когда» (меняется по очереди). Вторая: текст сообщения.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(RwPalette.Danger))
            Text(PendingReplies.title(items.size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            Crossfade(targetState = current, modifier = Modifier.weight(1f), label = "pending-who") { p ->
                Text(
                    (if (p.isNew) "новое · " else "") + PendingReplies.line(p),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text("›", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Crossfade(targetState = current, modifier = Modifier.fillMaxWidth(), label = "pending-text") { p ->
            Text(
                p.preview.ifBlank { " " },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
