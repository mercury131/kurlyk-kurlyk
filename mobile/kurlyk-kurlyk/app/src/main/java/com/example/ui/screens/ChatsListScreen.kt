package com.example.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.MarkChatRead
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.db.ChatEntity
import com.example.data.db.UserEntity
import com.example.data.prefs.AuthSession
import com.example.domain.ChatConstants
import com.example.domain.ChatFilter
import com.example.domain.ConnectionState
import com.example.ui.components.AvatarView
import com.example.ui.components.DoveBadge
import com.example.ui.theme.AuroraAccent
import com.example.ui.theme.AuroraCyan
import com.example.ui.theme.LocalAuroraChatColors
import com.example.ui.theme.ReadBlue
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatsListScreen(
    session: AuthSession?,
    chats: List<ChatEntity>,
    users: List<UserEntity>,
    connectionState: ConnectionState,
    wsError: String?,
    isRefreshing: Boolean,
    askContactsPermission: Boolean,
    onDismissContactsPrompt: () -> Unit,
    onContactsPermissionGranted: () -> Unit,
    onRefresh: () -> Unit,
    onReconnect: () -> Unit,
    onOpenChat: (String) -> Unit,
    onOpenContacts: () -> Unit,
    onOpenNewGroup: () -> Unit,
    onOpenCalls: () -> Unit,
    onOpenSettings: () -> Unit,
    onTogglePin: (ChatEntity) -> Unit,
    onToggleMute: (ChatEntity) -> Unit,
    onToggleArchive: (ChatEntity) -> Unit,
    onMarkRead: (ChatEntity) -> Unit,
    onDeleteChat: (ChatEntity) -> Unit,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val chatColors = LocalAuroraChatColors.current

    var selectedFilter by remember { mutableStateOf(ChatFilter.ALL) }
    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showArchived by remember { mutableStateOf(false) }
    var selectedChatIds by remember { mutableStateOf(setOf<String>()) }

    val usersByPhone = remember(users) { users.associateBy { it.phone } }
    val me = usersByPhone[session?.phone]

    val contactsPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onContactsPermissionGranted()
        onDismissContactsPrompt()
    }

    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    if (askContactsPermission) {
        AlertDialog(
            onDismissRequest = onDismissContactsPrompt,
            title = { Text(stringResource(R.string.contacts_permission_title)) },
            text = { Text(stringResource(R.string.contacts_permission_rationale)) },
            confirmButton = {
                Button(onClick = { contactsPermLauncher.launch(Manifest.permission.READ_CONTACTS) }) {
                    Text(stringResource(R.string.allow))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissContactsPrompt) {
                    Text(stringResource(R.string.not_now))
                }
            },
        )
    }

    val archivedCount = remember(chats) { chats.count { it.archived } }
    val filteredChats = remember(chats, selectedFilter, searchQuery, showArchived) {
        chats.filter { chat ->
            val matchesArchive = if (showArchived) chat.archived else !chat.archived
            val matchesFilter = when (selectedFilter) {
                ChatFilter.ALL -> true
                ChatFilter.PERSONAL -> !chat.isGroup
                ChatFilter.GROUPS -> chat.isGroup
            }
            val matchesSearch = if (searchQuery.isBlank()) true else {
                chat.title.contains(searchQuery, ignoreCase = true) ||
                    chat.chatId.contains(searchQuery, ignoreCase = true) ||
                    chat.lastMessagePreview.contains(searchQuery, ignoreCase = true)
            }
            matchesArchive && matchesFilter && matchesSearch
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Brush.linearGradient(listOf(AuroraAccent, AuroraCyan)))
                        .padding(horizontal = 20.dp, vertical = 28.dp),
                ) {
                    Column {
                        AvatarView(
                            id = session?.phone.orEmpty(),
                            name = me?.displayName ?: session?.name.orEmpty(),
                            avatarLocalPath = me?.avatarLocalPath,
                            size = 64.dp,
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = me?.displayName ?: session?.name.orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = session?.phone.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.85f),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.new_group)) },
                    icon = { Icon(Icons.Filled.GroupAdd, contentDescription = null) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenNewGroup()
                    },
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.contacts)) },
                    icon = { Icon(Icons.Filled.Contacts, contentDescription = null) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenContacts()
                    },
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.calls)) },
                    icon = { Icon(Icons.Filled.Call, contentDescription = null) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenCalls()
                    },
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.saved_messages)) },
                    icon = { Icon(Icons.Filled.Bookmark, contentDescription = null) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenChat(ChatConstants.SAVED_CHAT_ID)
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.settings)) },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenSettings()
                    },
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                if (selectedChatIds.isNotEmpty()) {
                    TopAppBar(
                        title = { Text(stringResource(R.string.selected_count, selectedChatIds.size)) },
                        navigationIcon = {
                            IconButton(onClick = { selectedChatIds = emptySet() }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear selection")
                            }
                        },
                        actions = {
                            IconButton(onClick = {
                                selectedChatIds.forEach { id -> chats.find { it.chatId == id }?.let(onTogglePin) }
                                selectedChatIds = emptySet()
                            }) {
                                Icon(Icons.Filled.PushPin, contentDescription = stringResource(R.string.pin))
                            }
                            IconButton(onClick = {
                                selectedChatIds.forEach { id -> chats.find { it.chatId == id }?.let(onToggleMute) }
                                selectedChatIds = emptySet()
                            }) {
                                Icon(Icons.AutoMirrored.Filled.VolumeOff, contentDescription = stringResource(R.string.mute))
                            }
                            IconButton(onClick = {
                                selectedChatIds.forEach { id -> chats.find { it.chatId == id }?.let(onMarkRead) }
                                selectedChatIds = emptySet()
                            }) {
                                Icon(Icons.Filled.MarkChatRead, contentDescription = stringResource(R.string.mark_read))
                            }
                            IconButton(onClick = {
                                selectedChatIds.forEach { id ->
                                    if (id != ChatConstants.SAVED_CHAT_ID) {
                                        chats.find { it.chatId == id }?.let(onDeleteChat)
                                    }
                                }
                                selectedChatIds = emptySet()
                            }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete")
                            }
                        },
                    )
                } else {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                        navigationIcon = {
                            IconButton(
                                onClick = { scope.launch { drawerState.open() } },
                                modifier = Modifier.testTag("drawer_menu_button"),
                            ) {
                                Icon(Icons.Filled.Menu, contentDescription = "Menu")
                            }
                        },
                        title = {
                            if (searchActive) {
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    placeholder = { Text(stringResource(R.string.search_hint)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                Column {
                                    Text(
                                        text = if (showArchived) stringResource(R.string.archived_chats)
                                        else stringResource(R.string.app_name),
                                        style = MaterialTheme.typography.titleLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val statusSubtitle = when (connectionState) {
                                        ConnectionState.CONNECTING -> stringResource(R.string.connecting)
                                        ConnectionState.WAITING_FOR_NETWORK -> stringResource(R.string.waiting_for_network)
                                        ConnectionState.REPLACED -> stringResource(R.string.signed_in_elsewhere)
                                        ConnectionState.DISCONNECTED -> wsError ?: ""
                                        ConnectionState.CONNECTED -> ""
                                    }
                                    if (statusSubtitle.isNotBlank()) {
                                        Text(
                                            text = statusSubtitle,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        },
                        actions = {
                            if (archivedCount > 0 || showArchived) {
                                IconButton(onClick = { showArchived = !showArchived }) {
                                    Icon(Icons.Filled.Archive, contentDescription = stringResource(R.string.archived_chats))
                                }
                            }
                            IconButton(onClick = {
                                searchActive = !searchActive
                                if (!searchActive) searchQuery = ""
                            }) {
                                Icon(
                                    if (searchActive) Icons.Filled.Close else Icons.Filled.Search,
                                    contentDescription = "Search",
                                )
                            }
                        },
                    )
                }
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = onOpenContacts,
                    containerColor = AuroraAccent,
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier.testTag("new_chat_fab"),
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.new_message))
                }
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                AnimatedVisibility(visible = connectionState == ConnectionState.REPLACED) {
                    Surface(
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.16f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = stringResource(R.string.signed_in_elsewhere),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onReconnect) {
                                Text(stringResource(R.string.reconnect))
                            }
                        }
                    }
                }

                // Filter tabs: All / Personal / Groups
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = selectedFilter == ChatFilter.ALL,
                        onClick = { selectedFilter = ChatFilter.ALL },
                        label = { Text(stringResource(R.string.tab_all)) },
                    )
                    FilterChip(
                        selected = selectedFilter == ChatFilter.PERSONAL,
                        onClick = { selectedFilter = ChatFilter.PERSONAL },
                        label = { Text(stringResource(R.string.tab_personal)) },
                    )
                    FilterChip(
                        selected = selectedFilter == ChatFilter.GROUPS,
                        onClick = { selectedFilter = ChatFilter.GROUPS },
                        label = { Text(stringResource(R.string.tab_groups)) },
                    )
                }

                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (filteredChats.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            DoveBadge(size = 84.dp)
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = stringResource(R.string.no_chats_yet),
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.no_chats_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 88.dp),
                        ) {
                            items(filteredChats, key = { it.chatId }) { chat ->
                                val peer = if (!chat.isGroup && chat.chatId != ChatConstants.SAVED_CHAT_ID) {
                                    usersByPhone[chat.chatId]
                                } else null
                                val isSelected = selectedChatIds.contains(chat.chatId)

                                val dismissState = rememberSwipeToDismissBoxState(
                                    confirmValueChange = { value ->
                                        when (value) {
                                            SwipeToDismissBoxValue.StartToEnd -> {
                                                onTogglePin(chat)
                                                false
                                            }
                                            SwipeToDismissBoxValue.EndToStart -> {
                                                if (chat.chatId != ChatConstants.SAVED_CHAT_ID) {
                                                    onToggleArchive(chat)
                                                }
                                                false
                                            }
                                            else -> false
                                        }
                                    },
                                )

                                SwipeToDismissBox(
                                    state = dismissState,
                                    backgroundContent = {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                                .padding(horizontal = 20.dp),
                                            contentAlignment = Alignment.CenterStart,
                                        ) {
                                            Icon(Icons.Filled.PushPin, contentDescription = null)
                                        }
                                    },
                                ) {
                                    ChatRow(
                                        chat = chat,
                                        peer = peer,
                                        isSelected = isSelected,
                                        modifier = Modifier
                                            .background(
                                                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                                else MaterialTheme.colorScheme.background
                                            )
                                            .combinedClickable(
                                                onClick = {
                                                    if (selectedChatIds.isNotEmpty()) {
                                                        selectedChatIds = if (isSelected) selectedChatIds - chat.chatId
                                                        else selectedChatIds + chat.chatId
                                                    } else {
                                                        onOpenChat(chat.chatId)
                                                    }
                                                },
                                                onLongClick = {
                                                    selectedChatIds = selectedChatIds + chat.chatId
                                                },
                                            ),
                                    )
                                }
                                HorizontalDivider(
                                    color = chatColors.divider,
                                    modifier = Modifier.padding(start = 82.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatRow(
    chat: ChatEntity,
    peer: UserEntity?,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
) {
    val isSaved = chat.chatId == ChatConstants.SAVED_CHAT_ID
    val displayTitle = when {
        isSaved -> stringResource(R.string.saved_messages)
        peer != null -> peer.displayName
        else -> chat.title
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarView(
            id = chat.chatId,
            name = displayTitle,
            avatarLocalPath = peer?.avatarLocalPath,
            size = 56.dp,
            isOnline = peer?.online == true,
            isGroup = chat.isGroup,
            isSavedMessages = isSaved,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (chat.muted) {
                    Icon(
                        Icons.AutoMirrored.Filled.VolumeOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp).padding(end = 4.dp),
                    )
                }
                if (chat.lastMessageDirection == "out" && !isSaved) {
                    MessageStatusIcon(status = chat.lastMessageStatus)
                    Spacer(Modifier.width(4.dp))
                }
                if (chat.lastMessageId != null && chat.lastMessageTs > 0L) {
                    Text(
                        text = formatShortTime(chat.lastMessageTs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val preview = when {
                    isSaved && chat.lastMessagePreview.isBlank() -> stringResource(R.string.saved_messages_subtitle)
                    chat.lastMessageDirection == "out" && !isSaved && chat.lastMessagePreview.isNotBlank() ->
                        "${stringResource(R.string.you_prefix)} ${chat.lastMessagePreview}"
                    else -> chat.lastMessagePreview
                }
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (chat.unreadCount > 0) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (chat.muted) MaterialTheme.colorScheme.onSurfaceVariant else AuroraAccent)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = chat.unreadCount.toString(),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                } else if (chat.pinned && !isSaved) {
                    Icon(
                        Icons.Filled.PushPin,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun MessageStatusIcon(status: String, tintOverride: Color? = null) {
    val defaultTint = tintOverride ?: MaterialTheme.colorScheme.onSurfaceVariant
    when (status) {
        "pending" -> Icon(Icons.Filled.Schedule, contentDescription = "Pending", tint = defaultTint, modifier = Modifier.size(15.dp))
        "sent" -> Icon(Icons.Filled.Done, contentDescription = "Sent", tint = defaultTint, modifier = Modifier.size(15.dp))
        "delivered" -> Icon(Icons.Filled.DoneAll, contentDescription = "Delivered", tint = defaultTint, modifier = Modifier.size(15.dp))
        "read" -> Icon(Icons.Filled.DoneAll, contentDescription = "Read", tint = ReadBlue, modifier = Modifier.size(15.dp))
        else -> {}
    }
}

fun formatShortTime(tsMs: Long): String {
    val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    return fmt.format(Date(tsMs))
}
