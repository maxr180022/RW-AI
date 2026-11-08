package com.rwai.agent.wake

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.rwai.agent.Graph
import com.rwai.agent.accessibility.RwAccessibilityService
import com.rwai.agent.assistant.AssistantOverlayActivity
import com.rwai.agent.logic.WakeGate
import com.rwai.agent.logic.WakePhrase
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Слушатель «Окей, Руся». Распознавание — офлайн-моделью Vosk прямо на телефоне, звук не записывается и никуда не отправляется.
 * Экономия: модель получает звук только когда есть голос (простой детектор речи), а вне выбранного режима
 * (например, не на зарядке) микрофон и модель полностью освобождены — остаётся только значок в шторке.
 */
class WakeWordService : Service() {
    private var worker: Thread? = null
    private var receiver: BroadcastReceiver? = null
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var stopping = false
    @Volatile private var charging = false
    @Volatile private var screenOn = true
    @Volatile private var pendingLaunch = false
    private var lastStatus = ""

    // замер расхода при выключенном экране
    private var sampleLevel = -1
    private var sampleTs = 0L
    private var sampleClean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Graph.settings.update { it.copy(wakeEnabled = false) }
            stopSelf()
            return START_NOT_STICKY
        }
        if (worker != null) {
            WakeState.poke()
            return START_NOT_STICKY
        }
        ensureChannels(this)
        try {
            val n = statusNotification(this, "Запускаюсь…")
            if (Build.VERSION.SDK_INT >= 29) ServiceCompat.startForeground(this, ID_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(ID_STATUS, n)
        } catch (e: Exception) {
            stopSelf() // Android не разрешил запуск микрофонной службы из фона: включится при следующем открытии приложения
            return START_NOT_STICKY
        }
        registerReceivers()
        stopping = false
        worker = Thread({ loop() }, "rwai-wake").also { it.start() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopping = true
        WakeState.poke()
        receiver?.let { try { unregisterReceiver(it) } catch (_: Exception) { } }
        receiver = null
        super.onDestroy()
    }

    private fun registerReceivers() {
        screenOn = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> { screenOn = true; sampleClean = false }
                    Intent.ACTION_SCREEN_OFF -> { screenOn = false; sampleClean = false }
                    Intent.ACTION_POWER_CONNECTED -> { charging = true; sampleClean = false }
                    Intent.ACTION_POWER_DISCONNECTED -> { charging = false; sampleClean = false }
                    Intent.ACTION_BATTERY_CHANGED -> onBattery(intent)
                }
                WakeState.poke()
            }
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        val sticky = ContextCompat.registerReceiver(this, r, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiver = r
        if (sticky != null) onBattery(sticky)
    }

    private fun onBattery(i: Intent) {
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL ||
            i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        if (level < 0) return
        val pct = level * 100 / scale
        val now = SystemClock.elapsedRealtime()
        val cleanNow = WakeState.holdingMic && !charging && !screenOn
        if (sampleLevel >= 0 && sampleClean && cleanNow && pct <= sampleLevel) {
            WakeStats.addDrain(this, (sampleLevel - pct).toFloat(), (now - sampleTs) / 1000)
        }
        sampleLevel = pct
        sampleTs = now
        sampleClean = cleanNow
    }

    private fun hasMic(): Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun setStatus(text: String) {
        if (text == lastStatus) return
        lastStatus = text
        try { NotificationManagerCompat.from(this).notify(ID_STATUS, statusNotification(this, text)) } catch (_: SecurityException) { }
    }

    private fun createAudio(): AudioRecord {
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val a = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, max(min, 8192),
        )
        if (a.state != AudioRecord.STATE_INITIALIZED) { a.release(); throw IllegalStateException("Микрофон недоступен") }
        a.startRecording()
        return a
    }

    private fun loop() {
        var model: Model? = null
        var rec: Recognizer? = null
        var audio: AudioRecord? = null
        val buf = ShortArray(1600) // 100 мс
        var noise = 200.0
        var hang = 0
        var feeding = false
        var lastTick = SystemClock.elapsedRealtime()
        var listenMs = 0L
        var lastWake = 0L

        fun release() {
            try { audio?.stop() } catch (_: Exception) { }
            try { audio?.release() } catch (_: Exception) { }
            audio = null
            try { rec?.close() } catch (_: Exception) { }
            rec = null
            try { model?.close() } catch (_: Exception) { }
            model = null
            WakeState.holdingMic = false
            feeding = false
            hang = 0
        }

        fun flushStats() {
            if (listenMs >= 1000) { WakeStats.addListen(this, listenMs / 1000); listenMs = 0 }
        }

        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            while (!stopping) {
                val s = Graph.settings.current
                val should = s.wakeEnabled && !WakeState.paused && WakeGate.shouldListen(s.wakeMode, charging, screenOn)
                if (!should) {
                    release()
                    flushStats()
                    if (pendingLaunch) { pendingLaunch = false; launchOverlay() }
                    setStatus(
                        when {
                            WakeState.paused -> "Пауза: ассистент занят"
                            else -> "Пауза: " + WakeGate.pauseReason(s.wakeMode)
                        },
                    )
                    WakeState.awaitChange(10_000)
                    lastTick = SystemClock.elapsedRealtime()
                    continue
                }
                if (!hasMic()) { setStatus("Нет разрешения на микрофон"); WakeState.awaitChange(10_000); continue }
                if (audio == null) {
                    if (!WakeModel.ready(this)) { setStatus("Нет голосовой модели: скачайте её на вкладке «Телефон»"); WakeState.awaitChange(10_000); continue }
                    try {
                        model = Model(WakeModel.dir(this).absolutePath)
                        rec = Recognizer(model, SAMPLE_RATE.toFloat())
                        audio = createAudio()
                        WakeState.holdingMic = true
                        lastTick = SystemClock.elapsedRealtime()
                        setStatus("Слушаю «${s.wakeName.replaceFirstChar { it.uppercase() }}», «Окей, …», «Привет, …»")
                    } catch (e: Throwable) {
                        release()
                        setStatus("Не удалось включить микрофон или модель")
                        WakeState.awaitChange(15_000)
                        continue
                    }
                }
                val a = audio ?: continue
                val n = a.read(buf, 0, buf.size)
                val now = SystemClock.elapsedRealtime()
                listenMs += now - lastTick
                lastTick = now
                if (listenMs >= 30_000) flushStats()
                if (n <= 0) {
                    if (n < 0) { release(); WakeState.awaitChange(2_000) }
                    continue
                }
                var sum = 0.0
                for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                val rms = sqrt(sum / n)
                val active = rms > max(noise * 3.0, 300.0)
                if (active) hang = 12 else if (hang > 0) hang-- else noise = 0.97 * noise + 0.03 * rms
                val r = rec ?: continue
                if (active || hang > 0) {
                    feeding = true
                    val ended = r.acceptWaveForm(buf, n)
                    val text = try {
                        if (ended) JSONObject(r.getResult()).optString("text") else JSONObject(r.getPartialResult()).optString("partial")
                    } catch (_: Exception) { "" }
                    if (text.isNotBlank() && now - lastWake > 3_000 && WakePhrase.matches(text, s.wakeName)) {
                        lastWake = now
                        pendingLaunch = true
                        WakeState.setPaused(true) // освободим микрофон и откроем панель
                        main.postDelayed({ if (!WakeState.overlayVisible) WakeState.setPaused(false) }, 25_000)
                    }
                } else if (feeding) {
                    feeding = false
                    try { r.reset() } catch (_: Exception) { }
                }
            }
        } catch (e: Throwable) {
            // служба остановится; при следующем открытии приложения включится снова
        } finally {
            release()
            flushStats()
            main.post { stopSelf() }
        }
    }

    private fun launchOverlay() {
        val intent = Intent(this, AssistantOverlayActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(AssistantOverlayActivity.EXTRA_FROM_WAKE, true)
        try { (RwAccessibilityService.instance ?: this).startActivity(intent) } catch (_: Exception) { }
        // Android может не пустить окно из фона: тогда показываем уведомление, нажатие которого открывает панель.
        main.postDelayed({
            if (!WakeState.overlayVisible) showAlert(this, intent)
        }, 1500)
    }

    companion object {
        const val ID_STATUS = 1005
        const val ID_ALERT = 1006
        const val CH_STATUS = "rwai_wake"
        const val CH_ALERT = "rwai_wake_alert"
        const val ACTION_STOP = "com.rwai.agent.action.WAKE_STOP"
        private const val SAMPLE_RATE = 16000

        /** Запускает или останавливает слушатель по настройкам. Вызывать, пока приложение на экране. */
        fun sync(context: Context) {
            val s = Graph.settings.current
            val ok = s.wakeEnabled && WakeModel.ready(context) &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            val i = Intent(context, WakeWordService::class.java)
            if (ok) {
                try { ContextCompat.startForegroundService(context, i) } catch (_: Exception) { }
            } else {
                try { context.stopService(i) } catch (_: Exception) { }
            }
            WakeState.poke()
        }

        fun ensureChannels(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CH_STATUS, "Окей, Руся: слушатель", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
            nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Окей, Руся: вызов", NotificationManager.IMPORTANCE_HIGH))
        }

        fun statusNotification(context: Context, text: String): Notification {
            val stop = PendingIntent.getService(
                context, 7, Intent(context, WakeWordService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(context, CH_STATUS)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Руся AI: голосовой вызов")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(0, "Выключить", stop)
                .build()
        }

        fun showAlert(context: Context, overlay: Intent) {
            ensureChannels(context)
            val pi = PendingIntent.getActivity(context, 8, overlay, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(context, CH_ALERT)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Слушаю вас")
                .setContentText("Нажмите, чтобы открыть панель Руся AI")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setAutoCancel(true)
                .setTimeoutAfter(15_000)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .build()
            try { NotificationManagerCompat.from(context).notify(ID_ALERT, n) } catch (_: SecurityException) { }
        }
    }
}
