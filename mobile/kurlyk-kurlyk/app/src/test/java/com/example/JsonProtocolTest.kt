package com.example

import com.example.data.api.AuroraJson
import com.example.data.api.ConfigResponse
import com.example.data.api.EncryptedMessageBody
import com.example.data.api.WsRawEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonProtocolTest {

    @Test
    fun `parses all server WebSocket event types cleanly`() {
        val hello = AuroraJson.decodeFromString<WsRawEvent>(
            """{"type":"hello","phone":"+79990000001","server_time":1700000000000,"extra_field":123}"""
        )
        assertEquals("hello", hello.type)
        assertEquals(1700000000000L, hello.serverTime)

        val sent = AuroraJson.decodeFromString<WsRawEvent>(
            """{"type":"sent","id":"msg-1","ts":1700000000100}"""
        )
        assertEquals("sent", sent.type)
        assertEquals("msg-1", sent.id)

        val msg = AuroraJson.decodeFromString<WsRawEvent>(
            """{"type":"msg","qid":"00000000000000a1","id":"m1","from":"+79990000002","to":"group:g1","group":"g1","payload":"abc","ts":1700000000200}"""
        )
        assertEquals("msg", msg.type)
        assertEquals("00000000000000a1", msg.qid)

        val receipt = AuroraJson.decodeFromString<WsRawEvent>(
            """{"type":"receipt","qid":"q2","ids":["m1","m2"],"by":"+79990000002","status":"read","ts":1700000000300}"""
        )
        assertEquals(listOf("m1", "m2"), receipt.ids)
        assertEquals("read", receipt.status)

        val groupEv = AuroraJson.decodeFromString<WsRawEvent>(
            """{"type":"group","qid":"q3","event":"upsert","group":{"id":"g1","name":"Family","owner":"+79990000001","members":["+79990000001","+79990000002"]},"ts":1700000000400}"""
        )
        assertEquals("upsert", groupEv.event)

        val presence = AuroraJson.decodeFromString<WsRawEvent>(
            """{"type":"presence","phone":"+79990000002","online":true,"last_seen":1700000000}"""
        )
        assertTrue(presence.online == true)

        val userEv = AuroraJson.decodeFromString<WsRawEvent>(
            """{"type":"user","user":{"phone":"+79990000002","name":"Bob","online":false,"last_seen":1700000100,"avatar_ver":1700000100000}}"""
        )
        assertEquals(1700000100000L, userEv.user?.avatarVer)

        val typing = AuroraJson.decodeFromString<WsRawEvent>(
            """{"type":"typing","from":"+79990000002","ts":1700000000500}"""
        )
        assertEquals("typing", typing.type)

        val call = AuroraJson.decodeFromString<WsRawEvent>(
            """{"type":"call","from":"+79990000002","call_id":"c1","action":"offer","data":{"sdp":"v=0...","video":true},"ts":1700000000600}"""
        )
        assertEquals("offer", call.action)

        val err = AuroraJson.decodeFromString<WsRawEvent>(
            """{"type":"error","id":"e1","code":"replaced","message":"Signed in elsewhere"}"""
        )
        assertEquals("replaced", err.code)

        val pong = AuroraJson.decodeFromString<WsRawEvent>(
            """{"type":"pong","server_time":1700000000700}"""
        )
        assertEquals("pong", pong.type)
    }

    @Test
    fun `parses config and unknown encrypted message kinds gracefully`() {
        val cfg = AuroraJson.decodeFromString<ConfigResponse>(
            """{"version":"1.0","server_time":1700000000,"max_file_mb":100,"file_ttl_h":168,"queue_ttl_h":720,"max_users":100,"ws_url":"wss://chat.example.com/chat/ws","ice_servers":[{"urls":["stun:chat.example.com:3478"]}]}"""
        )
        assertEquals(100, cfg.maxFileMb)
        assertFalse(cfg.iceServers.isEmpty())

        val futureBody = AuroraJson.decodeFromString<EncryptedMessageBody>(
            """{"kind":"future_sticker_v2","stickerId":"st1","msgTs":1700000000000}"""
        )
        assertEquals("future_sticker_v2", futureBody.kind)
    }
}
