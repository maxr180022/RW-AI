package com.rwai.agent.local

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Запуск локальной модели (Gemma 4 E4B через LiteRT-LM). Движок поднимается при первом запросе (загрузка модели занимает секунды),
 * держится в памяти, пока им пользуются, и сам выгружается через минуту простоя, чтобы не занимать оперативную память телефона.
 * Нет файла модели или она не запустилась — бросает [LocalModelException], вызывающий откатывается на DeepSeek.
 */
object LocalLlm {
    class LocalModelException(message: String) : Exception(message)

    private val lock = Mutex()
    private var engine: Engine? = null
    private var backendName: String = ""
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var idleJob: Job? = null

    /** Каким устройством считает движок: «видеоускоритель» или «процессор» (после первого запроса). */
    fun backend(): String = backendName

    private fun open(ctx: Context): Engine {
        val path = LocalModelStore.file(ctx)
        if (!LocalModelStore.isReady(ctx)) throw LocalModelException("Модель не скачана.")
        var lastError: Throwable? = null
        for ((name, backend) in listOf("видеоускоритель" to { Backend.GPU() }, "процессор" to { Backend.CPU() })) {
            try {
                val e = Engine(EngineConfig(modelPath = path.path, backend = backend(), cacheDir = ctx.cacheDir.path))
                e.initialize()
                backendName = name
                return e
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw LocalModelException("Модель не запустилась: " + (lastError?.message ?: lastError?.javaClass?.simpleName ?: "неизвестная ошибка"))
    }

    /** Один запрос без истории: [prompt] целиком (инструкция и данные). Работает в фоновом потоке. */
    suspend fun generate(ctx: Context, prompt: String): String = lock.withLock {
        withContext(Dispatchers.Default) {
            idleJob?.cancel()
            try {
                val e = engine ?: open(ctx.applicationContext).also { engine = it }
                val answer = e.createConversation().use { c -> c.sendMessage(prompt).toString() }
                scheduleRelease()
                answer.trim()
            } catch (t: LocalModelException) {
                throw t
            } catch (t: Throwable) {
                release()
                throw LocalModelException("Ошибка локальной модели: " + (t.message ?: t.javaClass.simpleName))
            }
        }
    }

    private fun scheduleRelease() {
        idleJob = scope.launch {
            delay(60_000)
            release()
        }
    }

    /** Выгрузить модель из оперативной памяти (файл остаётся на диске). */
    fun release() {
        idleJob?.cancel()
        try { engine?.close() } catch (_: Throwable) { }
        engine = null
    }
}
