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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rwai.agent.Graph
import com.rwai.agent.logic.DialogRules

/**
 * Вкладка «Проекты» (внизу): список проектов и кнопка «Новый проект». Нажали проект — открылась его страница с диалогами.
 * Оформление из общих элементов приложения.
 */
@Composable
fun ProjectsScreen(onOpen: (Long) -> Unit) {
    val ui by Graph.controller.dialogsUi.collectAsState()
    var creating by rememberSaveable { mutableStateOf(false) }
    var newName by rememberSaveable { mutableStateOf("") }
    var newNote by rememberSaveable { mutableStateOf("") }
    var createError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(accentBrush())
                .clickable { createError = null; creating = true }
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) { Text("＋  Новый проект", style = MaterialTheme.typography.labelLarge, color = Color.White) }

        SectionTitle("Проекты")
        if (ui.projects.isEmpty()) {
            Text("Проектов пока нет. Нажмите «Новый проект».", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ui.projects.forEach { p ->
            GlassCard(modifier = Modifier.clickable { onOpen(p.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (p.note.isNotBlank()) {
                            Text(p.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Text("›", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (creating) {
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("Новый проект") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "У проекта своя страница, свои диалоги и своя память. Ничего не смешивается с другими.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it.take(DialogRules.NAME_CAP) },
                        label = { Text("Название") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = newNote,
                        onValueChange = { newNote = it.take(DialogRules.NOTE_CAP) },
                        label = { Text("О чём проект (необязательно)") },
                        minLines = 2,
                        maxLines = 4,
                    )
                    createError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val err = Graph.controller.createProject(newName, newNote)
                    if (err == null) {
                        // Новый проект сразу открывается его страницей.
                        val created = Graph.controller.dialogsUi.value.projects.lastOrNull()
                        creating = false; newName = ""; newNote = ""; createError = null
                        if (created != null) onOpen(created.id)
                    } else createError = err
                }) { Text("Создать") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Отмена") } },
        )
    }
}
