package com.example.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

val AuroraJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

// --- REST DTOs ---

@Serializable
data class AuthRequest(
    val phone: String,
    val name: String,
    val ts: Long,
    val nonce: String,
    val sig: String,
)

@Serializable
data class UserDto(
    val phone: String,
    val name: String = "",
    val online: Boolean = false,
    @SerialName("last_seen") val lastSeen: Long = 0L,
    @SerialName("avatar_ver") val avatarVer: Long = 0L,
)

@Serializable
data class AuthResponse(
    val token: String,
    val expires: Long = 0L,
    val me: UserDto,
)

@Serializable
data class UpdateMeRequest(
    val name: String,
)

@Serializable
data class UsersResponse(
    val users: List<UserDto> = emptyList(),
)

@Serializable
data class IceServerDto(
    val urls: List<String> = emptyList(),
    val username: String? = null,
    val credential: String? = null,
)

@Serializable
data class ConfigResponse(
    val version: String = "1.0",
    @SerialName("server_time") val serverTime: Long = 0L,
    @SerialName("max_file_mb") val maxFileMb: Int = 100,
    @SerialName("file_ttl_h") val fileTtlH: Int = 168,
    @SerialName("queue_ttl_h") val queueTtlH: Int = 720,
    @SerialName("max_users") val maxUsers: Int = 100,
    @SerialName("ws_url") val wsUrl: String = "",
    @SerialName("ice_servers") val iceServers: List<IceServerDto> = emptyList(),
)

@Serializable
data class TurnResponse(
    @SerialName("ice_servers") val iceServers: List<IceServerDto> = emptyList(),
    val ttl: Long = 86400L,
)

@Serializable
data class GroupDto(
    val id: String,
    val name: String,
    val owner: String,
    val members: List<String> = emptyList(),
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class GroupsResponse(
    val groups: List<GroupDto> = emptyList(),
)

@Serializable
data class CreateGroupRequest(
    val name: String,
    val members: List<String>,
)

@Serializable
data class PatchGroupRequest(
    val name: String? = null,
    val add: List<String>? = null,
    val remove: List<String>? = null,
)

@Serializable
data class FileUploadResponse(
    val id: String,
    val size: Long,
    val expires: Long = 0L,
)

@Serializable
data class OkResponse(
    val ok: Boolean = true,
)

@Serializable
data class ErrorResponse(
    val error: String = "",
)

// --- WebSocket Outgoing Frames ---

@Serializable
data class WsOutMsg(
    val type: String = "msg",
    val id: String,
    val to: String,
    val payload: String,
)

@Serializable
data class WsOutAck(
    val type: String = "ack",
    val qids: List<String>,
)

@Serializable
data class WsOutRead(
    val type: String = "read",
    val to: String,
    val ids: List<String>,
)

@Serializable
data class WsOutTyping(
    val type: String = "typing",
    val to: String,
)

@Serializable
data class WsOutCall(
    val type: String = "call",
    val to: String,
    @SerialName("call_id") val callId: String,
    val action: String,
    val data: JsonObject? = null,
)

@Serializable
data class WsOutPing(
    val type: String = "ping",
)

// --- WebSocket Incoming Frames ---

@Serializable
data class WsRawEvent(
    val type: String,
    val qid: String? = null,
    val id: String? = null,
    val phone: String? = null,
    val from: String? = null,
    val to: String? = null,
    val group: JsonElement? = null,
    val payload: String? = null,
    val ts: Long? = null,
    @SerialName("server_time") val serverTime: Long? = null,
    val ids: List<String>? = null,
    val by: String? = null,
    val status: String? = null,
    val event: String? = null,
    val online: Boolean? = null,
    @SerialName("last_seen") val lastSeen: Long? = null,
    val user: UserDto? = null,
    @SerialName("call_id") val callId: String? = null,
    val action: String? = null,
    val data: JsonObject? = null,
    val code: String? = null,
    val message: String? = null,
)

// --- Encrypted Message Body (Section 6.3, camelCase) ---

@Serializable
data class EncryptedMessageBody(
    val kind: String,
    val msgTs: Long? = null,
    val replyTo: String? = null,
    // kind = "text" or "edit"
    val text: String? = null,
    // kind = "file"
    val fileId: String? = null,
    val name: String? = null,
    val mime: String? = null,
    val size: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val thumb: String? = null,
    val caption: String? = null,
    // kind = "edit" | "delete" | "reaction"
    val target: String? = null,
    // kind = "reaction"
    val emoji: String? = null,
    // kind = "call_log"
    val callId: String? = null,
    val video: Boolean? = null,
    val result: String? = null,
    val durationSec: Int? = null,
)
