package com.rwai.agent.audio

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.rwai.agent.logic.AudioDev
import com.rwai.agent.logic.CallRoute
import com.rwai.agent.logic.DeviceKind
import com.rwai.agent.logic.RouteFallback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Звук звонков не на машину. Пока идёт голосовой звонок (сотовый или через мессенджер), следим за подключёнными устройствами и выбираем:
 * наушники, если надеты; иначе динамик телефона (или у уха, как выбрано). Звук машины не выбирается никогда.
 *
 * Честно про ограничения: Android 12+ даёт обычному приложению просить смену устройства связи, но для обычного сотового звонка
 * система вправе проигнорировать просьбу. Поэтому экран настроек показывает, что мы просили и что система сделала на самом деле.
 */
object CallRouter {
    data class Event(val ts: Long, val text: String)

    private const val PREFS = "rwai_callroute"
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var weSet = false
    private var modeListener: Any? = null
    private var deviceCallback: AudioDeviceCallback? = null
    private var lastApplyTs = 0L

    private val _events = MutableStateFlow<List<Event>>(emptyList())

    /** Последние события: что происходило со звуком звонков (для проверки в машине). */
    val events: StateFlow<List<Event>> get() = _events

    fun supported(): Boolean = Build.VERSION.SDK_INT >= 31

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean("enabled", false)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("enabled", on).apply()
        if (on) start(ctx) else stop(ctx)
    }

    fun fallback(ctx: Context): RouteFallback =
        if (prefs(ctx).getString("fallback", "SPEAKER") == "EARPIECE") RouteFallback.EARPIECE else RouteFallback.SPEAKER

    fun setFallback(ctx: Context, f: RouteFallback) { prefs(ctx).edit().putString("fallback", f.name).apply() }

    fun overrides(ctx: Context): Map<String, DeviceKind> {
        val raw = prefs(ctx).getString("overrides", "").orEmpty()
        val out = HashMap<String, DeviceKind>()
        for (line in raw.split('\n')) {
            val i = line.indexOf('=')
            if (i <= 0) continue
            val kind = try { DeviceKind.valueOf(line.substring(i + 1)) } catch (_: IllegalArgumentException) { continue }
            out[line.substring(0, i)] = kind
        }
        return out
    }

    /** [kind] = null — сбросить ручную пометку и вернуться к автоопределению. */
    fun setOverride(ctx: Context, address: String, kind: DeviceKind?) {
        val m = HashMap(overrides(ctx))
        if (kind == null) m.remove(address) else m[address] = kind
        prefs(ctx).edit().putString("overrides", m.entries.joinToString("\n") { it.key + "=" + it.value.name }).apply()
    }

    fun hasBluetoothPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    data class Paired(val name: String, val address: String, val kind: DeviceKind, val manual: Boolean)

    /** Сопряжённые устройства Bluetooth с тем, как приложение их определило (нужно разрешение Bluetooth). */
    fun paired(ctx: Context): List<Paired> {
        if (!hasBluetoothPermission(ctx)) return emptyList()
        return try {
            val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return emptyList()
            val ov = overrides(ctx)
            adapter.bondedDevices.orEmpty().map { d ->
                val name = d.name ?: d.address
                val cls = d.bluetoothClass?.deviceClass
                Paired(name, d.address, CallRoute.kindOf(AudioDev(0, "bt_sco", d.address, name, cls), ov[d.address]), ov.containsKey(d.address))
            }.sortedBy { it.name.lowercase() }
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    private fun log(text: String) {
        _events.value = (listOf(Event(System.currentTimeMillis(), text)) + _events.value).take(12)
    }

    fun stamp(ts: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ts))

    private fun inCall(am: AudioManager) = am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION

    /** Запускается из фоновой службы приложения и при включении настройки. Ничего не делает, пока настройка выключена. */
    fun start(ctx: Context) {
        if (!supported() || started || !enabled(ctx)) return
        val app = ctx.applicationContext
        try {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val listener = AudioManager.OnModeChangedListener { _ -> schedule(app) }
            am.addOnModeChangedListener(app.mainExecutor, listener)
            modeListener = listener
            val cb = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { schedule(app) }
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) { schedule(app) }
            }
            am.registerAudioDeviceCallback(cb, handler)
            deviceCallback = cb
            started = true
            if (inCall(am)) schedule(app)
        } catch (e: Exception) {
            log("не удалось включить слежение: " + e.javaClass.simpleName)
        }
    }

    fun stop(ctx: Context) {
        val app = ctx.applicationContext
        try {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            (modeListener as? AudioManager.OnModeChangedListener)?.let { am.removeOnModeChangedListener(it) }
            deviceCallback?.let { am.unregisterAudioDeviceCallback(it) }
            if (weSet && Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
        } catch (_: Exception) { }
        modeListener = null
        deviceCallback = null
        weSet = false
        started = false
        handler.removeCallbacksAndMessages(null)
    }

    /** Все события за 0,8 с (переподключение машины шлёт их десятками) схлопываются в одно действие. */
    private fun schedule(ctx: Context) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ apply(ctx) }, 800)
    }

    private fun toDev(ctx: Context, info: AudioDeviceInfo): AudioDev {
        val type = when (info.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bt_sco"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "ble_headset"
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired"
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "usb"
            AudioDeviceInfo.TYPE_HEARING_AID -> "hearing_aid"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
            else -> "other"
        }
        val address = try { info.address } catch (_: Exception) { "" }
        var cls: Int? = null
        if ((type == "bt_sco" || type == "ble_headset") && hasBluetoothPermission(ctx)) {
            try {
                val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                cls = adapter?.bondedDevices?.firstOrNull { it.address == address }?.bluetoothClass?.deviceClass
            } catch (_: SecurityException) { }
        }
        return AudioDev(info.id, type, address, info.productName?.toString().orEmpty(), cls)
    }

    private fun apply(ctx: Context) {
        if (!supported() || !enabled(ctx)) return
        val now = System.currentTimeMillis()
        if (!CallRoute.ready(lastApplyTs, now, 300)) return
        lastApplyTs = now
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (!inCall(am)) {
                if (weSet) {
                    am.clearCommunicationDevice()
                    weSet = false
                    log("звонок закончился, мой выбор сброшен")
                }
                return
            }
            val available = am.availableCommunicationDevices
            val choice = CallRoute.choose(available.map { toDev(ctx, it) }, overrides(ctx), fallback(ctx))
            val target = available.firstOrNull { it.id == choice.deviceId }
            if (target == null) {
                log("звонок: " + choice.reason)
                return
            }
            if (am.communicationDevice?.id == target.id) {
                log("звонок: уже на «" + target.productName + "» (" + choice.kind.title + ")")
                return
            }
            val ok = am.setCommunicationDevice(target)
            weSet = weSet || ok
            val actual = am.communicationDevice?.productName ?: "не определено"
            log(
                if (ok) "звонок: попросил " + choice.reason + "; система сейчас: «" + actual + "»"
                else "звонок: система отказалась переключить на «" + target.productName + "»; сейчас: «" + actual + "»",
            )
        } catch (e: SecurityException) {
            log("нет разрешения на работу со звуком или Bluetooth: " + (e.message ?: "").take(80))
        } catch (e: Exception) {
            log("ошибка: " + e.javaClass.simpleName + " " + (e.message ?: "").take(80))
        }
    }
}
