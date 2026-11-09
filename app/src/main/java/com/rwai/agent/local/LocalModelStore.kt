package com.rwai.agent.local

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.rwai.agent.logic.DownloadMath
import com.rwai.agent.logic.LocalModelSpec
import com.rwai.agent.logic.StartDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Файл локальной модели: докачка по частям, проверка целостности, хранение в закрытой папке приложения.
 * Обновления приложения (установка поверх с той же подписью) файл не трогают; из проводника его не видно и случайно не удалить.
 */
object LocalModelStore {
    enum class State { NONE, PARTIAL, DOWNLOADING, VERIFYING, READY, ERROR }

    data class Status(val state: State, val done: Long, val total: Long, val message: String? = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _status = MutableStateFlow(Status(State.NONE, 0, LocalModelSpec.SIZE_BYTES))
    val status: StateFlow<Status> = _status

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).followRedirects(true).build()
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, "models").apply { mkdirs() }
    fun file(ctx: Context) = File(dir(ctx), LocalModelSpec.FILE_NAME)
    private fun part(ctx: Context) = File(dir(ctx), LocalModelSpec.FILE_NAME + ".part")

    /** Определяет состояние по файлам на диске (при запуске экрана и после действий). */
    fun refresh(ctx: Context) {
        if (job?.isActive == true) return
        val f = file(ctx)
        val p = part(ctx)
        _status.value = when {
            f.exists() && f.length() == LocalModelSpec.SIZE_BYTES -> Status(State.READY, f.length(), LocalModelSpec.SIZE_BYTES)
            p.exists() && p.length() > 0 -> Status(State.PARTIAL, p.length(), LocalModelSpec.SIZE_BYTES)
            else -> Status(State.NONE, 0, LocalModelSpec.SIZE_BYTES)
        }
    }

    fun isReady(ctx: Context): Boolean = file(ctx).let { it.exists() && it.length() == LocalModelSpec.SIZE_BYTES }

    fun isDownloading(): Boolean = job?.isActive == true

    private fun onUnmeteredNetwork(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Запускает или продолжает загрузку. Возвращает текст причины, если начать нельзя; null — загрузка пошла. */
    fun start(ctx: Context, wifiOnly: Boolean): String? {
        if (job?.isActive == true) return null
        val appCtx = ctx.applicationContext
        val have = part(appCtx).let { if (it.exists()) it.length() else 0L }
        when (DownloadMath.canStart(wifiOnly, onUnmeteredNetwork(appCtx), appCtx.filesDir.usableSpace, have, LocalModelSpec.SIZE_BYTES)) {
            StartDecision.NEED_WIFI -> return "Нужен Wi-Fi или другая безлимитная сеть. Включите Wi-Fi или разрешите загрузку по мобильной сети."
            StartDecision.NO_SPACE -> return "Не хватает места на телефоне: нужно около ${DownloadMath.human(LocalModelSpec.SIZE_BYTES - have + LocalModelSpec.SPACE_MARGIN_BYTES)}."
            StartDecision.OK -> Unit
        }
        job = scope.launch { download(appCtx) }
        return null
    }

    fun stop(ctx: Context) {
        job?.cancel()
        job = null
        refresh(ctx.applicationContext)
    }

    fun delete(ctx: Context) {
        job?.cancel()
        job = null
        LocalLlm.release()
        file(ctx).delete()
        part(ctx).delete()
        _status.value = Status(State.NONE, 0, LocalModelSpec.SIZE_BYTES)
    }

    private suspend fun download(ctx: Context) {
        val part = part(ctx)
        val target = file(ctx)
        val total = LocalModelSpec.SIZE_BYTES
        try {
            var from = DownloadMath.resumeFrom(if (part.exists()) part.length() else 0L, total)
            if (from == 0L && part.exists()) part.delete()
            _status.value = Status(State.DOWNLOADING, from, total)
            val req = Request.Builder().url(LocalModelSpec.URL).apply { DownloadMath.rangeHeader(from)?.let { header("Range", it) } }.build()
            http.newCall(req).execute().use { resp ->
                if (resp.code == 416) { part.delete(); throw IOException("Сервер не принял докачку, нажмите «Скачать» ещё раз.") }
                if (!resp.isSuccessful) throw IOException("Сервер ответил ${resp.code}.")
                val body = resp.body ?: throw IOException("Пустой ответ сервера.")
                val append = DownloadMath.serverAcceptedResume(resp.code) && from > 0
                if (!append) from = 0L
                var done = from
                var lastEmit = 0L
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(256 * 1024)
                    body.byteStream().use { input ->
                        while (true) {
                            if (!currentCoroutineContext().isActive) throw kotlinx.coroutines.CancellationException()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - lastEmit > 400) { lastEmit = now; _status.value = Status(State.DOWNLOADING, done, total) }
                        }
                    }
                }
            }
            if (part.length() != total) throw IOException("Файл скачан не полностью (${part.length()} из $total байт). Нажмите «Скачать», чтобы докачать.")
            _status.value = Status(State.VERIFYING, total, total)
            val ok = withContext(Dispatchers.IO) { sha256(part).equals(LocalModelSpec.SHA256, ignoreCase = true) }
            if (!ok) {
                part.delete()
                throw IOException("Контрольная сумма не совпала: файл повреждён. Скачайте заново.")
            }
            target.delete()
            if (!part.renameTo(target)) throw IOException("Не удалось сохранить файл модели.")
            _status.value = Status(State.READY, total, total)
        } catch (e: kotlinx.coroutines.CancellationException) {
            refresh(ctx)
            throw e
        } catch (e: Exception) {
            val have = if (part.exists()) part.length() else 0L
            _status.value = Status(State.ERROR, have, total, e.message ?: e.javaClass.simpleName)
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
}
