package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Query("SELECT * FROM users ORDER BY COALESCE(contactName, serverName) COLLATE NOCASE ASC")
    fun observeAllUsers(): Flow<List<UserEntity>>

    @Query("SELECT * FROM users WHERE phone = :phone LIMIT 1")
    fun observeUser(phone: String): Flow<UserEntity?>

    @Query("SELECT * FROM users WHERE phone = :phone LIMIT 1")
    suspend fun getUser(phone: String): UserEntity?

    @Query("SELECT * FROM users")
    suspend fun getAllUsersOnce(): List<UserEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUser(user: UserEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUsers(users: List<UserEntity>)

    @Query("UPDATE users SET online = :online, lastSeen = :lastSeen WHERE phone = :phone")
    suspend fun updatePresence(phone: String, online: Boolean, lastSeen: Long)

    @Query("UPDATE users SET avatarVer = :avatarVer, avatarLocalPath = :localPath WHERE phone = :phone")
    suspend fun updateAvatar(phone: String, avatarVer: Long, localPath: String?)

    @Query("UPDATE users SET contactName = :contactName WHERE phone = :phone")
    suspend fun updateContactName(phone: String, contactName: String?)

    @Query("DELETE FROM users")
    suspend fun deleteAll()
}

@Dao
interface ChatDao {
    @Query(
        """
        SELECT * FROM chats
        ORDER BY (chatId = 'saved') DESC, pinned DESC, lastMessageTs DESC
        """
    )
    fun observeAllChats(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE chatId = :chatId LIMIT 1")
    fun observeChat(chatId: String): Flow<ChatEntity?>

    @Query("SELECT * FROM chats WHERE chatId = :chatId LIMIT 1")
    suspend fun getChat(chatId: String): ChatEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChat(chat: ChatEntity)

    @Query("UPDATE chats SET unreadCount = 0 WHERE chatId = :chatId")
    suspend fun clearUnread(chatId: String)

    @Query("UPDATE chats SET pinned = :pinned WHERE chatId = :chatId")
    suspend fun setPinned(chatId: String, pinned: Boolean)

    @Query("UPDATE chats SET muted = :muted WHERE chatId = :chatId")
    suspend fun setMuted(chatId: String, muted: Boolean)

    @Query("UPDATE chats SET archived = :archived WHERE chatId = :chatId")
    suspend fun setArchived(chatId: String, archived: Boolean)

    @Query("UPDATE chats SET title = :title WHERE chatId = :chatId")
    suspend fun updateTitle(chatId: String, title: String)

    @Query("DELETE FROM chats WHERE chatId = :chatId")
    suspend fun deleteChat(chatId: String)

    @Query("DELETE FROM chats")
    suspend fun deleteAll()
}

@Dao
interface GroupDao {
    @Query("SELECT * FROM groups ORDER BY name COLLATE NOCASE ASC")
    fun observeAllGroups(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups WHERE id = :id LIMIT 1")
    fun observeGroup(id: String): Flow<GroupEntity?>

    @Query("SELECT * FROM groups WHERE id = :id LIMIT 1")
    suspend fun getGroup(id: String): GroupEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroup(group: GroupEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroups(groups: List<GroupEntity>)

    @Query("UPDATE groups SET isMember = :isMember WHERE id = :id")
    suspend fun setIsMember(id: String, isMember: Boolean)

    @Query("DELETE FROM groups WHERE id = :id")
    suspend fun deleteGroup(id: String)

    @Query("DELETE FROM groups")
    suspend fun deleteAll()
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY createdAt ASC, localId ASC")
    fun observeMessages(chatId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY createdAt DESC, localId DESC LIMIT 1")
    suspend fun getLastMessage(chatId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE msgId = :msgId LIMIT 1")
    suspend fun getByMsgId(msgId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE sender = :sender AND msgId = :msgId LIMIT 1")
    suspend fun getBySenderAndMsgId(sender: String, msgId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE chatId = :chatId AND direction = 'in' AND status != 'read'")
    suspend fun getUnreadIncoming(chatId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE direction = 'out' AND status = 'pending' AND chatId != 'saved' ORDER BY createdAt ASC")
    suspend fun getPendingOutbox(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE kind = 'call_log' ORDER BY createdAt DESC")
    fun observeCallHistory(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId AND text LIKE '%' || :query || '%' AND deleted = 0 ORDER BY createdAt DESC")
    suspend fun searchInChat(chatId: String, query: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(message: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity): Long

    @Update
    suspend fun update(message: MessageEntity)

    @Query("UPDATE messages SET status = :status WHERE msgId = :msgId AND direction = 'out'")
    suspend fun updateOutgoingStatus(msgId: String, status: String)

    @Query("UPDATE messages SET status = :status WHERE msgId IN (:msgIds) AND direction = 'out'")
    suspend fun updateOutgoingStatusBatch(msgIds: List<String>, status: String)

    @Query("UPDATE messages SET status = 'read' WHERE msgId IN (:msgIds) AND direction = 'in'")
    suspend fun markIncomingReadBatch(msgIds: List<String>)

    @Query("UPDATE messages SET text = :newText, editedAt = :editedAt WHERE msgId = :msgId")
    suspend fun applyEdit(msgId: String, newText: String, editedAt: Long)

    @Query("UPDATE messages SET deleted = 1, text = NULL, fileJson = NULL WHERE msgId = :msgId")
    suspend fun markDeleted(msgId: String)

    @Query("UPDATE messages SET reactionsJson = :reactionsJson WHERE msgId = :msgId")
    suspend fun updateReactions(msgId: String, reactionsJson: String)

    @Query("DELETE FROM messages WHERE localId = :localId")
    suspend fun deleteByLocalId(localId: Long)

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun deleteChatMessages(chatId: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}

@Dao
interface ReceiptDao {
    @Query("SELECT * FROM receipts WHERE msgId = :msgId")
    fun observeReceipts(msgId: String): Flow<List<ReceiptEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReceipts(receipts: List<ReceiptEntity>)

    @Query("DELETE FROM receipts")
    suspend fun deleteAll()
}

@Dao
interface FileDao {
    @Query("SELECT * FROM files")
    fun observeAllFiles(): Flow<List<FileEntity>>

    @Query("SELECT * FROM files WHERE fileId = :fileId LIMIT 1")
    fun observeFile(fileId: String): Flow<FileEntity?>

    @Query("SELECT * FROM files WHERE fileId = :fileId LIMIT 1")
    suspend fun getFile(fileId: String): FileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFile(file: FileEntity)

    @Query("UPDATE files SET downloadState = :state, progress = :progress, localPath = COALESCE(:localPath, localPath) WHERE fileId = :fileId")
    suspend fun updateProgress(fileId: String, state: String, progress: Float, localPath: String? = null)

    @Query("DELETE FROM files")
    suspend fun deleteAll()
}
