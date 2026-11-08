package com.rwai.agent.brain

import com.rwai.agent.logic.ChatMessage
import com.rwai.agent.logic.Json
import com.rwai.agent.logic.ModelClient
import com.rwai.agent.logic.ModelException
import com.rwai.agent.logic.SecretRedactor
import com.rwai.agent.logic.asJsonList
import com.rwai.agent.logic.asJsonMap
import com.rwai.agent.settings.SettingsStore
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

data class ValidationResult(val ok: Boolean, val message: String)

/**
 * Клиент DeepSeek (формат OpenAI-совместимого /chat/completions). Адрес, модель и режим thinking берутся из настроек.
 * Повторяются только запросы, безопасные для повтора (чтение/генерация): сетевые сбои, 429 и 5xx.
 * Ключ не попадает в логи и тексты ошибок; содержимое переписки не логируется.
 */
class DeepSeekClient(
    private val settings: SettingsStore,
    private val http: OkHttpClient = defaultHttp(),
) : ModelClient {

    override suspend fun complete(messages: List<ChatMessage>): String {
        val s = settings.current
        return completeWith(messages, s.model, s.thinking, "agent")
    }

    /** Простая служебная работа (сводка, перевод, совет по телефону): лёгкая модель без рассуждения, если включён автовыбор. */
    suspend fun completeLight(messages: List<ChatMessage>, source: String = "other"): String {
        val s = settings.current
        return if (s.autoModel) completeWith(messages, com.rwai.agent.settings.Settings.lightModelFor(s.model), false, source) else completeWith(messages, s.model, s.thinking, source)
    }

    /** Один запрос с явной моделью и режимом: так автовыбор ведёт простые задачи на лёгкой модели, а сложные на точной. */
    suspend fun completeWith(messages: List<ChatMessage>, model: String, thinking: Boolean, source: String = "agent"): String {
        val s = settings.current
        val key = settings.apiKey() ?: throw ModelException("API-ключ DeepSeek не задан. Откройте «Настройки» и введите ключ.")
        if (s.dailyTokenCap > 0 && settings.tokensUsedToday() >= s.dailyTokenCap) {
            throw ModelException("Достигнут дневной лимит токенов (${s.dailyTokenCap}). Измените лимит в настройках.")
        }
        val totalChars = messages.sumOf { it.content.length }
        if (totalChars > MAX_REQUEST_CHARS) throw ModelException("Запрос слишком большой ($totalChars символов).")

        val body = buildBody(model, thinking, messages)
        var attempt = 0
        var lastError: ModelException? = null
        while (attempt <= MAX_RETRIES) {
            if (attempt > 0) delay(1000L * (1L shl (attempt - 1)))
            attempt++
            try {
                return doRequest(s.baseUrl, key, body, source = source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ModelException) {
                lastError = e
                if (!e.retryable) throw e
            }
        }
        throw lastError ?: ModelException("Неизвестная ошибка DeepSeek")
    }

    suspend fun validate(): ValidationResult {
        val s = settings.current
        val key = settings.apiKey() ?: return ValidationResult(false, "Ключ не введён.")
        // 1) Список моделей — самая дешёвая проверка ключа.
        try {
            val req = Request.Builder().url(s.baseUrl + "/models").header("Authorization", "Bearer $key").get().build()
            http.newCall(req).await().use { r ->
                when {
                    r.code == 401 -> return ValidationResult(false, "Неверный или отозванный API-ключ (401).")
                    r.isSuccessful -> return ValidationResult(true, "Соединение установлено, ключ принят.")
                    else -> Unit // 404/405: адрес без /models — проверим минимальным запросом
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return ValidationResult(false, networkMessage(e))
        }
        // 2) Запасной вариант: минимальный запрос чата без режима thinking.
        return try {
            val body = Json.stringify(
                mapOf(
                    "model" to s.model,
                    "messages" to listOf(mapOf("role" to "user", "content" to "ping")),
                    "max_tokens" to 8,
                    "stream" to false,
                    "thinking" to mapOf("type" to "disabled"),
                ),
            )
            doRequest(s.baseUrl, key, body, countTokens = false)
            ValidationResult(true, "Соединение установлено, модель «${s.model}» отвечает.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelException) {
            ValidationResult(false, e.message ?: "Ошибка")
        }
    }

    internal fun buildBody(model: String, thinking: Boolean, messages: List<ChatMessage>): String {
        val map = LinkedHashMap<String, Any?>()
        map["model"] = model
        map["messages"] = messages.map { m ->
            if (m.images.isEmpty() || m.role != "user") {
                mapOf("role" to m.role, "content" to m.content)
            } else {
                val blocks = ArrayList<Any?>()
                blocks += mapOf("type" to "text", "text" to m.content)
                m.images.forEach { url -> blocks += mapOf("type" to "image_url", "image_url" to mapOf("url" to url)) }
                mapOf("role" to m.role, "content" to blocks)
            }
        }
        map["stream"] = false
        map["thinking"] = mapOf("type" to if (thinking) "enabled" else "disabled")
        map["max_tokens"] = if (thinking) 32768 else 4096
        if (!thinking) map["temperature"] = 0.2 // в режиме thinking температура не действует
        return Json.stringify(map)
    }

    private suspend fun doRequest(baseUrl: String, key: String, body: String, countTokens: Boolean = true, source: String = "other"): String {
        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val response = try {
            http.newCall(request).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw ModelException(networkMessage(e), retryable = e !is UnknownHostException)
        }
        response.use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw httpError(r.code, text)
            val parsed = try {
                Json.parse(text).asJsonMap()
            } catch (e: Exception) {
                null
            } ?: throw ModelException("DeepSeek вернул ответ в неожиданном формате.", retryable = true)
            if (countTokens) {
                val usage = parsed["usage"].asJsonMap()
                fun n(key: String): Int = (usage?.get(key) as? Long)?.toInt() ?: 0
                val total = n("total_tokens")
                if (total > 0) settings.addUsage(source, total, n("prompt_tokens"), n("prompt_cache_hit_tokens"))
            }
            val choice = parsed["choices"].asJsonList()?.firstOrNull().asJsonMap()
                ?: throw ModelException("DeepSeek вернул пустой список ответов.", retryable = true)
            val content = (choice["message"].asJsonMap()?.get("content") as? String).orEmpty()
            if (content.isBlank()) {
                val reason = choice["finish_reason"] as? String
                throw if (reason == "length") ModelException("Ответ модели оборван по длине; упростите задачу.") else
                    ModelException("Модель вернула пустой ответ (finish_reason=$reason).", retryable = true)
            }
            return content
        }
    }

    private fun httpError(code: Int, body: String): ModelException {
        val apiMessage = try {
            Json.parse(body).asJsonMap()?.get("error").asJsonMap()?.get("message") as? String
        } catch (e: Exception) { null }
        val tail = apiMessage?.let { ": " + SecretRedactor.redact(it).take(200) }.orEmpty()
        return when (code) {
            400, 422 -> ModelException("Некорректный запрос к DeepSeek ($code)$tail")
            401 -> ModelException("Неверный или отозванный API-ключ (401).")
            402 -> ModelException("Недостаточно средств на аккаунте DeepSeek (402).")
            403 -> ModelException("Доступ запрещён (403)$tail")
            404 -> ModelException("Адрес или модель не найдены (404). Проверьте Base URL и название модели$tail")
            429 -> ModelException("Слишком много запросов (429), повторяю…", retryable = true)
            in 500..599 -> ModelException("Сервер DeepSeek временно недоступен ($code).", retryable = true)
            else -> ModelException("Ошибка DeepSeek: HTTP $code$tail")
        }
    }

    private fun networkMessage(e: IOException): String = when (e) {
        is UnknownHostException -> "Нет подключения к интернету или не найден адрес DeepSeek."
        is SocketTimeoutException -> "DeepSeek не ответил вовремя (тайм-аут)."
        else -> "Сетевая ошибка: ${e.javaClass.simpleName}"
    }

    companion object {
        private const val MAX_RETRIES = 2
        private const val MAX_REQUEST_CHARS = 400_000

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(240, TimeUnit.SECONDS) // режим thinking может отвечать долго
            .callTimeout(300, TimeUnit.SECONDS)
            .build()
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    })
}
