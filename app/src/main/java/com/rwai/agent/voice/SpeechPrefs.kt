package com.rwai.agent.voice

import android.content.Context

/** Длина автоматической озвучки ответа. По умолчанию коротко: только первые фразы, остальное в чате или кнопкой-динамиком у сообщения. */
object SpeechPrefs {
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("rwai_speech", Context.MODE_PRIVATE)

    fun full(ctx: Context): Boolean = prefs(ctx).getBoolean("full", false)

    fun setFull(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("full", on).apply() }
}
