package com.securegate.maskvpn.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Base64

/** Crypto test vectors - must match the Go server (internal/maskvpn) byte for byte. */
class CryptoTest {
    private val secret = "TestVpnSecret-1234567890"

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun authAndTunKeys() {
        assertEquals("b09491b695b5a4d7d201b635cac92a778e514ac0039f26778adaca26565c535b", hex(Crypto.authKey(secret)))
        assertEquals("4913081c11b1e75ee381361b1e2c063876dbd5e8e90def663a1b5db8b1f061a3", hex(Crypto.tunKey(secret)))
    }

    @Test fun hkdfVector() {
        val salt = ByteArray(32) { 0 } + ByteArray(32) { 1 }
        val km = Crypto.hkdf(Crypto.tunKey(secret), salt, "securegate-maskvpn-v1".toByteArray(), 64)
        assertEquals(
            "c8f6e8c8f48526cdfb62b4812a1888fbf057f1af16339e530616fe1b4a17a7a7" +
                "c15d7cbf4be63633c2a67df2f5c7bd2523ab44ce6b4a7a162d280477ec7318d5",
            hex(km),
        )
    }

    @Test fun aeadRoundTripAndCounter() {
        val key = Crypto.tunKey(secret).copyOf(32)
        val enc = Crypto.chacha(key)
        val dec = Crypto.chacha(key)
        val msg = "hello securegate".toByteArray()
        val c0 = enc.seal(msg)
        val c1 = enc.seal(msg)
        assertFalse("counter must change the ciphertext", c0.contentEquals(c1))
        assertArrayEquals(msg, dec.open(c0))
        assertArrayEquals(msg, dec.open(c1))
    }

    @Test fun proofVerifies() {
        val ak = Crypto.authKey(secret)
        val proof = Crypto.makeProof(ak, 1700000000L)
        val raw = Base64.getUrlDecoder().decode(proof)
        assertEquals(32, raw.size)
        val data = raw.copyOfRange(0, 16)
        val tag = raw.copyOfRange(16, 32)
        val expect = Crypto.hmacSha256(ak, "proof".toByteArray() + data).copyOf(16)
        assertArrayEquals(expect, tag)
    }
}
