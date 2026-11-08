package com.rwai.agent.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/** Push-to-talk распознавание речи. Все вызовы — из главного потока. Требует разрешение RECORD_AUDIO. */
class VoiceInput(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * @param onResult текст и уверенность (0..1, либо null, если система её не сообщила)
     */
    fun start(
        languageTag: String,
        onPartial: (String) -> Unit,
        onResult: (String, Float?) -> Unit,
        onFail: (String) -> Unit,
        onListening: () -> Unit = {},
        onLevel: (Float) -> Unit = {},
    ) {
        stop()
        if (!isAvailable()) {
            onFail("На телефоне нет службы распознавания речи (установите/включите «Google» или голосовой ввод Samsung).")
            return
        }
        val r = createRecognizer()
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = onListening()
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) = onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() = onLevel(0f)
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
            }

            override fun onResults(results: Bundle?) {
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = texts?.firstOrNull()?.trim().orEmpty()
                val conf = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull()
                if (text.isEmpty()) onFail("Речь не распознана. Попробуйте ещё раз.") else onResult(text, conf)
            }

            override fun onError(error: Int) {
                onFail(describe(error))
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        r.startListening(intent)
    }

    /** Выбирает настоящую службу распознавания (Google, затем любая другая), но не заглушку самого RW AI. */
    private fun createRecognizer(): SpeechRecognizer {
        val services = context.packageManager.queryIntentServices(Intent(android.speech.RecognitionService.SERVICE_INTERFACE), 0)
            .map { it.serviceInfo }
            .filter { it.packageName != context.packageName }
        val chosen = services.firstOrNull { it.packageName.contains("google") } ?: services.firstOrNull()
        return if (chosen != null) {
            SpeechRecognizer.createSpeechRecognizer(context, android.content.ComponentName(chosen.packageName, chosen.name))
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
    }

    fun stop() {
        recognizer?.let {
            try { it.cancel(); it.destroy() } catch (_: Exception) { }
        }
        recognizer = null
    }

    private fun describe(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "Ошибка записи звука."
        SpeechRecognizer.ERROR_CLIENT -> "Распознавание прервано."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет разрешения на микрофон."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Нет сети для распознавания речи."
        SpeechRecognizer.ERROR_NO_MATCH -> "Речь не распознана. Попробуйте ещё раз."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Распознаватель занят, повторите."
        SpeechRecognizer.ERROR_SERVER -> "Ошибка сервера распознавания."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Вы ничего не сказали."
        else -> "Ошибка распознавания ($code)."
    }
}

/** Озвучивание ответов через системный TextToSpeech. */
class Speaker(context: Context) {
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false

    private val _speaking = MutableStateFlow(false)

    /** true, пока идёт озвучивание (для анимации логотипа). */
    val speaking: StateFlow<Boolean> get() = _speaking

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { _speaking.value = true }
                    override fun onDone(utteranceId: String?) { _speaking.value = false }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { _speaking.value = false }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) { _speaking.value = false }
                })
            }
        }
    }

    fun speak(text: String, languageTag: String): Boolean {
        val t = tts ?: return false
        if (!ready) return false
        val locale = Locale.forLanguageTag(languageTag)
        val avail = t.isLanguageAvailable(locale)
        if (avail == TextToSpeech.LANG_MISSING_DATA || avail == TextToSpeech.LANG_NOT_SUPPORTED) {
            // Запасной вариант: язык без региона (es вместо es-UY)
            val base = Locale(locale.language)
            val a2 = t.isLanguageAvailable(base)
            if (a2 == TextToSpeech.LANG_MISSING_DATA || a2 == TextToSpeech.LANG_NOT_SUPPORTED) return false
            t.setLanguage(base)
        } else {
            t.setLanguage(locale)
        }
        preferMaleVoice(t)
        return t.speak(text.take(3000), TextToSpeech.QUEUE_FLUSH, null, "rwai-" + System.nanoTime()) == TextToSpeech.SUCCESS
    }

    /** Руся — мужского рода: по возможности выбираем мужской голос (определяется по имени голоса; если такого нет — остаётся системный). */
    private fun preferMaleVoice(t: TextToSpeech) {
        try {
            val lang = t.voice?.locale?.language ?: return
            val male = t.voices.orEmpty().filter { v ->
                val n = v.name.lowercase()
                v.locale.language == lang && !v.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) &&
                    (n.contains("-rud-") || n.contains("smtm") || (n.contains("male") && !n.contains("female")))
            }
            male.firstOrNull { !it.isNetworkConnectionRequired }?.let { t.voice = it } ?: male.firstOrNull()?.let { t.voice = it }
        } catch (_: Exception) { }
    }

    fun stop() { tts?.stop() }

    fun shutdown() {
        tts?.stop(); tts?.shutdown(); tts = null; ready = false
    }
}
