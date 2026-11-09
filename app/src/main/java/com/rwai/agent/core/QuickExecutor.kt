package com.rwai.agent.core

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.Settings
import com.rwai.agent.Graph
import com.rwai.agent.executor.AndroidToolExecutor
import com.rwai.agent.logic.BankGuard
import com.rwai.agent.logic.QuickAction
import com.rwai.agent.logic.QuickActions
import com.rwai.agent.logic.ToolCall
import com.rwai.agent.logic.ToolStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar

/** [ok] — действие выполнено и проверено; иначе [message] объясняет, почему, и команда уходит агенту. */
/** [final] — при неудаче агенту команду НЕ отдавать: он с ней всё равно не справится (фонарик, громкость, будильник), только сожжёт токены. */
data class QuickResult(val ok: Boolean, val message: String, val final: Boolean = false)

/**
 * Выполнение быстрых действий прямыми системными вызовами. Где результат можно проверить (фонарик, громкость, «Не беспокоить», будильник),
 * он проверяется; не вышло — честно сообщаем и отдаём команду агенту.
 */
object QuickExecutor {
    suspend fun run(ctx: Context, a: QuickAction): QuickResult {
        val r = try {
            when (a) {
                is QuickAction.Flashlight -> flashlight(ctx, a.on)
                is QuickAction.MediaVolume -> volume(ctx, a)
                is QuickAction.Dnd -> dnd(ctx, a.on)
                is QuickAction.Alarm -> alarm(ctx, a)
                is QuickAction.Timer -> timer(ctx, a)
                is QuickAction.OpenApp -> viaTool(ctx, ToolCall("launch_app", mapOf("app" to a.name)))
                QuickAction.Battery -> viaTool(ctx, ToolCall("phone_status"))
                is QuickAction.Media -> viaTool(ctx, ToolCall("media_control", mapOf("action" to a.kind)))
                QuickAction.TimeNow -> timeNow()
                is QuickAction.Brightness -> brightness(ctx, a)
                is QuickAction.Ringer -> ringer(ctx, a.mode)
                is QuickAction.PlayMusic -> viaTool(ctx, ToolCall("play_music", mapOf("query" to a.query)))
                is QuickAction.Weather -> viaTool(ctx, ToolCall("weather", if (a.city != null) mapOf("city" to a.city) else emptyMap()))
                QuickAction.CalendarToday -> viaTool(ctx, ToolCall("calendar_events", mapOf("days" to 1L)))
            }
        } catch (e: SecurityException) {
            QuickResult(false, "Системе не хватило разрешения для действия «" + QuickActions.label(a) + "»: " + (e.message ?: "").take(100))
        } catch (e: Exception) {
            QuickResult(false, "Не получилось выполнить «" + QuickActions.label(a) + "»: " + e.javaClass.simpleName + " " + (e.message ?: "").take(100))
        }
        // Агент умеет только искать приложение по другому названию; для остального он по экранам не поможет.
        return if (!r.ok && a !is QuickAction.OpenApp) r.copy(final = true) else r
    }

    private suspend fun viaTool(ctx: Context, call: ToolCall): QuickResult {
        val ex = AndroidToolExecutor(ctx, Graph.db, Graph.settings, Graph.speaker) { }
        val r = ex.execute(call)
        val text = r.message.trim().replace(Regex(" \\([a-zA-Z0-9_.]+\\)"), "")
        return when {
            r.status == ToolStatus.OK -> QuickResult(true, text.take(400))
            text == BankGuard.REFUSAL -> QuickResult(true, text) // отказ по банкам окончательный, агенту его не отдаём
            else -> QuickResult(false, text.take(200))
        }
    }

    // ---------------------------------------------------------------- фонарик

