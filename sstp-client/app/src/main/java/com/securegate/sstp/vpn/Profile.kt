package com.securegate.sstp.vpn

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class Profile(
    val server: String = "",
    val user: String = "",
    val password: String = "",
    val fullTunnel: Boolean = true,
    val trustAll: Boolean = false,
    val autoReconnect: Boolean = true,
) {
    /** "host", "host:port" or a pasted URL -> (host, port). */
    fun hostPort(): Pair<String, Int> {
        var s = server.trim().removePrefix("https://").removePrefix("http://").removePrefix("sstp://")
        s = s.substringBefore('/')
        val idx = s.lastIndexOf(':')
        if (idx > 0 && !s.startsWith("[")) {
            val p = s.substring(idx + 1).toIntOrNull()
            if (p != null && p in 1..65535) return s.substring(0, idx) to p
        }
        return s to 443
    }

    val isValid: Boolean get() = hostPort().first.isNotBlank() && user.isNotBlank() && password.isNotEmpty()
}

object Profiles {
    private fun prefs(ctx: Context): SharedPreferences = try {
        val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            ctx, "sstp_profile", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (_: Exception) {
        ctx.getSharedPreferences("sstp_profile_plain", Context.MODE_PRIVATE)
    }

    fun load(ctx: Context): Profile {
        val p = prefs(ctx)
        return Profile(
            server = p.getString("server", "") ?: "",
            user = p.getString("user", "") ?: "",
            password = p.getString("password", "") ?: "",
            fullTunnel = p.getBoolean("fullTunnel", true),
            trustAll = p.getBoolean("trustAll", false),
            autoReconnect = p.getBoolean("autoReconnect", true),
        )
    }

    fun save(ctx: Context, profile: Profile) {
        prefs(ctx).edit()
            .putString("server", profile.server.trim())
            .putString("user", profile.user.trim())
            .putString("password", profile.password)
            .putBoolean("fullTunnel", profile.fullTunnel)
            .putBoolean("trustAll", profile.trustAll)
            .putBoolean("autoReconnect", profile.autoReconnect)
            .apply()
    }
}
