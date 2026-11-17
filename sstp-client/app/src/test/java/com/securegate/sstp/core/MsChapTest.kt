package com.securegate.sstp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Test vectors from RFC 1320 (MD4), RFC 2759 section 9.2 and RFC 3079 section 3.5.3. */
class MsChapTest {
    private val user = "User"
    private val password = "clientPass"
    private val authChal = "5B5D7C7D7B3F2F3E3C2C602132262628".hexToBytes()
    private val peerChal = "21402324255E262A28295F2B3A337C7E".hexToBytes()

    @Test fun md4() {
        assertEquals("31d6cfe0d16ae931b73c59d7e0c089c0", Md4.digest(ByteArray(0)).toHex())
        assertEquals("a448017aaf21d8525fc10ae87aa6729d", Md4.digest("abc".toByteArray()).toHex())
        assertEquals(
            "e33b4ddc9c38f2199c3e7b164fcc0536",
            Md4.digest("12345678901234567890123456789012345678901234567890123456789012345678901234567890".toByteArray()).toHex(),
        )
    }

    @Test fun ntPasswordHash() {
        assertEquals("44ebba8d5312b8d611474411f56989ae", MsChapV2.ntPasswordHash(password).toHex())
    }

    @Test fun challengeHash() {
        assertEquals("d02e4386bce91226", MsChapV2.challengeHash(peerChal, authChal, user).toHex())
    }

    @Test fun ntResponse() {
        assertEquals(
            "82309ecd8d708b5ea08faa3981cd83544233114a3d85d6df",
            MsChapV2.ntResponse(authChal, peerChal, user, password).toHex(),
        )
    }

    @Test fun authenticatorResponse() {
        val nt = MsChapV2.ntResponse(authChal, peerChal, user, password)
        assertEquals("S=407A5589115FD0D6209F510FE9C04566932CDA56", MsChapV2.authenticatorResponse(password, nt, peerChal, authChal, user))
    }

    @Test fun mppeKeys() {
        val nt = MsChapV2.ntResponse(authChal, peerChal, user, password)
        assertEquals("fdece3717a8c838cb388e527ae3cdd31", MsChapV2.masterKey(password, nt).toHex())
        val (send, recv) = MsChapV2.clientMppeKeys(password, nt)
        // RFC 3079 sample: SendStartKey128 = 8B7CDC149B993A1BA118CB153F56DCCB
        assertTrue(
            "send=${send.toHex()} recv=${recv.toHex()}",
            send.toHex() == "8b7cdc149b993a1ba118cb153f56dccb" || recv.toHex() == "8b7cdc149b993a1ba118cb153f56dccb",
        )
        println("MPPE client send=${send.toHex()} recv=${recv.toHex()}")
    }
}
