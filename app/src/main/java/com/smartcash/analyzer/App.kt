package com.smartcash.analyzer

import android.app.Application
import com.smartcash.analyzer.notifications.AppNotifier
import com.smartcash.analyzer.telegram.TelegramSyncWorker

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        AppNotifier.createChannels(this)
        TelegramSyncWorker.schedule(this)
    }
}
