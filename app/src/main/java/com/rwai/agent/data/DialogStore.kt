package com.rwai.agent.data

import android.content.ContentValues
import com.rwai.agent.logic.DialogRules

data class ProjectRow(val id: Long, val name: String, val note: String, val created: Long, val memory: String = "")

data class DialogRow(
    val id: Long,
    val title: String,
    val projectId: Long,
    val created: Long,
    val updated: Long,
    val summary: String,
    val carriedFrom: String,
    val compactions: Int,
)

data class DialogMsgRow(val id: Long, val ts: Long, val role: String, val text: String)

/**
 * Диалоги и проекты на телефоне (таблицы dialogs, dialog_msgs, projects в [RwDb]). Ничего не уходит в сеть.
 * projectId = 0 — диалог без проекта.
 */
class DialogStore(private val helper: RwDb) {

    // ---------------- проекты ----------------

    fun projects(): List<ProjectRow> = helper.readableDatabase.rawQuery("SELECT id, name, note, created, memory FROM projects ORDER BY created", null).use { c ->
        val out = ArrayList<ProjectRow>()
        while (c.moveToNext()) out += ProjectRow(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getString(4))
        out
    }

    fun addProject(name: String, note: String, now: Long): Long {
        val v = ContentValues().apply { put("name", name); put("note", note); put("created", now) }
        return helper.writableDatabase.insert("projects", null, v)
    }

    /** Общая память проекта: суть его диалогов. Читается только внутри проекта. */
    fun saveProjectMemory(id: Long, memory: String) {
        val v = ContentValues().apply { put("memory", memory) }
        helper.writableDatabase.update("projects", v, "id = ?", arrayOf(id.toString()))
    }

    // ---------------- диалоги ----------------

    private fun readDialog(c: android.database.Cursor) = DialogRow(
        id = c.getLong(0), title = c.getString(1), projectId = c.getLong(2), created = c.getLong(3), updated = c.getLong(4),
        summary = c.getString(5), carriedFrom = c.getString(6), compactions = c.getInt(7),
    )

    private val dialogCols = "id, title, project_id, created, updated, summary, carried_from, compactions"

    /** Все диалоги: новые сверху. */
    fun dialogs(): List<DialogRow> = helper.readableDatabase.rawQuery("SELECT $dialogCols FROM dialogs ORDER BY created DESC, id DESC", null).use { c ->
        val out = ArrayList<DialogRow>()
        while (c.moveToNext()) out += readDialog(c)
        out
    }

    fun dialog(id: Long): DialogRow? = helper.readableDatabase.rawQuery("SELECT $dialogCols FROM dialogs WHERE id = ?", arrayOf(id.toString())).use { c ->
        if (c.moveToFirst()) readDialog(c) else null
    }

    /** Самый свежий диалог проекта (для переноса его памяти в новый диалог), либо null. */
    fun latestInProject(projectId: Long): DialogRow? =
        helper.readableDatabase.rawQuery("SELECT $dialogCols FROM dialogs WHERE project_id = ? ORDER BY created DESC, id DESC LIMIT 1", arrayOf(projectId.toString())).use { c ->
            if (c.moveToFirst()) readDialog(c) else null
        }

    fun createDialog(title: String, projectId: Long, summary: String, carriedFrom: String, now: Long): Long {
        val v = ContentValues().apply {
            put("title", title); put("project_id", projectId); put("created", now); put("updated", now)
            put("summary", summary); put("carried_from", carriedFrom); put("compactions", 0); put("ctx_from", 0)
        }
        return helper.writableDatabase.insert("dialogs", null, v)
    }

    /** Пустой диалог за прошлый день получает сегодняшнюю дату. */
    fun retitle(id: Long, title: String, now: Long) {
        val v = ContentValues().apply { put("title", title); put("created", now); put("updated", now) }
        helper.writableDatabase.update("dialogs", v, "id = ?", arrayOf(id.toString()))
    }

    /** Граница «живой» памяти: сообщения с номером ≤ ctxFrom уже свёрнуты в выжимку. */
    fun ctxFrom(id: Long): Long = helper.readableDatabase.rawQuery("SELECT ctx_from FROM dialogs WHERE id = ?", arrayOf(id.toString())).use { c ->
        if (c.moveToFirst()) c.getLong(0) else 0L
    }

    fun saveMemory(id: Long, summary: String, ctxFrom: Long, compactions: Int) {
        val v = ContentValues().apply { put("summary", summary); put("ctx_from", ctxFrom); put("compactions", compactions) }
        helper.writableDatabase.update("dialogs", v, "id = ?", arrayOf(id.toString()))
    }

    // ---------------- сообщения ----------------

    /** Возвращает номер сохранённого сообщения (по нему отмечается граница сжатой памяти). */
    fun addMessage(dialogId: Long, ts: Long, role: String, text: String): Long {
        val db = helper.writableDatabase
        var newId = -1L
        db.beginTransaction()
        try {
            val v = ContentValues().apply { put("dialog_id", dialogId); put("ts", ts); put("role", role); put("text", text) }
            newId = db.insert("dialog_msgs", null, v)
            val up = ContentValues().apply { put("updated", ts) }
            db.update("dialogs", up, "id = ?", arrayOf(dialogId.toString()))
            // Страховка размера: самые старые сообщения сверх лимита удаляются (в обычной работе до лимита не доходит).
            db.execSQL(
                "DELETE FROM dialog_msgs WHERE dialog_id = ? AND id NOT IN (SELECT id FROM dialog_msgs WHERE dialog_id = ? ORDER BY id DESC LIMIT ?)",
                arrayOf<Any>(dialogId, dialogId, DialogRules.MAX_MESSAGES_PER_DIALOG),
            )
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return newId
    }

    fun messages(dialogId: Long): List<DialogMsgRow> =
        helper.readableDatabase.rawQuery("SELECT id, ts, role, text FROM dialog_msgs WHERE dialog_id = ? ORDER BY id", arrayOf(dialogId.toString())).use { c ->
            val out = ArrayList<DialogMsgRow>()
            while (c.moveToNext()) out += DialogMsgRow(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3))
            out
        }

    /** «Очистить» в чате: сообщения и память одного диалога; сам диалог (дата, проект) остаётся. */
    fun clearDialog(id: Long) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete("dialog_msgs", "dialog_id = ?", arrayOf(id.toString()))
            val v = ContentValues().apply { put("summary", ""); put("ctx_from", 0); put("compactions", 0) }
            db.update("dialogs", v, "id = ?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    // ---------------- срок хранения и полная очистка (вызываются из RwDb) ----------------

    internal fun purgeOlderThan(cutoffTs: Long) {
        val db = helper.writableDatabase
        db.execSQL("DELETE FROM dialog_msgs WHERE dialog_id IN (SELECT id FROM dialogs WHERE updated < ?)", arrayOf<Any>(cutoffTs))
        db.delete("dialogs", "updated < ?", arrayOf(cutoffTs.toString()))
    }

    internal fun wipe() {
        val db = helper.writableDatabase
        db.delete("dialog_msgs", null, null)
        db.delete("dialogs", null, null)
        db.delete("projects", null, null)
    }
}
