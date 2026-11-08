package com.rwai.agent.digest

import android.content.Context
import com.rwai.agent.Graph
import com.rwai.agent.logic.AnsweredCards

/** Отметки «карточка отработана» для экрана «Входящие»: хранятся только на телефоне, рядом со сводкой. */
object AnsweredStore {
    private val prefs get() = Graph.appContext().getSharedPreferences("rwai_digest", Context.MODE_PRIVATE)

    fun raw(): String = prefs.getString("answered", "").orEmpty()

    fun mark(key: String): String = AnsweredCards.add(raw(), key).also { prefs.edit().putString("answered", it).apply() }

    fun unmark(key: String): String = AnsweredCards.remove(raw(), key).also { prefs.edit().putString("answered", it).apply() }
}
