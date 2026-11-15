package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.R
import com.example.data.api.AuroraJson
import com.example.data.db.FileEntity
import com.example.data.db.MessageEntity
import com.example.data.db.UserEntity
import com.example.domain.FileAttachmentMeta
import com.example.ui.components.AvatarView
import com.example.ui.theme.OnlineGreen
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    user: UserEntity?,
    phone: String,
    messages: List<MessageEntity>,
    filesMap: Map<String, FileEntity>,
    onBack: () -> Unit,
    onSendMessage: () -> Unit,
    onStartCall: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var selectedTab by remember { mutableIntStateOf(0) }
    var fullAvatarPath by remember { mutableStateOf<String?>(null) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        val audioOk = res[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val camOk = res[Manifest.permission.CAMERA] == true
        if (audioOk) onStartCall(camOk)
    }

    val attachments = remember(messages) {
        messages.filter { it.kind == "file" && !it.deleted }
            .mapNotNull { m ->
                m.fileJson?.let { runCatching { AuroraJson.decodeFromString<FileAttachmentMeta>(it) }.getOrNull() }
            }
    }
    val mediaItems = remember(attachments) {
        attachments.filter { it.mime.startsWith("image/") || it.mime.startsWith("video/") }
    }
    val voiceItems = remember(attachments) {
        attachments.filter { it.mime.startsWith("audio/") }
    }
    val fileItems = remember(attachments) {
        attachments.filter { !it.mime.startsWith("image/") && !it.mime.startsWith("video/") && !it.mime.startsWith("audio/") }
    }

    val displayName = user?.displayName ?: phone

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AvatarView(
                            id = phone,
                            name = displayName,
                            avatarLocalPath = user?.avatarLocalPath,
                            size = 48.dp,
                            isOnline = user?.online == true,
                            onClick = {
                                user?.avatarLocalPath?.takeIf { File(it).exists() }?.let { fullAvatarPath = it }
                            },
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(displayName, fontWeight = FontWeight.Bold)
                            Text(
                                text = if (user?.online == true) stringResource(R.string.online) else phone,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (user?.online == true) OnlineGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Phone, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(phone, style = MaterialTheme.typography.titleMedium)
                            if (!user?.serverName.isNullOrBlank() && user?.serverName != displayName) {
                                Text(user!!.serverName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = onSendMessage,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.send_message))
                        }
                        OutlinedButton(
                            onClick = {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                    onStartCall(false)
                                } else {
                                    permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                                }
                            },
                        ) {
                            Icon(Icons.Filled.Call, contentDescription = stringResource(R.string.audio_call))
                        }
                        OutlinedButton(
                            onClick = {
                                val a = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                                val c = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                                if (a && c) onStartCall(true)
                                else permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA))
                            },
                        ) {
                            Icon(Icons.Filled.Videocam, contentDescription = stringResource(R.string.video_call))
                        }
                    }
                }
                HorizontalDivider()
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text(stringResource(R.string.tab_media)) })
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text(stringResource(R.string.tab_files)) })
                    Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text(stringResource(R.string.tab_voice)) })
                }
            }

            when (selectedTab) {
                0 -> {
                    val rows = mediaItems.chunked(3)
                    items(rows) { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            row.forEach { meta ->
                                val path = filesMap[meta.fileId]?.localPath ?: meta.localPath
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable {
                                            if (path != null && File(path).exists() && meta.mime.startsWith("image/")) {
                                                fullAvatarPath = path
                                            }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (path != null && File(path).exists()) {
                                        AsyncImage(
                                            model = File(path),
                                            contentDescription = meta.name,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    } else {
                                        Text(meta.name, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(4.dp))
                                    }
                                }
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                1 -> {
                    items(fileItems) { meta ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.InsertDriveFile, contentDescription = null)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(meta.name, fontWeight = FontWeight.Medium)
                                Text(formatFileSize(meta.size), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
                2 -> {
                    items(voiceItems) { meta ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Mic, contentDescription = null)
                            Spacer(Modifier.width(12.dp))
                            val secs = ((meta.durationMs ?: 0L) / 1000L).toInt()
                            Text("Voice message • %02d:%02d".format(secs / 60, secs % 60))
                        }
                    }
                }
            }
        }
    }

    fullAvatarPath?.let { path ->
        ZoomableImageDialog(imagePath = path, onDismiss = { fullAvatarPath = null })
    }
}
