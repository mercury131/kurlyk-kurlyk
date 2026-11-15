package com.example.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.db.UserEntity
import com.example.ui.components.AvatarView
import com.example.ui.theme.LocalAuroraChatColors
import com.example.ui.theme.OnlineGreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    myPhone: String,
    users: List<UserEntity>,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelectUser: (String) -> Unit,
    onNewGroup: () -> Unit,
) {
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val chatColors = LocalAuroraChatColors.current

    val filtered = remember(users, myPhone, query) {
        users.filter { u ->
            u.phone != myPhone && (
                query.isBlank() ||
                    u.displayName.contains(query, ignoreCase = true) ||
                    u.serverName.contains(query, ignoreCase = true) ||
                    u.phone.contains(query, ignoreCase = true)
                )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    if (searchOpen) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text(stringResource(R.string.search_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(stringResource(R.string.contacts))
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = {
                        searchOpen = !searchOpen
                        if (!searchOpen) query = ""
                    }) {
                        Icon(if (searchOpen) Icons.Filled.Close else Icons.Filled.Search, contentDescription = "Search")
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onNewGroup)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.GroupAdd,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = stringResource(R.string.new_group),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                HorizontalDivider(color = chatColors.divider)
                Text(
                    text = stringResource(R.string.on_this_server),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }

            if (filtered.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.no_contacts_found),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            } else {
                items(filtered, key = { it.phone }) { user ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectUser(user.phone) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AvatarView(
                            id = user.phone,
                            name = user.displayName,
                            avatarLocalPath = user.avatarLocalPath,
                            size = 48.dp,
                            isOnline = user.online,
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = user.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            val subtitle = buildString {
                                if (!user.contactName.isNullOrBlank() && user.serverName.isNotBlank()) {
                                    append("${user.serverName} • ")
                                }
                                if (user.online) {
                                    append(stringResource(R.string.online))
                                } else if (user.lastSeen > 0L) {
                                    val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                                    append(stringResource(R.string.last_seen_at, fmt.format(Date(user.lastSeen * 1000L))))
                                } else {
                                    append(user.phone)
                                }
                            }
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (user.online) OnlineGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
