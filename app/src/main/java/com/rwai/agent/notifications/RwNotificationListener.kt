package com.rwai.agent.notifications

import com.rwai.agent.logic.BankGuard
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import com.rwai.agent.logic.MessageTriage
import com.rwai.agent.digest.DigestManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.rwai.agent.Graph
import com.rwai.agent.logic.SensitiveMasker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Сохраняет доступные системные уведомления (приложение-источник, время, имя отправителя, текст) в локальную базу.
 * Работает только после того, как пользователь сам включил «Доступ к уведомлениям» для Руся AI.
 * Скрытые приложением данные не извлекаются: если уведомление помечено как секретное, сохраняется только факт его появления.
 */
class RwNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var screenReceiver: BroadcastReceiver? = null

    /** Экран выключен/разблокирован: события, а не опрос, — на расход заряда не влияет. */
    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> DigestManager.onScreenOff()
                    Intent.ACTION_USER_PRESENT -> DigestManager.onUserPresent(context.applicationContext)
                }
            }
        }
        val f = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT) }
        try { registerReceiver(r, f); screenReceiver = r } catch (_: Exception) { }
    }

    override fun onListenerDisconnected() {
        screenReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) { } }
        screenReceiver = null
        super.onListenerDisconnected()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        registerScreenReceiver()
        scope.launch {
            val days = Graph.settings.current.retentionDays
            if (days > 0) Graph.db.purgeOlderThan(System.currentTimeMillis() - days * 86_400_000L)
        }
    }

    override fun onDestroy() {
        screenReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) { } }
        screenReceiver = null
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        val settings = Graph.settings.current
        if (!settings.captureNotifications) return
        if (n.packageName == packageName) return
        if (n.packageName in settings.excludedPackages()) return
        if (BankGuard.isBankingPackage(n.packageName)) return // банковские уведомления не сохраняются никогда
        val notif = n.notification ?: return
        val flags = notif.flags
        if (flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (notif.category == Notification.CATEGORY_PROGRESS || notif.category == Notification.CATEGORY_TRANSPORT ||
            notif.category == Notification.CATEGORY_SERVICE || notif.category == Notification.CATEGORY_SYSTEM
        ) return

        val appLabel = labelOf(n.packageName)
        val secret = notif.visibility == Notification.VISIBILITY_SECRET
        val mask = settings.maskCodes
        val extras = notif.extras

        val items = ArrayList<Triple<Long, String?, String>>() // время, отправитель, текст
        val style = try { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notif) } catch (e: Exception) { null }
        val isGroup = (style?.isGroupConversation == true) ||
            (Build.VERSION.SDK_INT >= 28 && extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false))
        if (style != null && style.messages.isNotEmpty()) {
            for (m in style.messages) {
                val t = m.text?.toString()?.trim().orEmpty()
                if (t.isNotEmpty()) items += Triple(m.timestamp, m.person?.name?.toString(), t)
            }
        } else {
            val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
            if (body.isNotEmpty()) items += Triple(n.postTime, extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(), body)
        }
        val title = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

        scope.launch {
            try {
                if (secret || items.isEmpty()) {
                    Graph.db.insertNotification(
                        n.postTime, n.packageName, appLabel, null, title?.let { if (secret) null else it }, null, hidden = true,
                        dedupe = "${n.packageName}|hidden|${n.key}|${n.postTime}",
                    )
                } else {
                    for ((ts, sender, text) in items) {
                        val safeText = if (mask) SensitiveMasker.mask(text) else text
                        Graph.db.insertNotification(
                            ts, n.packageName, appLabel, sender, title, safeText, hidden = false,
                            dedupe = "${n.packageName}|${sender.orEmpty()}|$ts|${safeText.hashCode()}",
                            group = isGroup,
                        )
                    }
                    if (settings.autoTranslate && MessageTriage.isMessaging(n.packageName)) DigestManager.queueTranslation()
                }
            } catch (_: Exception) {
                // Запись уведомления не должна ронять службу
            }
        }
    }

    private fun labelOf(pkg: String): String = try {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }
}
