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
        val text = r.message.trim()
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

    private suspend fun alarm(ctx: Context, a: QuickAction.Alarm): QuickResult {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, a.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, a.minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        delay(900)
        val label = "%02d:%02d".format(a.hour, a.minute)
        val expected = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, a.hour); set(Calendar.MINUTE, a.minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        val next = (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).nextAlarmClock?.triggerTime
        return if (next != null && Math.abs(next - expected) < 90_000) QuickResult(true, "Будильник на $label поставлен (проверено по системе).")
        else QuickResult(true, "Будильник на $label отправлен в «Часы». Проверить по системе не вышло (ближайший у вас другой): загляните в список будильников.")
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
