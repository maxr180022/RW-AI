package com.rwai.agent

import android.app.Application
import android.content.Context
import com.rwai.agent.brain.DeepSeekClient
import com.rwai.agent.core.AgentController
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

    val secretStore: SecretStore by lazy { SecretStore(app) }
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
    }
}
