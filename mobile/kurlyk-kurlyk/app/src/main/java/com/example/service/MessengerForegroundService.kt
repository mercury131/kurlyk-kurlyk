package com.example.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.example.R
import com.example.data.repository.MessagingRepository
import com.example.domain.ConnectionState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MessengerForegroundService : Service() {

    @Inject lateinit var repository: MessagingRepository
    @Inject lateinit var notificationHelper: NotificationHelper

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null
    private var inCallMode = false
    private var inVideoCall = false

    override fun onCreate() {
        super.onCreate()
        notificationHelper.createChannels()
        startWithCurrentType(getString(R.string.service_notification_connecting))
        stateJob = scope.launch {
            repository.ensureInitializedOnAppStart()
            repository.connectionState.collect { state ->
                if (repository.session.value == null) {
                    stopSelf()
                    return@collect
                }
                val text = when (state) {
                    ConnectionState.CONNECTED -> getString(R.string.service_notification_connected)
                    ConnectionState.WAITING_FOR_NETWORK -> getString(R.string.waiting_for_network)
                    ConnectionState.REPLACED -> getString(R.string.signed_in_elsewhere)
                    else -> getString(R.string.service_notification_connecting)
                }
                startWithCurrentType(text)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CALL -> {
                inCallMode = true
                inVideoCall = intent.getBooleanExtra(EXTRA_VIDEO, false)
                startWithCurrentType(getString(R.string.service_notification_connected))
            }
            ACTION_STOP_CALL -> {
                inCallMode = false
                inVideoCall = false
                startWithCurrentType(getString(R.string.service_notification_connected))
            }
            ACTION_STOP_SERVICE -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun startWithCurrentType(text: String) {
        val notification = notificationHelper.buildServiceNotification(text)
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            if (inCallMode) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (inVideoCall) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                }
            }
            type
        } else {
            0
        }
        ServiceCompat.startForeground(this, NotificationHelper.SERVICE_NOTIF_ID, notification, fgsType)
    }

    override fun onDestroy() {
        stateJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val ACTION_START_CALL = "com.example.service.START_CALL"
        private const val ACTION_STOP_CALL = "com.example.service.STOP_CALL"
        private const val ACTION_STOP_SERVICE = "com.example.service.STOP"
        private const val EXTRA_VIDEO = "extra_video"

        fun start(context: Context) {
            val intent = Intent(context, MessengerForegroundService::class.java)
            runCatching {
                context.startForegroundService(intent)
            }
        }

        fun startCallMode(context: Context, isVideo: Boolean) {
            val intent = Intent(context, MessengerForegroundService::class.java).apply {
                action = ACTION_START_CALL
                putExtra(EXTRA_VIDEO, isVideo)
            }
            runCatching { context.startForegroundService(intent) }
        }

        fun stopCallMode(context: Context) {
            val intent = Intent(context, MessengerForegroundService::class.java).apply {
                action = ACTION_STOP_CALL
            }
            runCatching { context.startService(intent) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MessengerForegroundService::class.java))
        }
    }
}
