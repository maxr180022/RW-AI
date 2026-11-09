package com.rwai.agent.local

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.rwai.agent.logic.PiperVoice
import com.rwai.agent.logic.SpeechChunks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Озвучка голосами Piper на телефоне (движок sherpa-onnx). Текст режется на предложения: первое звучит сразу, следующие
 * синтезируются, пока играет предыдущее. Любой сбой молча возвращает озвучку системному голосу (см. [com.rwai.agent.voice.Speaker]).
 */
object PiperTts {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    @Volatile private var track: AudioTrack? = null
    private var engine: OfflineTts? = null
    private var engineId: String? = null
    private val lock = Any()

    private val _info = MutableStateFlow("")

    /** Что сейчас происходит с озвучкой Piper: «играет», «ошибка …». Показывается в настройках после «Проверить». */
    val info: StateFlow<String> get() = _info

    /** Можно ли озвучить этим движком: включено, и голос выбранного языка скачан. */
    fun canSpeak(ctx: Context, lang: String): Boolean {
        if (!PiperStore.enabled(ctx)) return false
        val v = PiperStore.chosen(ctx, lang) ?: return false
        return PiperStore.isInstalled(ctx, v)
    }

    /** Запускает озвучку и сразу возвращается. [onState] получает true, когда зазвучало, и false, когда закончилось или остановлено. */
    fun speak(ctx: Context, text: String, lang: String, speed: Float = 1.0f, onState: (Boolean) -> Unit = {}, onError: (String) -> Unit = {}): Boolean {
        val v = PiperStore.chosen(ctx, lang) ?: return false
        if (!PiperStore.isInstalled(ctx, v)) return false
        stop()
        val app = ctx.applicationContext
        _info.value = "готовлю голос «" + v.title + "»…"
        job = scope.launch {
            try {
                play(app, v, text, speed, onState)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // Не получилось: причина видна в настройках, а озвучку подхватывает системный голос (onError).
                val m = (t.message ?: t.javaClass.simpleName)
                _info.value = "ошибка: " + t.javaClass.simpleName + ": " + m.take(300)
                onError(m)
            } finally {
                onState(false)
            }
        }
        return true
    }

    fun stop() {
        job?.cancel()
        job = null
        try { track?.pause(); track?.flush() } catch (_: Throwable) { }
    }

    /** Выгрузить движок (при удалении голоса или нехватке памяти). */
    fun release(voiceId: String? = null) {
        synchronized(lock) {
            if (voiceId == null || voiceId == engineId) {
                try { engine?.release() } catch (_: Throwable) { }
                engine = null
                engineId = null
            }
        }
    }

    private fun engineFor(ctx: Context, v: PiperVoice): OfflineTts {
      synchronized(lock) {
        engine?.let { if (engineId == v.id) return it }
        try { engine?.release() } catch (_: Throwable) { }
        val d = PiperStore.dir(ctx, v)
        val onnx = d.walkTopDown().firstOrNull { it.isFile && it.name.endsWith(".onnx") } ?: throw IllegalStateException("нет файла голоса")
        val vits = OfflineTtsVitsModelConfig(model = onnx.path, tokens = File(d, "tokens.txt").path, dataDir = File(d, "espeak-ng-data").path)
        val model = OfflineTtsModelConfig(vits = vits, numThreads = 4, debug = false, provider = "cpu")
        val e = OfflineTts(config = OfflineTtsConfig(model = model, maxNumSentences = 1))
        engine = e
        engineId = v.id
        return e
      }
    }

    private suspend fun play(ctx: Context, v: PiperVoice, text: String, speed: Float, onState: (Boolean) -> Unit) {
        val chunks = SpeechChunks.split(text)
        if (chunks.isEmpty()) return
        val t0 = System.currentTimeMillis()
        val tts = engineFor(ctx, v)
        val rate = tts.sampleRate()
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val at = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(minBuf, rate * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = at
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        var peak = 0f
        var route = ""
        val queue = Channel<FloatArray>(2)
        val producer = scope.launch {
            try {
                for (c in chunks) {
                    if (!isActive) break
                    queue.send(tts.generate(c, 0, speed).samples)
                }
            } finally {
                queue.close()
            }
        }
        var frames = 0L
        try {
            at.play()
            onState(true)
            var first = true
            for (samples in queue) {
                if (first) {
                    first = false
                    _info.value = "играет «" + v.title + "»: голос загружен и первая фраза готова за " + String.format(java.util.Locale.forLanguageTag("ru"), "%.1f", (System.currentTimeMillis() - t0) / 1000.0) + " с"
                }
                for (x in samples) { val a = if (x < 0f) -x else x; if (a > peak) peak = a }
                var off = 0
                while (off < samples.size && currentCoroutineContext().isActive) {
                    val n = at.write(samples, off, samples.size - off, AudioTrack.WRITE_BLOCKING)
                    if (n < 0) break
                    off += n
                }
                frames += samples.size
                if (route.isEmpty()) route = try { at.routedDevice?.productName?.toString() ?: "?" } catch (_: Throwable) { "?" }
            }
            at.stop()
            while (currentCoroutineContext().isActive && at.playbackHeadPosition < frames) delay(60)
            val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            _info.value = "Piper озвучил «" + v.title + "» до конца. Пик сигнала " + String.format(java.util.Locale.forLanguageTag("ru"), "%.2f", peak) +
                (if (peak < 0.02f) " (почти тишина: голос выдал пустой звук)" else "") +
                ". Громкость мультимедиа " + vol + " из " + max + (if (vol == 0) " (НА НУЛЕ, поэтому не слышно)" else "") +
                ". Звук идёт на: «" + route + "»"
        } finally {
            producer.cancel()
            try { at.release() } catch (_: Throwable) { }
            if (track === at) track = null
        }
    }
}
