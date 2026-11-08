package com.rwai.agent.core

import android.app.Activity
import android.app.Application
import android.os.Bundle

/** Открыто ли приложение сейчас (есть хотя бы один видимый экран). Нужен, чтобы не слать уведомления, пока владелец внутри. */
object AppVisibility {
    @Volatile private var started = 0
    val inForeground: Boolean get() = started > 0

    @Volatile private var resumed: Activity? = null

    /** Открыт ли сейчас основной экран Руся AI (а не панель ассистента поверх другого приложения). */
    val mainScreenOpen: Boolean get() = resumed is com.rwai.agent.MainActivity

    /** [onBackground] вызывается, когда последний экран приложения ушёл из виду (приложение свернули). */
    fun install(app: Application, onBackground: (Application) -> Unit) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { started++ }
            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                if (started == 0) onBackground(app)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) { resumed = activity }
            override fun onActivityPaused(activity: Activity) { if (resumed === activity) resumed = null }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
