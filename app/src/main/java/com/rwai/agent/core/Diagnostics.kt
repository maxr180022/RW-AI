package com.rwai.agent.core

import android.content.Context
import android.os.Build
import com.rwai.agent.Graph
import com.rwai.agent.audio.CallRouter
import com.rwai.agent.local.LocalModelStore
import com.rwai.agent.local.PiperStore
import com.rwai.agent.local.PiperTts
import com.rwai.agent.logic.ProfileTemplates
import com.rwai.agent.logic.SecretRedactor
import com.rwai.agent.logic.UsageView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Один текстовый файл со всем, что нужно, чтобы разобраться, что пошло не так: версия и телефон, настройки (без ключей), шаги агента,
 * ошибки, журнал быстрых действий, состояние голоса и звука звонков, хвост открытого диалога. Ключи и значения профиля вырезаются.
 * Файл остаётся у владельца: он сам решает, кому его отправить.
 */
object Diagnostics {
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private fun t(ts: Long) = fmt.format(Date(ts))

    fun build(ctx: Context): String {
        val sb = StringBuilder()
        fun h(title: String) { sb.append("\n=== ").append(title).append(" ===\n") }

        h("О приложении и телефоне")
        val pi = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0) } catch (_: Exception) { null }
        sb.append("Руся AI ").append(pi?.versionName ?: "?").append(" (сборка ").append(@Suppress("DEPRECATION") (pi?.versionCode ?: 0)).append(")\n")
        sb.append("Телефон: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(", Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("Выгружено: ").append(t(System.currentTimeMillis())).append(", язык телефона ").append(Locale.getDefault().toLanguageTag()).append('\n')

        h("Настройки (без ключей)")
        try {
            val s = Graph.settings.current
            sb.append("Режим: ").append(s.mode).append(", модель: ").append(s.model).append(", автовыбор: ").append(s.autoModel).append(", рассуждение: ").append(s.thinking).append('\n')
            sb.append("Язык: ").append(s.language).append(", озвучивать итог: ").append(s.speakResults).append(", после задачи: ").append(s.afterTask).append('\n')
            sb.append("Сводка: ").append(s.digestEnabled).append(", автоперевод: ").append(s.autoTranslate).append(", сохранять уведомления: ").append(s.captureNotifications).append('\n')
            sb.append("Дневной лимит токенов: ").append(s.dailyTokenCap).append('\n')
        } catch (_: Exception) { sb.append("не удалось прочитать\n") }
        sb.append("Быстрые действия: ").append(QuickRouter.enabled(ctx)).append(", локальный разбор: ").append(QuickRouter.localEnabled(ctx)).append('\n')
        sb.append("Piper: включён=").append(PiperStore.enabled(ctx)).append(", полная озвучка=").append(com.rwai.agent.voice.SpeechPrefs.full(ctx)).append('\n')
        sb.append("Локальная модель: ").append(LocalModelStore.status.value.state).append('\n')
        sb.append("Звонки не на машину: ").append(CallRouter.enabled(ctx)).append(", запасной вывод: ").append(CallRouter.fallback(ctx)).append('\n')

        h("Доступы")
        sb.append("Уведомления: ").append(com.rwai.agent.ui.isNotificationAccessEnabled(ctx)).append(", специальные возможности: ").append(com.rwai.agent.ui.isAccessibilityEnabled(ctx))
        sb.append(", Bluetooth: ").append(CallRouter.hasBluetoothPermission(ctx))
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            sb.append(", «Не беспокоить»: ").append(nm.isNotificationPolicyAccessGranted)
        } catch (_: Exception) { }
        sb.append('\n')

        h("Расход токенов сегодня")
        sb.append("Всего: ").append(UsageView.group(Graph.settings.tokensUsedToday())).append('\n')
        UsageView.lines(Graph.settings.usageToday()).forEach { sb.append(it).append('\n') }

        h("Piper (голоса)")
        sb.append("Состояние: ").append(PiperTts.info.value.ifBlank { "проверка не запускалась" }).append('\n')
        PiperStore.status.value.forEach { (id, st) -> sb.append(id).append(": ").append(st.state).append(st.message?.let { " — $it" } ?: "").append('\n') }

        h("Звук звонков (последние события)")
        CallRouter.events.value.ifEmpty { null }?.forEach { sb.append(t(it.ts)).append("  ").append(it.text).append('\n') } ?: sb.append("пусто\n")

        h("Последний сбой приложения")
        sb.append(CrashLog.last(ctx) ?: "сбоев не записано").append('\n')

        h("Журнал решений быстрых действий (последние 80)")
        try {
            val rows = Graph.db.recentRouteLog(80)
            if (rows.isEmpty()) sb.append("пусто\n")
            rows.forEach { sb.append(t(it.ts)).append(if (it.ok) "  ✓ " else "  ✗ ").append(it.source).append(" | ").append(it.command).append(" -> ").append(it.action).append(" | ").append(it.message.replace('\n', ' ')).append('\n') }
        } catch (_: Exception) { sb.append("не удалось прочитать\n") }

        h("Журнал действий агента (последние 300, новые сверху)")
        try {
            val rows = Graph.db.recentActivity(300)
            if (rows.isEmpty()) sb.append("пусто\n")
            rows.forEach { sb.append(t(it.ts)).append(" [").append(it.taskId % 100000).append("] ").append(it.kind).append(' ').append(it.tool ?: "").append(" | ").append(it.detail.replace('\n', ' ').take(400)).append(" | ").append(it.status).append('\n') }
        } catch (_: Exception) { sb.append("не удалось прочитать\n") }

        h("Нагрев: замеры и разбор (48 часов)")
        try { sb.append(com.rwai.agent.phone.HeatExport.build(ctx)) } catch (_: Exception) { sb.append("не удалось собрать\n") }

        h("Открытый диалог (последние 60 сообщений)")
        try {
            val id = Graph.controller.state.value.dialogId
            val msgs = Graph.db.dialogStore.messages(id).takeLast(60)
            if (msgs.isEmpty()) sb.append("пусто\n")
            msgs.forEach { sb.append(t(it.ts)).append(' ').append(it.role).append(": ").append(it.text.replace('\n', ' ').take(500)).append('\n') }
        } catch (_: Exception) { sb.append("не удалось прочитать\n") }

        // Ключи и значения профиля владельца не должны попасть в файл, что бы ни лежало в журналах.
        return ProfileTemplates.redact(SecretRedactor.redact(sb.toString(), listOfNotNull(Graph.settings.apiKey())), Graph.profile.values())
    }

    /** Пишет отчёт в файл кэша и возвращает его (старые отчёты удаляются). */
    fun write(ctx: Context): File {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles { f -> f.name.startsWith("rwai-logs-") }?.forEach { it.delete() }
        val f = File(dir, "rwai-logs-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt")
        f.writeText(build(ctx))
        return f
    }
}
