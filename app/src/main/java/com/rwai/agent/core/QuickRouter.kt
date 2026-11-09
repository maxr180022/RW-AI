package com.rwai.agent.core

import android.content.Context
import com.rwai.agent.Graph
import com.rwai.agent.local.LocalLlm
import com.rwai.agent.local.LocalModelStore
import com.rwai.agent.logic.ProfileTemplates
import com.rwai.agent.logic.QuickAction
import com.rwai.agent.logic.QuickActions
import com.rwai.agent.logic.QuickDispatch
import com.rwai.agent.logic.QuickRules
import com.rwai.agent.logic.SecretRedactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Решает, можно ли выполнить команду напрямую, без агента. Порядок: точные правила, потом локальная модель (если скачана) для нечётких
 * коротких фраз, иначе команда уходит агенту. Каждое решение пишется в журнал, чтобы потом проверять качество и учить модель.
 */
object QuickRouter {
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("rwai_quick", Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean("enabled", true)
    fun setEnabled(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("enabled", on).apply() }

    fun localEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("local", true)
    fun setLocalEnabled(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("local", on).apply() }

    /** Быстрая проверка без модели: стоит ли вообще заходить в быстрый путь. */
    fun mayHandle(ctx: Context, text: String): Boolean {
        if (!enabled(ctx)) return false
        if (QuickRules.match(text) != null) return true
        return localEnabled(ctx) && LocalModelStore.isReady(ctx) && QuickDispatch.worthAsking(text)
    }

    private fun redact(text: String): String =
        ProfileTemplates.redact(SecretRedactor.redact(text, listOfNotNull(Graph.settings.apiKey())), Graph.profile.values())

    /** null — быстрым путём не обрабатывается (агенту); иначе результат (ok или причина неудачи). */
    suspend fun handle(ctx: Context, text: String): QuickResult? {
        var action: QuickAction? = QuickRules.match(text)
        var source = "rule"
        if (action == null && localEnabled(ctx) && LocalModelStore.isReady(ctx) && QuickDispatch.worthAsking(text)) {
            source = "local"
            action = try {
                val out = withTimeoutOrNull(25_000) { LocalLlm.generate(ctx, QuickDispatch.prompt(text)) }
                out?.let { QuickDispatch.parse(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        if (action == null) {
            if (source == "local") log(text, "local", "нет подходящего", false, "отдано агенту")
            return null
        }
        val r = QuickExecutor.run(ctx, action)
        log(text, source, QuickActions.label(action), r.ok, r.message)
        return r
    }

    private fun log(command: String, source: String, action: String, ok: Boolean, message: String) {
        try { Graph.db.addRouteLog(source, redact(command), action, ok, redact(message)) } catch (_: Exception) { }
        RouteAudit.maybeRun(Graph.appContext())
    }
}
