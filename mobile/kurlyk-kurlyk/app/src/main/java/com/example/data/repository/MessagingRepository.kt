package com.example.data.repository

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.example.R
import com.example.data.api.AuroraApiClient
import com.example.data.api.AuroraJson
import com.example.data.api.ConfigResponse
import com.example.data.api.EncryptedMessageBody
import com.example.data.api.GroupDto
import com.example.data.api.TurnResponse
import com.example.data.api.UserDto
import com.example.data.api.WsRawEvent
import com.example.data.contacts.ContactsSyncManager
import com.example.data.crypto.AuroraCrypto
import com.example.data.db.AuroraDatabase
import com.example.data.db.ChatDao
import com.example.data.db.ChatEntity
import com.example.data.db.FileDao
import com.example.data.db.FileEntity
import com.example.data.db.GroupDao
import com.example.data.db.GroupEntity
import com.example.data.db.MessageDao
import com.example.data.db.MessageEntity
import com.example.data.db.ReceiptDao
import com.example.data.db.ReceiptEntity
import com.example.data.db.UserDao
import com.example.data.db.UserEntity
import com.example.data.prefs.AppSettings
import com.example.data.prefs.AuthSession
import com.example.data.prefs.SessionPrefs
import com.example.data.prefs.ThemeMode
import com.example.data.ws.WebSocketManager
import com.example.domain.CallLogMeta
import com.example.domain.ChatConstants
import com.example.domain.ConnectionState
import com.example.domain.FileAttachmentMeta
import com.example.service.NotificationHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessagingRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AuroraDatabase,
    private val userDao: UserDao,
    private val chatDao: ChatDao,
    private val groupDao: GroupDao,
    private val messageDao: MessageDao,
    private val receiptDao: ReceiptDao,
    private val fileDao: FileDao,
    private val apiClient: AuroraApiClient,
    private val webSocketManager: WebSocketManager,
    private val sessionPrefs: SessionPrefs,
    private val contactsSyncManager: ContactsSyncManager,
    private val notificationHelper: NotificationHelper,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val session: StateFlow<AuthSession?> = sessionPrefs.session
    val settings: Flow<AppSettings> = sessionPrefs.settings
    val connectionState: StateFlow<ConnectionState> = webSocketManager.connectionState
    val wsError: StateFlow<String?> = webSocketManager.lastError
    val serverClockOffsetMs: StateFlow<Long> = webSocketManager.serverClockOffsetMs

    private val _typingByChat = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val typingByChat: StateFlow<Map<String, Set<String>>> = _typingByChat.asStateFlow()

    private val typingExpiryJobs = mutableMapOf<Pair<String, String>, Job>()
    private val lastTypingSentByChat = mutableMapOf<String, Long>()

    @Volatile
    var activeChatId: String? = null
        private set

    init {
        webSocketManager.setEventProcessor { event ->
            handleWsEvent(event)
        }
        webSocketManager.setOnConnectedCallback {
            flushOutbox()
            scope.launch {
                runCatching { refreshDirectory() }
            }
        }
    }

    fun setCallSignalHandler(handler: (WsRawEvent) -> Unit) {
        webSocketManager.setCallSignalHandler(handler)
    }

    fun sendCallSignal(to: String, callId: String, action: String, data: JsonObject? = null): Boolean {
        return webSocketManager.sendCallSignal(to, callId, action, data)
    }

    fun observeChats(): Flow<List<ChatEntity>> = chatDao.observeAllChats()
    fun observeChat(chatId: String): Flow<ChatEntity?> = chatDao.observeChat(chatId)
    fun observeUsers(): Flow<List<UserEntity>> = userDao.observeAllUsers()
    fun observeUser(phone: String): Flow<UserEntity?> = userDao.observeUser(phone)
    fun observeGroup(groupId: String): Flow<GroupEntity?> = groupDao.observeGroup(groupId)
    fun observeMessages(chatId: String): Flow<List<MessageEntity>> = messageDao.observeMessages(chatId)
    fun observeFiles(): Flow<List<FileEntity>> = fileDao.observeAllFiles()
    fun observeCallHistory(): Flow<List<MessageEntity>> = messageDao.observeCallHistory()

    fun currentServerNowMs(): Long = System.currentTimeMillis() + serverClockOffsetMs.value

    suspend fun signIn(
        domain: String,
        secretKey: String,
        phone: String,
        name: String,
    ) {
        val cleanSecret = secretKey.trim()
        val auth = apiClient.authenticate(domain, phone, name, cleanSecret)
        val newSession = AuthSession(
            domain = domain,
            secretKey = cleanSecret,
            phone = auth.me.phone,
            name = auth.me.name.ifBlank { name },
            token = auth.token,
            tokenExpires = auth.expires,
        )
        sessionPrefs.saveSession(newSession)
        ensureSavedMessagesChat()
        upsertUserDto(auth.me)
        runCatching { refreshConfig() }
        runCatching { refreshDirectory() }
        webSocketManager.connect(forceReconnect = true)
    }

    suspend fun ensureInitializedOnAppStart() {
        val current = sessionPrefs.session.value ?: return
        ensureSavedMessagesChat()
        webSocketManager.connect()
        scope.launch {
            runCatching { refreshDirectory() }
            runCatching { contactsSyncManager.syncDeviceContacts() }
        }
    }

    fun reconnectWebSocket() {
        webSocketManager.connect(forceReconnect = true)
    }

    suspend fun ensureSavedMessagesChat() {
        if (chatDao.getChat(ChatConstants.SAVED_CHAT_ID) == null) {
            chatDao.upsertChat(
                ChatEntity(
                    chatId = ChatConstants.SAVED_CHAT_ID,
                    title = context.getString(R.string.saved_messages),
                    isGroup = false,
                    pinned = true,
                    lastMessageTs = Long.MAX_VALUE / 2,
                )
            )
        }
    }

    suspend fun refreshConfig(): ConfigResponse {
        val cfg = apiClient.getConfig()
        sessionPrefs.saveServerConfig(
            version = cfg.version,
            maxFileMb = cfg.maxFileMb,
            fileTtlH = cfg.fileTtlH,
            queueTtlH = cfg.queueTtlH,
            maxUsers = cfg.maxUsers,
        )
        return cfg
    }

    suspend fun fetchTurnServers(): TurnResponse = apiClient.getTurn()

    suspend fun refreshDirectory() {
        if (sessionPrefs.session.value == null) return
        val users = apiClient.getUsers()
        val groups = apiClient.getGroups()
        val existingUsers = userDao.getAllUsersOnce().associateBy { it.phone }

        val updatedUsers = users.map { dto ->
            val prev = existingUsers[dto.phone]
            val localAvatarPath = if (dto.avatarVer > 0L && prev?.avatarVer == dto.avatarVer) {
                prev.avatarLocalPath?.takeIf { File(it).exists() }
            } else {
                null
            }
            UserEntity(
                phone = dto.phone,
                serverName = dto.name,
                contactName = prev?.contactName,
                online = dto.online,
                lastSeen = dto.lastSeen,
                avatarVer = dto.avatarVer,
                avatarLocalPath = localAvatarPath,
            )
        }
        userDao.upsertUsers(updatedUsers)
        contactsSyncManager.syncDeviceContacts()

        // Update 1:1 chat titles from display name
        for (u in userDao.getAllUsersOnce()) {
            val chat = chatDao.getChat(u.phone)
            if (chat != null && chat.title != u.displayName) {
                chatDao.updateTitle(u.phone, u.displayName)
            }
        }

        // Sync groups
        for (g in groups) {
            upsertGroupDto(g, isMember = true)
        }

        // Download changed avatars in background
        for (dto in users) {
            val prev = existingUsers[dto.phone]
            if (dto.avatarVer > 0L) {
                val expected = avatarFileFor(dto.phone, dto.avatarVer)
                if (!expected.exists() || prev?.avatarVer != dto.avatarVer) {
                    scope.launch {
                        runCatching { downloadAndCacheAvatar(dto.phone, dto.avatarVer) }
                    }
                }
            } else if (prev != null && !prev.avatarLocalPath.isNullOrBlank()) {
                deleteOldAvatars(dto.phone, keepVer = 0L)
                userDao.updateAvatar(dto.phone, 0L, null)
            }
        }
    }

    private suspend fun upsertUserDto(dto: UserDto) {
        val prev = userDao.getUser(dto.phone)
        val keptPath = if (dto.avatarVer > 0L && prev?.avatarVer == dto.avatarVer) {
            prev.avatarLocalPath?.takeIf { File(it).exists() }
        } else null
        userDao.upsertUser(
            UserEntity(
                phone = dto.phone,
                serverName = dto.name,
                contactName = prev?.contactName,
                online = dto.online,
                lastSeen = dto.lastSeen,
                avatarVer = dto.avatarVer,
                avatarLocalPath = keptPath,
            )
        )
        if (dto.avatarVer > 0L && keptPath == null) {
            scope.launch {
                runCatching { downloadAndCacheAvatar(dto.phone, dto.avatarVer) }
            }
        } else if (dto.avatarVer == 0L) {
            deleteOldAvatars(dto.phone, 0L)
        }
    }

    private suspend fun upsertGroupDto(dto: GroupDto, isMember: Boolean) {
        val membersJson = AuroraJson.encodeToString(dto.members)
        groupDao.upsertGroup(
            GroupEntity(
                id = dto.id,
                name = dto.name,
                owner = dto.owner,
                membersJson = membersJson,
                isMember = isMember,
                createdAt = dto.createdAt,
                updatedAt = dto.updatedAt,
            )
        )
        val chatId = ChatConstants.toGroupChatId(dto.id)
        val existingChat = chatDao.getChat(chatId)
        if (existingChat == null) {
            chatDao.upsertChat(
                ChatEntity(
                    chatId = chatId,
                    title = dto.name,
                    isGroup = true,
                    lastMessageTs = System.currentTimeMillis(),
                )
            )
        } else if (existingChat.title != dto.name) {
            chatDao.updateTitle(chatId, dto.name)
        }
    }

    // --- Avatars (Section 5.2a) ---

    private fun avatarsDir(): File = File(context.filesDir, "avatars").apply { mkdirs() }

    private fun safePhoneKey(phone: String): String =
        phone.replace("+", "p_").replace(Regex("[^A-Za-z0-9_-]"), "_")

    private fun avatarFileFor(phone: String, avatarVer: Long): File =
        File(avatarsDir(), "${safePhoneKey(phone)}_$avatarVer.jpg")

    private fun deleteOldAvatars(phone: String, keepVer: Long) {
        val prefix = "${safePhoneKey(phone)}_"
        val keepName = if (keepVer > 0L) "${prefix}$keepVer.jpg" else ""
        avatarsDir().listFiles()?.forEach { f ->
            if (f.name.startsWith(prefix) && f.name != keepName) {
                f.delete()
            }
        }
    }

    suspend fun downloadAndCacheAvatar(phone: String, expectedVer: Long): String? = withContext(Dispatchers.IO) {
        val currentSession = sessionPrefs.session.value ?: return@withContext null
        val contentKey = currentSession.derivedKeys().contentKey
        val result = apiClient.downloadAvatar(phone)
        val decrypted = AuroraCrypto.decryptBytes(contentKey, result.encryptedBytes)
        val ver = if (result.avatarVer > 0L) result.avatarVer else expectedVer
        val target = avatarFileFor(phone, ver)
        target.writeBytes(decrypted)
        deleteOldAvatars(phone, ver)
        userDao.updateAvatar(phone, ver, target.absolutePath)
        target.absolutePath
    }

    suspend fun uploadMyAvatar(jpegBytes: ByteArray): Result<UserDto> = withContext(Dispatchers.IO) {
        val currentSession = sessionPrefs.session.value
            ?: return@withContext Result.failure(IllegalStateException("Not signed in"))
        val myPhone = currentSession.phone
        val prevUser = userDao.getUser(myPhone)
        val optimisticVer = System.currentTimeMillis()
        val optimisticFile = avatarFileFor(myPhone, optimisticVer)
        optimisticFile.writeBytes(jpegBytes)
        userDao.updateAvatar(myPhone, optimisticVer, optimisticFile.absolutePath)

        return@withContext try {
            val contentKey = currentSession.derivedKeys().contentKey
            val encrypted = AuroraCrypto.encryptBytes(contentKey, jpegBytes)
            val updated = apiClient.uploadAvatar(encrypted)
            val serverVer = if (updated.avatarVer > 0L) updated.avatarVer else optimisticVer
            val finalFile = avatarFileFor(myPhone, serverVer)
            if (finalFile.absolutePath != optimisticFile.absolutePath) {
                optimisticFile.renameTo(finalFile)
            }
            deleteOldAvatars(myPhone, serverVer)
            userDao.updateAvatar(myPhone, serverVer, finalFile.absolutePath)
            Result.success(updated)
        } catch (t: Throwable) {
            optimisticFile.delete()
            userDao.updateAvatar(myPhone, prevUser?.avatarVer ?: 0L, prevUser?.avatarLocalPath)
            Result.failure(t)
        }
    }

    suspend fun removeMyAvatar(): Result<UserDto> = withContext(Dispatchers.IO) {
        val currentSession = sessionPrefs.session.value
            ?: return@withContext Result.failure(IllegalStateException("Not signed in"))
        val myPhone = currentSession.phone
        val prevUser = userDao.getUser(myPhone)
        userDao.updateAvatar(myPhone, 0L, null)
        return@withContext try {
            val updated = apiClient.deleteAvatar()
            deleteOldAvatars(myPhone, 0L)
            Result.success(updated)
        } catch (t: Throwable) {
            userDao.updateAvatar(myPhone, prevUser?.avatarVer ?: 0L, prevUser?.avatarLocalPath)
            Result.failure(t)
        }
    }

    suspend fun updateMyDisplayName(newName: String): Result<UserDto> = withContext(Dispatchers.IO) {
        return@withContext runCatching {
            val updated = apiClient.updateMe(newName.trim())
            sessionPrefs.updateName(updated.name)
            upsertUserDto(updated)
            updated
        }
    }

    // --- Chat Lifecycle & Read Receipts ---

    suspend fun ensureDirectChat(peerPhone: String): ChatEntity {
        val existing = chatDao.getChat(peerPhone)
        if (existing != null) return existing
        val user = userDao.getUser(peerPhone)
        val chat = ChatEntity(
            chatId = peerPhone,
            title = user?.displayName ?: peerPhone,
            isGroup = false,
            lastMessageTs = System.currentTimeMillis(),
        )
        chatDao.upsertChat(chat)
        return chat
    }

    suspend fun onChatVisible(chatId: String?) {
        activeChatId = chatId
        if (chatId != null) {
            notificationHelper.cancelChatNotification(chatId)
            markChatRead(chatId)
        }
    }

    suspend fun markChatRead(chatId: String) {
        chatDao.clearUnread(chatId)
        val unread = messageDao.getUnreadIncoming(chatId)
        if (unread.isEmpty()) return
        val ids = unread.map { it.msgId }
        messageDao.markIncomingReadBatch(ids)

        if (chatId == ChatConstants.SAVED_CHAT_ID) return
        // Group by original sender and send read receipt to each sender
        unread.groupBy { it.sender }.forEach { (senderPhone, msgs) ->
            webSocketManager.sendReadReceipt(senderPhone, msgs.map { it.msgId })
        }
    }

    fun sendTypingIfNeeded(chatId: String) {
        if (chatId == ChatConstants.SAVED_CHAT_ID) return
        val now = System.currentTimeMillis()
        val last = lastTypingSentByChat[chatId] ?: 0L
        if (now - last >= 3000L) {
            lastTypingSentByChat[chatId] = now
            webSocketManager.sendTyping(chatId)
        }
    }

    // --- Sending Messages ---

    suspend fun sendTextMessage(
        chatId: String,
        text: String,
        replyTo: String? = null,
    ): MessageEntity {
        val currentSession = sessionPrefs.session.value
            ?: throw IllegalStateException("Not signed in")
        val now = currentServerNowMs()
        val msgId = UUID.randomUUID().toString()
        val isSaved = chatId == ChatConstants.SAVED_CHAT_ID
        val entity = MessageEntity(
            msgId = msgId,
            chatId = chatId,
            sender = currentSession.phone,
            kind = "text",
            text = text,
            replyTo = replyTo,
            createdAt = now,
            status = if (isSaved) "read" else "pending",
            direction = "out",
        )
        db.withTransaction {
            ensureChatExistsLocked(chatId)
            messageDao.upsert(entity)
            updateChatLastMessage(chatId)
        }
        if (!isSaved) {
            sendEncryptedBody(chatId, msgId, EncryptedMessageBody(
                kind = "text",
                msgTs = now,
                replyTo = replyTo,
                text = text,
            ))
        }
        return entity
    }

    suspend fun sendEditMessage(chatId: String, targetMsgId: String, newText: String) {
        val now = currentServerNowMs()
        db.withTransaction {
            messageDao.applyEdit(targetMsgId, newText, now)
            updateChatLastMessage(chatId)
        }
        if (chatId != ChatConstants.SAVED_CHAT_ID) {
            val msgId = UUID.randomUUID().toString()
            sendEncryptedBody(chatId, msgId, EncryptedMessageBody(
                kind = "edit",
                msgTs = now,
                target = targetMsgId,
                text = newText,
            ))
        }
    }

    suspend fun sendDeleteForEveryone(chatId: String, targetMsgId: String) {
        val now = currentServerNowMs()
        db.withTransaction {
            messageDao.markDeleted(targetMsgId)
            updateChatLastMessage(chatId)
        }
        if (chatId != ChatConstants.SAVED_CHAT_ID) {
            val msgId = UUID.randomUUID().toString()
            sendEncryptedBody(chatId, msgId, EncryptedMessageBody(
                kind = "delete",
                msgTs = now,
                target = targetMsgId,
            ))
        }
    }

    suspend fun deleteMessageForMe(chatId: String, localId: Long) {
        db.withTransaction {
            messageDao.deleteByLocalId(localId)
            updateChatLastMessage(chatId)
        }
    }

    suspend fun sendReaction(chatId: String, targetMsgId: String, emoji: String) {
        val myPhone = sessionPrefs.session.value?.phone ?: return
        val msg = messageDao.getByMsgId(targetMsgId) ?: return
        val reactions = parseReactions(msg.reactionsJson).toMutableMap()
        if (emoji.isBlank() || reactions[myPhone] == emoji) {
            reactions.remove(myPhone)
            messageDao.updateReactions(targetMsgId, AuroraJson.encodeToString(reactions))
            if (chatId != ChatConstants.SAVED_CHAT_ID) {
                sendEncryptedBody(
                    chatId,
                    UUID.randomUUID().toString(),
                    EncryptedMessageBody(kind = "reaction", msgTs = currentServerNowMs(), target = targetMsgId, emoji = ""),
                )
            }
        } else {
            reactions[myPhone] = emoji
            messageDao.updateReactions(targetMsgId, AuroraJson.encodeToString(reactions))
            if (chatId != ChatConstants.SAVED_CHAT_ID) {
                sendEncryptedBody(
                    chatId,
                    UUID.randomUUID().toString(),
                    EncryptedMessageBody(kind = "reaction", msgTs = currentServerNowMs(), target = targetMsgId, emoji = emoji),
                )
            }
        }
    }

    suspend fun forwardMessage(source: MessageEntity, targetChatId: String) {
        if (source.deleted) return
        when (source.kind) {
            "text" -> source.text?.let { sendTextMessage(targetChatId, it) }
            "file" -> {
                val meta = source.fileJson?.let { runCatching { AuroraJson.decodeFromString<FileAttachmentMeta>(it) }.getOrNull() }
                    ?: return
                val localPath = fileDao.getFile(meta.fileId)?.localPath ?: meta.localPath
                if (localPath != null && File(localPath).exists()) {
                    sendLocalFile(
                        chatId = targetChatId,
                        sourceFile = File(localPath),
                        mime = meta.mime,
                        displayName = meta.name,
                        caption = meta.caption,
                        durationMs = meta.durationMs,
                        width = meta.width,
                        height = meta.height,
                    )
                } else {
                    // Forward metadata directly if remote file still exists
                    val currentSession = sessionPrefs.session.value ?: return
                    val now = currentServerNowMs()
                    val msgId = UUID.randomUUID().toString()
                    val isSaved = targetChatId == ChatConstants.SAVED_CHAT_ID
                    val entity = MessageEntity(
                        msgId = msgId,
                        chatId = targetChatId,
                        sender = currentSession.phone,
                        kind = "file",
                        text = meta.caption,
                        fileJson = AuroraJson.encodeToString(meta),
                        createdAt = now,
                        status = if (isSaved) "read" else "pending",
                        direction = "out",
                    )
                    db.withTransaction {
                        ensureChatExistsLocked(targetChatId)
                        messageDao.upsert(entity)
                        updateChatLastMessage(targetChatId)
                    }
                    if (!isSaved) {
                        sendEncryptedBody(
                            targetChatId,
                            msgId,
                            EncryptedMessageBody(
                                kind = "file",
                                msgTs = now,
                                fileId = meta.fileId,
                                name = meta.name,
                                mime = meta.mime,
                                size = meta.size,
                                width = meta.width,
                                height = meta.height,
                                durationMs = meta.durationMs,
                                thumb = meta.thumb,
                                caption = meta.caption,
                            ),
                        )
                    }
                }
            }
        }
    }

    suspend fun sendCallLog(peerPhone: String, callId: String, video: Boolean, result: String, durationSec: Int) {
        val currentSession = sessionPrefs.session.value ?: return
        val now = currentServerNowMs()
        val msgId = UUID.randomUUID().toString()
        val callMeta = CallLogMeta(callId = callId, video = video, result = result, durationSec = durationSec)
        val entity = MessageEntity(
            msgId = msgId,
            chatId = peerPhone,
            sender = currentSession.phone,
            kind = "call_log",
            callLogJson = AuroraJson.encodeToString(callMeta),
            createdAt = now,
            status = "pending",
            direction = "out",
        )
        db.withTransaction {
            ensureChatExistsLocked(peerPhone)
            messageDao.upsert(entity)
            updateChatLastMessage(peerPhone)
        }
        sendEncryptedBody(
            peerPhone,
            msgId,
            EncryptedMessageBody(
                kind = "call_log",
                msgTs = now,
                callId = callId,
                video = video,
                result = result,
                durationSec = durationSec,
            ),
        )
    }

    // --- Files & Attachments ---

    private fun decryptedDir(): File = File(context.filesDir, "decrypted").apply { mkdirs() }
    private fun tempDir(): File = File(context.cacheDir, "crypto_tmp").apply { mkdirs() }

    suspend fun sendUriAttachment(
        chatId: String,
        uri: Uri,
        caption: String? = null,
        durationMs: Long? = null,
        replyTo: String? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext runCatching {
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val name = queryFileName(uri) ?: "attachment_${System.currentTimeMillis()}"
            val localTemp = File(tempDir(), "up_${UUID.randomUUID()}_$name")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(localTemp).use { output -> input.copyTo(output) }
            } ?: error("Cannot read selected file")
            try {
                sendLocalFile(
                    chatId = chatId,
                    sourceFile = localTemp,
                    mime = mime,
                    displayName = name,
                    caption = caption,
                    durationMs = durationMs,
                    replyTo = replyTo,
                )
            } finally {
                localTemp.delete()
            }
        }
    }

    suspend fun sendLocalFile(
        chatId: String,
        sourceFile: File,
        mime: String,
        displayName: String,
        caption: String? = null,
        durationMs: Long? = null,
        width: Int? = null,
        height: Int? = null,
        replyTo: String? = null,
    ) = withContext(Dispatchers.IO) {
        val currentSession = sessionPrefs.session.value ?: error("Not signed in")
        val contentKey = currentSession.derivedKeys().contentKey
        val msgId = UUID.randomUUID().toString()
        val now = currentServerNowMs()
        val isSaved = chatId == ChatConstants.SAVED_CHAT_ID

        var w = width
        var h = height
        var dur = durationMs
        var thumbBase64: String? = null

        if (mime.startsWith("image/")) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(sourceFile.absolutePath, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                w = opts.outWidth
                h = opts.outHeight
                thumbBase64 = createImageThumbnailBase64(sourceFile)
            }
        } else if (mime.startsWith("video/") || mime.startsWith("audio/")) {
            runCatching {
                val mmr = MediaMetadataRetriever()
                mmr.setDataSource(sourceFile.absolutePath)
                if (dur == null) {
                    dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                }
                if (mime.startsWith("video/")) {
                    w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                    h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                    mmr.getFrameAtTime(0)?.let { bmp ->
                        thumbBase64 = compressThumbnailBitmap(bmp)
                    }
                }
                mmr.release()
            }
        }

        if (isSaved) {
            val localFileId = "local_${UUID.randomUUID()}"
            val permanentLocal = File(decryptedDir(), "${localFileId}_${sanitizeFileName(displayName)}")
            sourceFile.copyTo(permanentLocal, overwrite = true)
            val meta = FileAttachmentMeta(
                fileId = localFileId,
                name = displayName,
                mime = mime,
                size = permanentLocal.length(),
                width = w,
                height = h,
                durationMs = dur,
                thumb = thumbBase64,
                caption = caption,
                localPath = permanentLocal.absolutePath,
            )
            fileDao.upsertFile(
                FileEntity(
                    fileId = localFileId,
                    localPath = permanentLocal.absolutePath,
                    encryptedSize = permanentLocal.length(),
                    mime = mime,
                    name = displayName,
                    downloadState = "downloaded",
                    progress = 1f,
                )
            )
            db.withTransaction {
                ensureChatExistsLocked(chatId)
                messageDao.upsert(
                    MessageEntity(
                        msgId = msgId,
                        chatId = chatId,
                        sender = currentSession.phone,
                        kind = "file",
                        text = caption,
                        fileJson = AuroraJson.encodeToString(meta),
                        replyTo = replyTo,
                        createdAt = now,
                        status = "read",
                        direction = "out",
                    )
                )
                updateChatLastMessage(chatId)
            }
            return@withContext
        }

        val tempUploadId = "up_${UUID.randomUUID()}"
        fileDao.upsertFile(
            FileEntity(
                fileId = tempUploadId,
                localPath = null,
                encryptedSize = sourceFile.length(),
                mime = mime,
                name = displayName,
                downloadState = "uploading",
                progress = 0f,
            )
        )

        val encryptedTemp = File(tempDir(), "enc_${UUID.randomUUID()}.bin")
        try {
            AuroraCrypto.encryptFileStreaming(contentKey, sourceFile, encryptedTemp)
            val uploadResp = apiClient.uploadFile(encryptedTemp) { prog ->
                scope.launch {
                    fileDao.updateProgress(tempUploadId, "uploading", prog)
                }
            }
            val permanentLocal = File(decryptedDir(), "${uploadResp.id}_${sanitizeFileName(displayName)}")
            sourceFile.copyTo(permanentLocal, overwrite = true)

            val meta = FileAttachmentMeta(
                fileId = uploadResp.id,
                name = displayName,
                mime = mime,
                size = sourceFile.length(),
                width = w,
                height = h,
                durationMs = dur,
                thumb = thumbBase64,
                caption = caption,
                localPath = permanentLocal.absolutePath,
            )
            fileDao.upsertFile(
                FileEntity(
                    fileId = uploadResp.id,
                    localPath = permanentLocal.absolutePath,
                    encryptedSize = uploadResp.size,
                    mime = mime,
                    name = displayName,
                    downloadState = "downloaded",
                    progress = 1f,
                )
            )
            db.withTransaction {
                ensureChatExistsLocked(chatId)
                messageDao.upsert(
                    MessageEntity(
                        msgId = msgId,
                        chatId = chatId,
                        sender = currentSession.phone,
                        kind = "file",
                        text = caption,
                        fileJson = AuroraJson.encodeToString(meta),
                        replyTo = replyTo,
                        createdAt = now,
                        status = "pending",
                        direction = "out",
                    )
                )
                updateChatLastMessage(chatId)
            }
            sendEncryptedBody(
                chatId,
                msgId,
                EncryptedMessageBody(
                    kind = "file",
                    msgTs = now,
                    replyTo = replyTo,
                    fileId = uploadResp.id,
                    name = displayName,
                    mime = mime,
                    size = sourceFile.length(),
                    width = w,
                    height = h,
                    durationMs = dur,
                    thumb = thumbBase64,
                    caption = caption,
                ),
            )
        } finally {
            encryptedTemp.delete()
        }
    }

    suspend fun downloadFile(meta: FileAttachmentMeta): Result<String> = withContext(Dispatchers.IO) {
        val currentSession = sessionPrefs.session.value
            ?: return@withContext Result.failure(IllegalStateException("Not signed in"))
        val existing = fileDao.getFile(meta.fileId)
        if (existing?.localPath != null && File(existing.localPath).exists()) {
            return@withContext Result.success(existing.localPath)
        }

        fileDao.upsertFile(
            FileEntity(
                fileId = meta.fileId,
                localPath = null,
                encryptedSize = meta.size,
                mime = meta.mime,
                name = meta.name,
                downloadState = "downloading",
                progress = 0f,
            )
        )

        val encTmp = File(tempDir(), "dl_${meta.fileId}.enc")
        val destFile = File(decryptedDir(), "${meta.fileId}_${sanitizeFileName(meta.name)}")
        return@withContext try {
            apiClient.downloadFile(meta.fileId, encTmp) { prog ->
                scope.launch {
                    fileDao.updateProgress(meta.fileId, "downloading", prog)
                }
            }
            val contentKey = currentSession.derivedKeys().contentKey
            AuroraCrypto.decryptFileStreaming(contentKey, encTmp, destFile)
            encTmp.delete()
            fileDao.updateProgress(meta.fileId, "downloaded", 1f, destFile.absolutePath)
            Result.success(destFile.absolutePath)
        } catch (t: Throwable) {
            fileDao.updateProgress(meta.fileId, "failed", 0f)
            Result.failure(t)
        }
    }

    suspend fun saveFileToDownloads(fileId: String, displayName: String, mime: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            return@withContext runCatching {
                val entry = fileDao.getFile(fileId) ?: error("File not downloaded yet")
                val srcPath = entry.localPath ?: error("File missing")
                val srcFile = File(srcPath)
                if (!srcFile.exists()) error("File missing on disk")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                        put(MediaStore.Downloads.MIME_TYPE, mime)
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: error("Failed to create MediaStore entry")
                    resolver.openOutputStream(uri)?.use { out ->
                        srcFile.inputStream().use { it.copyTo(out) }
                    }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } else {
                    val dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    dlDir.mkdirs()
                    srcFile.copyTo(File(dlDir, sanitizeFileName(displayName)), overwrite = true)
                }
                Unit
            }
        }

    // --- Group Operations ---

    suspend fun createGroup(name: String, members: List<String>): Result<GroupDto> = withContext(Dispatchers.IO) {
        runCatching {
            val dto = apiClient.createGroup(name.trim(), members)
            upsertGroupDto(dto, isMember = true)
            dto
        }
    }

    suspend fun renameGroup(groupId: String, newName: String): Result<GroupDto> = withContext(Dispatchers.IO) {
        runCatching {
            val dto = apiClient.patchGroup(groupId, name = newName.trim())
            upsertGroupDto(dto, isMember = true)
            dto
        }
    }

    suspend fun addGroupMembers(groupId: String, phones: List<String>): Result<GroupDto> = withContext(Dispatchers.IO) {
        runCatching {
            val dto = apiClient.patchGroup(groupId, add = phones)
            upsertGroupDto(dto, isMember = true)
            dto
        }
    }

    suspend fun removeGroupMember(groupId: String, phone: String): Result<GroupDto> = withContext(Dispatchers.IO) {
        runCatching {
            val dto = apiClient.patchGroup(groupId, remove = listOf(phone))
            upsertGroupDto(dto, isMember = true)
            dto
        }
    }

    suspend fun leaveGroup(groupId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            apiClient.leaveGroup(groupId)
            groupDao.setIsMember(groupId, false)
        }
    }

    suspend fun deleteGroup(groupId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            apiClient.deleteGroup(groupId)
            groupDao.setIsMember(groupId, false)
        }
    }

    // --- Chat Management ---

    suspend fun setChatPinned(chatId: String, pinned: Boolean) = chatDao.setPinned(chatId, pinned)
    suspend fun setChatMuted(chatId: String, muted: Boolean) = chatDao.setMuted(chatId, muted)
    suspend fun setChatArchived(chatId: String, archived: Boolean) = chatDao.setArchived(chatId, archived)
    suspend fun deleteChatLocally(chatId: String) {
        db.withTransaction {
            messageDao.deleteChatMessages(chatId)
            chatDao.deleteChat(chatId)
        }
    }

    // --- Settings & Storage ---

    suspend fun setThemeMode(mode: ThemeMode) = sessionPrefs.setThemeMode(mode)
    suspend fun setWallpaperId(id: Int) = sessionPrefs.setWallpaperId(id)
    suspend fun setNotificationSound(enabled: Boolean) = sessionPrefs.setNotificationSound(enabled)
    suspend fun setNotificationVibration(enabled: Boolean) = sessionPrefs.setNotificationVibration(enabled)
    suspend fun setAutoDownloadWifi(enabled: Boolean) = sessionPrefs.setAutoDownloadWifi(enabled)
    suspend fun setAllowSelfSigned(enabled: Boolean) = sessionPrefs.setAllowSelfSigned(enabled)
    suspend fun setAppLock(enabled: Boolean, pinHash: String? = null) = sessionPrefs.setAppLock(enabled, pinHash)
    suspend fun setContactsPromptShown() = sessionPrefs.setContactsPromptShown()

    fun calculateCacheUsageBytes(): Long {
        val dec = decryptedDir().walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val tmp = tempDir().walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val av = avatarsDir().walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return dec + tmp + av
    }

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        tempDir().deleteRecursively()
        decryptedDir().deleteRecursively()
        fileDao.deleteAll()
    }

    suspend fun logoutAndWipeAll() = withContext(Dispatchers.IO) {
        runCatching { apiClient.logout() }
        webSocketManager.disconnect()
        db.withTransaction {
            receiptDao.deleteAll()
            messageDao.deleteAll()
            chatDao.deleteAll()
            groupDao.deleteAll()
            userDao.deleteAll()
            fileDao.deleteAll()
        }
        avatarsDir().deleteRecursively()
        decryptedDir().deleteRecursively()
        tempDir().deleteRecursively()
        sessionPrefs.clearSession()
        sessionPrefs.clearAllSettings()
    }

    // --- WebSocket Event Processing & Outbox ---

    private suspend fun sendEncryptedBody(chatId: String, msgId: String, body: EncryptedMessageBody) {
        val currentSession = sessionPrefs.session.value ?: return
        val contentKey = currentSession.derivedKeys().contentKey
        val json = AuroraJson.encodeToString(body)
        val payload = AuroraCrypto.encryptPayload(contentKey, json)
        webSocketManager.sendMessage(id = msgId, to = chatId, payload = payload)
    }

    suspend fun flushOutbox() {
        val currentSession = sessionPrefs.session.value ?: return
        val contentKey = currentSession.derivedKeys().contentKey
        val pending = messageDao.getPendingOutbox()
        for (msg in pending) {
            val body = when (msg.kind) {
                "text" -> EncryptedMessageBody(
                    kind = "text",
                    msgTs = msg.createdAt,
                    replyTo = msg.replyTo,
                    text = msg.text,
                )
                "file" -> {
                    val meta = msg.fileJson?.let {
                        runCatching { AuroraJson.decodeFromString<FileAttachmentMeta>(it) }.getOrNull()
                    } ?: continue
                    EncryptedMessageBody(
                        kind = "file",
                        msgTs = msg.createdAt,
                        replyTo = msg.replyTo,
                        fileId = meta.fileId,
                        name = meta.name,
                        mime = meta.mime,
                        size = meta.size,
                        width = meta.width,
                        height = meta.height,
                        durationMs = meta.durationMs,
                        thumb = meta.thumb,
                        caption = meta.caption,
                    )
                }
                "call_log" -> {
                    val meta = msg.callLogJson?.let {
                        runCatching { AuroraJson.decodeFromString<CallLogMeta>(it) }.getOrNull()
                    } ?: continue
                    EncryptedMessageBody(
                        kind = "call_log",
                        msgTs = msg.createdAt,
                        callId = meta.callId,
                        video = meta.video,
                        result = meta.result,
                        durationSec = meta.durationSec,
                    )
                }
                else -> continue
            }
            val payload = AuroraCrypto.encryptPayload(contentKey, AuroraJson.encodeToString(body))
            webSocketManager.sendMessage(id = msg.msgId, to = msg.chatId, payload = payload)
        }
    }

    private suspend fun handleWsEvent(event: WsRawEvent) {
        when (event.type) {
            "sent" -> {
                val id = event.id ?: return
                db.withTransaction {
                    val msg = messageDao.getByMsgId(id)
                    if (msg != null && msg.status == "pending") {
                        messageDao.updateOutgoingStatus(id, "sent")
                        updateChatLastMessage(msg.chatId)
                    }
                }
            }

            "msg" -> {
                val qid = event.qid
                val msgId = event.id ?: return
                val from = event.from ?: return
                val payload = event.payload ?: return
                val ts = event.ts ?: currentServerNowMs()
                val groupId = extractStringGroupId(event.group)
                    ?: event.to?.takeIf { it.startsWith(ChatConstants.GROUP_PREFIX) }?.let { ChatConstants.extractGroupId(it) }
                val myPhone = sessionPrefs.session.value?.phone
                val chatId = if (groupId != null) {
                    ChatConstants.toGroupChatId(groupId)
                } else if (from == myPhone && event.to != null) {
                    event.to
                } else {
                    from
                }

                var notificationPayload: PendingNotification? = null
                var autoDownloadMeta: FileAttachmentMeta? = null

                db.withTransaction {
                    // Deduplicate by (sender, msgId)
                    if (messageDao.getBySenderAndMsgId(from, msgId) != null) {
                        return@withTransaction
                    }

                    val contentKey = sessionPrefs.session.value?.derivedKeys()?.contentKey
                    val decryptedBody: EncryptedMessageBody? = if (contentKey != null) {
                        runCatching {
                            val json = AuroraCrypto.decryptPayload(contentKey, payload)
                            AuroraJson.decodeFromString<EncryptedMessageBody>(json)
                        }.getOrNull()
                    } else null

                    if (decryptedBody == null) {
                        ensureChatExistsLocked(chatId)
                        messageDao.insertIgnore(
                            MessageEntity(
                                msgId = msgId,
                                chatId = chatId,
                                sender = from,
                                kind = "text",
                                text = context.getString(R.string.unable_to_decrypt),
                                createdAt = ts,
                                status = "delivered",
                                direction = if (from == myPhone) "out" else "in",
                                decryptFailed = true,
                            )
                        )
                        updateChatLastMessage(chatId)
                        return@withTransaction
                    }

                    when (decryptedBody.kind) {
                        "edit" -> {
                            val target = decryptedBody.target
                            val newText = decryptedBody.text
                            if (target != null && newText != null) {
                                val existing = messageDao.getByMsgId(target)
                                if (existing != null && existing.sender == from) {
                                    messageDao.applyEdit(target, newText, ts)
                                    updateChatLastMessage(existing.chatId)
                                }
                            }
                            return@withTransaction
                        }

                        "delete" -> {
                            val target = decryptedBody.target
                            if (target != null) {
                                val existing = messageDao.getByMsgId(target)
                                if (existing != null && existing.sender == from) {
                                    messageDao.markDeleted(target)
                                    updateChatLastMessage(existing.chatId)
                                }
                            }
                            return@withTransaction
                        }

                        "reaction" -> {
                            val target = decryptedBody.target
                            if (target != null) {
                                val existing = messageDao.getByMsgId(target)
                                if (existing != null) {
                                    val reactions = parseReactions(existing.reactionsJson).toMutableMap()
                                    val emoji = decryptedBody.emoji.orEmpty()
                                    if (emoji.isBlank()) reactions.remove(from) else reactions[from] = emoji
                                    messageDao.updateReactions(target, AuroraJson.encodeToString(reactions))
                                }
                            }
                            return@withTransaction
                        }

                        "text", "file", "call_log" -> {
                            ensureChatExistsLocked(chatId)
                            val direction = if (from == myPhone) "out" else "in"
                            val isVisible = activeChatId == chatId
                            val status = if (direction == "out") "sent" else if (isVisible) "read" else "delivered"

                            val fileMeta = if (decryptedBody.kind == "file" && decryptedBody.fileId != null) {
                                FileAttachmentMeta(
                                    fileId = decryptedBody.fileId,
                                    name = decryptedBody.name ?: "file",
                                    mime = decryptedBody.mime ?: "application/octet-stream",
                                    size = decryptedBody.size ?: 0L,
                                    width = decryptedBody.width,
                                    height = decryptedBody.height,
                                    durationMs = decryptedBody.durationMs,
                                    thumb = decryptedBody.thumb,
                                    caption = decryptedBody.caption,
                                )
                            } else null

                            if (fileMeta != null) {
                                fileDao.upsertFile(
                                    FileEntity(
                                        fileId = fileMeta.fileId,
                                        encryptedSize = fileMeta.size,
                                        mime = fileMeta.mime,
                                        name = fileMeta.name,
                                        downloadState = "none",
                                    )
                                )
                                if (fileMeta.mime.startsWith("image/") && fileMeta.size < 5 * 1024 * 1024) {
                                    autoDownloadMeta = fileMeta
                                }
                            }

                            val callMeta = if (decryptedBody.kind == "call_log") {
                                CallLogMeta(
                                    callId = decryptedBody.callId ?: msgId,
                                    video = decryptedBody.video ?: false,
                                    result = decryptedBody.result ?: "ended",
                                    durationSec = decryptedBody.durationSec ?: 0,
                                )
                            } else null

                            messageDao.insertIgnore(
                                MessageEntity(
                                    msgId = msgId,
                                    chatId = chatId,
                                    sender = from,
                                    kind = decryptedBody.kind,
                                    text = decryptedBody.text ?: decryptedBody.caption,
                                    fileJson = fileMeta?.let { AuroraJson.encodeToString(it) },
                                    callLogJson = callMeta?.let { AuroraJson.encodeToString(it) },
                                    replyTo = decryptedBody.replyTo,
                                    createdAt = ts,
                                    status = status,
                                    direction = direction,
                                )
                            )

                            val currentChat = chatDao.getChat(chatId)
                            val newUnread = if (direction == "in" && !isVisible) {
                                (currentChat?.unreadCount ?: 0) + 1
                            } else {
                                currentChat?.unreadCount ?: 0
                            }
                            if (currentChat != null) {
                                chatDao.upsertChat(currentChat.copy(unreadCount = newUnread))
                            }
                            updateChatLastMessage(chatId)

                            if (direction == "in" && isVisible) {
                                webSocketManager.sendReadReceipt(from, listOf(msgId))
                            } else if (direction == "in" && currentChat?.muted != true) {
                                val senderUser = userDao.getUser(from)
                                val preview = previewForBody(decryptedBody, fileMeta)
                                notificationPayload = PendingNotification(
                                    chatId = chatId,
                                    chatTitle = currentChat?.title ?: senderUser?.displayName ?: from,
                                    senderName = senderUser?.displayName ?: from,
                                    text = preview,
                                    avatarPath = senderUser?.avatarLocalPath,
                                    isGroup = groupId != null,
                                )
                            }
                        }

                        else -> {
                            // Unknown kinds ignored gracefully for forward compatibility
                            return@withTransaction
                        }
                    }
                }

                // Ack AFTER Room transaction commits
                if (qid != null) {
                    webSocketManager.queueAck(qid)
                }

                notificationPayload?.let { pn ->
                    val appSettings = settings.first()
                    notificationHelper.showMessageNotification(
                        chatId = pn.chatId,
                        chatTitle = pn.chatTitle,
                        senderName = pn.senderName,
                        messageText = pn.text,
                        avatarLocalPath = pn.avatarPath,
                        isGroup = pn.isGroup,
                        soundEnabled = appSettings.notificationSound,
                        vibrationEnabled = appSettings.notificationVibration,
                    )
                }

                autoDownloadMeta?.let { meta ->
                    scope.launch {
                        val appSettings = settings.first()
                        if (appSettings.autoDownloadWifi && isOnWifi()) {
                            downloadFile(meta)
                        }
                    }
                }
            }

            "receipt" -> {
                val qid = event.qid
                val ids = event.ids.orEmpty()
                val by = event.by ?: ""
                val status = event.status ?: return
                val ts = event.ts ?: currentServerNowMs()

                db.withTransaction {
                    val receipts = ids.map { id ->
                        ReceiptEntity(msgId = id, member = by, status = status, ts = ts)
                    }
                    receiptDao.upsertReceipts(receipts)

                    for (id in ids) {
                        val msg = messageDao.getByMsgId(id) ?: continue
                        if (msg.direction == "out") {
                            val nextStatus = when {
                                msg.status == "read" -> "read"
                                status == "read" -> "read"
                                status == "delivered" && msg.status in listOf("pending", "sent") -> "delivered"
                                else -> msg.status
                            }
                            if (nextStatus != msg.status) {
                                messageDao.updateOutgoingStatus(id, nextStatus)
                                updateChatLastMessage(msg.chatId)
                            }
                        }
                    }
                }
                if (qid != null) {
                    webSocketManager.queueAck(qid)
                }
            }

            "group" -> {
                val qid = event.qid
                val ev = event.event
                val groupDto = extractGroupDto(event.group)
                db.withTransaction {
                    if (groupDto != null) {
                        upsertGroupDto(groupDto, isMember = ev != "removed")
                    }
                }
                if (qid != null) {
                    webSocketManager.queueAck(qid)
                }
            }

            "presence" -> {
                val phone = event.phone ?: return
                val online = event.online ?: false
                val lastSeen = event.lastSeen ?: (System.currentTimeMillis() / 1000L)
                userDao.updatePresence(phone, online, lastSeen)
            }

            "user" -> {
                val userDto = event.user ?: return
                upsertUserDto(userDto)
                val u = userDao.getUser(userDto.phone)
                if (u != null) {
                    chatDao.updateTitle(u.phone, u.displayName)
                }
            }

            "typing" -> {
                val from = event.from ?: return
                val groupId = extractStringGroupId(event.group)
                val chatId = if (groupId != null) ChatConstants.toGroupChatId(groupId) else from
                val key = chatId to from
                typingExpiryJobs[key]?.cancel()
                _typingByChat.value = _typingByChat.value.toMutableMap().apply {
                    put(chatId, (get(chatId) ?: emptySet()) + from)
                }
                typingExpiryJobs[key] = scope.launch {
                    delay(5000L)
                    _typingByChat.value = _typingByChat.value.toMutableMap().apply {
                        val updated = (get(chatId) ?: emptySet()) - from
                        if (updated.isEmpty()) remove(chatId) else put(chatId, updated)
                    }
                }
            }
        }
    }

    private suspend fun ensureChatExistsLocked(chatId: String) {
        if (chatDao.getChat(chatId) != null) return
        val isGroup = ChatConstants.isGroupChat(chatId)
        val title = if (chatId == ChatConstants.SAVED_CHAT_ID) {
            context.getString(R.string.saved_messages)
        } else if (isGroup) {
            groupDao.getGroup(ChatConstants.extractGroupId(chatId))?.name ?: "Group"
        } else {
            userDao.getUser(chatId)?.displayName ?: chatId
        }
        chatDao.upsertChat(
            ChatEntity(
                chatId = chatId,
                title = title,
                isGroup = isGroup,
                pinned = chatId == ChatConstants.SAVED_CHAT_ID,
            )
        )
    }

    private suspend fun updateChatLastMessage(chatId: String) {
        val chat = chatDao.getChat(chatId) ?: return
        val last = messageDao.getLastMessage(chatId)
        val preview = if (last == null) {
            ""
        } else if (last.deleted) {
            context.getString(R.string.deleted_message)
        } else {
            when (last.kind) {
                "text" -> last.text.orEmpty()
                "file" -> {
                    val meta = last.fileJson?.let {
                        runCatching { AuroraJson.decodeFromString<FileAttachmentMeta>(it) }.getOrNull()
                    }
                    when {
                        meta == null -> "File"
                        meta.mime.startsWith("image/") -> context.getString(R.string.photo_preview)
                        meta.mime.startsWith("video/") -> context.getString(R.string.video_preview)
                        meta.mime.startsWith("audio/") -> context.getString(R.string.voice_preview)
                        else -> context.getString(R.string.file_preview, meta.name)
                    }
                }
                "call_log" -> context.getString(R.string.call_preview)
                else -> ""
            }
        }
        chatDao.upsertChat(
            chat.copy(
                lastMessageId = last?.msgId,
                lastMessagePreview = preview,
                lastMessageTs = last?.createdAt ?: chat.lastMessageTs,
                lastMessageDirection = last?.direction.orEmpty(),
                lastMessageStatus = last?.status.orEmpty(),
            )
        )
    }

    private fun previewForBody(body: EncryptedMessageBody, fileMeta: FileAttachmentMeta?): String {
        return when (body.kind) {
            "text" -> body.text.orEmpty()
            "file" -> when {
                fileMeta == null -> "File"
                fileMeta.mime.startsWith("image/") -> context.getString(R.string.photo_preview)
                fileMeta.mime.startsWith("video/") -> context.getString(R.string.video_preview)
                fileMeta.mime.startsWith("audio/") -> context.getString(R.string.voice_preview)
                else -> context.getString(R.string.file_preview, fileMeta.name)
            }
            "call_log" -> context.getString(R.string.call_preview)
            else -> ""
        }
    }

    private fun extractStringGroupId(elem: JsonElement?): String? {
        return when {
            elem == null -> null
            elem is JsonObject -> elem["id"]?.jsonPrimitive?.content
            else -> runCatching { elem.jsonPrimitive.content }.getOrNull()
        }
    }

    private fun extractGroupDto(elem: JsonElement?): GroupDto? {
        if (elem == null || elem !is JsonObject) return null
        return runCatching { AuroraJson.decodeFromJsonElement<GroupDto>(elem) }.getOrNull()
    }

    private fun parseReactions(json: String): Map<String, String> {
        return runCatching { AuroraJson.decodeFromString<Map<String, String>>(json) }.getOrDefault(emptyMap())
    }

    private fun isOnWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun queryFileName(uri: Uri): String? {
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "file" }

    private fun createImageThumbnailBase64(file: File): String? {
        val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        return compressThumbnailBitmap(bmp)
    }

    private fun compressThumbnailBitmap(bitmap: Bitmap): String? {
        val maxSide = 160
        val ratio = minOf(maxSide.toFloat() / bitmap.width, maxSide.toFloat() / bitmap.height, 1f)
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 65, out)
        val bytes = out.toByteArray()
        return if (bytes.size <= 8 * 1024) Base64.getEncoder().encodeToString(bytes) else null
    }

    private data class PendingNotification(
        val chatId: String,
        val chatTitle: String,
        val senderName: String,
        val text: String,
        val avatarPath: String?,
        val isGroup: Boolean,
    )
}
