package com.securegate.maskvpn.core

import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Crypto matching the server (internal/maskvpn) byte for byte:
 *   authKey = HMAC-SHA256(secret, "securegate-maskvpn-auth-v1")
 *   tunKey  = HMAC-SHA256(secret, "securegate-maskvpn-tun-v1")
 *   proof cookie = base64url( ts(8, BE) || salt(8) || HMAC-SHA256(authKey, "proof"||ts||salt)[:16] )
 *   session keys = HKDF-SHA256(tunKey, salt=clientRand||serverRand, info="securegate-maskvpn-v1", 64)
 *   AEAD = ChaCha20-Poly1305, 12-byte nonce = 4 zero bytes || big-endian 64-bit frame counter.
 */
object Crypto {
    private val rng = SecureRandom()

    fun random(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    fun hmacSha256(key: ByteArray, msg: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(msg)
    }

    fun authKey(secret: String) = hmacSha256(secret.toByteArray(Charsets.UTF_8), "securegate-maskvpn-auth-v1".toByteArray())
    fun tunKey(secret: String) = hmacSha256(secret.toByteArray(Charsets.UTF_8), "securegate-maskvpn-tun-v1".toByteArray())

    /** Build the auth cookie value for the current (or given) unix time. */
    fun makeProof(authKey: ByteArray, tsSec: Long = System.currentTimeMillis() / 1000): String {
        val data = ByteBuffer.allocate(16)
        data.putLong(tsSec)
        data.put(random(8))
        val d = data.array()
        val tag = hmacSha256(authKey, "proof".toByteArray() + d).copyOf(16)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(d + tag)
    }

    /** HKDF-SHA256 (RFC 5869). */
    fun hkdf(key: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmacSha256(salt, key)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var filled = 0
        var i = 1
        while (filled < length) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t); mac.update(info); mac.update(i.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, length - filled)
            System.arraycopy(t, 0, out, filled, n)
            filled += n; i++
        }
        return out
    }

    fun nonce(counter: Long): ByteArray = ByteBuffer.allocate(12).put(ByteArray(4)).putLong(counter).array()

    fun chacha(key: ByteArray): AeadKey = AeadKey(key)
}

/** A one-direction ChaCha20-Poly1305 key with an implicit frame counter (nonce never sent). */
class AeadKey(private val key: ByteArray) {
    private var counter = 0L

    fun seal(plaintext: ByteArray): ByteArray {
        val c = Cipher.getInstance("ChaCha20-Poly1305")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(Crypto.nonce(counter)))
        val out = c.doFinal(plaintext)
        counter++
        return out
    }

    fun open(ciphertext: ByteArray): ByteArray {
        val c = Cipher.getInstance("ChaCha20-Poly1305")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(Crypto.nonce(counter)))
        val out = c.doFinal(ciphertext)
        counter++
        return out
    }
}
