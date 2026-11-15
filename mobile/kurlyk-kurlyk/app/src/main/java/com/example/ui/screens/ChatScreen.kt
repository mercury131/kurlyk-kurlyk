package com.example.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.example.R
import com.example.data.api.AuroraJson
import com.example.data.db.ChatEntity
import com.example.data.db.FileEntity
import com.example.data.db.MessageEntity
import com.example.data.db.UserEntity
import com.example.domain.CallLogMeta
import com.example.domain.ChatConstants
import com.example.domain.FileAttachmentMeta
import com.example.ui.components.AvatarView
import com.example.ui.components.ChatWallpaper
import com.example.ui.theme.AuroraAccent
import com.example.ui.theme.AvatarGradientPalettes
import com.example.ui.theme.LocalAuroraChatColors
import com.example.ui.theme.OnlineGreen
import com.example.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val QuickEmojis = listOf("👍", "❤️", "😂", "😮", "😢", "🙏", "🔥", "🎉")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    chatId: String,
    viewModel: ChatViewModel,
    wallpaperId: Int,
    allChats: List<ChatEntity>,
    onBack: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenGroupInfo: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chatColors = LocalAuroraChatColors.current

    LaunchedEffect(chatId) {
        viewModel.setChatId(chatId)
    }
    DisposableEffect(chatId) {
        onDispose { viewModel.onScreenDisposed() }
    }

    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val peerUser by viewModel.peerUser.collectAsStateWithLifecycle()
    val group by viewModel.group.collectAsStateWithLifecycle()
    val allUsers by viewModel.allUsers.collectAsStateWithLifecycle()
    val filesMap by viewModel.filesMap.collectAsStateWithLifecycle()
    val typingUsers by viewModel.typingUsers.collectAsStateWithLifecycle()
    val replyTo by viewModel.replyToMessage.collectAsStateWithLifecycle()
    val editingMsg by viewModel.editingMessage.collectAsStateWithLifecycle()
    val isRecording by viewModel.isRecordingVoice.collectAsStateWithLifecycle()
    val recSeconds by viewModel.recordingSeconds.collectAsStateWithLifecycle()
    val toastMsg by viewModel.toastMessage.collectAsStateWithLifecycle()

    LaunchedEffect(toastMsg) {
        toastMsg?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    LaunchedEffect(messages.size) {
        viewModel.onIncomingWhileVisible()
    }

    val isSaved = chatId == ChatConstants.SAVED_CHAT_ID
    val isGroup = ChatConstants.isGroupChat(chatId)
    val isGroupMember = group?.isMember != false

    var inputText by remember(chatId) { mutableStateOf("") }
    LaunchedEffect(editingMsg) {
        if (editingMsg != null) {
            inputText = editingMsg?.text.orEmpty()
        }
    }

    var showAttachSheet by remember { mutableStateOf(false) }
    var showEmojiBar by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }
    var searchMode by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var forwardMessageTarget by remember { mutableStateOf<MessageEntity?>(null) }
    var fullscreenImagePath by remember { mutableStateOf<String?>(null) }
    var videoPlayerPath by remember { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState()
    val isScrolledUp by remember {
        derivedStateOf {
            messages.isNotEmpty() && listState.firstVisibleItemIndex + listState.layoutInfo.visibleItemsInfo.size < messages.size - 1
        }
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && !isScrolledUp) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    // Launchers
    val pickVisualLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { viewModel.sendUriAttachment(it) }
    }
    val openFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.sendUriAttachment(it) }
    }

    var pendingCameraFile by remember { mutableStateOf<File?>(null) }
    val takePictureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val file = pendingCameraFile
        if (success && file != null && file.exists()) {
            viewModel.sendCameraFile(file)
        }
    }

    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startVoiceRecording()
    }

    val callPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val audioOk = results[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val videoOk = results[Manifest.permission.CAMERA] == true
        if (audioOk) {
            viewModel.startCall(isVideo = videoOk)
        }
    }

    val title = when {
        isSaved -> stringResource(R.string.saved_messages)
        isGroup -> group?.name ?: chat?.title ?: "Group"
        else -> peerUser?.displayName ?: chat?.title ?: chatId
    }

    val statusText = when {
        isSaved -> stringResource(R.string.saved_messages_subtitle)
        typingUsers.isNotEmpty() -> {
            if (isGroup) {
                val names = typingUsers.mapNotNull { allUsers[it]?.displayName ?: it }.joinToString(", ")
                "$names ${stringResource(R.string.typing)}"
            } else {
                stringResource(R.string.typing)
            }
        }
        isGroup -> {
            val count = runCatching {
                AuroraJson.decodeFromString<List<String>>(group?.membersJson ?: "[]").size
            }.getOrDefault(0)
            stringResource(R.string.members_count, count)
        }
        peerUser?.online == true -> stringResource(R.string.online)
        (peerUser?.lastSeen ?: 0L) > 0L -> {
            val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            stringResource(R.string.last_seen_at, fmt.format(Date((peerUser?.lastSeen ?: 0L) * 1000L)))
        }
        else -> peerUser?.phone ?: ""
    }

    val visibleMessages = remember(messages, searchQuery) {
        if (searchQuery.isBlank()) messages
        else messages.filter { it.text?.contains(searchQuery, ignoreCase = true) == true }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    if (searchMode) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text(stringResource(R.string.search_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    when {
                                        isGroup -> onOpenGroupInfo(ChatConstants.extractGroupId(chatId))
                                        !isSaved -> onOpenProfile(chatId)
                                    }
                                },
                        ) {
                            AvatarView(
                                id = chatId,
                                name = title,
                                avatarLocalPath = peerUser?.avatarLocalPath,
                                size = 40.dp,
                                isOnline = peerUser?.online == true,
                                isGroup = isGroup,
                                isSavedMessages = isSaved,
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (statusText.isNotBlank()) {
                                    Text(
                                        text = statusText,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (typingUsers.isNotEmpty() || peerUser?.online == true) OnlineGreen
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                },
                actions = {
                    if (searchMode) {
                        IconButton(onClick = { searchMode = false; searchQuery = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Close search")
                        }
                    } else {
                        if (!isGroup && !isSaved) {
                            IconButton(
                                onClick = {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                        viewModel.startCall(isVideo = false)
                                    } else {
                                        callPermLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                                    }
                                },
                                modifier = Modifier.testTag("audio_call_button"),
                            ) {
                                Icon(Icons.Filled.Call, contentDescription = stringResource(R.string.audio_call))
                            }
                            IconButton(
                                onClick = {
                                    val hasAudio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                                    val hasCam = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                                    if (hasAudio && hasCam) {
                                        viewModel.startCall(isVideo = true)
                                    } else {
                                        callPermLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA))
                                    }
                                },
                                modifier = Modifier.testTag("video_call_button"),
                            ) {
                                Icon(Icons.Filled.Videocam, contentDescription = stringResource(R.string.video_call))
                            }
                        }
                        Box {
                            IconButton(onClick = { showOverflow = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                                DropdownMenuItem(
                                    text = { Text("Search") },
                                    onClick = { showOverflow = false; searchMode = true },
                                )
                                if (isGroup) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.group_info)) },
                                        onClick = {
                                            showOverflow = false
                                            onOpenGroupInfo(ChatConstants.extractGroupId(chatId))
                                        },
                                    )
                                } else if (!isSaved) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.profile)) },
                                        onClick = {
                                            showOverflow = false
                                            onOpenProfile(chatId)
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                if (isGroup && !isGroupMember) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.group_read_only),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                } else {
                    // Reply / Edit banner
                    val bannerMsg = editingMsg ?: replyTo
                    if (bannerMsg != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(30.dp)
                                    .background(AuroraAccent),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (editingMsg != null) stringResource(R.string.editing_message)
                                    else stringResource(
                                        R.string.replying_to,
                                        allUsers[bannerMsg.sender]?.displayName ?: bannerMsg.sender,
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = AuroraAccent,
                                )
                                Text(
                                    text = bannerMsg.text ?: "Attachment",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            IconButton(onClick = {
                                viewModel.cancelReplyOrEdit()
                                if (editingMsg != null) inputText = ""
                            }) {
                                Icon(Icons.Filled.Close, contentDescription = "Cancel")
                            }
                        }
                    }

                    AnimatedVisibility(visible = showEmojiBar) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            QuickEmojis.forEach { emoji ->
                                Text(
                                    text = emoji,
                                    fontSize = 24.sp,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .clickable { inputText += emoji }
                                        .padding(6.dp),
                                )
                            }
                        }
                    }

                    if (isRecording) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.error),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "00:%02d  •  ${stringResource(R.string.slide_to_cancel)}".format(recSeconds),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Row {
                                TextButton(onClick = { viewModel.stopVoiceRecording(cancel = true) }) {
                                    Text(stringResource(R.string.cancel))
                                }
                                TextButton(onClick = { viewModel.stopVoiceRecording(cancel = false) }) {
                                    Text(stringResource(R.string.send))
                                }
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            IconButton(onClick = { showEmojiBar = !showEmojiBar }) {
                                Icon(
                                    Icons.Filled.EmojiEmotions,
                                    contentDescription = "Emoji",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextField(
                                value = inputText,
                                onValueChange = {
                                    inputText = it
                                    if (it.isNotBlank()) viewModel.onTextTyped()
                                },
                                placeholder = { Text(stringResource(R.string.message_hint)) },
                                maxLines = 5,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                ),
                                modifier = Modifier.weight(1f).testTag("message_input"),
                            )
                            IconButton(onClick = { showAttachSheet = true }) {
                                Icon(
                                    Icons.Filled.AttachFile,
                                    contentDescription = "Attach",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (inputText.isNotBlank()) {
                                IconButton(
                                    onClick = {
                                        val msg = inputText
                                        inputText = ""
                                        viewModel.sendOrEditText(msg)
                                    },
                                    modifier = Modifier.testTag("send_button"),
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.Send,
                                        contentDescription = stringResource(R.string.send),
                                        tint = AuroraAccent,
                                    )
                                }
                            } else {
                                var dragDelta by remember { mutableFloatStateOf(0f) }
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .pointerInput(Unit) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = {
                                                    dragDelta = 0f
                                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                                        viewModel.startVoiceRecording()
                                                    } else {
                                                        micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                                    }
                                                },
                                                onDrag = { _, dragAmount ->
                                                    dragDelta += dragAmount.x
                                                },
                                                onDragEnd = {
                                                    viewModel.stopVoiceRecording(cancel = dragDelta < -120f)
                                                },
                                                onDragCancel = {
                                                    viewModel.stopVoiceRecording(cancel = true)
                                                },
                                            )
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Filled.Mic,
                                        contentDescription = stringResource(R.string.record_voice),
                                        tint = AuroraAccent,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ChatWallpaper(wallpaperId = wallpaperId)

            val messagesById = remember(messages) { messages.associateBy { it.msgId } }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
            ) {
                itemsIndexed(visibleMessages, key = { _, m -> m.localId }) { index, msg ->
                    val prev = visibleMessages.getOrNull(index - 1)
                    val next = visibleMessages.getOrNull(index + 1)

                    val showDateHeader = prev == null || !isSameDay(prev.createdAt, msg.createdAt)
                    if (showDateHeader) {
                        DatePill(msg.createdAt)
                    }

                    val isOutgoing = msg.direction == "out"
                    val isFirstInGroup = prev == null || prev.sender != msg.sender || showDateHeader
                    val isLastInGroup = next == null || next.sender != msg.sender || !isSameDay(msg.createdAt, next.createdAt)

                    var swipeOffset by remember(msg.localId) { mutableFloatStateOf(0f) }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = if (isLastInGroup) 3.dp else 1.dp)
                            .offset { IntOffset(swipeOffset.roundToInt(), 0) }
                            .pointerInput(msg.localId) {
                                detectHorizontalDragGestures(
                                    onDragEnd = {
                                        if (swipeOffset < -70f) {
                                            viewModel.setReplyTo(msg)
                                        }
                                        swipeOffset = 0f
                                    },
                                    onDragCancel = { swipeOffset = 0f },
                                    onHorizontalDrag = { _, dragAmount ->
                                        swipeOffset = (swipeOffset + dragAmount).coerceIn(-120f, 0f)
                                    },
                                )
                            },
                        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start,
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        if (isGroup && !isOutgoing) {
                            if (isLastInGroup) {
                                val senderUser = allUsers[msg.sender]
                                AvatarView(
                                    id = msg.sender,
                                    name = senderUser?.displayName ?: msg.sender,
                                    avatarLocalPath = senderUser?.avatarLocalPath,
                                    size = 32.dp,
                                    onClick = { onOpenProfile(msg.sender) },
                                )
                            } else {
                                Spacer(Modifier.width(32.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                        }

                        val senderColor = AvatarGradientPalettes[abs(msg.sender.hashCode()) % AvatarGradientPalettes.size].first
                        val bubbleShape = RoundedCornerShape(
                            topStart = if (!isOutgoing && !isFirstInGroup) 6.dp else 16.dp,
                            topEnd = if (isOutgoing && !isFirstInGroup) 6.dp else 16.dp,
                            bottomStart = if (!isOutgoing && isLastInGroup) 4.dp else 16.dp,
                            bottomEnd = if (isOutgoing && isLastInGroup) 4.dp else 16.dp,
                        )

                        Surface(
                            color = if (isOutgoing) chatColors.outgoingBubble else chatColors.incomingBubble,
                            shape = bubbleShape,
                            tonalElevation = 1.dp,
                            modifier = Modifier
                                .widthIn(max = 300.dp)
                                .combinedClickable(
                                    onClick = {
                                        if (msg.kind == "file" && !msg.deleted) {
                                            val meta = msg.fileJson?.let {
                                                runCatching { AuroraJson.decodeFromString<FileAttachmentMeta>(it) }.getOrNull()
                                            }
                                            if (meta != null) {
                                                val fileEntry = filesMap[meta.fileId]
                                                val localPath = fileEntry?.localPath ?: meta.localPath
                                                if (localPath != null && File(localPath).exists()) {
                                                    when {
                                                        meta.mime.startsWith("image/") -> fullscreenImagePath = localPath
                                                        meta.mime.startsWith("video/") -> videoPlayerPath = localPath
                                                    }
                                                } else {
                                                    viewModel.downloadAttachment(meta)
                                                }
                                            }
                                        }
                                    },
                                    onLongClick = { selectedMessage = msg },
                                ),
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                if (isGroup && !isOutgoing && isFirstInGroup) {
                                    Text(
                                        text = allUsers[msg.sender]?.displayName ?: msg.sender,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = senderColor,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                }

                                // Reply quote
                                msg.replyTo?.let { replyId ->
                                    val replied = messagesById[replyId]
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 6.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color.Black.copy(alpha = 0.14f))
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .width(3.dp)
                                                .height(24.dp)
                                                .background(AuroraAccent),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                text = replied?.let { allUsers[it.sender]?.displayName ?: it.sender } ?: "Reply",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = AuroraAccent,
                                            )
                                            Text(
                                                text = replied?.text ?: "Attachment",
                                                style = MaterialTheme.typography.labelMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }

                                if (msg.deleted) {
                                    Text(
                                        text = stringResource(R.string.deleted_message),
                                        fontStyle = FontStyle.Italic,
                                        color = chatColors.bubbleMetaText,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                } else {
                                    when (msg.kind) {
                                        "call_log" -> {
                                            val callMeta = msg.callLogJson?.let {
                                                runCatching { AuroraJson.decodeFromString<CallLogMeta>(it) }.getOrNull()
                                            }
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                Icon(
                                                    if (callMeta?.video == true) Icons.Filled.Videocam else Icons.Filled.Call,
                                                    contentDescription = null,
                                                    tint = if (callMeta?.result == "missed") MaterialTheme.colorScheme.error else AuroraAccent,
                                                )
                                                Column {
                                                    val label = when (callMeta?.result) {
                                                        "missed" -> stringResource(R.string.call_missed)
                                                        "declined" -> stringResource(R.string.call_declined)
                                                        else -> stringResource(R.string.call_ended)
                                                    }
                                                    Text(label, fontWeight = FontWeight.Medium)
                                                    if ((callMeta?.durationSec ?: 0) > 0) {
                                                        Text(
                                                            "${callMeta!!.durationSec}s",
                                                            style = MaterialTheme.typography.labelMedium,
                                                            color = chatColors.bubbleMetaText,
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        "file" -> {
                                            val meta = msg.fileJson?.let {
                                                runCatching { AuroraJson.decodeFromString<FileAttachmentMeta>(it) }.getOrNull()
                                            }
                                            if (meta != null) {
                                                FileBubbleContent(
                                                    meta = meta,
                                                    fileEntity = filesMap[meta.fileId],
                                                    onDownload = { viewModel.downloadAttachment(meta) },
                                                )
                                                if (!meta.caption.isNullOrBlank()) {
                                                    Spacer(Modifier.height(4.dp))
                                                    Text(
                                                        text = meta.caption,
                                                        color = if (isOutgoing) chatColors.outgoingText else chatColors.incomingText,
                                                        style = MaterialTheme.typography.bodyLarge,
                                                    )
                                                }
                                            }
                                        }

                                        else -> {
                                            Text(
                                                text = msg.text.orEmpty(),
                                                color = if (isOutgoing) chatColors.outgoingText else chatColors.incomingText,
                                                style = MaterialTheme.typography.bodyLarge,
                                            )
                                        }
                                    }
                                }

                                // Reactions
                                val reactions = runCatching {
                                    AuroraJson.decodeFromString<Map<String, String>>(msg.reactionsJson)
                                }.getOrDefault(emptyMap())
                                if (reactions.isNotEmpty() && !msg.deleted) {
                                    Row(
                                        modifier = Modifier.padding(top = 4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        reactions.values.groupingBy { it }.eachCount().forEach { (emoji, count) ->
                                            Surface(
                                                color = Color.Black.copy(alpha = 0.18f),
                                                shape = RoundedCornerShape(10.dp),
                                                modifier = Modifier.clickable { viewModel.react(msg.msgId, emoji) },
                                            ) {
                                                Text(
                                                    text = if (count > 1) "$emoji $count" else emoji,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                )
                                            }
                                        }
                                    }
                                }

                                // Timestamp + Edited + Ticks
                                Row(
                                    modifier = Modifier.align(Alignment.End).padding(top = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    if (msg.editedAt != null && !msg.deleted) {
                                        Text(
                                            text = stringResource(R.string.edited_label),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = chatColors.bubbleMetaText,
                                        )
                                    }
                                    Text(
                                        text = formatShortTime(msg.createdAt),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = chatColors.bubbleMetaText,
                                    )
                                    if (isOutgoing && !isSaved) {
                                        MessageStatusIcon(status = msg.status, tintOverride = chatColors.bubbleMetaText)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (isScrolledUp) {
                SmallFloatingActionButton(
                    onClick = {
                        scope.launch {
                            if (visibleMessages.isNotEmpty()) {
                                listState.animateScrollToItem(visibleMessages.lastIndex)
                            }
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                ) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Scroll to bottom")
                }
            }
        }
    }

    // Attachment sheet
    if (showAttachSheet) {
        ModalBottomSheet(onDismissRequest = { showAttachSheet = false }) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showAttachSheet = false
                            pickVisualLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.PhotoLibrary, contentDescription = null, tint = AuroraAccent)
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.attach_gallery), style = MaterialTheme.typography.titleMedium)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showAttachSheet = false
                            val camDir = File(context.cacheDir, "camera").apply { mkdirs() }
                            val photoFile = File(camDir, "cam_${System.currentTimeMillis()}.jpg")
                            pendingCameraFile = photoFile
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
                            takePictureLauncher.launch(uri)
                        }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.CameraAlt, contentDescription = null, tint = AuroraAccent)
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.attach_camera), style = MaterialTheme.typography.titleMedium)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showAttachSheet = false
                            openFileLauncher.launch(arrayOf("*/*"))
                        }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.InsertDriveFile, contentDescription = null, tint = AuroraAccent)
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.attach_file), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }

    // Message context menu
    selectedMessage?.let { msg ->
        val canEdit = msg.direction == "out" && msg.kind == "text" && !msg.deleted &&
            (System.currentTimeMillis() - msg.createdAt) < 48L * 3600L * 1000L
        val canDeleteAll = msg.direction == "out" && !msg.deleted && !isSaved
        val fileMeta = msg.fileJson?.let { runCatching { AuroraJson.decodeFromString<FileAttachmentMeta>(it) }.getOrNull() }

        ModalBottomSheet(onDismissRequest = { selectedMessage = null }) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                if (!msg.deleted) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        QuickEmojis.take(6).forEach { emoji ->
                            Text(
                                text = emoji,
                                fontSize = 26.sp,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable {
                                        viewModel.react(msg.msgId, emoji)
                                        selectedMessage = null
                                    }
                                    .padding(6.dp),
                            )
                        }
                    }
                    HorizontalDivider()
                }
                ContextOption(stringResource(R.string.reply)) {
                    viewModel.setReplyTo(msg)
                    selectedMessage = null
                }
                if (!msg.text.isNullOrBlank() && !msg.deleted) {
                    ContextOption(stringResource(R.string.copy)) {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Message", msg.text))
                        selectedMessage = null
                    }
                }
                if (canEdit) {
                    ContextOption(stringResource(R.string.edit)) {
                        viewModel.startEditing(msg)
                        selectedMessage = null
                    }
                }
                if (!msg.deleted) {
                    ContextOption(stringResource(R.string.forward)) {
                        forwardMessageTarget = msg
                        selectedMessage = null
                    }
                    if (!isSaved) {
                        ContextOption(stringResource(R.string.add_to_saved)) {
                            viewModel.saveToSavedMessages(msg)
                            selectedMessage = null
                        }
                    }
                }
                if (fileMeta != null && !msg.deleted) {
                    ContextOption(stringResource(R.string.save_to_downloads)) {
                        viewModel.saveToDownloads(fileMeta)
                        selectedMessage = null
                    }
                }
                ContextOption(stringResource(R.string.delete_for_me)) {
                    viewModel.deleteForMe(msg.localId)
                    selectedMessage = null
                }
                if (canDeleteAll) {
                    ContextOption(stringResource(R.string.delete_for_everyone), isDestructive = true) {
                        viewModel.deleteForEveryone(msg.msgId)
                        selectedMessage = null
                    }
                }
            }
        }
    }

    // Forward target picker
    forwardMessageTarget?.let { targetMsg ->
        AlertDialog(
            onDismissRequest = { forwardMessageTarget = null },
            title = { Text(stringResource(R.string.forward)) },
            text = {
                LazyColumn(modifier = Modifier.height(280.dp)) {
                    itemsIndexed(allChats) { _, c ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.forwardMessageTo(targetMsg, c.chatId)
                                    forwardMessageTarget = null
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AvatarView(
                                id = c.chatId,
                                name = c.title,
                                avatarLocalPath = allUsers[c.chatId]?.avatarLocalPath,
                                size = 38.dp,
                                isGroup = c.isGroup,
                                isSavedMessages = c.chatId == ChatConstants.SAVED_CHAT_ID,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(c.title, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { forwardMessageTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // Fullscreen zoomable image viewer
    fullscreenImagePath?.let { path ->
        ZoomableImageDialog(
            imagePath = path,
            onDismiss = { fullscreenImagePath = null },
        )
    }

    // Video player (Media3 ExoPlayer)
    videoPlayerPath?.let { path ->
        VideoPlayerDialog(
            videoPath = path,
            onDismiss = { videoPlayerPath = null },
        )
    }
}

@Composable
private fun FileBubbleContent(
    meta: FileAttachmentMeta,
    fileEntity: FileEntity?,
    onDownload: () -> Unit,
) {
    val localPath = fileEntity?.localPath ?: meta.localPath
    val isDownloaded = localPath != null && File(localPath).exists()
    val state = fileEntity?.downloadState ?: "none"

    when {
        meta.mime.startsWith("image/") -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(
                        if ((meta.width ?: 0) > 0 && (meta.height ?: 0) > 0) {
                            (meta.width!!.toFloat() / meta.height!!.toFloat()).coerceIn(0.6f, 1.8f)
                        } else 1.2f
                    )
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center,
            ) {
                if (isDownloaded) {
                    AsyncImage(
                        model = File(localPath!!),
                        contentDescription = meta.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    meta.thumb?.let { b64 ->
                        val bmp = remember(b64) {
                            runCatching {
                                val bytes = Base64.getDecoder().decode(b64)
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            }.getOrNull()
                        }
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = meta.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Surface(
                        color = Color.Black.copy(alpha = 0.55f),
                        shape = CircleShape,
                        modifier = Modifier.clickable(onClick = onDownload),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = "Download", tint = Color.White)
                            Spacer(Modifier.width(6.dp))
                            Text(formatFileSize(meta.size), color = Color.White, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                if (state == "downloading" || state == "uploading") {
                    LinearProgressIndicator(
                        progress = { fileEntity?.progress ?: 0f },
                        modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
                    )
                }
            }
        }

        meta.mime.startsWith("audio/") -> {
            VoiceMessageRow(
                meta = meta,
                localPath = if (isDownloaded) localPath else null,
                isDownloading = state == "downloading",
                onDownload = onDownload,
            )
        }

        else -> {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    color = AuroraAccent.copy(alpha = 0.2f),
                    shape = CircleShape,
                    modifier = Modifier.size(42.dp).clickable(onClick = onDownload),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = when {
                                meta.mime.startsWith("video/") && isDownloaded -> Icons.Filled.PlayArrow
                                isDownloaded -> Icons.Filled.InsertDriveFile
                                else -> Icons.Filled.Download
                            },
                            contentDescription = meta.name,
                            tint = AuroraAccent,
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(meta.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    Text(formatFileSize(meta.size), style = MaterialTheme.typography.labelMedium)
                    if (state == "downloading" || state == "uploading") {
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(progress = { fileEntity?.progress ?: 0f }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceMessageRow(
    meta: FileAttachmentMeta,
    localPath: String?,
    isDownloading: Boolean,
    onDownload: () -> Unit,
) {
    var playing by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { player?.release() }
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconButton(
            onClick = {
                if (localPath == null) {
                    onDownload()
                } else if (playing) {
                    runCatching { player?.pause() }
                    playing = false
                } else {
                    if (player == null) {
                        player = MediaPlayer().apply {
                            setDataSource(localPath)
                            setOnCompletionListener { playing = false }
                            prepare()
                        }
                    }
                    player?.start()
                    playing = true
                }
            },
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(AuroraAccent),
        ) {
            Icon(
                imageVector = when {
                    localPath == null -> Icons.Filled.Download
                    playing -> Icons.Filled.Pause
                    else -> Icons.Filled.PlayArrow
                },
                contentDescription = "Voice playback",
                tint = Color.White,
            )
        }
        Column {
            Text(
                text = if (isDownloading) "Downloading…" else stringResource(R.string.voice_preview),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            val secs = ((meta.durationMs ?: 0L) / 1000L).toInt()
            Text(
                text = "%02d:%02d".format(secs / 60, secs % 60),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun DatePill(tsMs: Long) {
    val label = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(tsMs))
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = Color.Black.copy(alpha = 0.35f),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text(
                text = label,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
fun ContextOption(
    title: String,
    isDestructive: Boolean = false,
    onClick: () -> Unit,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    )
}

@Composable
fun ZoomableImageDialog(
    imagePath: String,
    onDismiss: () -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 4f)
                    }
                },
        ) {
            AsyncImage(
                model = File(imagePath),
                contentDescription = "Full screen image",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(scaleX = scale, scaleY = scale),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}

@Composable
fun VideoPlayerDialog(
    videoPath: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val exoPlayer = remember(videoPath) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(videoPath))))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply { player = exoPlayer }
                },
                modifier = Modifier.fillMaxSize(),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}

private fun isSameDay(ts1: Long, ts2: Long): Boolean {
    val fmt = SimpleDateFormat("yyyyMMdd", Locale.US)
    return fmt.format(Date(ts1)) == fmt.format(Date(ts2))
}

fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "%.1f MB".format(bytes / (1024f * 1024f))
    }
}
