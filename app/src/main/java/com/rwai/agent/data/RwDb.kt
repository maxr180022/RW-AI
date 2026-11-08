package com.rwai.agent.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.rwai.agent.logic.FtsQuery
import com.rwai.agent.logic.Json

data class ActivityRow(val id: Long, val ts: Long, val taskId: Long, val kind: String, val tool: String?, val detail: String, val status: String)

data class NotificationRow(
    val id: Long, val ts: Long, val pkg: String, val app: String,
    val sender: String?, val title: String?, val body: String?, val hidden: Boolean,
    val group: Boolean = false, val tr: String? = null,
)

data class MemoryRow(val id: Long, val ts: Long, val source: String, val app: String?, val contact: String?, val title: String, val body: String)

/**
 * Локальная база: журнал действий, уведомления и память с полнотекстовым поиском (FTS4, unicode61 —
 * корректно сравнивает регистр кириллицы). Всё хранится только на устройстве; резервное копирование выключено.
 */
class RwDb(context: Context) : SQLiteOpenHelper(context.applicationContext, "rwai.db", null, 4) {

    /** Диалоги и проекты (v3). Живут в той же базе, чистятся вместе с остальными данными. */
    val dialogStore: DialogStore by lazy { DialogStore(this) }

    private fun createDialogTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS projects(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, note TEXT NOT NULL DEFAULT '', created INTEGER NOT NULL, memory TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE TABLE IF NOT EXISTS dialogs(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, project_id INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL, updated INTEGER NOT NULL, summary TEXT NOT NULL DEFAULT '', carried_from TEXT NOT NULL DEFAULT '', ctx_from INTEGER NOT NULL DEFAULT 0, compactions INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE IF NOT EXISTS dialog_msgs(id INTEGER PRIMARY KEY AUTOINCREMENT, dialog_id INTEGER NOT NULL, ts INTEGER NOT NULL, role TEXT NOT NULL, text TEXT NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_dialog_msgs_dialog ON dialog_msgs(dialog_id)")
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE activity(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, task_id INTEGER NOT NULL, kind TEXT NOT NULL, tool TEXT, detail TEXT NOT NULL, status TEXT NOT NULL)")
        db.execSQL("CREATE INDEX idx_activity_ts ON activity(ts)")
        db.execSQL("CREATE TABLE notifications(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, pkg TEXT NOT NULL, app TEXT NOT NULL, sender TEXT, title TEXT, body TEXT, hidden INTEGER NOT NULL DEFAULT 0, dedupe TEXT NOT NULL UNIQUE, grp INTEGER NOT NULL DEFAULT 0, tr TEXT)")
        db.execSQL("CREATE INDEX idx_notifications_ts ON notifications(ts)")
        db.execSQL("CREATE TABLE memory(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, source TEXT NOT NULL, app TEXT, contact TEXT, title TEXT NOT NULL, body TEXT NOT NULL)")
        db.execSQL("CREATE VIRTUAL TABLE memory_fts USING fts4(title, body, tokenize=unicode61)")
        createDialogTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // v2: признак группового чата и перевод сообщения (для сводки и автоперевода)
            db.execSQL("ALTER TABLE notifications ADD COLUMN grp INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE notifications ADD COLUMN tr TEXT")
        }
        if (oldVersion < 3) createDialogTables(db)   // v3: диалоги и проекты (сразу с общей памятью проекта); старые данные не затрагиваются
        else if (oldVersion == 3) db.execSQL("ALTER TABLE projects ADD COLUMN memory TEXT NOT NULL DEFAULT ''")   // v4: общая память проекта
    }

    // ---------------- Журнал действий ----------------

    fun logActivity(taskId: Long, kind: String, tool: String?, detail: String, status: String) {
        val v = ContentValues().apply {
            put("ts", System.currentTimeMillis()); put("task_id", taskId); put("kind", kind)
            put("tool", tool); put("detail", detail.take(2000)); put("status", status.take(300))
        }
        writableDatabase.insert("activity", null, v)
    }

    fun recentActivity(limit: Int = 300): List<ActivityRow> {
        val out = ArrayList<ActivityRow>()
        readableDatabase.rawQuery("SELECT id, ts, task_id, kind, tool, detail, status FROM activity ORDER BY id DESC LIMIT ?", arrayOf(limit.toString())).use { c ->
            while (c.moveToNext()) out += ActivityRow(c.getLong(0), c.getLong(1), c.getLong(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6))
        }
        return out
    }

    fun clearActivity() { writableDatabase.delete("activity", null, null) }

    // ---------------- Уведомления ----------------

    /** true — запись добавлена; false — дубликат. Запись попадает и в память для поиска (если не скрыта). */
    fun insertNotification(
        ts: Long, pkg: String, app: String, sender: String?, title: String?, body: String?, hidden: Boolean, dedupe: String,
        group: Boolean = false,
    ): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val v = ContentValues().apply {
                put("ts", ts); put("pkg", pkg); put("app", app); put("sender", sender); put("title", title)
                put("body", body); put("hidden", if (hidden) 1 else 0); put("dedupe", dedupe); put("grp", if (group) 1 else 0)
            }
            val id = db.insertWithOnConflict("notifications", null, v, SQLiteDatabase.CONFLICT_IGNORE)
            if (id == -1L) return false
            if (!hidden && !body.isNullOrBlank()) {
                insertMemoryLocked(db, ts, "notification", app, sender, (title ?: app).take(200), body)
            }
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    fun recentNotifications(limit: Int = 100, appFilter: String? = null): List<NotificationRow> {
        val out = ArrayList<NotificationRow>()
        val (sql, args) = if (appFilter.isNullOrBlank()) {
            "SELECT id, ts, pkg, app, sender, title, body, hidden, grp, tr FROM notifications ORDER BY ts DESC LIMIT ?" to arrayOf(limit.toString())
        } else {
            "SELECT id, ts, pkg, app, sender, title, body, hidden, grp, tr FROM notifications WHERE lower(app) LIKE ? OR lower(pkg) LIKE ? ORDER BY ts DESC LIMIT ?" to
                arrayOf("%" + appFilter.lowercase() + "%", "%" + appFilter.lowercase() + "%", limit.toString())
        }
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) out += NotificationRow(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getInt(7) == 1, c.getInt(8) == 1, c.getString(9))
        }
        return out
    }

    /** Уведомления начиная с момента [sinceTs] (старые — первыми). */
    fun notificationsSince(sinceTs: Long, limit: Int = 400): List<NotificationRow> {
        val out = ArrayList<NotificationRow>()
        readableDatabase.rawQuery(
            "SELECT id, ts, pkg, app, sender, title, body, hidden, grp, tr FROM notifications WHERE ts >= ? ORDER BY ts ASC LIMIT ?",
            arrayOf(sinceTs.toString(), limit.toString()),
        ).use { c ->
            while (c.moveToNext()) out += NotificationRow(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getInt(7) == 1, c.getInt(8) == 1, c.getString(9))
        }
        return out
    }

    fun setTranslation(id: Long, translation: String) {
        val v = ContentValues().apply { put("tr", translation) }
        writableDatabase.update("notifications", v, "id = ?", arrayOf(id.toString()))
    }

    fun notificationCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM notifications", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun clearNotifications() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("notifications", null, null)
            deleteMemoryWhere(db, "source = ?", arrayOf("notification"))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    // ---------------- Память ----------------

    fun addMemory(source: String, app: String?, contact: String?, title: String, body: String): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val id = insertMemoryLocked(db, System.currentTimeMillis(), source, app, contact, title, body)
            db.setTransactionSuccessful()
            return id
        } finally { db.endTransaction() }
    }

    private fun insertMemoryLocked(db: SQLiteDatabase, ts: Long, source: String, app: String?, contact: String?, title: String, body: String): Long {
        val v = ContentValues().apply {
            put("ts", ts); put("source", source); put("app", app); put("contact", contact)
            put("title", title.take(300)); put("body", body.take(8000))
        }
        val id = db.insert("memory", null, v)
        val f = ContentValues().apply { put("docid", id); put("title", title.take(300)); put("body", body.take(8000)) }
        db.insert("memory_fts", null, f)
        return id
    }

    private fun deleteMemoryWhere(db: SQLiteDatabase, where: String, args: Array<String>) {
        db.execSQL("DELETE FROM memory_fts WHERE docid IN (SELECT id FROM memory WHERE $where)", args)
        db.delete("memory", where, args)
    }

    fun searchMemory(query: String, appFilter: String? = null, limit: Int = 20): List<MemoryRow> {
        val match = FtsQuery.build(query) ?: return emptyList()
        val out = ArrayList<MemoryRow>()
        val args = ArrayList<String>().apply { add(match) }
        var sql = "SELECT m.id, m.ts, m.source, m.app, m.contact, m.title, m.body FROM memory m JOIN memory_fts f ON f.docid = m.id WHERE memory_fts MATCH ?"
        if (!appFilter.isNullOrBlank()) { sql += " AND lower(m.app) LIKE ?"; args.add("%" + appFilter.lowercase() + "%") }
        sql += " ORDER BY m.ts DESC LIMIT ?"
        args.add(limit.coerceIn(1, 50).toString())
        readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) out += MemoryRow(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6))
        }
        return out
    }

    fun memoryCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM memory", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun clearMemory() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("memory_fts", null, null)
            db.delete("memory", null, null)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Экспорт памяти в JSON-текст (пользователь сам решает, куда его отправить). */
    fun exportMemoryJson(): String {
        val rows = ArrayList<Map<String, Any?>>()
        readableDatabase.rawQuery("SELECT id, ts, source, app, contact, title, body FROM memory ORDER BY ts", null).use { c ->
            while (c.moveToNext()) rows += mapOf(
                "id" to c.getLong(0), "ts" to c.getLong(1), "source" to c.getString(2), "app" to c.getString(3),
                "contact" to c.getString(4), "title" to c.getString(5), "body" to c.getString(6),
            )
        }
        return Json.stringify(mapOf("exported_at" to System.currentTimeMillis(), "memory" to rows))
    }

    /** Ограничение срока хранения (спецификация 5.8). */
    fun purgeOlderThan(cutoffTs: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("notifications", "ts < ?", arrayOf(cutoffTs.toString()))
            deleteMemoryWhere(db, "ts < ?", arrayOf(cutoffTs.toString()))
            db.delete("activity", "ts < ?", arrayOf(cutoffTs.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        dialogStore.purgeOlderThan(cutoffTs)
    }

    fun wipeAll() { clearActivity(); clearNotifications(); clearMemory(); dialogStore.wipe() }
}
