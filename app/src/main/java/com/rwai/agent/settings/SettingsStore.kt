package com.rwai.agent.settings

import android.content.Context
import com.rwai.agent.logic.AutonomyMode
import com.rwai.agent.logic.Endpoint
import com.rwai.agent.security.SecretStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Settings(
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = DEFAULT_MODEL,
    val thinking: Boolean = true,
    val mode: AutonomyMode = AutonomyMode.ASSISTANT,
    val autoSend: Boolean = false,
    /** BCP-47: ru-RU, es-UY, es-ES, en-US */
    val language: String = "ru-RU",
    val speakResults: Boolean = false,
    val voiceAutoRun: Boolean = true,
    val retentionDays: Int = 30,
    val captureNotifications: Boolean = true,
    val maskCodes: Boolean = true,
    val excludedApps: String = "",
    val dailyTokenCap: Int = 0,
    /** Постоянное уведомление с полем «Написать RW AI» в шторке. */
    val quickReply: Boolean = true,
    /** Что делать после задачи: home — на главный экран, app — в RW AI, stay — остаться где есть. */
    val afterTask: String = "origin",
) {
    val languageName: String
        get() = when (language.substringBefore('-')) {
            "es" -> "español"
            "en" -> "English"
            else -> "русский"
        }

    fun excludedPackages(): Set<String> =
        excludedApps.split(',', ';', '\n', ' ').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"

        // Названия моделей взяты из документации DeepSeek (api-docs.deepseek.com) на 2026-11-07. Поле редактируемое.
        const val DEFAULT_MODEL = "deepseek-flash"
        val KNOWN_MODELS = listOf("deepseek-flash", "deepseek-v4-pro")
    }
}

/** Настройки в SharedPreferences + секреты в [SecretStore]. */
class SettingsStore(context: Context, private val secrets: SecretStore) {
    private val prefs = context.applicationContext.getSharedPreferences("rwai_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Settings> get() = _state

    private val _hasKey = MutableStateFlow(secrets.has(SecretStore.KEY_DEEPSEEK))
    val hasApiKey: StateFlow<Boolean> get() = _hasKey

    val current: Settings get() = _state.value

    private fun load(): Settings {
        val d = Settings()
        return Settings(
            baseUrl = Endpoint.normalize(prefs.getString("baseUrl", d.baseUrl) ?: d.baseUrl) ?: d.baseUrl,
            model = prefs.getString("model", d.model)?.takeIf { it.isNotBlank() } ?: d.model,
            thinking = prefs.getBoolean("thinking", d.thinking),
            mode = AutonomyMode.fromName(prefs.getString("mode", d.mode.name)),
            autoSend = prefs.getBoolean("autoSend", d.autoSend),
            language = prefs.getString("language", d.language) ?: d.language,
            speakResults = prefs.getBoolean("speakResults", d.speakResults),
            voiceAutoRun = prefs.getBoolean("voiceAutoRun", d.voiceAutoRun),
            retentionDays = prefs.getInt("retentionDays", d.retentionDays),
            captureNotifications = prefs.getBoolean("captureNotifications", d.captureNotifications),
            maskCodes = prefs.getBoolean("maskCodes", d.maskCodes),
            excludedApps = prefs.getString("excludedApps", d.excludedApps) ?: d.excludedApps,
            dailyTokenCap = prefs.getInt("dailyTokenCap", d.dailyTokenCap),
            quickReply = prefs.getBoolean("quickReply", d.quickReply),
            afterTask = prefs.getString("afterTaskMode", d.afterTask)?.takeIf { it in listOf("origin", "home", "app", "stay") } ?: d.afterTask,
        )
    }

    fun update(transform: (Settings) -> Settings) {
        val s = transform(_state.value)
        prefs.edit()
            .putString("baseUrl", s.baseUrl).putString("model", s.model).putBoolean("thinking", s.thinking)
            .putString("mode", s.mode.name).putBoolean("autoSend", s.autoSend).putString("language", s.language)
            .putBoolean("speakResults", s.speakResults).putBoolean("voiceAutoRun", s.voiceAutoRun)
            .putInt("retentionDays", s.retentionDays).putBoolean("captureNotifications", s.captureNotifications)
            .putBoolean("maskCodes", s.maskCodes).putString("excludedApps", s.excludedApps)
            .putInt("dailyTokenCap", s.dailyTokenCap).putBoolean("quickReply", s.quickReply).putString("afterTaskMode", s.afterTask)
            .apply()
        _state.value = s
    }

    // ---- API-ключ ----
    fun apiKey(): String? = secrets.get(SecretStore.KEY_DEEPSEEK)

    fun saveApiKey(key: String) {
        secrets.put(SecretStore.KEY_DEEPSEEK, key.trim())
        _hasKey.value = true
    }

    fun deleteApiKey() {
        secrets.remove(SecretStore.KEY_DEEPSEEK)
        _hasKey.value = false
    }

    // ---- Расход токенов за сутки ----
    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun tokensUsedToday(): Int = if (prefs.getString("tokensDay", "") == today()) prefs.getInt("tokensUsed", 0) else 0

    fun addTokens(n: Int) {
        val used = tokensUsedToday() + n.coerceAtLeast(0)
        prefs.edit().putString("tokensDay", today()).putInt("tokensUsed", used).apply()
    }
}