    private suspend fun flashlight(ctx: Context, on: Boolean): QuickResult {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return QuickResult(false, "На телефоне не нашёл вспышку для фонарика.")
        val reached = CompletableDeferred<Boolean>()
        val cb = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { if (cameraId == id && enabled == on) reached.complete(true) }
        }
        cm.registerTorchCallback(cb, Handler(Looper.getMainLooper()))
        return try {
            cm.setTorchMode(id, on)
            val ok = withTimeoutOrNull(2_000) { reached.await() } ?: false
            if (ok) QuickResult(true, if (on) "Фонарик включён." else "Фонарик выключен.")
            else QuickResult(false, "Система не подтвердила, что фонарик " + (if (on) "включился" else "выключился") + ".")
        } finally {
            cm.unregisterTorchCallback(cb)
        }
    }

    // ---------------------------------------------------------------- громкость

    private fun volume(ctx: Context, a: QuickAction.MediaVolume): QuickResult {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (a.percent != null) {
            val want = Math.round(a.percent / 100f * max).coerceIn(0, max)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, want, AudioManager.FLAG_SHOW_UI)
            val now = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            return if (now == want) QuickResult(true, "Громкость музыки: $now из $max.") else QuickResult(false, "Громкость осталась $now из $max: система не дала поменять.")
        }
        val dir = if (a.steps > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        repeat(Math.abs(a.steps)) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI) }
        return QuickResult(true, "Громкость музыки: " + am.getStreamVolume(AudioManager.STREAM_MUSIC) + " из $max.")
    }

    // ---------------------------------------------------------------- время, яркость, режим звука

    private fun timeNow(): QuickResult {
        val now = java.time.ZonedDateTime.now()
        val date = now.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", java.util.Locale.forLanguageTag("ru")))
        return QuickResult(true, "Сейчас " + "%02d:%02d".format(now.hour, now.minute) + ", " + date + ".")
    }

    private fun brightness(ctx: Context, a: QuickAction.Brightness): QuickResult {
        if (!Settings.System.canWrite(ctx)) {
            return QuickResult(false, "Нет доступа к изменению яркости. Дайте его: Ещё → Настройки → Параметры → Быстрые действия → «Дать доступ к яркости».")
        }
        val cr = ctx.contentResolver
        val cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
        val want = (if (a.percent != null) Math.round(a.percent * 2.55f) else cur + a.steps * 25).coerceIn(5, 255)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, want)
        val now = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, want)
        return if (Math.abs(now - want) <= 1) QuickResult(true, "Яркость: " + Math.round(now / 2.55f) + " %.") else QuickResult(false, "Яркость не изменилась: система не дала поменять.")
    }

    private fun ringer(ctx: Context, mode: String): QuickResult {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val want = when (mode) { "silent" -> AudioManager.RINGER_MODE_SILENT; "vibrate" -> AudioManager.RINGER_MODE_VIBRATE; else -> AudioManager.RINGER_MODE_NORMAL }
        if (want == AudioManager.RINGER_MODE_SILENT) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!nm.isNotificationPolicyAccessGranted) return QuickResult(false, "Для беззвучного режима нужен доступ к «Не беспокоить»: Ещё → Настройки → Параметры → Быстрые действия.")
        }
        am.ringerMode = want
        return if (am.ringerMode == want) QuickResult(true, when (mode) { "silent" -> "Беззвучный режим включён."; "vibrate" -> "Режим вибрации включён."; else -> "Звук включён." })
        else QuickResult(false, "Режим звука не изменился: система не дала поменять.")
    }

    // ---------------------------------------------------------------- «Не беспокоить»

    private suspend fun dnd(ctx: Context, on: Boolean): QuickResult {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            return QuickResult(false, "Нет доступа к режиму «Не беспокоить». Дайте его: Ещё → Настройки → Параметры → Быстрые действия. Пока сделаю через агента.")
        }
        val want = if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL
        nm.setInterruptionFilter(want)
        delay(400)
        val now = nm.currentInterruptionFilter
        return if (now == want) QuickResult(true, if (on) "Режим «Не беспокоить» включён." else "Режим «Не беспокоить» выключен.")
        else QuickResult(false, "Режим «Не беспокоить» не изменился: его, видимо, держит другое приложение (например, «Режимы и сценарии»).")
    }

    // ---------------------------------------------------------------- будильник и таймер

    /**
     * Будильник через системный запрос «Часам». Проверка: Android показывает приложению только ближайший будильник. Если новый становится ближайшим, он должен появиться
     * и это подтверждается. Если у вас есть будильник раньше нового, по системе подтвердить нельзя: так и пишем, без тревоги.
     */
    private suspend fun alarm(ctx: Context, a: QuickAction.Alarm): QuickResult {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val before = am.nextAlarmClock?.triggerTime
        val expected = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, a.hour); set(Calendar.MINUTE, a.minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        ctx.startActivity(
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, a.hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, a.minute)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val label = "%02d:%02d".format(a.hour, a.minute)
        var after: Long? = null
        repeat(3) {
            delay(900)
            after = am.nextAlarmClock?.triggerTime
            if (after != null && Math.abs(after!! - expected) < 90_000) return QuickResult(true, "Будильник на $label поставлен (подтверждено системой).")
        }
        val earlierExists = before != null && before < expected - 90_000
        return if (earlierExists) {
            val other = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(before!!))
            QuickResult(true, "Будильник на $label поставлен. Подтвердить по системе нельзя: она показывает только ближайший будильник (у вас он на $other).")
        } else {
            QuickResult(true, "Будильник на $label отправлен в «Часы», но система его не показала как ближайший. Проверьте список будильников.")
        }
    }

    private fun timer(ctx: Context, a: QuickAction.Timer): QuickResult {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, a.seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        return QuickResult(true, "Таймер на " + QuickActions.timerText(a.seconds) + " запущен.")
    }
}
