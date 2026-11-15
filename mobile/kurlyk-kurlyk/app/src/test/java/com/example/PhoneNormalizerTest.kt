package com.example

import com.example.data.crypto.PhoneNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneNormalizerTest {

    @Test
    fun `normalizes Russian 8-prefix 11-digit numbers`() {
        assertEquals("+79991234567", PhoneNormalizer.normalize("8 (999) 123-45-67"))
        assertEquals("+79991234567", PhoneNormalizer.normalize("89991234567"))
    }

    @Test
    fun `normalizes international formats`() {
        assertEquals("+14155552671", PhoneNormalizer.normalize("+1 415-555-2671"))
        assertEquals("+447911123456", PhoneNormalizer.normalize("44.7911.123456"))
    }

    @Test
    fun `rejects invalid phone numbers`() {
        assertNull(PhoneNormalizer.normalize("+01234567"))
        assertNull(PhoneNormalizer.normalize("12345"))
        assertNull(PhoneNormalizer.normalize("abc"))
    }

    @Test
    fun `normalizes server domains stripping schemes and paths`() {
        assertEquals("chat.example.com", PhoneNormalizer.normalizeDomain("https://chat.example.com/chat"))
        assertEquals("chat.example.com:8443", PhoneNormalizer.normalizeDomain("  wss://chat.example.com:8443/ws  "))
        assertNull(PhoneNormalizer.normalizeDomain("localhost"))
        assertNull(PhoneNormalizer.normalizeDomain(""))
    }
}
