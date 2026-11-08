package com.rwai.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.rwai.agent.Graph
import com.rwai.agent.core.ProjectItem

/**
 * Страница проекта: отдельное место внутри приложения. Здесь заметка проекта и ВСЕ его диалоги; «Новый диалог» и любой диалог из списка
 * открывают экран общения уже внутри проекта (шапка «‹ Проект · название» со стрелкой назад). С главной проект не смешивается.
 * Оформление из общих элементов приложения.
 */
@Composable
fun ProjectPage(project: ProjectItem, onOpenChat: () -> Unit) {
    val ui by Graph.controller.dialogsUi.collectAsState()
    val dialogs = ui.dialogs.filter { it.projectId == project.id }
    var hint by remember { mutableStateOf<String?>(null) }
    val busy = "Дождитесь окончания задачи или нажмите «Стоп» на вкладке «Агент»."

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (project.note.isNotBlank()) {
            GlassCard(modifier = Modifier.padding(top = 8.dp)) {
                Text("О проекте", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MdText(project.note, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f))
            }
        }
        Box(
            modifier = Modifier.padding(top = if (project.note.isBlank()) 8.dp else 0.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(accentBrush())
                .clickable { if (Graph.controller.newDialog(project.id)) { hint = null; onOpenChat() } else hint = busy }
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) { Text("＋  Новый диалог в проекте", style = MaterialTheme.typography.labelLarge, color = Color.White) }
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

        SectionTitle("Диалоги проекта")
        if (dialogs.isEmpty()) {
            Text(
                "Диалогов пока нет. Нажмите «Новый диалог в проекте».",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        dialogs.forEach { d ->
            GlassCard(modifier = Modifier.clickable { if (Graph.controller.selectDialog(d.id)) { hint = null; onOpenChat() } else hint = busy }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(d.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text("›", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
