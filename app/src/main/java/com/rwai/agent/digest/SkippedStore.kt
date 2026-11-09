package com.rwai.agent.digest

import android.content.Context
import com.rwai.agent.Graph
import com.rwai.agent.logic.AnsweredCards
import kotlinx.coroutines.flow.MutableStateFlow

/** Карточки «Входящих», которые владелец нажатием «Пропустить» убрал: они больше не показываются и не считаются неотвеченными. */
object SkippedStore {
    private val prefs get() = Graph.appContext().getSharedPreferences("rwai_digest", Context.MODE_PRIVATE)

    /** Меняется при каждом «Пропустить»: список «Входящих» перерисовывается. */
    val tick = MutableStateFlow(0L)

    fun raw(): String = prefs.getString("skipped", "").orEmpty()

    fun skip(key: String): String = AnsweredCards.add(raw(), key).also { prefs.edit().putString("skipped", it).apply(); tick.value = System.nanoTime() }
}
