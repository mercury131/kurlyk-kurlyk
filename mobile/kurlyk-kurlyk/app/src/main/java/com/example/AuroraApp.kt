package com.example

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.example.call.WebRtcCallManager
import com.example.data.prefs.SessionPrefs
import com.example.service.ConnectionWatchdogWorker
import com.example.service.MessengerForegroundService
import com.example.service.NotificationHelper
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class AuroraApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var notificationHelper: NotificationHelper
    @Inject lateinit var sessionPrefs: SessionPrefs

    // Created eagerly: its constructor registers the WebSocket "call" signal handler.
    // Without this, incoming calls were silently dropped whenever the process was started by the
    // foreground service / watchdog (no Activity -> no MainViewModel -> no WebRtcCallManager).
    @Inject lateinit var callManager: WebRtcCallManager

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        notificationHelper.createChannels()
        if (sessionPrefs.session.value?.isLoggedIn == true) {
            MessengerForegroundService.start(this)
            ConnectionWatchdogWorker.schedule(this)
        }
    }
}
