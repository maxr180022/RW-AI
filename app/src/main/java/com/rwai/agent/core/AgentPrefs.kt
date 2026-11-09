package com.rwai.agent.core

import android.content.Context

/** Настройки поведения агента, не входящие в основные: экономия рассуждения (по умолчанию включена). */
object AgentPrefs {
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("rwai_agent", Context.MODE_PRIVATE)

    fun economy(ctx: Context): Boolean = prefs(ctx).getBoolean("economy", true)

    fun setEconomy(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("economy", on).apply() }
}
