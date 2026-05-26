package com.mobilegem.gemma

import android.app.Application
import com.mobilegem.gemma.logging.AppLog

class GemmaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        AppLog.install(container.fileLogger)
        AppLog.event(
            "app", "onCreate",
            "logFile" to container.fileLogger.currentFile.absolutePath,
        )
    }

    override fun onTerminate() {
        AppLog.event("app", "onTerminate")
        AppLog.flush()
        AppLog.close()
        super.onTerminate()
    }
}
