package com.securegate.maskvpn.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TunnelConfigTest {
    @Test fun parseJson() {
        val c = TunnelConfig.parse(
            """{"v":1,"name":"phone","host":"vpn.example.com","port":443,"path":"/app/events",""" +
                """"sni":"vpn.example.com","secret":"deadbeef","transport":"tcp-https"}""",
        )
        assertEquals("phone", c.name)
        assertEquals("vpn.example.com", c.host)
        assertEquals(443, c.port)
        assertEquals("/app/events", c.path)
        assertEquals("deadbeef", c.secret)
    }

    @Test fun uriRoundTrip() {
        val c = TunnelConfig("ivan", "proxy.example.net", 443, "/app/events", "proxy.example.net", "s3cr3t", "tcp-https")
        val uri = c.toUri()
        assert(uri.startsWith("sgvpn://")) { "uri must use the sgvpn scheme: $uri" }
        assertEquals(c, TunnelConfig.parse(uri))
    }

    @Test fun parseUriFromServerFormat() {
        // base64url(JSON) with no padding, exactly as ./sgctl vpn prints it.
        val json = """{"v":1,"name":"a","host":"h.example.com","port":443,"path":"/app/events",""" +
            """"sni":"h.example.com","secret":"abcdef0123456789","transport":"tcp-https"}"""
        val uri = "sgvpn://" + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toByteArray())
        val c = TunnelConfig.parse(uri)
        assertEquals("h.example.com", c.host)
        assertEquals("abcdef0123456789", c.secret)
    }
}
