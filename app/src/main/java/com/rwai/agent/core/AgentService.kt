package com.rwai.agent.core

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.rwai.agent.Graph
import com.rwai.agent.MainActivity
import com.rwai.agent.logic.ConfirmationRequest

/**
 * Foreground-служба существует только пока выполняется задача пользователя: постоянное уведомление с кнопкой «Стоп»
 * и защита процесса от выгрузки, пока агент работает в других приложениях.
 */
class AgentService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AgentNotifications.ensureChannels(this)
        val notification = AgentNotifications.running(this)
        try {
            ServiceCompat.startForeground(
                this, AgentNotifications.ID_RUNNING, notification,
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
            )
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    companion object {
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, AgentService::class.java))
            } catch (_: Exception) {
                // Задача продолжится и без службы, пока процесс жив
            }
        }

        fun stop(context: Context) {
            try { context.stopService(Intent(context, AgentService::class.java)) } catch (_: Exception) { }
        }
    }
}

object AgentNotifications {
    const val ID_RUNNING = 1001
    const val ID_CONFIRM = 1002
    const val ID_RESULT = 1003
    const val CH_RUNNING = "rwai_running"
    const val CH_CONFIRM = "rwai_confirm"
    const val CH_RESULT = "rwai_result"

    const val ACTION_APPROVE = "com.rwai.agent.action.APPROVE"
    const val ACTION_DECLINE = "com.rwai.agent.action.DECLINE"
    const val ACTION_STOP = "com.rwai.agent.action.STOP"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH_RUNNING, "Выполнение задачи", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_CONFIRM, "Запросы подтверждения", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_RESULT, "Результаты задач", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun broadcast(context: Context, action: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
        context, code, Intent(context, AgentActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun running(context: Context): Notification = NotificationCompat.Builder(context, CH_RUNNING)
        .setSmallIcon(android.R.drawable.ic_popup_sync)
        .setContentTitle("RW AI выполняет задачу")
        .setContentText("Агент работает по вашей команде. Нажмите «Стоп», чтобы прервать.")
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(openApp(context))
        .addAction(0, "Стоп", broadcast(context, ACTION_STOP, 1))
        .build()

    fun showConfirmation(context: Context, req: ConfirmationRequest) {
        ensureChannels(context)
        val b = NotificationCompat.Builder(context, CH_CONFIRM)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(req.title)
            .setContentText(req.reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(req.details + "\n\n" + req.reason))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openApp(context))
            .addAction(0, "Отмена", broadcast(context, ACTION_DECLINE, 2))
        // Опасные действия (оплата, удаление, настройки безопасности) подтверждаются только внутри приложения.
        // Обычную отправку можно подтвердить из уведомления, но только после разблокировки телефона (Android 12+).
        if (!req.dangerous) {
            b.addAction(
                NotificationCompat.Action.Builder(0, "Подтвердить", broadcast(context, ACTION_APPROVE, 3))
                    .setAuthenticationRequired(true)
                    .build(),
            )
        }
        notifySafely(context, ID_CONFIRM, b.build())
    }

    fun cancelConfirmation(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_CONFIRM)
    }

    fun showResult(context: Context, title: String, text: String) {
        ensureChannels(context)
        val n = NotificationCompat.Builder(context, CH_RESULT)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(1500)))
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        notifySafely(context, ID_RESULT, n)
    }

    fun clearResult(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_RESULT)
    }

    private fun notifySafely(context: Context, id: Int, n: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        try { NotificationManagerCompat.from(context).notify(id, n) } catch (_: SecurityException) { }
    }
}

/** Кнопки уведомлений: подтвердить / отменить / остановить задачу. */
class AgentActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AgentNotifications.ACTION_APPROVE -> Graph.controller.resolveConfirmation(true)
            AgentNotifications.ACTION_DECLINE -> Graph.controller.resolveConfirmation(false)
            AgentNotifications.ACTION_STOP -> Graph.controller.stop()
        }
    }
}
