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
import com.rwai.agent.logic.PendingReplies
import com.rwai.agent.digest.DigestManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.rwai.agent.Graph
import com.rwai.agent.logic.SensitiveMasker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.launch

/**
 * Сохраняет доступные системные уведомления (приложение-источник, время, имя отправителя, текст) в локальную базу.
 * Работает только после того, как пользователь сам включил «Доступ к уведомлениям» для Руся AI.
 * Скрытые приложением данные не извлекаются: если уведомление помечено как секретное, сохраняется только факт его появления.
 */
class RwNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var screenReceiver: BroadcastReceiver? = null

    /** Когда по разговору последний раз пришло уведомление (ключ разговора → время): чтобы отличить закрытие из-за ответа от замены новым сообщением. */
    private val lastPost = ConcurrentHashMap<String, Long>()

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
        try { com.rwai.agent.audio.CallRouter.start(applicationContext) } catch (_: Throwable) { }
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
        var lastOutgoing = 0L
        if (style != null && style.messages.isNotEmpty()) {
            val selfKey = style.user.key
            for (m in style.messages) {
                val t = m.text?.toString()?.trim().orEmpty()
                if (t.isEmpty()) continue
                val p = m.person
                val mine = p == null || (selfKey != null && p.key == selfKey)
                if (mine) lastOutgoing = maxOf(lastOutgoing, m.timestamp) else items += Triple(m.timestamp, p?.name?.toString(), t)
            }
        } else {
            val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
            if (body.isNotEmpty()) items += Triple(n.postTime, extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(), body)
        }
        val title = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

        // Исходящие: в разговоре есть сообщение самого владельца, значит, он уже общался. Всё, что пришло раньше него, закрыто.
        val convKeys = HashSet<String>()
        title?.let { convKeys += PendingReplies.key(it) }
        items.forEach { (_, sender, _) -> sender?.let { convKeys += PendingReplies.key(it) } }
        convKeys.remove("")
        if (!secret && !isGroup && MessageTriage.isMessaging(n.packageName)) {
            val nowTs = System.currentTimeMillis()
            convKeys.forEach { lastPost[it] = nowTs }
            if (lastOutgoing > 0) convKeys.forEach { com.rwai.agent.digest.RepliedStore.mark(it, lastOutgoing) }
        }

        // Поле «Ответить» из уведомления личного разговора: по нему можно ответить, не открывая приложение (см. ReplyHandles).
        if (!secret && !isGroup && MessageTriage.isMessaging(n.packageName)) {
            val reply = notif.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
            if (reply != null) {
                val keys = HashSet<String>()
                title?.let { keys += PendingReplies.key(it) }
                items.forEach { (_, sender, _) -> sender?.let { keys += PendingReplies.key(it) } }
                keys.remove("")
                ReplyHandles.put(ReplyHandle(n.packageName, appLabel, keys, reply, n.postTime))
            }
        }

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
                Graph.db.changes.value = System.nanoTime() // строка «Не отвечено» пересчитывается
            } catch (_: Exception) {
                // Запись уведомления не должна ронять службу
            }
        }
    }

    /**
     * Уведомление личного разговора убрали, потому что владелец нажал его (открыл чат) или мессенджер сам закрыл его (чат прочитан или ответ отправлен в самом приложении).
     * Значит, он уже общался: разговор помечается отвеченным на этот момент. Смахнул вручную (REASON_CANCEL) не считается. Закрытие из-за замены новым сообщением отсекается:
     * если за 2,5 секунды по разговору пришло новое уведомление, отметка не ставится.
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        super.onNotificationRemoved(sbn, rankingMap, reason)
        try {
            val n = sbn ?: return
            if (n.packageName == packageName || !MessageTriage.isMessaging(n.packageName)) return
            if (reason != REASON_CLICK && reason != REASON_APP_CANCEL) return
            val notif = n.notification ?: return
            if (notif.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
            val extras = notif.extras
            val style = try { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notif) } catch (_: Exception) { null }
            val isGroup = (style?.isGroupConversation == true) || (Build.VERSION.SDK_INT >= 28 && extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false))
            if (isGroup) return
            val keys = HashSet<String>()
            (extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) ?: extras.getCharSequence(Notification.EXTRA_TITLE))?.toString()?.let { keys += PendingReplies.key(it) }
            style?.messages?.forEach { m -> m.person?.name?.toString()?.let { keys += PendingReplies.key(it) } }
            keys.remove("")
            if (keys.isEmpty()) return
            val removedAt = System.currentTimeMillis()
            scope.launch {
                if (reason == REASON_APP_CANCEL) delay(2_500)
                val still = keys.filter { (lastPost[it] ?: 0L) < removedAt }
                still.forEach { com.rwai.agent.digest.RepliedStore.mark(it, removedAt) }
                if (still.isNotEmpty()) Graph.db.changes.value = System.nanoTime()
            }
        } catch (_: Exception) { }
    }

    private fun labelOf(pkg: String): String = try {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }
}
