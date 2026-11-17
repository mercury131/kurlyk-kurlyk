package com.securegate.sstp.core

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** MD4 (RFC 1320). Not available in Android's JCA, needed for the NT password hash. */
object Md4 {
    fun digest(input: ByteArray): ByteArray {
        val msgLen = input.size
        val padLen = ((55 - msgLen) % 64 + 64) % 64
        val buf = ByteArray(msgLen + 1 + padLen + 8)
        System.arraycopy(input, 0, buf, 0, msgLen)
        buf[msgLen] = 0x80.toByte()
        val bitLen = msgLen.toLong() * 8
        for (i in 0 until 8) buf[buf.size - 8 + i] = (bitLen ushr (8 * i)).toByte()

        var a = 0x67452301
        var b = 0xefcdab89.toInt()
        var c = 0x98badcfe.toInt()
        var d = 0x10325476
        val x = IntArray(16)
        val r2 = intArrayOf(0, 4, 8, 12, 1, 5, 9, 13, 2, 6, 10, 14, 3, 7, 11, 15)
        val r3 = intArrayOf(0, 8, 4, 12, 2, 10, 6, 14, 1, 9, 5, 13, 3, 11, 7, 15)
        val s1 = intArrayOf(3, 7, 11, 19)
        val s2 = intArrayOf(3, 5, 9, 13)
        val s3 = intArrayOf(3, 9, 11, 15)
        var off = 0
        while (off < buf.size) {
            for (i in 0 until 16) {
                val p = off + i * 4
                x[i] = (buf[p].toInt() and 0xff) or ((buf[p + 1].toInt() and 0xff) shl 8) or
                    ((buf[p + 2].toInt() and 0xff) shl 16) or ((buf[p + 3].toInt() and 0xff) shl 24)
            }
            val aa = a; val bb = b; val cc = c; val dd = d
            for (i in 0 until 16) {
                val k = i
                val s = s1[i % 4]
                when (i % 4) {
                    0 -> a = Integer.rotateLeft(a + ((b and c) or (b.inv() and d)) + x[k], s)
                    1 -> d = Integer.rotateLeft(d + ((a and b) or (a.inv() and c)) + x[k], s)
                    2 -> c = Integer.rotateLeft(c + ((d and a) or (d.inv() and b)) + x[k], s)
                    else -> b = Integer.rotateLeft(b + ((c and d) or (c.inv() and a)) + x[k], s)
                }
            }
            for (i in 0 until 16) {
                val k = r2[i]
                val s = s2[i % 4]
                val q = 0x5A827999
                when (i % 4) {
                    0 -> a = Integer.rotateLeft(a + ((b and c) or (b and d) or (c and d)) + x[k] + q, s)
                    1 -> d = Integer.rotateLeft(d + ((a and b) or (a and c) or (b and c)) + x[k] + q, s)
                    2 -> c = Integer.rotateLeft(c + ((d and a) or (d and b) or (a and b)) + x[k] + q, s)
                    else -> b = Integer.rotateLeft(b + ((c and d) or (c and a) or (d and a)) + x[k] + q, s)
                }
            }
            for (i in 0 until 16) {
                val k = r3[i]
                val s = s3[i % 4]
                val q = 0x6ED9EBA1
                when (i % 4) {
                    0 -> a = Integer.rotateLeft(a + (b xor c xor d) + x[k] + q, s)
                    1 -> d = Integer.rotateLeft(d + (a xor b xor c) + x[k] + q, s)
                    2 -> c = Integer.rotateLeft(c + (d xor a xor b) + x[k] + q, s)
                    else -> b = Integer.rotateLeft(b + (c xor d xor a) + x[k] + q, s)
                }
            }
            a += aa; b += bb; c += cc; d += dd
            off += 64
        }
        val out = ByteArray(16)
        for ((j, v) in intArrayOf(a, b, c, d).withIndex()) {
            for (i in 0 until 4) out[j * 4 + i] = (v ushr (8 * i)).toByte()
        }
        return out
    }
}

/** MS-CHAPv2 (RFC 2759) and MPPE key derivation (RFC 3079). */
object MsChapV2 {
    private val AUTH_MAGIC1 = "Magic server to client signing constant".toByteArray(Charsets.US_ASCII)
    private val AUTH_MAGIC2 = "Pad to make it do more than one iteration".toByteArray(Charsets.US_ASCII)
    private val MPPE_MAGIC1 = "This is the MPPE Master Key".toByteArray(Charsets.US_ASCII)
    private val MPPE_MAGIC2 =
        "On the client side, this is the send key; on the server side, it is the receive key.".toByteArray(Charsets.US_ASCII)
    private val MPPE_MAGIC3 =
        "On the client side, this is the receive key; on the server side, it is the send key.".toByteArray(Charsets.US_ASCII)

