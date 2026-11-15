package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.call.WebRtcCallManager
import com.example.data.api.ConfigResponse
import com.example.data.contacts.ContactsSyncManager
import com.example.data.crypto.PhoneNormalizer
import com.example.data.db.ChatEntity
import com.example.data.db.MessageEntity
import com.example.data.db.UserEntity
import com.example.data.prefs.AppSettings
import com.example.data.prefs.AuthSession
import com.example.data.prefs.ThemeMode
import com.example.data.repository.MessagingRepository
import com.example.domain.ConnectionState
import com.example.service.ConnectionWatchdogWorker
import com.example.service.MessengerForegroundService
import com.example.ui.components.sha256Hex
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SignInUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val askContactsPermission: Boolean = false,
)

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MessagingRepository,
    private val contactsSyncManager: ContactsSyncManager,
    private val callManager: WebRtcCallManager,
) : ViewModel() {

    val session: StateFlow<AuthSession?> = repository.session
    val settings: StateFlow<AppSettings> = repository.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        AppSettings(),
    )
    val connectionState: StateFlow<ConnectionState> = repository.connectionState
    val wsError: StateFlow<String?> = repository.wsError

    val chats: StateFlow<List<ChatEntity>> = repository.observeChats().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList(),
    )
    val users: StateFlow<List<UserEntity>> = repository.observeUsers().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList(),
    )
    val callHistory: StateFlow<List<MessageEntity>> = repository.observeCallHistory().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList(),
    )

    private val _signInState = MutableStateFlow(SignInUiState())
    val signInState: StateFlow<SignInUiState> = _signInState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _serverConfig = MutableStateFlow<ConfigResponse?>(null)
    val serverConfig: StateFlow<ConfigResponse?> = _serverConfig.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    init {
        viewModelScope.launch {
            repository.ensureInitializedOnAppStart()
        }
    }

    fun signIn(rawDomain: String, secretKey: String, countryCode: String, localPhone: String, name: String) {
        val domain = PhoneNormalizer.normalizeDomain(rawDomain)
        if (domain == null) {
            _signInState.value = SignInUiState(error = "Enter a valid server domain (e.g. chat.example.com)")
            return
        }
        if (secretKey.trim().isEmpty()) {
            _signInState.value = SignInUiState(error = "Enter the server secret key")
            return
        }
        val combinedPhone = if (localPhone.trim().startsWith("+")) localPhone else "$countryCode$localPhone"
        val normalizedPhone = PhoneNormalizer.normalize(combinedPhone)
        if (normalizedPhone == null) {
            _signInState.value = SignInUiState(error = "Enter a valid international phone number")
            return
        }
        if (name.trim().isEmpty()) {
            _signInState.value = SignInUiState(error = "Enter your display name")
            return
        }

        viewModelScope.launch {
            _signInState.value = SignInUiState(isLoading = true)
            try {
                repository.signIn(domain, secretKey, normalizedPhone, name.trim())
                MessengerForegroundService.start(context)
                ConnectionWatchdogWorker.schedule(context)
                val shouldAskContacts = !contactsSyncManager.hasContactsPermission() && !settings.value.contactsPromptShown
                _signInState.value = SignInUiState(
                    isLoading = false,
                    askContactsPermission = shouldAskContacts,
                )
            } catch (t: Throwable) {
                _signInState.value = SignInUiState(
                    isLoading = false,
                    error = t.message ?: "Connection failed",
                )
            }
        }
    }

    fun dismissContactsPrompt(markShown: Boolean = true) {
        _signInState.value = _signInState.value.copy(askContactsPermission = false)
        if (markShown) {
            viewModelScope.launch { repository.setContactsPromptShown() }
        }
    }

    fun syncContacts() {
        viewModelScope.launch {
            contactsSyncManager.syncDeviceContacts()
        }
    }

    fun refreshDirectory() {
        viewModelScope.launch {
            _isRefreshing.value = true
            runCatching { repository.refreshDirectory() }
                .onFailure { _statusBanner.value = it.message }
            _isRefreshing.value = false
        }
    }

    fun reconnectWs() {
        repository.reconnectWebSocket()
    }

    suspend fun openDirectChat(peerPhone: String): String {
        repository.ensureDirectChat(peerPhone)
        return peerPhone
    }

    fun togglePin(chatId: String, currentPinned: Boolean) {
        viewModelScope.launch { repository.setChatPinned(chatId, !currentPinned) }
    }

    fun toggleMute(chatId: String, currentMuted: Boolean) {
        viewModelScope.launch { repository.setChatMuted(chatId, !currentMuted) }
    }

    fun toggleArchive(chatId: String, currentArchived: Boolean) {
        viewModelScope.launch { repository.setChatArchived(chatId, !currentArchived) }
    }

    fun markRead(chatId: String) {
        viewModelScope.launch { repository.markChatRead(chatId) }
    }

    fun deleteChat(chatId: String) {
        viewModelScope.launch { repository.deleteChatLocally(chatId) }
    }

    fun startCall(peerPhone: String, isVideo: Boolean) {
        callManager.startOutgoingCall(peerPhone, isVideo)
    }

    fun createGroup(name: String, members: List<String>, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            repository.createGroup(name, members)
                .onSuccess { dto -> onCreated("group:${dto.id}") }
                .onFailure { _statusBanner.value = it.message }
        }
    }

    fun updateMyName(newName: String) {
        viewModelScope.launch {
            repository.updateMyDisplayName(newName)
                .onFailure { _statusBanner.value = it.message }
        }
    }

    fun uploadMyAvatar(jpegBytes: ByteArray, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            repository.uploadMyAvatar(jpegBytes)
                .onSuccess { onDone() }
                .onFailure { _statusBanner.value = it.message }
        }
    }

    fun removeMyAvatar() {
        viewModelScope.launch {
            repository.removeMyAvatar()
                .onFailure { _statusBanner.value = it.message }
        }
    }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { repository.setThemeMode(mode) }
    fun setWallpaperId(id: Int) = viewModelScope.launch { repository.setWallpaperId(id) }
    fun setNotificationSound(enabled: Boolean) = viewModelScope.launch { repository.setNotificationSound(enabled) }
    fun setNotificationVibration(enabled: Boolean) = viewModelScope.launch { repository.setNotificationVibration(enabled) }
    fun setAutoDownloadWifi(enabled: Boolean) = viewModelScope.launch { repository.setAutoDownloadWifi(enabled) }
    fun setAllowSelfSigned(enabled: Boolean) = viewModelScope.launch { repository.setAllowSelfSigned(enabled) }
    fun setAppLock(enabled: Boolean, pin: String? = null) = viewModelScope.launch {
        repository.setAppLock(enabled, pin?.takeIf { it.isNotBlank() }?.let { sha256Hex(it) })
    }

    fun loadServerConfig() {
        viewModelScope.launch {
            runCatching { repository.refreshConfig() }
                .onSuccess { _serverConfig.value = it }
        }
    }

    fun getCacheSizeBytes(): Long = repository.calculateCacheUsageBytes()

    fun clearCache(onCleared: () -> Unit) {
        viewModelScope.launch {
            repository.clearCache()
            onCleared()
        }
    }

    fun logout(onComplete: () -> Unit) {
        viewModelScope.launch {
            ConnectionWatchdogWorker.cancel(context)
            MessengerForegroundService.stop(context)
            repository.logoutAndWipeAll()
            onComplete()
        }
    }

    fun clearStatusBanner() {
        _statusBanner.value = null
    }
}
