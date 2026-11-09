package com.rwai.agent.local

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.rwai.agent.logic.DownloadMath
import com.rwai.agent.logic.PiperCatalog
import com.rwai.agent.logic.PiperVoice
import com.rwai.agent.logic.StartDecision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Голоса Piper на телефоне: скачивание пакета (с докачкой и проверкой контрольной суммы), распаковка в закрытую папку приложения,
 * выбор голоса для каждого языка. Файлы лежат в закрытой папке: из проводника не видны, обновления приложения их не трогают.
 */
object PiperStore {
    enum class State { NONE, DOWNLOADING, VERIFYING, EXTRACTING, READY, ERROR }

    data class VoiceStatus(val state: State, val done: Long = 0, val total: Long = 0, val message: String? = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val _status = MutableStateFlow<Map<String, VoiceStatus>>(emptyMap())
    val status: StateFlow<Map<String, VoiceStatus>> = _status

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).followRedirects(true).build()
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("rwai_piper", Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean("enabled", true)
    fun setEnabled(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("enabled", on).apply() }

    /**
     * Голос языка: выбранный владельцем, если он скачан; иначе любой скачанный голос этого языка; иначе выбранный или первый из каталога.
     * Так удаление выбранного голоса не оставляет приложение «искать» удалённый и молча падать на системный голос.
     */
    fun chosen(ctx: Context, lang: String): PiperVoice? {
        val l = PiperCatalog.langOf(lang)
        val saved = PiperCatalog.byId(prefs(ctx).getString("voice_$l", null).orEmpty())?.takeIf { it.lang == l }
        if (saved != null && isInstalled(ctx, saved)) return saved
        return PiperCatalog.forLanguage(l).firstOrNull { isInstalled(ctx, it) } ?: saved ?: PiperCatalog.defaultFor(l)
    }

    fun choose(ctx: Context, v: PiperVoice) { prefs(ctx).edit().putString("voice_" + v.lang, v.id).apply() }

    fun dir(ctx: Context, v: PiperVoice) = File(ctx.applicationContext.filesDir, "piper/" + v.id)
    private fun partFile(ctx: Context, v: PiperVoice) = File(ctx.applicationContext.filesDir, "piper/" + v.id + ".tar.bz2.part")
    fun isInstalled(ctx: Context, v: PiperVoice): Boolean = File(dir(ctx, v), ".ok").exists()

    /** Состояние по файлам на диске (при открытии экрана и после действий). Идущие загрузки не трогает. */
    fun refresh(ctx: Context) {
        cleanOrphans(ctx)
        val next = HashMap<String, VoiceStatus>(_status.value)
        for (v in PiperCatalog.ALL) {
            if (jobs[v.id]?.isActive == true) continue
            next[v.id] = if (isInstalled(ctx, v)) VoiceStatus(State.READY, v.size, v.size) else VoiceStatus(State.NONE, 0, v.size)
        }
        _status.value = next
    }

    /** Голоса, которых больше нет в каталоге (например, убранный Денис), удаляются с телефона, чтобы не занимать место. */
    private fun cleanOrphans(ctx: Context) {
        val root = File(ctx.applicationContext.filesDir, "piper")
        root.listFiles()?.forEach { f ->
            if (PiperCatalog.ALL.none { f.name == it.id || f.name.startsWith(it.id + ".") }) f.deleteRecursively()
        }
    }

    /** Все постоянные голоса скачиваются сами по Wi-Fi при запуске приложения: по одному, следующий после предыдущего. */
    fun ensureDefault(ctx: Context) {
        val app = ctx.applicationContext
        if (!enabled(app)) return
        cleanOrphans(app)
        if (jobs.values.any { it.isActive }) return
        val v = PiperCatalog.ALL.firstOrNull { !isInstalled(app, it) } ?: return
        download(app, v, wifiOnly = true)
    }

    private fun set(id: String, st: VoiceStatus) { _status.value = _status.value + (id to st) }

    private fun unmetered(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Запускает или продолжает загрузку голоса. Возвращает причину отказа или null, если пошло. */
    fun download(ctx: Context, v: PiperVoice, wifiOnly: Boolean = true): String? {
        if (jobs[v.id]?.isActive == true) return null
        val app = ctx.applicationContext
        val have = partFile(app, v).let { if (it.exists()) it.length() else 0L }
        when (DownloadMath.canStart(wifiOnly, unmetered(app), app.filesDir.usableSpace, have, v.size)) {
            StartDecision.NEED_WIFI -> return "Нужен Wi-Fi или другая безлимитная сеть."
            StartDecision.NO_SPACE -> return "Не хватает места на телефоне."
            StartDecision.OK -> Unit
        }
        jobs[v.id] = scope.launch { fetch(app, v) }
        return null
    }

    fun stop(ctx: Context, v: PiperVoice) {
        jobs.remove(v.id)?.cancel()
        refresh(ctx)
    }

    fun delete(ctx: Context, v: PiperVoice) {
        jobs.remove(v.id)?.cancel()
        PiperTts.release(v.id)
        dir(ctx, v).deleteRecursively()
        partFile(ctx, v).delete()
        refresh(ctx)
    }

    private suspend fun fetch(ctx: Context, v: PiperVoice) {
        val part = partFile(ctx, v)
        part.parentFile?.mkdirs()
        try {
            var from = DownloadMath.resumeFrom(if (part.exists()) part.length() else 0L, v.size)
            if (from == 0L && part.exists()) part.delete()
            set(v.id, VoiceStatus(State.DOWNLOADING, from, v.size))
            val req = Request.Builder().url(PiperCatalog.url(v)).apply { DownloadMath.rangeHeader(from)?.let { header("Range", it) } }.build()
            http.newCall(req).execute().use { resp ->
                if (resp.code == 416) { part.delete(); throw IOException("Сервер не принял докачку, нажмите «Скачать» ещё раз.") }
                if (!resp.isSuccessful) throw IOException("Сервер ответил ${resp.code}.")
                val body = resp.body ?: throw IOException("Пустой ответ сервера.")
                val append = DownloadMath.serverAcceptedResume(resp.code) && from > 0
                if (!append) from = 0L
                var done = from
                var last = 0L
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(256 * 1024)
                    body.byteStream().use { input ->
                        while (true) {
                            if (!currentCoroutineContext().isActive) throw CancellationException()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - last > 400) { last = now; set(v.id, VoiceStatus(State.DOWNLOADING, done, v.size)) }
                        }
                    }
                }
            }
            if (part.length() != v.size) throw IOException("Файл скачан не полностью. Нажмите «Скачать», чтобы докачать.")
            set(v.id, VoiceStatus(State.VERIFYING, v.size, v.size))
            if (!sha256(part).equals(v.sha256, ignoreCase = true)) {
                part.delete()
                throw IOException("Контрольная сумма не совпала: файл повреждён. Скачайте заново.")
            }
            set(v.id, VoiceStatus(State.EXTRACTING, v.size, v.size))
            extract(part, dir(ctx, v))
            part.delete()
            set(v.id, VoiceStatus(State.READY, v.size, v.size))
            scope.launch { delay(800); ensureDefault(ctx) } // следующий голос по очереди
        } catch (e: CancellationException) {
            refresh(ctx)
            throw e
        } catch (e: Exception) {
            set(v.id, VoiceStatus(State.ERROR, 0, v.size, e.message ?: e.javaClass.simpleName))
        } finally {
            jobs.remove(v.id)
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Распаковка tar.bz2 без верхней папки архива. Пути проверяются: файл не может выйти за пределы папки голоса. */
    private fun extract(archive: File, target: File) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        val root = tmp.canonicalPath + File.separator
        BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive))).use { bz ->
            TarArchiveInputStream(bz).use { tar ->
                while (true) {
                    val e = tar.nextEntry ?: break
                    if (e.isSymbolicLink || e.isLink) continue
                    val rel = e.name.substringAfter('/', "")
                    if (rel.isEmpty()) continue
                    val out = File(tmp, rel)
                    if (!out.canonicalPath.startsWith(root)) throw IOException("Небезопасный путь в архиве.")
                    if (e.isDirectory) { out.mkdirs(); continue }
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { tar.copyTo(it) }
                }
            }
        }
        if (tmp.walkTopDown().none { it.isFile && it.name.endsWith(".onnx") }) {
            tmp.deleteRecursively()
            throw IOException("В архиве нет файла голоса.")
        }
        target.deleteRecursively()
        if (!tmp.renameTo(target)) throw IOException("Не удалось сохранить голос.")
        File(target, ".ok").writeText("ok")
    }
}
