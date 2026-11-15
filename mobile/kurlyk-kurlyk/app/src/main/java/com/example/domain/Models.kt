package com.example.domain

import kotlinx.serialization.Serializable

@Serializable
data class FileAttachmentMeta(
    val fileId: String,
    val name: String,
    val mime: String,
    val size: Long,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val thumb: String? = null,
    val caption: String? = null,
    val localPath: String? = null,
)

@Serializable
data class CallLogMeta(
    val callId: String,
    val video: Boolean,
    val result: String, // missed | declined | ended
    val durationSec: Int = 0,
)

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    WAITING_FOR_NETWORK,
    REPLACED,
}

enum class ChatFilter {
    ALL,
    PERSONAL,
    GROUPS,
}

object ChatConstants {
    const val SAVED_CHAT_ID = "saved"
    const val GROUP_PREFIX = "group:"

    fun isGroupChat(chatId: String): Boolean = chatId.startsWith(GROUP_PREFIX)
    fun extractGroupId(chatId: String): String = chatId.removePrefix(GROUP_PREFIX)
    fun toGroupChatId(groupId: String): String = "$GROUP_PREFIX$groupId"
}
