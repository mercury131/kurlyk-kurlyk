package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.prefs.SessionPrefs
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var sessionPrefs: SessionPrefs

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && sessionPrefs.session.value?.isLoggedIn == true) {
            MessengerForegroundService.start(context)
            ConnectionWatchdogWorker.schedule(context)
        }
    }
}
