package com.example.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val phone: String,
    val serverName: String,
    val contactName: String? = null,
    val online: Boolean = false,
    val lastSeen: Long = 0L,
    val avatarVer: Long = 0L,
    val avatarLocalPath: String? = null,
) {
    val displayName: String
        get() = contactName?.takeIf { it.isNotBlank() } ?: serverName.ifBlank { phone }
}

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val chatId: String,
    val title: String,
    val isGroup: Boolean = false,
    val lastMessageId: String? = null,
    val lastMessagePreview: String = "",
    val lastMessageTs: Long = 0L,
    val lastMessageDirection: String = "",
    val lastMessageStatus: String = "",
    val unreadCount: Int = 0,
    val pinned: Boolean = false,
    val muted: Boolean = false,
    val archived: Boolean = false,
)

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val owner: String,
    val membersJson: String,
    val isMember: Boolean = true,
    val createdAt: String = "",
    val updatedAt: String = "",
)

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["sender", "msgId"], unique = true),
        Index(value = ["chatId", "createdAt"]),
    ],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val msgId: String,
    val chatId: String,
    val sender: String,
    val kind: String,
    val text: String? = null,
    val fileJson: String? = null,
    val callLogJson: String? = null,
    val replyTo: String? = null,
    val createdAt: Long,
    val status: String, // pending | sent | delivered | read | failed
    val editedAt: Long? = null,
    val deleted: Boolean = false,
    val reactionsJson: String = "{}",
    val direction: String, // in | out
    val decryptFailed: Boolean = false,
)

@Entity(
    tableName = "receipts",
    primaryKeys = ["msgId", "member"],
)
data class ReceiptEntity(
    val msgId: String,
    val member: String,
    val status: String, // delivered | read
    val ts: Long = 0L,
)

@Entity(tableName = "files")
data class FileEntity(
    @PrimaryKey val fileId: String,
    val localPath: String? = null,
    val encryptedSize: Long = 0L,
    val mime: String = "application/octet-stream",
    val name: String = "file",
    val downloadState: String = "none", // none | downloading | uploading | downloaded | failed
    val progress: Float = 0f,
)
