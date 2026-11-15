package com.example.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.prefs.SessionPrefs
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

@HiltWorker
class ConnectionWatchdogWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val sessionPrefs: SessionPrefs,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (sessionPrefs.session.value?.isLoggedIn == true) {
            MessengerForegroundService.start(appContext)
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "aurora_ws_watchdog"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<ConnectionWatchdogWorker>(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
