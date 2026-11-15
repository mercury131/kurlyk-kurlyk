package com.example.ui.viewmodel

import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.call.WebRtcCallManager
import com.example.data.db.ChatEntity
import com.example.data.db.FileEntity
import com.example.data.db.GroupEntity
import com.example.data.db.MessageEntity
import com.example.data.db.UserEntity
import com.example.data.repository.MessagingRepository
import com.example.domain.ChatConstants
import com.example.domain.FileAttachmentMeta
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MessagingRepository,
    private val callManager: WebRtcCallManager,
) : ViewModel() {

    private val _chatId = MutableStateFlow("")
    val chatId: StateFlow<String> = _chatId.asStateFlow()

    val myPhone: String
        get() = repository.session.value?.phone.orEmpty()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val chat: StateFlow<ChatEntity?> = _chatId.flatMapLatest { id ->
        if (id.isBlank()) flowOf(null) else repository.observeChat(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val messages: StateFlow<List<MessageEntity>> = _chatId.flatMapLatest { id ->
        if (id.isBlank()) flowOf(emptyList()) else repository.observeMessages(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val peerUser: StateFlow<UserEntity?> = _chatId.flatMapLatest { id ->
        if (id.isBlank() || ChatConstants.isGroupChat(id) || id == ChatConstants.SAVED_CHAT_ID) {
            flowOf(null)
        } else {
            repository.observeUser(id)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val group: StateFlow<GroupEntity?> = _chatId.flatMapLatest { id ->
        if (ChatConstants.isGroupChat(id)) {
            repository.observeGroup(ChatConstants.extractGroupId(id))
        } else {
            flowOf(null)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val allUsers: StateFlow<Map<String, UserEntity>> = repository.observeUsers()
        .map { list -> list.associateBy { it.phone } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val filesMap: StateFlow<Map<String, FileEntity>> = repository.observeFiles()
        .map { list -> list.associateBy { it.fileId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val typingUsers: StateFlow<Set<String>> = _chatId.flatMapLatest { id ->
        repository.typingByChat.map { it[id] ?: emptySet() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private val _replyToMessage = MutableStateFlow<MessageEntity?>(null)
    val replyToMessage: StateFlow<MessageEntity?> = _replyToMessage.asStateFlow()

    private val _editingMessage = MutableStateFlow<MessageEntity?>(null)
    val editingMessage: StateFlow<MessageEntity?> = _editingMessage.asStateFlow()

    private val _isRecordingVoice = MutableStateFlow(false)
    val isRecordingVoice: StateFlow<Boolean> = _isRecordingVoice.asStateFlow()

    private val _recordingSeconds = MutableStateFlow(0)
    val recordingSeconds: StateFlow<Int> = _recordingSeconds.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private var mediaRecorder: MediaRecorder? = null
    private var voiceFile: File? = null
    private var voiceStartMs: Long = 0L
    private var recordTimerJob: Job? = null

    fun setChatId(id: String) {
        if (_chatId.value == id) return
        _chatId.value = id
        viewModelScope.launch {
            if (id != ChatConstants.SAVED_CHAT_ID && !ChatConstants.isGroupChat(id)) {
                repository.ensureDirectChat(id)
            }
            repository.onChatVisible(id)
        }
    }

    fun onScreenDisposed() {
        viewModelScope.launch {
            repository.onChatVisible(null)
        }
    }

    fun onIncomingWhileVisible() {
        val id = _chatId.value
        if (id.isNotBlank()) {
            viewModelScope.launch { repository.markChatRead(id) }
        }
    }

    fun onTextTyped() {
        val id = _chatId.value
        if (id.isNotBlank()) {
            repository.sendTypingIfNeeded(id)
        }
    }

    fun setReplyTo(msg: MessageEntity?) {
        _editingMessage.value = null
        _replyToMessage.value = msg
    }

    fun startEditing(msg: MessageEntity) {
        _replyToMessage.value = null
        _editingMessage.value = msg
    }

    fun cancelReplyOrEdit() {
        _replyToMessage.value = null
        _editingMessage.value = null
    }

    fun sendOrEditText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val id = _chatId.value
        if (id.isBlank()) return

        val editing = _editingMessage.value
        val reply = _replyToMessage.value
        _editingMessage.value = null
        _replyToMessage.value = null

        viewModelScope.launch {
            if (editing != null) {
                repository.sendEditMessage(id, editing.msgId, trimmed)
            } else {
                repository.sendTextMessage(id, trimmed, replyTo = reply?.msgId)
            }
        }
    }

    fun deleteForMe(localId: Long) {
        viewModelScope.launch {
            repository.deleteMessageForMe(_chatId.value, localId)
        }
    }

    fun deleteForEveryone(msgId: String) {
        viewModelScope.launch {
            repository.sendDeleteForEveryone(_chatId.value, msgId)
        }
    }

    fun react(msgId: String, emoji: String) {
        viewModelScope.launch {
            repository.sendReaction(_chatId.value, msgId, emoji)
        }
    }

    fun saveToSavedMessages(msg: MessageEntity) {
        viewModelScope.launch {
            repository.forwardMessage(msg, ChatConstants.SAVED_CHAT_ID)
            _toastMessage.value = "Added to Saved messages"
        }
    }

    fun forwardMessageTo(msg: MessageEntity, targetChatId: String) {
        viewModelScope.launch {
            repository.forwardMessage(msg, targetChatId)
            _toastMessage.value = "Forwarded"
        }
    }

    fun sendUriAttachment(uri: Uri, caption: String? = null) {
        val id = _chatId.value
        val reply = _replyToMessage.value?.msgId
        _replyToMessage.value = null
        viewModelScope.launch {
            repository.sendUriAttachment(id, uri, caption = caption, replyTo = reply)
                .onFailure { _toastMessage.value = it.message ?: "Failed to send attachment" }
        }
    }

    fun sendCameraFile(file: File) {
        val id = _chatId.value
        val reply = _replyToMessage.value?.msgId
        _replyToMessage.value = null
        viewModelScope.launch {
            runCatching {
                repository.sendLocalFile(
                    chatId = id,
                    sourceFile = file,
                    mime = "image/jpeg",
                    displayName = file.name,
                    replyTo = reply,
                )
            }.onFailure { _toastMessage.value = it.message }
        }
    }

    fun downloadAttachment(meta: FileAttachmentMeta) {
        viewModelScope.launch {
            repository.downloadFile(meta)
                .onFailure { _toastMessage.value = it.message ?: "Download failed" }
        }
    }

    fun saveToDownloads(meta: FileAttachmentMeta) {
        viewModelScope.launch {
            repository.saveFileToDownloads(meta.fileId, meta.name, meta.mime)
                .onSuccess { _toastMessage.value = "Saved to Downloads" }
                .onFailure { _toastMessage.value = it.message ?: "Download file first" }
        }
    }

    @Suppress("DEPRECATION")
    fun startVoiceRecording() {
        if (_isRecordingVoice.value) return
        val dir = File(context.cacheDir, "voice").apply { mkdirs() }
        val file = File(dir, "voice_${UUID.randomUUID()}.m4a")
        voiceFile = file
        voiceStartMs = System.currentTimeMillis()

        runCatching {
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                MediaRecorder()
            }
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64000)
                setAudioSamplingRate(44100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            mediaRecorder = recorder
            _isRecordingVoice.value = true
            _recordingSeconds.value = 0
            recordTimerJob?.cancel()
            recordTimerJob = viewModelScope.launch {
                while (_isRecordingVoice.value) {
                    _recordingSeconds.value = ((System.currentTimeMillis() - voiceStartMs) / 1000L).toInt()
                    delay(500L)
                }
            }
        }.onFailure {
            _toastMessage.value = "Unable to record audio"
            file.delete()
        }
    }

    fun stopVoiceRecording(cancel: Boolean) {
        if (!_isRecordingVoice.value) return
        _isRecordingVoice.value = false
        recordTimerJob?.cancel()
        val durationMs = (System.currentTimeMillis() - voiceStartMs).coerceAtLeast(500L)
        runCatching {
            mediaRecorder?.stop()
        }
        runCatching {
            mediaRecorder?.release()
        }
        mediaRecorder = null
        val recorded = voiceFile
        voiceFile = null

        if (cancel || recorded == null || !recorded.exists() || durationMs < 600L) {
            recorded?.delete()
            return
        }
        val id = _chatId.value
        val reply = _replyToMessage.value?.msgId
        _replyToMessage.value = null
        viewModelScope.launch {
            runCatching {
                repository.sendLocalFile(
                    chatId = id,
                    sourceFile = recorded,
                    mime = "audio/mp4",
                    displayName = "voice_${System.currentTimeMillis()}.m4a",
                    durationMs = durationMs,
                    replyTo = reply,
                )
            }
            recorded.delete()
        }
    }

    fun startCall(isVideo: Boolean) {
        val id = _chatId.value
        if (id.isNotBlank() && !ChatConstants.isGroupChat(id) && id != ChatConstants.SAVED_CHAT_ID) {
            callManager.startOutgoingCall(id, isVideo)
        }
    }

    fun renameGroup(newName: String) {
        val gId = ChatConstants.extractGroupId(_chatId.value)
        viewModelScope.launch {
            repository.renameGroup(gId, newName)
                .onFailure { _toastMessage.value = it.message }
        }
    }

    fun addGroupMembers(phones: List<String>) {
        val gId = ChatConstants.extractGroupId(_chatId.value)
        viewModelScope.launch {
            repository.addGroupMembers(gId, phones)
                .onFailure { _toastMessage.value = it.message }
        }
    }

    fun removeGroupMember(phone: String) {
        val gId = ChatConstants.extractGroupId(_chatId.value)
        viewModelScope.launch {
            repository.removeGroupMember(gId, phone)
                .onFailure { _toastMessage.value = it.message }
        }
    }

    fun leaveGroup(onDone: () -> Unit) {
        val gId = ChatConstants.extractGroupId(_chatId.value)
        viewModelScope.launch {
            repository.leaveGroup(gId)
                .onSuccess { onDone() }
                .onFailure { _toastMessage.value = it.message }
        }
    }

    fun deleteGroup(onDone: () -> Unit) {
        val gId = ChatConstants.extractGroupId(_chatId.value)
        viewModelScope.launch {
            repository.deleteGroup(gId)
                .onSuccess { onDone() }
                .onFailure { _toastMessage.value = it.message }
        }
    }

    fun clearToast() {
        _toastMessage.value = null
    }
}
