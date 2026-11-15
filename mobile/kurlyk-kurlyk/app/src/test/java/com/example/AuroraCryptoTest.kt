package com.example

import com.example.data.crypto.AuroraCrypto
import com.example.data.crypto.AuroraCrypto.hexToBytes
import com.example.data.crypto.AuroraCrypto.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import javax.crypto.AEADBadTagException

class AuroraCryptoTest {

    @Test
    fun `deriveKeys matches specification test vectors`() {
        val secret = "TestSecretKey-1234567890"
        val keys = AuroraCrypto.deriveKeys(secret)
        assertEquals(
            "6314c4ec96086bc37f5b120965615c2d8837324b288881af331a96648b779cd2",
            keys.authKey.toHex(),
        )
        assertEquals(
            "c59e462bf7a62273f5206e32b0dc13dbdaed115b43756a23f4165e5bb77fc3cc",
            keys.contentKey.toHex(),
        )
    }

    @Test
    fun `signAuth matches specification test vector`() {
        val keys = AuroraCrypto.deriveKeys("TestSecretKey-1234567890")
        val sig = AuroraCrypto.signAuth(
            authKey = keys.authKey,
            phone = "+79990000001",
            ts = 1700000000L,
            nonceHex = "00112233445566778899aabbccddeeff",
        )
        assertEquals(
            "222b86d7c628b9df1961f5289f1146f1bea0c86d69eb47d9cdd92159974e0d0a",
            sig,
        )
    }

    @Test
    fun `encryptPayload and decryptPayload match specification test vector`() {
        val keys = AuroraCrypto.deriveKeys("TestSecretKey-1234567890")
        val iv = "000102030405060708090a0b".hexToBytes()
        val plaintext = """{"kind":"text","text":"hi"}"""
        val payload = AuroraCrypto.encryptPayload(keys.contentKey, plaintext, iv)
        assertEquals(
            "AAECAwQFBgcICQoLSU+9NwwM7hx2xaYw6Q6nBuAwVrwyWEX/tkH3Gkjuh1/RUw5JFHHJd5J9/w==",
            payload,
        )
        val decrypted = AuroraCrypto.decryptPayload(keys.contentKey, payload)
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun `decryptPayload fails with wrong secret key`() {
        val keys1 = AuroraCrypto.deriveKeys("TestSecretKey-1234567890")
        val keys2 = AuroraCrypto.deriveKeys("WrongSecretKey")
        val payload = AuroraCrypto.encryptPayload(keys1.contentKey, """{"kind":"text","text":"secret"}""")
        assertThrows(AEADBadTagException::class.java) {
            AuroraCrypto.decryptPayload(keys2.contentKey, payload)
        }
    }

    @Test
    fun `streaming file encryption round-trips accurately`() {
        val keys = AuroraCrypto.deriveKeys("TestSecretKey-1234567890")
        val src = File.createTempFile("aurora_src", ".bin")
        val enc = File.createTempFile("aurora_enc", ".bin")
        val dec = File.createTempFile("aurora_dec", ".bin")
        try {
            val data = ByteArray(256 * 1024) { (it % 251).toByte() }
            src.writeBytes(data)
            AuroraCrypto.encryptFileStreaming(keys.contentKey, src, enc)
            AuroraCrypto.decryptFileStreaming(keys.contentKey, enc, dec)
            assertEquals(data.toList(), dec.readBytes().toList())
        } finally {
            src.delete()
            enc.delete()
            dec.delete()
        }
    }
}
