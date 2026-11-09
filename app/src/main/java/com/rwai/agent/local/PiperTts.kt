package com.rwai.agent.local

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
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

    /** Можно ли озвучить этим движком: включено, и голос выбранного языка скачан. */
    fun canSpeak(ctx: Context, lang: String): Boolean {
        if (!PiperStore.enabled(ctx)) return false
        val v = PiperStore.chosen(ctx, lang) ?: return false
        return PiperStore.isInstalled(ctx, v)
    }

    /** Запускает озвучку и сразу возвращается. [onState] получает true, когда зазвучало, и false, когда закончилось или остановлено. */
    fun speak(ctx: Context, text: String, lang: String, speed: Float = 1.0f, onState: (Boolean) -> Unit = {}): Boolean {
        val v = PiperStore.chosen(ctx, lang) ?: return false
        if (!PiperStore.isInstalled(ctx, v)) return false
        stop()
        val app = ctx.applicationContext
        job = scope.launch {
            try {
                play(app, v, text, speed, onState)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // не получилось: тишина лучше падения; следующая фраза попробует снова
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
            for (samples in queue) {
                var off = 0
                while (off < samples.size && currentCoroutineContext().isActive) {
                    val n = at.write(samples, off, samples.size - off, AudioTrack.WRITE_BLOCKING)
                    if (n < 0) break
                    off += n
                }
                frames += samples.size
            }
            at.stop()
            while (currentCoroutineContext().isActive && at.playbackHeadPosition < frames) delay(60)
        } finally {
            producer.cancel()
            try { at.release() } catch (_: Throwable) { }
            if (track === at) track = null
        }
    }
}
