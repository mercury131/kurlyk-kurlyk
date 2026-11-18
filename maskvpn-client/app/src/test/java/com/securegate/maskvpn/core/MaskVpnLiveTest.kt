package com.securegate.maskvpn.core

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Live test against a running securegate server with VPN_ENABLE=true. Defaults target the sandbox;
 * override with -Dvpn.host / -Dvpn.sni / -Dvpn.secret / -Dvpn.port. If the server is unreachable the
 * test is skipped (so the unit suite still passes without a server).
 */
class MaskVpnLiveTest {
    private val host = System.getProperty("vpn.host", "192.168.1.148")
    private val sni = System.getProperty("vpn.sni", "sg.example.net")
    private val secret = System.getProperty("vpn.secret", "TestVpnSecret-1234567890")
    private val port = System.getProperty("vpn.port", "443").toInt()

    @Test fun connectAndPingGateway() {
        try {
            Socket().use { it.connect(InetSocketAddress(host, port), 3000) }
        } catch (e: Exception) {
            assumeNoException("server $host:$port not reachable - skipping live test", e)
        }
        val cfg = TunnelConfig("live", host, port, "/app/events", sni, secret, "tcp-https")
        val s = MaskVpnSession(cfg, allowSelfSigned = true)
        val sc = s.connect()
        try {
            val myIp = sc.address.substringBefore("/")
            println("live: address=$myIp gateway=${sc.gateway} dns=${sc.dns} mtu=${sc.mtu}")
            s.writePacket(Ip.icmpEcho(myIp, sc.gateway, 0xBEEF, 1))
            val deadline = System.currentTimeMillis() + 6000
            var got = false
            while (System.currentTimeMillis() < deadline && !got) {
                val (op, pkt) = s.readFrame()
                if (op == Op.IP && Ip.isIcmpEchoReplyFrom(pkt, sc.gateway)) got = true
            }
            assertTrue("no ICMP echo reply from tunnel gateway ${sc.gateway}", got)
            println("live: ICMP echo reply from ${sc.gateway} OK - L3 tunnel works")
        } finally {
            s.close()
        }
    }
}

/** Minimal IPv4 + ICMP helpers for the live test. */
object Ip {
    private fun checksum(b: ByteArray, len: Int = b.size): Int {
        var sum = 0L
        var i = 0
        while (i + 1 < len) { sum += ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF); i += 2 }
        if (i < len) sum += (b[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun ipBytes(ip: String) = ip.split(".").map { it.toInt().toByte() }.toByteArray()

    fun icmpEcho(src: String, dst: String, id: Int, seq: Int, payload: ByteArray = "securegate".toByteArray()): ByteArray {
        val icmp = ByteArray(8 + payload.size)
        icmp[0] = 8 // echo request
        icmp[4] = (id ushr 8).toByte(); icmp[5] = id.toByte()
        icmp[6] = (seq ushr 8).toByte(); icmp[7] = seq.toByte()
        System.arraycopy(payload, 0, icmp, 8, payload.size)
        val ic = checksum(icmp)
        icmp[2] = (ic ushr 8).toByte(); icmp[3] = ic.toByte()

        val total = 20 + icmp.size
        val ip = ByteArray(total)
        ip[0] = 0x45; ip[2] = (total ushr 8).toByte(); ip[3] = total.toByte()
        ip[4] = 0x12; ip[5] = 0x34; ip[8] = 64; ip[9] = 1 // TTL, proto=ICMP
        System.arraycopy(ipBytes(src), 0, ip, 12, 4)
        System.arraycopy(ipBytes(dst), 0, ip, 16, 4)
        val hc = checksum(ip, 20)
        ip[10] = (hc ushr 8).toByte(); ip[11] = hc.toByte()
        System.arraycopy(icmp, 0, ip, 20, icmp.size)
        return ip
    }

    fun isIcmpEchoReplyFrom(pkt: ByteArray, from: String): Boolean {
        if (pkt.size < 28 || (pkt[0].toInt() and 0xF0) != 0x40 || pkt[9].toInt() != 1) return false
        val src = (0 until 4).joinToString(".") { (pkt[12 + it].toInt() and 0xFF).toString() }
        val ihl = (pkt[0].toInt() and 0x0F) * 4
        return src == from && pkt.size > ihl && pkt[ihl].toInt() == 0 // ICMP echo reply
    }
}
