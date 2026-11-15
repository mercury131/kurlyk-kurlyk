package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.example.call.WebRtcCallManager
import com.example.data.repository.MessagingRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {
    @Inject lateinit var repository: MessagingRepository
    @Inject lateinit var callManager: WebRtcCallManager
    @Inject lateinit var notificationHelper: NotificationHelper

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    NotificationHelper.ACTION_INLINE_REPLY -> {
                        val chatId = intent.getStringExtra(NotificationHelper.EXTRA_CHAT_ID) ?: return@launch
                        val replyText = RemoteInput.getResultsFromIntent(intent)
                            ?.getCharSequence(NotificationHelper.KEY_REPLY_TEXT)
                            ?.toString()
                            ?.trim()
                        if (!replyText.isNullOrEmpty()) {
                            repository.sendTextMessage(chatId, replyText)
                            repository.markChatRead(chatId)
                            notificationHelper.cancelChatNotification(chatId)
                        }
                    }

                    NotificationHelper.ACTION_MARK_READ -> {
                        val chatId = intent.getStringExtra(NotificationHelper.EXTRA_CHAT_ID) ?: return@launch
                        repository.markChatRead(chatId)
                        notificationHelper.cancelChatNotification(chatId)
                    }

                    NotificationHelper.ACTION_ACCEPT_CALL -> {
                        callManager.acceptIncomingCall()
                    }

                    NotificationHelper.ACTION_DECLINE_CALL -> {
                        callManager.declineIncomingCall()
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
