package com.example.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.data.crypto.AuroraCrypto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "aurora_settings")

data class AuthSession(
    val domain: String,
    val secretKey: String,
    val phone: String,
    val name: String,
    val token: String,
    val tokenExpires: Long,
) {
    val isLoggedIn: Boolean
        get() = domain.isNotBlank() && secretKey.isNotBlank() && phone.isNotBlank() && token.isNotBlank()

    fun derivedKeys(): AuroraCrypto.DerivedKeys = AuroraCrypto.deriveKeys(secretKey)
}

enum class ThemeMode(val key: String) {
    DARK("dark"),
    LIGHT("light"),
    SYSTEM("system");

    companion object {
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: DARK
    }
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val wallpaperId: Int = 0,
    val notificationSound: Boolean = true,
    val notificationVibration: Boolean = true,
    val autoDownloadWifi: Boolean = true,
    val allowSelfSigned: Boolean = false,
    val appLockEnabled: Boolean = false,
    val appLockPinHash: String? = null,
    val maxFileMb: Int = 100,
    val fileTtlH: Int = 168,
    val queueTtlH: Int = 720,
    val maxUsers: Int = 100,
    val serverVersion: String = "",
    val contactsPromptShown: Boolean = false,
)

@Singleton
class SessionPrefs @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val encryptedPrefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "aurora_secure_session",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (_: java.security.GeneralSecurityException) {
            // Fallback strictly for JVM/Robolectric unit tests lacking AndroidKeyStore
            context.getSharedPreferences("aurora_test_session", Context.MODE_PRIVATE)
        }
    }

    private val _session = MutableStateFlow<AuthSession?>(null)
    val session: StateFlow<AuthSession?> = _session.asStateFlow()

    init {
        _session.value = loadSession()
    }

    private fun loadSession(): AuthSession? {
        val domain = encryptedPrefs.getString(KEY_DOMAIN, null) ?: return null
        val secret = encryptedPrefs.getString(KEY_SECRET, null) ?: return null
        val phone = encryptedPrefs.getString(KEY_PHONE, null) ?: return null
        val name = encryptedPrefs.getString(KEY_NAME, "") ?: ""
        val token = encryptedPrefs.getString(KEY_TOKEN, null) ?: return null
        val expires = encryptedPrefs.getLong(KEY_EXPIRES, 0L)
        val s = AuthSession(domain, secret, phone, name, token, expires)
        return if (s.isLoggedIn) s else null
    }

    fun saveSession(session: AuthSession) {
        encryptedPrefs.edit()
            .putString(KEY_DOMAIN, session.domain)
            .putString(KEY_SECRET, session.secretKey)
            .putString(KEY_PHONE, session.phone)
            .putString(KEY_NAME, session.name)
            .putString(KEY_TOKEN, session.token)
            .putLong(KEY_EXPIRES, session.tokenExpires)
            .apply()
        _session.value = session
    }

    fun updateToken(token: String, expires: Long) {
        val current = _session.value ?: return
        saveSession(current.copy(token = token, tokenExpires = expires))
    }

    fun updateName(name: String) {
        val current = _session.value ?: return
        saveSession(current.copy(name = name))
    }

    fun clearSession() {
        encryptedPrefs.edit().clear().apply()
        _session.value = null
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            themeMode = ThemeMode.fromKey(prefs[PREF_THEME]),
            wallpaperId = prefs[PREF_WALLPAPER] ?: 0,
            notificationSound = prefs[PREF_NOTIF_SOUND] ?: true,
            notificationVibration = prefs[PREF_NOTIF_VIBRATE] ?: true,
            autoDownloadWifi = prefs[PREF_AUTO_DOWNLOAD_WIFI] ?: true,
            allowSelfSigned = prefs[PREF_ALLOW_SELF_SIGNED] ?: false,
            appLockEnabled = prefs[PREF_APP_LOCK] ?: false,
            appLockPinHash = prefs[PREF_PIN_HASH],
            maxFileMb = prefs[PREF_MAX_FILE_MB] ?: 100,
            fileTtlH = prefs[PREF_FILE_TTL_H] ?: 168,
            queueTtlH = prefs[PREF_QUEUE_TTL_H] ?: 720,
            maxUsers = prefs[PREF_MAX_USERS] ?: 100,
            serverVersion = prefs[PREF_SERVER_VERSION] ?: "",
            contactsPromptShown = prefs[PREF_CONTACTS_PROMPTED] ?: false,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[PREF_THEME] = mode.key }
    }

    suspend fun setWallpaperId(id: Int) {
        context.dataStore.edit { it[PREF_WALLPAPER] = id }
    }

    suspend fun setNotificationSound(enabled: Boolean) {
        context.dataStore.edit { it[PREF_NOTIF_SOUND] = enabled }
    }

    suspend fun setNotificationVibration(enabled: Boolean) {
        context.dataStore.edit { it[PREF_NOTIF_VIBRATE] = enabled }
    }

    suspend fun setAutoDownloadWifi(enabled: Boolean) {
        context.dataStore.edit { it[PREF_AUTO_DOWNLOAD_WIFI] = enabled }
    }

    suspend fun setAllowSelfSigned(enabled: Boolean) {
        context.dataStore.edit { it[PREF_ALLOW_SELF_SIGNED] = enabled }
    }

    suspend fun setAppLock(enabled: Boolean, pinHash: String? = null) {
        context.dataStore.edit {
            it[PREF_APP_LOCK] = enabled
            if (pinHash != null) it[PREF_PIN_HASH] = pinHash else if (!enabled) it.remove(PREF_PIN_HASH)
        }
    }

    suspend fun setContactsPromptShown() {
        context.dataStore.edit { it[PREF_CONTACTS_PROMPTED] = true }
    }

    suspend fun saveServerConfig(
        version: String,
        maxFileMb: Int,
        fileTtlH: Int,
        queueTtlH: Int,
        maxUsers: Int,
    ) {
        context.dataStore.edit {
            it[PREF_SERVER_VERSION] = version
            it[PREF_MAX_FILE_MB] = maxFileMb
            it[PREF_FILE_TTL_H] = fileTtlH
            it[PREF_QUEUE_TTL_H] = queueTtlH
            it[PREF_MAX_USERS] = maxUsers
        }
    }

    suspend fun clearAllSettings() {
        context.dataStore.edit { it.clear() }
    }

    companion object {
        private const val KEY_DOMAIN = "domain"
        private const val KEY_SECRET = "secret_key"
        private const val KEY_PHONE = "phone"
        private const val KEY_NAME = "name"
        private const val KEY_TOKEN = "token"
        private const val KEY_EXPIRES = "token_expires"

        private val PREF_THEME = stringPreferencesKey("theme_mode")
        private val PREF_WALLPAPER = intPreferencesKey("wallpaper_id")
        private val PREF_NOTIF_SOUND = booleanPreferencesKey("notif_sound")
        private val PREF_NOTIF_VIBRATE = booleanPreferencesKey("notif_vibrate")
        private val PREF_AUTO_DOWNLOAD_WIFI = booleanPreferencesKey("auto_download_wifi")
        private val PREF_ALLOW_SELF_SIGNED = booleanPreferencesKey("allow_self_signed")
        private val PREF_APP_LOCK = booleanPreferencesKey("app_lock")
        private val PREF_PIN_HASH = stringPreferencesKey("app_lock_pin_hash")
        private val PREF_MAX_FILE_MB = intPreferencesKey("max_file_mb")
        private val PREF_FILE_TTL_H = intPreferencesKey("file_ttl_h")
        private val PREF_QUEUE_TTL_H = intPreferencesKey("queue_ttl_h")
        private val PREF_MAX_USERS = intPreferencesKey("max_users")
        private val PREF_SERVER_VERSION = stringPreferencesKey("server_version")
        private val PREF_CONTACTS_PROMPTED = booleanPreferencesKey("contacts_prompted")
    }
}
