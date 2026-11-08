package com.rwai.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rwai.agent.Graph
import com.rwai.agent.logic.ProfileTemplates

private val STANDARD_FIELDS = listOf(
    "first_name" to "Имя", "last_name" to "Фамилия", "full_name" to "Полное имя (как в документах)",
    "phone" to "Телефон", "email" to "Email", "address" to "Адрес", "city" to "Город", "postal_code" to "Почтовый индекс",
    "country" to "Страна", "birth_date" to "Дата рождения", "company" to "Компания", "position" to "Должность", "website" to "Сайт",
)

/** Профиль владельца: нужен, чтобы агент заполнял формы за вас. Хранится только на телефоне в зашифрованном виде. */
@Composable
fun ProfileScreen() {
    val loaded = remember { Graph.profile.values() }
    val fields = remember { mutableStateMapOf<String, String>().apply { putAll(loaded) } }
    var about by remember { mutableStateOf(Graph.profile.about()) }
    var newKey by remember { mutableStateOf("") }
    var newValue by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    val customKeys = fields.keys.filter { k -> STANDARD_FIELDS.none { it.first == k } }.sorted()

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.padding(top = 8.dp)) {
            Text(
                "Эти данные хранятся только на вашем телефоне, в зашифрованном виде (ключ в Android Keystore). " +
                    "Модели (DeepSeek) значения не отправляются: она видит только названия полей, а телефон сам подставляет значения при вводе. " +
                    "Каждый ввод ваших данных в приложение или на сайт агент делает только после вашего подтверждения. " +
                    "Не храните здесь номера карт, пароли и паспортные данные: номера карт программа не примет.",
                modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium,
            )
        }
        STANDARD_FIELDS.forEach { (key, label) ->
            OutlinedTextField(
                value = fields[key].orEmpty(), onValueChange = { fields[key] = it }, label = { Text(label) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }
        customKeys.forEach { key ->
            OutlinedTextField(
                value = fields[key].orEmpty(), onValueChange = { fields[key] = it }, label = { Text("{{profile.$key}}") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }
        Text("Своё поле (ключ латиницей, например work_phone)", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = newKey, onValueChange = { newKey = it.lowercase() }, label = { Text("Ключ") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(value = newValue, onValueChange = { newValue = it }, label = { Text("Значение") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        OutlinedButton(onClick = {
            if (ProfileTemplates.isValidKey(newKey) && newValue.isNotBlank()) {
                fields[newKey] = newValue; newKey = ""; newValue = ""; note = null
            } else note = "Ключ — латиница, цифры и _, начинается с буквы; значение не пустое."
        }) { Text("Добавить поле") }

        Text("О себе (необязательно)", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            value = about, onValueChange = { about = it.take(600) }, modifier = Modifier.fillMaxWidth(), minLines = 3,
            label = { Text("Например: живу в Монтевидео, переписываюсь по-русски и по-испански") },
        )
        Text(
            "Внимание: только эта заметка уходит в DeepSeek вместе с задачей, чтобы агент лучше вас понимал. Остальные поля остаются на телефоне. Не пишите сюда ничего секретного.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        note?.let { Text(it, color = RwPalette.Danger) }
        Button(onClick = {
            val rejected = Graph.profile.save(fields.toMap(), about)
            note = if (rejected.isEmpty()) "Сохранено на телефоне." else "Не принято (похоже на номер карты или неверный ключ): " + rejected.joinToString(", ")
            rejected.forEach { fields.remove(it) }
        }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить") }
        Row(modifier = Modifier.padding(bottom = 16.dp)) {
            OutlinedButton(onClick = {
                Graph.profile.save(emptyMap(), ""); fields.clear(); about = ""; note = "Профиль удалён с телефона."
            }) { Text("Удалить профиль") }
        }
    }
}
