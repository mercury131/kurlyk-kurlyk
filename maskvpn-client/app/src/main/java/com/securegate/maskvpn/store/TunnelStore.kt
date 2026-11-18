package com.securegate.maskvpn.store

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.securegate.maskvpn.core.TunnelConfig
import org.json.JSONArray

/** Stores tunnel profiles (secrets) in Android Keystore-backed encrypted preferences. */
class TunnelStore(context: Context) {
    private val prefs = run {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "maskvpn", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun list(): List<TunnelConfig> {
        val raw = prefs.getString(KEY_TUNNELS, "[]") ?: "[]"
        val arr = JSONArray(raw)
        return (0 until arr.length()).mapNotNull {
            runCatching { TunnelConfig.parse(arr.getString(it)) }.getOrNull()
        }
    }

    fun get(name: String): TunnelConfig? = list().firstOrNull { it.name == name }

    /** Add or replace a tunnel (keyed by name). Returns the stored config. */
    fun add(cfg: TunnelConfig): TunnelConfig {
        val updated = list().filter { it.name != cfg.name } + cfg
        save(updated)
        return cfg
    }

    fun remove(name: String) = save(list().filter { it.name != name })

    private fun save(items: List<TunnelConfig>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_TUNNELS, arr.toString()).apply()
    }

    var allowSelfSigned: Boolean
        get() = prefs.getBoolean(KEY_SELF_SIGNED, false)
        set(v) { prefs.edit().putBoolean(KEY_SELF_SIGNED, v).apply() }

    companion object {
        private const val KEY_TUNNELS = "tunnels"
        private const val KEY_SELF_SIGNED = "allow_self_signed"
    }
}
