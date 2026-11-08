package com.rwai.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
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
import com.rwai.agent.logic.FillLevel

/**
 * Боковое меню «Диалоги»: новый диалог, проекты и список диалогов (подпись — только дата).
 * Оформление целиком из общих элементов приложения (GlassCard, ActionPill, градиенты и цвета RwPalette).
 */
@Composable
fun DialogsDrawer(onClose: () -> Unit) {
    val ui by Graph.controller.dialogsUi.collectAsState()
    val state by Graph.controller.state.collectAsState()
    val busy = "Дождитесь окончания задачи или нажмите «Стоп»."

    var hint by remember { mutableStateOf<String?>(null) }

    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.background,
        drawerContentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            modifier = Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LogoMark(30.dp)
                Text("Диалоги", style = MaterialTheme.typography.titleLarge.copy(brush = brandBrush()))
            }
            Spacer(Modifier.height(4.dp))

            Box(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(accentBrush())
                    .clickable {
                        if (Graph.controller.newDialog(0L)) { hint = null; onClose() } else hint = busy
                    }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text("＋  Новый диалог", style = MaterialTheme.typography.labelLarge, color = Color.White) }
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

            SectionTitle("Диалоги")
            val list = ui.dialogs.filter { it.projectId == 0L }
            if (list.isEmpty()) {
                Text("Здесь пока пусто. Нажмите «Новый диалог».", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            list.forEach { d ->
                DrawerRow(d.title, selected = d.id == state.dialogId && state.projectName == null) {
                    if (Graph.controller.selectDialog(d.id)) { hint = null; onClose() } else hint = busy
                }
            }
        }
    }

}

/** Строка меню: стеклянная карточка, выбранная подсвечена синей рамкой. */
@Composable
private fun DrawerRow(text: String, selected: Boolean, arrow: Boolean = false, onClick: () -> Unit) {
    GlassCard(modifier = Modifier.clickable(onClick = onClick), accent = if (selected) RwPalette.Blue else null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (arrow) Text("›", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Полоска «Чат заполнен на N %» над перепиской. Показывает, сколько памяти диалога уходит модели; при 80 % старое сжимается само.
 * Цвета только из палитры приложения: градиент акцента, янтарный (внимание), красный (почти полон).
 */
@Composable
fun DialogMemoryBar(title: String, percent: Int, note: String?) {
    if (title.isBlank()) return
    val level = DialogRules.level(percent)
    val textColor = when (level) {
        FillLevel.OK -> MaterialTheme.colorScheme.onSurfaceVariant
        FillLevel.WARN -> MaterialTheme.colorScheme.secondary
        FillLevel.FULL -> RwPalette.Danger
    }
    Column(modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Text("Чат заполнен на $percent %", style = MaterialTheme.typography.labelMedium, color = textColor)
        }
        Box(modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.08f))) {
            val fill = Modifier.fillMaxWidth(percent.coerceIn(0, 100) / 100f).fillMaxHeight().clip(RoundedCornerShape(50))
            Box(
                modifier = when (level) {
                    FillLevel.OK -> fill.background(accentBrush())
                    FillLevel.WARN -> fill.background(MaterialTheme.colorScheme.secondary)
                    FillLevel.FULL -> fill.background(RwPalette.Danger)
                },
            )
        }
        if (level != FillLevel.OK) {
            Text(
                "Скоро старое сожмётся в краткую память, диалог продолжится.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (note != null) Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
