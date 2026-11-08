package com.rwai.agent.core

import android.content.Context
import android.util.Log
import java.io.File

/** Записывает последний сбой приложения в файл на телефоне (только локально), чтобы его можно было прочитать на вкладке «Журнал». */
object CrashLog {
    private fun file(c: Context) = File(c.applicationContext.filesDir, "last_crash.txt")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                file(app).writeText(
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()) +
                        " поток " + thread.name + "\n" + Log.getStackTraceString(error).take(6000),
                )
            } catch (_: Throwable) { }
            previous?.uncaughtException(thread, error)
        }
    }

    fun last(c: Context): String? = try { file(c).takeIf { it.isFile }?.readText() } catch (_: Exception) { null }

    fun clear(c: Context) { try { file(c).delete() } catch (_: Exception) { } }
}
