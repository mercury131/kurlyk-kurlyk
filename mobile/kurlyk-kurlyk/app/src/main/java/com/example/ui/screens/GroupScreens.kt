package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.api.AuroraJson
import com.example.data.db.GroupEntity
import com.example.data.db.UserEntity
import com.example.ui.components.AvatarView
import com.example.ui.theme.AuroraAccent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupScreen(
    myPhone: String,
    users: List<UserEntity>,
    onBack: () -> Unit,
    onCreate: (name: String, members: List<String>) -> Unit,
) {
    var groupName by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val selectable = remember(users, myPhone) { users.filter { it.phone != myPhone } }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text(stringResource(R.string.new_group)) },
            )
        },
        floatingActionButton = {
            if (groupName.isNotBlank()) {
                FloatingActionButton(
                    onClick = { onCreate(groupName.trim(), selected.toList()) },
                    containerColor = AuroraAccent,
                    contentColor = Color.White,
                ) {
                    Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.create_group))
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = groupName,
                onValueChange = { groupName = it },
                label = { Text(stringResource(R.string.group_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            Text(
                text = stringResource(R.string.select_members),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(selectable, key = { it.phone }) { user ->
                    val checked = selected.contains(user.phone)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = if (checked) selected - user.phone else selected + user.phone
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = {
                                selected = if (checked) selected - user.phone else selected + user.phone
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        AvatarView(
                            id = user.phone,
                            name = user.displayName,
                            avatarLocalPath = user.avatarLocalPath,
                            size = 44.dp,
                            isOnline = user.online,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(user.displayName, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoScreen(
    group: GroupEntity?,
    myPhone: String,
    usersByPhone: Map<String, UserEntity>,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    onAddMembers: (List<String>) -> Unit,
    onRemoveMember: (String) -> Unit,
    onLeaveGroup: () -> Unit,
    onDeleteGroup: () -> Unit,
    onOpenUserProfile: (String) -> Unit,
) {
    if (group == null) {
        onBack()
        return
    }
    val members = remember(group.membersJson) {
        runCatching { AuroraJson.decodeFromString<List<String>>(group.membersJson) }.getOrDefault(emptyList())
    }
    val isOwner = group.owner == myPhone
    var showRenameDialog by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var newNameInput by remember(group.name) { mutableStateOf(group.name) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text(stringResource(R.string.group_info)) },
                actions = {
                    if (isOwner) {
                        IconButton(onClick = { showRenameDialog = true }) {
                            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.rename_group))
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
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AvatarView(
                        id = "group:${group.id}",
                        name = group.name,
                        avatarLocalPath = null,
                        size = 88.dp,
                        isGroup = true,
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(group.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        text = stringResource(R.string.members_count, members.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
                if (group.isMember) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAddDialog = true }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.PersonAdd, contentDescription = null, tint = AuroraAccent)
                        Spacer(Modifier.width(14.dp))
                        Text(stringResource(R.string.add_members), color = AuroraAccent, style = MaterialTheme.typography.titleMedium)
                    }
                    HorizontalDivider()
                }
            }

            items(members) { phone ->
                val user = usersByPhone[phone]
                val dispName = user?.displayName ?: phone
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { if (phone != myPhone) onOpenUserProfile(phone) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AvatarView(
                        id = phone,
                        name = dispName,
                        avatarLocalPath = user?.avatarLocalPath,
                        size = 44.dp,
                        isOnline = user?.online == true,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(dispName, style = MaterialTheme.typography.titleMedium)
                        Text(phone, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (phone == group.owner) {
                        Surface(
                            color = AuroraAccent.copy(alpha = 0.16f),
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(
                                text = stringResource(R.string.owner_badge),
                                color = AuroraAccent,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    } else if (isOwner && group.isMember) {
                        IconButton(onClick = { onRemoveMember(phone) }) {
                            Icon(Icons.Filled.RemoveCircleOutline, contentDescription = stringResource(R.string.remove_member), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (group.isMember) {
                        OutlinedButton(
                            onClick = onLeaveGroup,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.leave_group))
                        }
                    }
                    if (isOwner) {
                        Button(
                            onClick = onDeleteGroup,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.delete_group))
                        }
                    }
                }
            }
        }
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(stringResource(R.string.rename_group)) },
            text = {
                OutlinedTextField(
                    value = newNameInput,
                    onValueChange = { newNameInput = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (newNameInput.isNotBlank()) onRename(newNameInput.trim())
                    showRenameDialog = false
                }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showAddDialog) {
        val candidates = usersByPhone.values.filter { !members.contains(it.phone) }
        var picked by remember { mutableStateOf(setOf<String>()) }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(stringResource(R.string.add_members)) },
            text = {
                LazyColumn(modifier = Modifier.height(260.dp)) {
                    items(candidates, key = { it.phone }) { u ->
                        val isPicked = picked.contains(u.phone)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { picked = if (isPicked) picked - u.phone else picked + u.phone }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = isPicked,
                                onCheckedChange = { picked = if (isPicked) picked - u.phone else picked + u.phone },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(u.displayName)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = picked.isNotEmpty(),
                    onClick = {
                        onAddMembers(picked.toList())
                        showAddDialog = false
                    },
                ) {
                    Text(stringResource(R.string.add_members))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
