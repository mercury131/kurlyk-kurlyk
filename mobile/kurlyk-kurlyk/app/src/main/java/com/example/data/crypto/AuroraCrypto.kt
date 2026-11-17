package com.example.data.crypto

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object AuroraCrypto {
    private const val HMAC_SHA256 = "HmacSHA256"
    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val NONCE_BYTES = 12
    private const val AUTH_INFO = "securegate-chat-auth-v1"
    private const val CONTENT_INFO = "securegate-chat-content-v1"
    private const val STREAM_BUFFER_SIZE = 64 * 1024

    private val secureRandom = SecureRandom()

    data class DerivedKeys(
        val authKey: ByteArray,
        val contentKey: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DerivedKeys) return false
            return authKey.contentEquals(other.authKey) && contentKey.contentEquals(other.contentKey)
        }

        override fun hashCode(): Int = 31 * authKey.contentHashCode() + contentKey.contentHashCode()
    }

    fun deriveKeys(secretInput: String): DerivedKeys {
        val secretBytes = secretInput.trim().toByteArray(StandardCharsets.UTF_8)
        val authKey = hmacSha256(secretBytes, AUTH_INFO.toByteArray(StandardCharsets.UTF_8))
        val contentKey = hmacSha256(secretBytes, CONTENT_INFO.toByteArray(StandardCharsets.UTF_8))
        return DerivedKeys(authKey = authKey, contentKey = contentKey)
    }

    fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(key, HMAC_SHA256))
        return mac.doFinal(message)
    }

    fun generateNonceHex(byteLength: Int = 16): String {
        val bytes = ByteArray(byteLength)
        secureRandom.nextBytes(bytes)
        return bytes.toHex()
    }

    fun generateIv12(): ByteArray {
        val iv = ByteArray(NONCE_BYTES)
        secureRandom.nextBytes(iv)
        return iv
    }

    fun signAuth(
        authKey: ByteArray,
        phone: String,
        ts: Long,
        nonceHex: String,
    ): String {
        val msg = "v1|$phone|$ts|$nonceHex"
        val sigBytes = hmacSha256(authKey, msg.toByteArray(StandardCharsets.UTF_8))
        return sigBytes.toHex()
    }

    fun encryptPayload(
        contentKey: ByteArray,
        plaintextJson: String,
        iv: ByteArray = generateIv12(),
    ): String {
        val encrypted = encryptBytes(contentKey, plaintextJson.toByteArray(StandardCharsets.UTF_8), iv)
        return Base64.getEncoder().encodeToString(encrypted)
    }

    fun decryptPayload(
        contentKey: ByteArray,
        payloadBase64: String,
    ): String {
        val raw = Base64.getDecoder().decode(payloadBase64)
        val plainBytes = decryptBytes(contentKey, raw)
        return String(plainBytes, StandardCharsets.UTF_8)
    }

    fun encryptBytes(
        contentKey: ByteArray,
        plaintext: ByteArray,
        iv: ByteArray = generateIv12(),
    ): ByteArray {
        require(iv.size == NONCE_BYTES) { "IV must be 12 bytes" }
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(contentKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = cipher.doFinal(plaintext)
        val out = ByteArray(NONCE_BYTES + ct.size)
        System.arraycopy(iv, 0, out, 0, NONCE_BYTES)
        System.arraycopy(ct, 0, out, NONCE_BYTES, ct.size)
        return out
    }

    fun decryptBytes(
        contentKey: ByteArray,
        encryptedWithIv: ByteArray,
    ): ByteArray {
        require(encryptedWithIv.size > NONCE_BYTES + 16) { "Ciphertext too short" }
        val iv = encryptedWithIv.copyOfRange(0, NONCE_BYTES)
        val ct = encryptedWithIv.copyOfRange(NONCE_BYTES, encryptedWithIv.size)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(contentKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ct)
    }

    fun encryptFileStreaming(
        contentKey: ByteArray,
        sourceFile: File,
        destFile: File,
        iv: ByteArray = generateIv12(),
    ) {
        require(iv.size == NONCE_BYTES) { "IV must be 12 bytes" }
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(contentKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        destFile.parentFile?.mkdirs()
        try {
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(destFile).use { output ->
                    output.write(iv)
                    val buffer = ByteArray(STREAM_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        val chunk = cipher.update(buffer, 0, read)
                        if (chunk != null && chunk.isNotEmpty()) {
                            output.write(chunk)
                        }
                    }
                    val finalChunk = cipher.doFinal()
                    if (finalChunk != null && finalChunk.isNotEmpty()) {
                        output.write(finalChunk)
                    }
                }
            }
        } catch (t: Throwable) {
            destFile.delete()
            throw t
        }
    }

    fun decryptFileStreaming(
        contentKey: ByteArray,
        encryptedFile: File,
        destFile: File,
    ) {
        destFile.parentFile?.mkdirs()
        try {
            FileInputStream(encryptedFile).use { input ->
                val iv = ByteArray(NONCE_BYTES)
                var ivRead = 0
                while (ivRead < NONCE_BYTES) {
                    val n = input.read(iv, ivRead, NONCE_BYTES - ivRead)
                    if (n < 0) throw IllegalArgumentException("Encrypted file missing IV")
                    ivRead += n
                }
                val cipher = Cipher.getInstance(AES_GCM)
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(contentKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(STREAM_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        val chunk = cipher.update(buffer, 0, read)
                        if (chunk != null && chunk.isNotEmpty()) {
                            output.write(chunk)
                        }
                    }
                    val finalChunk = cipher.doFinal()
                    if (finalChunk != null && finalChunk.isNotEmpty()) {
                        output.write(finalChunk)
                    }
                }
            }
        } catch (t: Throwable) {
            destFile.delete()
            throw t
        }
    }

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    fun String.hexToBytes(): ByteArray {
        val clean = trim()
        require(clean.length % 2 == 0) { "Odd hex length" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
