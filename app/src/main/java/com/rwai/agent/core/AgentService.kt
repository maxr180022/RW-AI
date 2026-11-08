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
import androidx.core.app.RemoteInput
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.rwai.agent.Graph
import com.rwai.agent.MainActivity
import com.rwai.agent.logic.ConfirmationRequest
import com.rwai.agent.logic.NotifyKind
import com.rwai.agent.logic.NotifyPolicy

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
    const val ID_QUICK = 1004
    const val CH_RUNNING = "rwai_running"
    const val CH_CONFIRM = "rwai_confirm"
    const val CH_RESULT = "rwai_result"
    const val CH_QUICK = "rwai_quick"
    const val KEY_QUICK_TEXT = "rwai_quick_text"

    const val ACTION_APPROVE = "com.rwai.agent.action.APPROVE"
    const val ACTION_DECLINE = "com.rwai.agent.action.DECLINE"
    const val ACTION_STOP = "com.rwai.agent.action.STOP"
    const val ACTION_QUICK = "com.rwai.agent.action.QUICK"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH_RUNNING, "Выполнение задачи", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_CONFIRM, "Запросы подтверждения", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_RESULT, "Результаты задач", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(
            NotificationChannel(CH_QUICK, "Быстрая команда в шторке", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
        )
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
        .setContentTitle("Руся AI выполняет задачу")
        .setContentText("Агент работает по вашей команде. Нажмите «Стоп», чтобы прервать.")
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(openApp(context))
        .addAction(0, "Стоп", broadcast(context, ACTION_STOP, 1))
        .build()

    fun showConfirmation(context: Context, req: ConfirmationRequest) {
        // Владелец внутри приложения: карточка подтверждения уже в чате, уведомление не нужно (при сворачивании оно придёт, см. RwApp).
        if (!NotifyPolicy.shouldShow(NotifyKind.CONFIRMATION, AppVisibility.inForeground)) return
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
        // Владелец внутри приложения: ответ виден в чате, уведомление не нужно.
        if (!NotifyPolicy.shouldShow(NotifyKind.RESULT, AppVisibility.inForeground)) return
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


    /**
     * Постоянное уведомление с полем ввода: команду можно написать прямо из шторки, не открывая приложение.
     * Действие требует разблокированного телефона (Android 12+), чтобы агентом нельзя было управлять с экрана блокировки.
     */
    fun showQuick(context: Context, status: String? = null) {
        ensureChannels(context)
        val remoteInput = RemoteInput.Builder(KEY_QUICK_TEXT).setLabel("Команда для Руси AI…").build()
        val replyIntent = PendingIntent.getBroadcast(
            context, 4, Intent(context, AgentActionReceiver::class.java).setAction(ACTION_QUICK),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val action = NotificationCompat.Action.Builder(0, "Написать Руси AI", replyIntent)
            .addRemoteInput(remoteInput)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .setAuthenticationRequired(true)
            .build()
        val n = NotificationCompat.Builder(context, CH_QUICK)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("Руся AI")
            .setContentText(status ?: "Нажмите «Написать Руси AI» и введите команду")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openApp(context))
            .addAction(action)
            .build()
        notifySafely(context, ID_QUICK, n)
    }

    fun cancelQuick(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_QUICK)
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
            AgentNotifications.ACTION_QUICK -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(AgentNotifications.KEY_QUICK_TEXT)?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    Graph.controller.submit(text, speak = true)
                    AgentNotifications.showQuick(context, "Принято: " + text.take(60))
                } else {
                    AgentNotifications.showQuick(context)
                }
            }
        }
    }
}
