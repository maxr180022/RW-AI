package com.rwai.agent.docs

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import com.rwai.agent.logic.LineBox
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Файл, который создала Руся (PDF, картинка). [path] — во внутреннем хранилище приложения. */
class GeneratedFile(
    val name: String,
    val path: String,
    val mime: String,
    val savedTo: String? = null,
    val ts: Long = System.currentTimeMillis(),
)

/** Реестр созданных и полученных файлов за время работы приложения + текстовые индексы PDF для пометок по фразам. */
object GeneratedFiles {
    private val items = CopyOnWriteArrayList<GeneratedFile>()
    private val indexes = java.util.concurrent.ConcurrentHashMap<String, List<LineBox>>()
    private val incoming = CopyOnWriteArrayList<GeneratedFile>()

    fun mark(): Int = items.size
    fun since(mark: Int): List<GeneratedFile> = items.drop(mark.coerceAtLeast(0))
    fun add(f: GeneratedFile, index: List<LineBox>? = null) {
        items.add(f)
        if (index != null) indexes[f.name] = index
        while (items.size > 60) items.removeAt(0)
    }

    fun addIncoming(f: GeneratedFile) {
        incoming.add(f)
        while (incoming.size > 20) incoming.removeAt(0)
    }

    fun index(name: String): List<LineBox>? = indexes[name]

    /** Источник для разметки: по имени среди созданных/полученных, либо «последний» ([name] пусто или "last"). */
    fun find(name: String?): GeneratedFile? {
        val all = (items + incoming).sortedBy { it.ts }
        val n = name?.trim().orEmpty()
        if (n.isEmpty() || n.equals("last", true) || n.equals("последний", true)) return all.lastOrNull()
        return all.lastOrNull { it.name.equals(n, true) } ?: all.lastOrNull { it.name.contains(n, true) }
    }

    fun incomingLast(): GeneratedFile? = incoming.lastOrNull()
}

object DocFiles {
    fun dir(ctx: Context): File = File(ctx.filesDir, "docs").apply { mkdirs() }

    /** Свободное имя в папке документов: «Заявление.pdf», «Заявление (2).pdf», … */
    fun uniqueFile(ctx: Context, name: String): File {
        val d = dir(ctx)
        var f = File(d, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var i = 2
        while (f.exists() && i < 1000) { f = File(d, "$base ($i).$ext"); i++ }
        return f
    }

    private fun uriOf(ctx: Context, f: GeneratedFile): Uri =
        FileProvider.getUriForFile(ctx, ctx.packageName + ".files", File(f.path))

    fun open(ctx: Context, f: GeneratedFile) {
        try {
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(uriOf(ctx, f), f.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (_: Exception) {
            Toast.makeText(ctx, "Нет приложения для открытия. Нажмите «Поделиться».", Toast.LENGTH_LONG).show()
        }
    }

    fun share(ctx: Context, f: GeneratedFile) {
        try {
            val send = Intent(Intent.ACTION_SEND).setType(f.mime)
                .putExtra(Intent.EXTRA_STREAM, uriOf(ctx, f))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(Intent.createChooser(send, f.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(ctx, "Не удалось поделиться файлом.", Toast.LENGTH_LONG).show()
        }
    }

    /** Копия в общую папку «Загрузки/Rusya AI». Возвращает понятный путь или null. */
    fun copyToDownloads(ctx: Context, f: GeneratedFile): String? = try {
        val src = File(f.path)
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, f.name)
                put(MediaStore.Downloads.MIME_TYPE, f.mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Rusya AI")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
            if (uri == null) null else {
                ctx.contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                "Загрузки/Rusya AI/${f.name}"
            }
        } else {
            val d = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Rusya AI").apply { mkdirs() }
            val dst = File(d, f.name)
            src.copyTo(dst, overwrite = true)
            "Загрузки/Rusya AI/${f.name}"
        }
    } catch (_: Exception) { null }

    fun saveToDownloads(ctx: Context, f: GeneratedFile) {
        val where = copyToDownloads(ctx, f)
        Toast.makeText(
            ctx,
            if (where != null) "Сохранено: $where" else "Не удалось сохранить в «Загрузки». Нажмите «Поделиться» и выберите «Файлы».",
            Toast.LENGTH_LONG,
        ).show()
    }
}
