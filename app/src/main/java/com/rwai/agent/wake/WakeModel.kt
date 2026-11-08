package com.rwai.agent.wake

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** Офлайн-модель распознавания речи (Vosk, русская, ~45 МБ). Скачивается один раз по кнопке и хранится только на телефоне. */
object WakeModel {
    const val URL_ZIP = "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
    private const val MAX_UNPACKED = 400L * 1024 * 1024

    private val _progress = MutableStateFlow(-1)

    /** -1 — не качается, 0..100 — идёт загрузка. */
    val progress: StateFlow<Int> get() = _progress

    fun dir(c: Context) = File(c.filesDir, "vosk-model-ru")

    fun ready(c: Context): Boolean = File(dir(c), "am/final.mdl").isFile

    fun delete(c: Context) { dir(c).deleteRecursively() }

    private class Counting(input: InputStream, val onBytes: (Long) -> Unit) : FilterInputStream(input) {
        var total = 0L
        override fun read(): Int { val r = super.read(); if (r >= 0) { total++; onBytes(total) }; return r }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val r = super.read(b, off, len)
            if (r > 0) { total += r; onBytes(total) }
            return r
        }
    }

    suspend fun download(c: Context): Result<Unit> = withContext(Dispatchers.IO) {
        val tmp = File(c.filesDir, "vosk-tmp")
        try {
            tmp.deleteRecursively()
            tmp.mkdirs()
            _progress.value = 0
            val conn = URL(URL_ZIP).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            if (conn.responseCode != 200) throw IllegalStateException("Сервер ответил ${conn.responseCode}")
            val length = conn.contentLengthLong
            val counting = Counting(conn.inputStream) { n ->
                if (length > 0) _progress.value = (n * 100 / length).toInt().coerceIn(0, 99)
            }
            var unpacked = 0L
            ZipInputStream(counting.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val rel = entry.name.substringAfter('/', "")
                    if (rel.isEmpty()) continue
                    val out = File(tmp, rel)
                    // защита от записи за пределы папки (zip-slip)
                    if (!out.canonicalPath.startsWith(tmp.canonicalPath + File.separator)) throw SecurityException("Недопустимый путь в архиве")
                    if (entry.isDirectory) { out.mkdirs(); continue }
                    out.parentFile?.mkdirs()
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = zip.read(buf)
                            if (n < 0) break
                            unpacked += n
                            if (unpacked > MAX_UNPACKED) throw SecurityException("Архив слишком большой")
                            o.write(buf, 0, n)
                        }
                    }
                }
            }
            if (!File(tmp, "am/final.mdl").isFile) throw IllegalStateException("В архиве нет файлов модели")
            dir(c).deleteRecursively()
            if (!tmp.renameTo(dir(c))) throw IllegalStateException("Не удалось сохранить модель")
            Result.success(Unit)
        } catch (e: Exception) {
            tmp.deleteRecursively()
            Result.failure(e)
        } finally {
            _progress.value = -1
        }
    }
}
