package com.securegate.sstp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * End-to-end test against a real SSTP server (skipped unless SSTP_TEST_HOST is set):
 *   SSTP_TEST_HOST=192.168.1.148 SSTP_TEST_USER=keenetic SSTP_TEST_PASS=... gradle testDebugUnitTest
 * Connects, gets an IP via IPCP and pings the PPP peer and 8.8.8.8 through the tunnel.
 */
class SstpLiveTest {
    @Test fun connectAndPing() {
        val host = System.getenv("SSTP_TEST_HOST")
        assumeTrue("SSTP_TEST_HOST not set - live test skipped", !host.isNullOrBlank())
        val cfg = SstpConfig(
            host = host!!,
            port = System.getenv("SSTP_TEST_PORT")?.toIntOrNull() ?: 443,
            user = System.getenv("SSTP_TEST_USER") ?: "keenetic",
            password = System.getenv("SSTP_TEST_PASS") ?: "",
            trustAll = true,
        )
        val stats = Stats()
        val s = SstpSession(cfg, { println("[sstp] $it") }, stats)
        val ip = s.connect()
        println("assigned ${ip.address.hostAddress} peer ${ip.peer?.hostAddress} dns ${ip.dns}")
        assertNotNull(ip.peer)

        val replies = LinkedBlockingQueue<ByteArray>()
        val loop = thread(isDaemon = true) {
            runCatching { s.run { b, o, l -> replies.add(b.copyOfRange(o, o + l)) } }
        }
        try {
            for ((i, target) in listOf(ip.peer!!, InetAddress.getByName("8.8.8.8")).withIndex()) {
                val id = 0x1234 + i
                val pkt = icmpEcho(ip.address.address, target.address, id, 1)
                s.sendIp(pkt, 0, pkt.size)
                val deadline = System.currentTimeMillis() + 5000
                var ok = false
                while (System.currentTimeMillis() < deadline && !ok) {
                    val r = replies.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    val ihl = (r[0].toInt() and 0x0f) * 4
                    if (r[9].toInt() == 1 && r[ihl].toInt() == 0 &&
                        (((r[ihl + 4].toInt() and 0xff) shl 8) or (r[ihl + 5].toInt() and 0xff)) == id
                    ) ok = true
                }
                if (!ok) fail("no ICMP echo reply from ${target.hostAddress}")
                println("ping ${target.hostAddress}: OK")
            }
            println("traffic in=${stats.bytesIn.get()} out=${stats.bytesOut.get()}")
        } finally {
            s.close()
            loop.join(3000)
        }
    }

    private fun checksum(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0L
        var i = off
        while (i < off + len - 1) {
            sum += ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff); i += 2
        }
        if (len % 2 == 1) sum += (b[off + len - 1].toInt() and 0xff) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xffff) + (sum shr 16)
        return (sum.inv() and 0xffff).toInt()
    }

    private fun icmpEcho(src: ByteArray, dst: ByteArray, id: Int, seq: Int): ByteArray {
        val payload = "securegate-sstp-test".toByteArray()
        val p = ByteArray(20 + 8 + payload.size)
        p[0] = 0x45; p[2] = (p.size shr 8).toByte(); p[3] = p.size.toByte()
        p[8] = 64; p[9] = 1
        System.arraycopy(src, 0, p, 12, 4); System.arraycopy(dst, 0, p, 16, 4)
        val c = checksum(p, 0, 20); p[10] = (c shr 8).toByte(); p[11] = c.toByte()
        p[20] = 8
        p[24] = (id shr 8).toByte(); p[25] = id.toByte(); p[26] = (seq shr 8).toByte(); p[27] = seq.toByte()
        System.arraycopy(payload, 0, p, 28, payload.size)
        val ic = checksum(p, 20, p.size - 20); p[22] = (ic shr 8).toByte(); p[23] = ic.toByte()
        assertEquals(0, checksum(p, 20, p.size - 20))
        return p
    }
}
