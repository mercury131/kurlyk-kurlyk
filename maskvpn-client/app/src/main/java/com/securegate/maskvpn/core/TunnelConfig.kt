package com.securegate.maskvpn.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * A tunnel profile as produced by `./sgctl vpn ...` and carried by a `sgvpn://` link (QR code) or a
 * `.sgvpn` file. The link is `sgvpn://` + base64url(JSON); the file holds the same JSON.
 */
data class TunnelConfig(
    val name: String,
    val host: String,
    val port: Int,
    val path: String,
    val sni: String,
    val secret: String,
    val transport: String,
) {
    fun toJson(): String = JSONObject()
        .put("v", 1).put("name", name).put("host", host).put("port", port)
        .put("path", path).put("sni", sni).put("secret", secret).put("transport", transport)
        .toString()

    /** `sgvpn://` link for a QR code. */
    fun toUri(): String = "sgvpn://" + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(toJson().toByteArray(Charsets.UTF_8))

    companion object {
        /** Parse a `sgvpn://` link or raw JSON (the content of a `.sgvpn` file). */
        fun parse(input: String): TunnelConfig {
            val s = input.trim()
            val json = when {
                s.startsWith("sgvpn://", ignoreCase = true) -> {
                    val b64 = s.substring("sgvpn://".length).trim().trimEnd('/')
                    String(Base64.getUrlDecoder().decode(padB64(b64)), Charsets.UTF_8)
                }
                s.startsWith("{") -> s
                else -> throw IllegalArgumentException("not a sgvpn:// link or JSON config")
            }
            val o = JSONObject(json)
            return TunnelConfig(
                name = o.optString("name", "tunnel"),
                host = o.getString("host"),
                port = if (o.has("port")) o.getInt("port") else 443,
                path = o.optString("path", "/app/events"),
                sni = o.optString("sni", o.getString("host")),
                secret = o.getString("secret"),
                transport = o.optString("transport", "tcp-https"),
            )
        }

        private fun padB64(s: String): String {
            val m = s.length % 4
            return if (m == 0) s else s + "=".repeat(4 - m)
        }
    }
}

/** Address/routes the server assigns right after the handshake (opConfig frame). */
data class SessionConfig(
    val address: String,
    val gateway: String,
    val dns: List<String>,
    val mtu: Int,
    val routes: List<String>,
) {
    companion object {
        fun parse(json: String): SessionConfig {
            val o = JSONObject(json)
            fun arr(k: String): List<String> {
                val a = o.optJSONArray(k) ?: JSONArray()
                return (0 until a.length()).map { a.getString(it) }
            }
            return SessionConfig(
                address = o.getString("address"),
                gateway = o.getString("gateway"),
                dns = arr("dns"),
                mtu = o.optInt("mtu", 1380),
                routes = arr("routes"),
            )
        }
    }
}