    private fun sha1(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-1")
        for (p in parts) md.update(p)
        return md.digest()
    }

    /** User name used in ChallengeHash: without a "DOMAIN\" prefix. */
    fun bareUser(user: String): String = user.substringAfterLast('\\')

    fun ntPasswordHash(password: String): ByteArray = Md4.digest(password.toByteArray(Charsets.UTF_16LE))

    fun challengeHash(peerChallenge: ByteArray, authChallenge: ByteArray, user: String): ByteArray =
        sha1(peerChallenge, authChallenge, bareUser(user).toByteArray(Charsets.UTF_8)).copyOf(8)

    fun ntResponse(authChallenge: ByteArray, peerChallenge: ByteArray, user: String, password: String): ByteArray =
        challengeResponse(challengeHash(peerChallenge, authChallenge, user), ntPasswordHash(password))

    fun challengeResponse(challenge: ByteArray, passwordHash: ByteArray): ByteArray {
        val z = passwordHash.copyOf(21)
        return desEncrypt(z, 0, challenge) + desEncrypt(z, 7, challenge) + desEncrypt(z, 14, challenge)
    }

    fun authenticatorResponse(
        password: String, ntResponse: ByteArray, peerChallenge: ByteArray, authChallenge: ByteArray, user: String,
    ): String {
        val pwHashHash = Md4.digest(ntPasswordHash(password))
        val digest = sha1(pwHashHash, ntResponse, AUTH_MAGIC1)
        val d2 = sha1(digest, challengeHash(peerChallenge, authChallenge, user), AUTH_MAGIC2)
        return "S=" + d2.toHex().uppercase()
    }

    fun masterKey(password: String, ntResponse: ByteArray): ByteArray =
        sha1(Md4.digest(ntPasswordHash(password)), ntResponse, MPPE_MAGIC1).copyOf(16)

    fun asymmetricStartKey(masterKey: ByteArray, isSend: Boolean, isServer: Boolean): ByteArray {
        val s = if (isSend) (if (isServer) MPPE_MAGIC3 else MPPE_MAGIC2) else (if (isServer) MPPE_MAGIC2 else MPPE_MAGIC3)
        return sha1(masterKey, ByteArray(40), s, ByteArray(40) { 0xF2.toByte() }).copyOf(16)
    }

    /** Client-side (MasterSendKey, MasterReceiveKey). */
    fun clientMppeKeys(password: String, ntResponse: ByteArray): Pair<ByteArray, ByteArray> {
        val mk = masterKey(password, ntResponse)
        return asymmetricStartKey(mk, isSend = true, isServer = false) to asymmetricStartKey(mk, isSend = false, isServer = false)
    }

    private fun expandDesKey(k: ByteArray, o: Int): ByteArray {
        val b = IntArray(7) { k[o + it].toInt() and 0xff }
        val r = intArrayOf(
            b[0],
            (b[0] shl 7) or (b[1] ushr 1),
            (b[1] shl 6) or (b[2] ushr 2),
            (b[2] shl 5) or (b[3] ushr 3),
            (b[3] shl 4) or (b[4] ushr 4),
            (b[4] shl 3) or (b[5] ushr 5),
            (b[5] shl 2) or (b[6] ushr 6),
            b[6] shl 1,
        )
        return ByteArray(8) { i ->
            var v = r[i] and 0xFE
            if (Integer.bitCount(v) % 2 == 0) v = v or 1 // odd parity
            v.toByte()
        }
    }

    private fun desEncrypt(key21: ByteArray, off: Int, data: ByteArray): ByteArray {
        val k = expandDesKey(key21, off)
        // Single DES; fall back to 3DES with K1=K2=K3 (identical result) where plain DES is unavailable.
        runCatching {
            val c = Cipher.getInstance("DES/ECB/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "DES"))
            return c.doFinal(data, 0, 8)
        }
        val c = Cipher.getInstance("DESede/ECB/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k + k + k, "DESede"))
        return c.doFinal(data, 0, 8)
    }
}

fun hmac(alg: String, key: ByteArray, data: ByteArray): ByteArray {
    val m = Mac.getInstance(alg)
    m.init(SecretKeySpec(key, alg))
    return m.doFinal(data)
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
