package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.api.AuroraJson
import com.example.data.db.MessageEntity
import com.example.data.db.UserEntity
import com.example.domain.CallLogMeta
import com.example.ui.components.AvatarView
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.OnlineGreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallsHistoryScreen(
    callMessages: List<MessageEntity>,
    usersByPhone: Map<String, UserEntity>,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    onCallBack: (String, Boolean) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text(stringResource(R.string.calls)) },
            )
        },
    ) { padding ->
        if (callMessages.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(stringResource(R.string.no_calls_yet), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                items(callMessages, key = { it.localId }) { msg ->
                    val peer = usersByPhone[msg.chatId]
                    val meta = msg.callLogJson?.let {
                        runCatching { AuroraJson.decodeFromString<CallLogMeta>(it) }.getOrNull()
                    }
                    val isMissed = meta?.result == "missed" || meta?.result == "declined"
                    val isOutgoing = msg.direction == "out"
                    val title = peer?.displayName ?: msg.chatId

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenChat(msg.chatId) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AvatarView(
                            id = msg.chatId,
                            name = title,
                            avatarLocalPath = peer?.avatarLocalPath,
                            size = 48.dp,
                            isOnline = peer?.online == true,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.titleMedium)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = when {
                                        isMissed -> Icons.AutoMirrored.Filled.CallMissed
                                        isOutgoing -> Icons.AutoMirrored.Filled.CallMade
                                        else -> Icons.AutoMirrored.Filled.CallReceived
                                    },
                                    contentDescription = null,
                                    tint = if (isMissed) ErrorRed else OnlineGreen,
                                )
                                Spacer(Modifier.width(4.dp))
                                val dateStr = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(msg.createdAt))
                                val durStr = if ((meta?.durationSec ?: 0) > 0) " (${meta!!.durationSec}s)" else ""
                                Text(
                                    text = "$dateStr$durStr",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        IconButton(onClick = { onCallBack(msg.chatId, meta?.video == true) }) {
                            Icon(
                                imageVector = if (meta?.video == true) Icons.Filled.Videocam else Icons.Filled.Call,
                                contentDescription = "Call back",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}
