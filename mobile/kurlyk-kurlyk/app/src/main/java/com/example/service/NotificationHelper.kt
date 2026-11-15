package com.example.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.graphics.drawable.IconCompat
import com.example.MainActivity
import com.example.R
import com.example.call.IncomingCallActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager = NotificationManagerCompat.from(context)

    fun createChannels() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val messagesChannel = NotificationChannel(
            CHANNEL_MESSAGES,
            context.getString(R.string.channel_messages_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            enableVibration(true)
            setShowBadge(true)
        }

        val callsChannel = NotificationChannel(
            CHANNEL_CALLS,
            context.getString(R.string.channel_calls_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val serviceChannel = NotificationChannel(
            CHANNEL_SERVICE,
            context.getString(R.string.channel_service_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
        }

        nm.createNotificationChannels(listOf(messagesChannel, callsChannel, serviceChannel))
    }

    fun buildServiceNotification(statusText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_dove)
            .setContentTitle(context.getString(R.string.service_notification_title))
            .setContentText(statusText)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openIntent)
            .build()
    }

    fun showMessageNotification(
        chatId: String,
        chatTitle: String,
        senderName: String,
        messageText: String,
        avatarLocalPath: String?,
        isGroup: Boolean,
        soundEnabled: Boolean,
        vibrationEnabled: Boolean,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val notifId = chatId.hashCode() and 0x7FFFFFFF
        val openIntent = PendingIntent.getActivity(
            context,
            notifId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_CHAT_ID, chatId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val personIcon = avatarLocalPath?.let { path ->
            val file = File(path)
            if (file.exists()) {
                BitmapFactory.decodeFile(file.absolutePath)?.let { IconCompat.createWithBitmap(it) }
            } else null
        }

        val senderPerson = Person.Builder()
            .setName(senderName)
            .apply { if (personIcon != null) setIcon(personIcon) }
            .build()

        val mePerson = Person.Builder().setName(context.getString(R.string.you_prefix).trimEnd(':')).build()

        val style = NotificationCompat.MessagingStyle(mePerson)
            .setConversationTitle(if (isGroup) chatTitle else null)
            .setGroupConversation(isGroup)
            .addMessage(messageText, System.currentTimeMillis(), senderPerson)

        val remoteInput = RemoteInput.Builder(KEY_REPLY_TEXT)
            .setLabel(context.getString(R.string.reply))
            .build()

        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            notifId + 1,
            Intent(context, NotificationActionReceiver::class.java).apply {
                action = ACTION_INLINE_REPLY
                putExtra(EXTRA_CHAT_ID, chatId)
                putExtra(EXTRA_NOTIF_ID, notifId)
            },
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.ic_dove,
            context.getString(R.string.reply),
            replyPendingIntent,
        ).addRemoteInput(remoteInput).build()

        val markReadPendingIntent = PendingIntent.getBroadcast(
            context,
            notifId + 2,
            Intent(context, NotificationActionReceiver::class.java).apply {
                action = ACTION_MARK_READ
                putExtra(EXTRA_CHAT_ID, chatId)
                putExtra(EXTRA_NOTIF_ID, notifId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val markReadAction = NotificationCompat.Action.Builder(
            0,
            context.getString(R.string.mark_read),
            markReadPendingIntent,
        ).build()

        val builder = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_dove)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setGroup("aurora_chat_$chatId")
            .setContentIntent(openIntent)
            .addAction(replyAction)
            .addAction(markReadAction)

        if (!soundEnabled) builder.setSilent(true)
        if (!vibrationEnabled) builder.setVibrate(longArrayOf(0L))

        manager.notify(notifId, builder.build())
    }

    fun cancelChatNotification(chatId: String) {
        val notifId = chatId.hashCode() and 0x7FFFFFFF
        manager.cancel(notifId)
    }

    fun showIncomingCallNotification(
        callId: String,
        callerPhone: String,
        callerName: String,
        isVideo: Boolean,
        avatarLocalPath: String?,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val fullScreenIntent = Intent(context, IncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_CALL_ID, callId)
            putExtra(EXTRA_CALLER_PHONE, callerPhone)
            putExtra(EXTRA_CALLER_NAME, callerName)
            putExtra(EXTRA_IS_VIDEO, isVideo)
        }
        val fullScreenPending = PendingIntent.getActivity(
            context,
            CALL_NOTIF_ID,
            fullScreenIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val acceptPending = PendingIntent.getBroadcast(
            context,
            CALL_NOTIF_ID + 1,
            Intent(context, NotificationActionReceiver::class.java).apply {
                action = ACTION_ACCEPT_CALL
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_CALLER_PHONE, callerPhone)
                putExtra(EXTRA_CALLER_NAME, callerName)
                putExtra(EXTRA_IS_VIDEO, isVideo)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val declinePending = PendingIntent.getBroadcast(
            context,
            CALL_NOTIF_ID + 2,
            Intent(context, NotificationActionReceiver::class.java).apply {
                action = ACTION_DECLINE_CALL
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_CALLER_PHONE, callerPhone)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val callerIcon = avatarLocalPath?.let { path ->
            val file = File(path)
            if (file.exists()) {
                BitmapFactory.decodeFile(file.absolutePath)?.let { IconCompat.createWithBitmap(it) }
            } else null
        }

        val callerPerson = Person.Builder()
            .setName(callerName)
            .apply { if (callerIcon != null) setIcon(callerIcon) }
            .setImportant(true)
            .build()

        val callStyle = NotificationCompat.CallStyle.forIncomingCall(
            callerPerson,
            declinePending,
            acceptPending,
        ).setIsVideo(isVideo)

        val notification = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_dove)
            .setContentTitle(callerName)
            .setContentText(
                if (isVideo) context.getString(R.string.incoming_video_call)
                else context.getString(R.string.incoming_audio_call),
            )
            .setStyle(callStyle)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setFullScreenIntent(fullScreenPending, true)
            .setContentIntent(fullScreenPending)
            .build()

        manager.notify(CALL_NOTIF_ID, notification)
    }

    fun cancelCallNotification() {
        manager.cancel(CALL_NOTIF_ID)
    }

    companion object {
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_CALLS = "calls"
        const val CHANNEL_SERVICE = "service"

        const val SERVICE_NOTIF_ID = 1001
        const val CALL_NOTIF_ID = 2002

        const val ACTION_INLINE_REPLY = "com.example.ACTION_INLINE_REPLY"
        const val ACTION_MARK_READ = "com.example.ACTION_MARK_READ"
        const val ACTION_ACCEPT_CALL = "com.example.ACTION_ACCEPT_CALL"
        const val ACTION_DECLINE_CALL = "com.example.ACTION_DECLINE_CALL"

        const val KEY_REPLY_TEXT = "key_reply_text"
        const val EXTRA_CHAT_ID = "extra_chat_id"
        const val EXTRA_NOTIF_ID = "extra_notif_id"
        const val EXTRA_CALL_ID = "extra_call_id"
        const val EXTRA_CALLER_PHONE = "extra_caller_phone"
        const val EXTRA_CALLER_NAME = "extra_caller_name"
        const val EXTRA_IS_VIDEO = "extra_is_video"
    }
}
