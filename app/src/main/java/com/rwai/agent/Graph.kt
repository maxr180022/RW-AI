package com.rwai.agent

import android.app.Application
import android.content.Context
import com.rwai.agent.brain.DeepSeekClient
import com.rwai.agent.core.AgentController
import com.rwai.agent.core.AgentNotifications
import com.rwai.agent.data.RwDb
import com.rwai.agent.security.SecretStore
import com.rwai.agent.settings.SettingsStore
import com.rwai.agent.voice.Speaker

/** Простейший сервис-локатор: один набор объектов на процесс (приложение, службы и получатели используют общие экземпляры). */
object Graph {
    private lateinit var app: Context

    fun init(context: Context) {
        app = context.applicationContext
    }

    fun appContext(): Context = app

    val secretStore: SecretStore by lazy { SecretStore(app) }
    val profile: com.rwai.agent.security.ProfileStore by lazy { com.rwai.agent.security.ProfileStore(secretStore) }
    val settings: SettingsStore by lazy { SettingsStore(app, secretStore) }
    val db: RwDb by lazy { RwDb(app) }
    val speaker: Speaker by lazy { Speaker(app) }
    val brain: DeepSeekClient by lazy { DeepSeekClient(settings) }
    val controller: AgentController by lazy { AgentController(app, settings, db, speaker, brain) }
}

class RwApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        com.rwai.agent.core.CrashLog.install(this)
        // Приложение свернули, а подтверждение ещё ждёт ответа (агент ушёл в другое приложение): теперь уведомление нужно.
        com.rwai.agent.core.AppVisibility.install(this) { app ->
            try { Graph.controller.state.value.confirmation?.let { AgentNotifications.showConfirmation(app, it) } } catch (_: Exception) { }
        }
        try {
            if (Graph.settings.current.quickReply && Graph.settings.hasApiKey.value) AgentNotifications.showQuick(this)
        } catch (_: Exception) {
            // уведомление — удобство, а не обязательная функция
        }
    }
}
