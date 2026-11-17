package com.securegate.sstp.core

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * MS-SSTP client (pure JVM, no Android dependencies):
 *   TCP -> TLS -> HTTP "SSTP_DUPLEX_POST" -> SSTP control (CALL_CONNECT_REQUEST/ACK, CALL_CONNECTED
 *   with crypto binding) -> PPP (LCP, MS-CHAPv2, IPCP) -> IPv4 packets in SSTP data packets.
 */
class SstpException(message: String, val fatal: Boolean = false) : IOException(message)

data class SstpConfig(
    val host: String,
    val port: Int = 443,
    val user: String,
    val password: String,
    val trustAll: Boolean = false,
    val mtu: Int = 1400,
)

data class IpConfig(
    val address: InetAddress,
    val peer: InetAddress?,
    val dns: List<InetAddress>,
    val mtu: Int,
)

class Stats {
    val bytesIn = AtomicLong()
    val bytesOut = AtomicLong()
}

class SstpSession(
    private val cfg: SstpConfig,
    private val log: (String) -> Unit,
    private val stats: Stats = Stats(),
    private val socketFactory: () -> Socket = { Socket() },
) {
    companion object {
        private const val SSTP_VERSION = 0x10
        private const val MSG_CALL_CONNECT_REQUEST = 0x0001
        private const val MSG_CALL_CONNECT_ACK = 0x0002
        private const val MSG_CALL_CONNECT_NAK = 0x0003
        private const val MSG_CALL_CONNECTED = 0x0004
        private const val MSG_CALL_ABORT = 0x0005
        private const val MSG_CALL_DISCONNECT = 0x0006
        private const val MSG_CALL_DISCONNECT_ACK = 0x0007
        private const val MSG_ECHO_REQUEST = 0x0008
        private const val MSG_ECHO_RESPONSE = 0x0009
        private const val ATTR_ENCAP_PROTO = 1
        private const val ATTR_CRYPTO_BINDING = 3
        private const val ATTR_CRYPTO_BINDING_REQ = 4

        const val PROTO_IP = 0x0021
        const val PROTO_IPCP = 0x8021
        const val PROTO_LCP = 0xC021
        const val PROTO_CHAP = 0xC223

        private val CHAP_MSCHAPV2 = byteArrayOf(0xC2.toByte(), 0x23, 0x81.toByte())
    }

    private class Packet(val control: Boolean, val body: ByteArray)

    private var socket: SSLSocket? = null
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private val writeLock = Any()
    private val rnd = SecureRandom()

    @Volatile private var closed = false
    @Volatile var established = false
        private set

    private var certSha256 = ByteArray(32)
    private var certSha1 = ByteArray(20)
    private var bindingNonce = ByteArray(32)
    private var bindingHashMask = 0
    private var connectAcked = false

    // LCP
    private val magic = rnd.nextInt().let { if (it == 0) 0x5354_5450 else it }
    private var lcpId = 0
    private var mru = cfg.mtu
    private val lcpRejected = mutableSetOf<Int>()
    private var lcpOurAcked = false
    private var lcpPeerAcked = false
    private var lastLcpReq = 0L
    private var lcpReqCount = 0

    // MS-CHAPv2
    private var authDone = false
    private var ntResp: ByteArray? = null
    private var expectedAuthResponse: String? = null

    // IPCP
    private var ipcpId = 0
    private var ipcpStarted = false
    private var ipcpOurAcked = false
    private var ipcpPeerAcked = false
    private var lastIpcpReq = 0L
    private var ipcpReqCount = 0
    private val ipcpRejected = mutableSetOf<Int>()
    private var myIp = ByteArray(4)
    private var dns1 = ByteArray(4)
    private var dns2 = ByteArray(4)
    private var peerIp: ByteArray? = null

    private var protoRejId = 0

    // receive buffer (keeps partial data across read timeouts)
    private val rbuf = ByteArray(32768)
    private var rlen = 0

    // ------------------------------------------------------------------ public API

    /** Connects and negotiates everything up to an assigned IPv4 address. Blocking. */
    fun connect(timeoutMs: Long = 30_000): IpConfig {
        val raw = socketFactory()
        raw.tcpNoDelay = true
        raw.keepAlive = true
        log("Connecting to ${cfg.host}:${cfg.port}…")
        raw.connect(InetSocketAddress(cfg.host, cfg.port), 15_000)

        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, if (cfg.trustAll) arrayOf<TrustManager>(TrustAll) else null, SecureRandom())
        val tls = ctx.socketFactory.createSocket(raw, cfg.host, cfg.port, true) as SSLSocket
        if (!cfg.trustAll) {
            val p = tls.sslParameters
            p.endpointIdentificationAlgorithm = "HTTPS"
            tls.sslParameters = p
        }
        tls.soTimeout = 15_000
        try {
            tls.startHandshake()
        } catch (e: IOException) {
            raw.close()
            val hint = if (!cfg.trustAll) " (self-signed certificate? enable \"Allow untrusted certificate\")" else ""
            throw SstpException("TLS handshake failed: ${e.message}$hint", fatal = !cfg.trustAll && e is javax.net.ssl.SSLHandshakeException)
        }
        socket = tls
        input = tls.inputStream
        output = tls.outputStream
        val cert = tls.session.peerCertificates[0]
        certSha256 = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        certSha1 = MessageDigest.getInstance("SHA-1").digest(cert.encoded)
        log("TLS OK (${tls.session.protocol}, ${(cert as? X509Certificate)?.subjectX500Principal?.name ?: "?"})")

        httpHandshake()
        writePacket(true, controlMsg(MSG_CALL_CONNECT_REQUEST, attr(ATTR_ENCAP_PROTO, byteArrayOf(0, 1))))

        tls.soTimeout = 500
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val now = System.currentTimeMillis()
            if (now > deadline) {
                throw SstpException(
                    when {
                        !connectAcked -> "Timeout: no SSTP CALL_CONNECT_ACK from server"
                        !(lcpOurAcked && lcpPeerAcked) -> "Timeout during PPP LCP negotiation"
                        !authDone -> "Timeout during authentication"
                        else -> "Timeout: no IP address assigned (IPCP)"
                    },
                )
            }
            val pkt = readPacket()
            if (pkt != null) {
                if (pkt.control) handleControl(pkt.body) else handlePpp(pkt.body, null)
            }
            if (connectAcked && !lcpOurAcked && (lastLcpReq == 0L || now - lastLcpReq > 3000)) sendLcpConfReq()
            if (authDone && lcpOurAcked && lcpPeerAcked && !ipcpStarted) {
                ipcpStarted = true
                sendIpcpConfReq()
            }
            if (ipcpStarted && !ipcpOurAcked && now - lastIpcpReq > 3000) sendIpcpConfReq()
            if (ipcpOurAcked && ipcpPeerAcked) break
        }
        established = true
        val dns = listOf(dns1, dns2).filter { !it.all { b -> b == 0.toByte() } }.map { InetAddress.getByAddress(it) }
        val ip = IpConfig(InetAddress.getByAddress(myIp), peerIp?.let { InetAddress.getByAddress(it) }, dns, mru)
        log("Connected: IP ${ip.address.hostAddress}, server ${ip.peer?.hostAddress ?: "-"}, DNS ${dns.joinToString { it.hostAddress ?: "" }}")
        return ip
    }

    /** Receive loop after [connect]. Calls [onIp] for every IPv4 packet. Returns/throws when the session ends. */
    fun run(onIp: (ByteArray, Int, Int) -> Unit) {
        val s = socket ?: throw SstpException("not connected")
        s.soTimeout = 5_000
        var lastRx = System.currentTimeMillis()
        while (!closed) {
            val pkt = readPacket()
            val now = System.currentTimeMillis()
            if (pkt == null) {
                if (now - lastRx > 100_000) throw SstpException("Server is not responding")
                continue
            }
            lastRx = now
            if (pkt.control) handleControl(pkt.body) else handlePpp(pkt.body, onIp)
        }
    }

    /** Sends one IPv4 packet through the tunnel (thread-safe). */
    fun sendIp(buf: ByteArray, off: Int, len: Int) {
        if (!established || closed) return
        val total = len + 8
        if (total > 4095) return // larger than the SSTP length field; MTU keeps us below that
        val p = ByteArray(total)
        p[0] = SSTP_VERSION.toByte()
        p[1] = 0
        p[2] = ((total shr 8) and 0x0f).toByte()
        p[3] = total.toByte()
        p[4] = 0xFF.toByte(); p[5] = 0x03; p[6] = 0x00; p[7] = 0x21
        System.arraycopy(buf, off, p, 8, len)
        writeRaw(p)
        stats.bytesOut.addAndGet(len.toLong())
    }

    /** Graceful shutdown: LCP Terminate + SSTP CALL_DISCONNECT, then close the socket. */
    fun close() {
        if (closed) return
        closed = true
        runCatching {
            if (socket != null) {
                if (lcpOurAcked) sendPpp(PROTO_LCP, cp(5, ++lcpId, ByteArray(0)))
                writePacket(true, controlMsg(MSG_CALL_DISCONNECT))
            }
        }
        runCatching { socket?.close() }
    }

    // ------------------------------------------------------------------ transport

    private fun httpHandshake() {
        val corr = "{" + UUID.randomUUID().toString().uppercase() + "}"
        val req = "SSTP_DUPLEX_POST /sra_{BA195980-CD49-458b-9E23-C84EE0ADCD75}/ HTTP/1.1\r\n" +
            "Host: ${cfg.host}\r\n" +
            "Content-Length: 18446744073709551615\r\n" +
            "SSTPCORRELATIONID: $corr\r\n\r\n"
        writeRaw(req.toByteArray(Charsets.US_ASCII))
        while (true) {
            val idx = indexOfCrlfCrlf()
            if (idx >= 0) {
                val header = String(rbuf, 0, idx, Charsets.ISO_8859_1)
                consume(idx + 4)
                val status = header.lineSequence().first()
                if (!status.matches(Regex("HTTP/1\\.[01] 200.*"))) {
                    throw SstpException("Server rejected SSTP request: $status", fatal = true)
                }
                return
            }
            if (rlen >= rbuf.size) throw SstpException("HTTP response too large")
            val n = input.read(rbuf, rlen, rbuf.size - rlen)
            if (n < 0) throw SstpException("Connection closed during HTTP handshake (not an SSTP server?)")
            rlen += n
        }
    }

    private fun indexOfCrlfCrlf(): Int {
        for (i in 0..rlen - 4) {
            if (rbuf[i] == '\r'.code.toByte() && rbuf[i + 1] == '\n'.code.toByte() &&
                rbuf[i + 2] == '\r'.code.toByte() && rbuf[i + 3] == '\n'.code.toByte()
            ) return i
        }
        return -1
    }

    private fun consume(n: Int) {
        System.arraycopy(rbuf, n, rbuf, 0, rlen - n)
        rlen -= n
    }

    /** Returns the next SSTP packet, or null on read timeout (partial data is kept). */
    private fun readPacket(): Packet? {
        while (true) {
            if (rlen >= 4) {
                if ((rbuf[0].toInt() and 0xff) != SSTP_VERSION) throw SstpException("Bad SSTP packet from server")
                val len = ((rbuf[2].toInt() and 0x0f) shl 8) or (rbuf[3].toInt() and 0xff)
                if (len < 4) throw SstpException("Bad SSTP packet length")
                if (rlen >= len) {
                    val p = Packet((rbuf[1].toInt() and 1) == 1, rbuf.copyOfRange(4, len))
                    consume(len)
                    return p
                }
            }
            val n = try {
                input.read(rbuf, rlen, rbuf.size - rlen)
            } catch (e: SocketTimeoutException) {
                return null
            }
            if (n < 0) throw SstpException("Connection closed by server")
            rlen += n
        }
    }

    private fun writeRaw(b: ByteArray) {
        synchronized(writeLock) {
            output.write(b)
            output.flush()
        }
    }

    private fun writePacket(control: Boolean, payload: ByteArray) {
        val total = payload.size + 4
        val p = ByteArray(total)
        p[0] = SSTP_VERSION.toByte()
        p[1] = if (control) 1 else 0
        p[2] = ((total shr 8) and 0x0f).toByte()
        p[3] = total.toByte()
        System.arraycopy(payload, 0, p, 4, payload.size)
        writeRaw(p)
    }

    private fun controlMsg(type: Int, vararg attrs: ByteArray): ByteArray {
        val size = 4 + attrs.sumOf { it.size }
        val b = ByteArray(size)
        b[0] = (type shr 8).toByte(); b[1] = type.toByte()
        b[2] = (attrs.size shr 8).toByte(); b[3] = attrs.size.toByte()
        var o = 4
        for (a in attrs) {
            System.arraycopy(a, 0, b, o, a.size); o += a.size
        }
        return b
    }

    private fun attr(id: Int, value: ByteArray): ByteArray {
        val len = value.size + 4
        val b = ByteArray(len)
        b[1] = id.toByte()
        b[2] = ((len shr 8) and 0x0f).toByte(); b[3] = len.toByte()
        System.arraycopy(value, 0, b, 4, value.size)
        return b
    }

    // ------------------------------------------------------------------ SSTP control

    private fun handleControl(body: ByteArray) {
        if (body.size < 4) return
        val type = u16(body, 0)
        when (type) {
            MSG_CALL_CONNECT_ACK -> {
                val n = u16(body, 2)
                var o = 4
                for (i in 0 until n) {
                    if (o + 4 > body.size) break
                    val id = body[o + 1].toInt() and 0xff
                    val len = u16(body, o + 2) and 0x0fff
                    if (len < 4 || o + len > body.size) break
                    if (id == ATTR_CRYPTO_BINDING_REQ && len >= 4 + 36) {
                        bindingHashMask = body[o + 7].toInt() and 0xff
                        bindingNonce = body.copyOfRange(o + 8, o + 40)
                    }
                    o += len
                }
                connectAcked = true
                log("SSTP call accepted, negotiating PPP…")
            }
            MSG_CALL_CONNECT_NAK -> throw SstpException("Server refused the SSTP call (CALL_CONNECT_NAK)", fatal = true)
            MSG_CALL_ABORT -> throw SstpException(if (authDone) "Server aborted the connection" else "Server aborted the call (authentication rejected?)")
            MSG_CALL_DISCONNECT -> {
                runCatching { writePacket(true, controlMsg(MSG_CALL_DISCONNECT_ACK)) }
                throw SstpException("Server closed the connection")
            }
            MSG_CALL_DISCONNECT_ACK -> throw SstpException("Disconnected")
            MSG_ECHO_REQUEST -> writePacket(true, controlMsg(MSG_ECHO_RESPONSE))
            MSG_ECHO_RESPONSE -> Unit
        }
    }

    private fun sendCallConnected() {
        val nt = ntResp ?: return
        val sha256 = (bindingHashMask and 0x02) != 0 || bindingHashMask == 0
        val value = ByteArray(100)
        value[3] = if (sha256) 2 else 1
        System.arraycopy(bindingNonce, 0, value, 4, 32)
        val certHash = if (sha256) certSha256 else certSha1
        System.arraycopy(certHash, 0, value, 36, certHash.size)
        val payload = controlMsg(MSG_CALL_CONNECTED, attr(ATTR_CRYPTO_BINDING, value))
        val total = payload.size + 4
        val pkt = ByteArray(total)
        pkt[0] = SSTP_VERSION.toByte(); pkt[1] = 1
        pkt[2] = ((total shr 8) and 0x0f).toByte(); pkt[3] = total.toByte()
        System.arraycopy(payload, 0, pkt, 4, payload.size)

        // HLAK (client view) = MasterSendKey | MasterReceiveKey; CMK = PRF(HLAK, seed|LEN|0x01);
        // Compound MAC = HMAC(CMK, whole CALL_CONNECTED packet with the MAC field zeroed).
        val (send, recv) = MsChapV2.clientMppeKeys(cfg.password, nt)
        val alg = if (sha256) "HmacSHA256" else "HmacSHA1"
        val seed = "SSTP inner method derived CMK".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(if (sha256) 32 else 20, 0, 1)
        val cmk = hmac(alg, send + recv, seed)
        val cmac = hmac(alg, cmk, pkt)
        System.arraycopy(cmac, 0, pkt, 4 + 4 + 4 + 68, cmac.size)
        writeRaw(pkt)
    }

    // ------------------------------------------------------------------ PPP

    private fun sendPpp(proto: Int, payload: ByteArray) {
        val b = ByteArray(payload.size + 4)
        b[0] = 0xFF.toByte(); b[1] = 0x03
        b[2] = (proto shr 8).toByte(); b[3] = proto.toByte()
        System.arraycopy(payload, 0, b, 4, payload.size)
        writePacket(false, b)
    }

    /** Control-protocol packet: code, id, length, data. */
    private fun cp(code: Int, id: Int, data: ByteArray): ByteArray {
        val len = data.size + 4
        val b = ByteArray(len)
        b[0] = code.toByte(); b[1] = id.toByte()
        b[2] = (len shr 8).toByte(); b[3] = len.toByte()
        System.arraycopy(data, 0, b, 4, data.size)
        return b
    }

    private class Opt(val type: Int, val data: ByteArray)

    private fun parseOpts(b: ByteArray, from: Int, to: Int): List<Opt> {
        val out = ArrayList<Opt>()
        var o = from
        while (o + 2 <= to) {
            val t = b[o].toInt() and 0xff
            val l = b[o + 1].toInt() and 0xff
            if (l < 2 || o + l > to) break
            out.add(Opt(t, b.copyOfRange(o + 2, o + l)))
            o += l
        }
        return out
    }

    private fun buildOpts(opts: List<Opt>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (op in opts) {
            out.write(op.type); out.write(op.data.size + 2); out.write(op.data)
        }
        return out.toByteArray()
    }

    private fun handlePpp(frame: ByteArray, onIp: ((ByteArray, Int, Int) -> Unit)?) {
        var o = 0
        if (frame.size >= 2 && frame[0] == 0xFF.toByte() && frame[1] == 0x03.toByte()) o = 2
        if (o >= frame.size) return
        val proto: Int
        if ((frame[o].toInt() and 1) == 1) {
            proto = frame[o].toInt() and 0xff; o += 1
        } else {
            if (o + 2 > frame.size) return
            proto = u16(frame, o); o += 2
        }
        when (proto) {
            PROTO_IP -> {
                stats.bytesIn.addAndGet((frame.size - o).toLong())
                onIp?.invoke(frame, o, frame.size - o)
            }
            PROTO_LCP -> handleLcp(frame, o)
            PROTO_CHAP -> handleChap(frame, o)
            PROTO_IPCP -> handleIpcp(frame, o)
            else -> {
                // IPv6CP, CCP, …: Protocol-Reject (only once LCP is up)
                if (lcpOurAcked && lcpPeerAcked) {
                    val info = frame.copyOfRange(o, minOf(frame.size, o + 1400))
                    val data = byteArrayOf((proto shr 8).toByte(), proto.toByte()) + info
                    sendPpp(PROTO_LCP, cp(8, ++protoRejId and 0xff, data))
                }
            }
        }
    }

    // ---- LCP

    private fun sendLcpConfReq() {
        lcpReqCount++
        if (lcpReqCount > 10) throw SstpException("PPP LCP negotiation failed")
        val opts = ArrayList<Opt>()
        if (1 !in lcpRejected) opts.add(Opt(1, byteArrayOf((mru shr 8).toByte(), mru.toByte())))
        if (5 !in lcpRejected) opts.add(Opt(5, int32(magic)))
        lcpId = (lcpId + 1) and 0xff
        sendPpp(PROTO_LCP, cp(1, lcpId, buildOpts(opts)))
        lastLcpReq = System.currentTimeMillis()
    }

    private fun handleLcp(f: ByteArray, o: Int) {
        if (o + 4 > f.size) return
        val code = f[o].toInt() and 0xff
        val id = f[o + 1].toInt() and 0xff
        val end = minOf(f.size, o + u16(f, o + 2))
        when (code) {
            1 -> { // Configure-Request from server
                val opts = parseOpts(f, o + 4, end)
                val rej = ArrayList<Opt>()
                val nak = ArrayList<Opt>()
                for (op in opts) when (op.type) {
                    1, 2, 5 -> Unit // MRU, ACCM, magic
                    3 -> if (!op.data.contentEquals(CHAP_MSCHAPV2)) nak.add(Opt(3, CHAP_MSCHAPV2))
                    else -> rej.add(op) // PFC, ACFC, callback, multilink…
                }
                when {
                    rej.isNotEmpty() -> sendPpp(PROTO_LCP, cp(4, id, buildOpts(rej)))
                    nak.isNotEmpty() -> sendPpp(PROTO_LCP, cp(3, id, buildOpts(nak)))
                    else -> {
                        sendPpp(PROTO_LCP, cp(2, id, f.copyOfRange(o + 4, end)))
                        lcpPeerAcked = true
                        if (opts.none { it.type == 3 }) {
                            // no authentication requested by the server
                            authDone = true
                        }
                    }
                }
            }
            2 -> if (id == lcpId) lcpOurAcked = true
            3 -> if (id == lcpId) { // Nak
                for (op in parseOpts(f, o + 4, end)) {
                    if (op.type == 1 && op.data.size == 2) mru = u16(op.data, 0).coerceIn(576, cfg.mtu)
                }
                sendLcpConfReq()
            }
            4 -> if (id == lcpId) { // Reject
                for (op in parseOpts(f, o + 4, end)) lcpRejected.add(op.type)
                sendLcpConfReq()
            }
            5 -> { // Terminate-Request
                sendPpp(PROTO_LCP, cp(6, id, ByteArray(0)))
                throw SstpException("Server terminated the PPP session")
            }
            9 -> { // Echo-Request -> Echo-Reply
                val rest = if (end > o + 8) f.copyOfRange(o + 8, end) else ByteArray(0)
                sendPpp(PROTO_LCP, cp(10, id, int32(magic) + rest))
            }
            else -> Unit // Echo-Reply, Discard, Code/Protocol-Reject
        }
    }

    // ---- MS-CHAPv2

    private fun handleChap(f: ByteArray, o: Int) {
        if (o + 4 > f.size) return
        val code = f[o].toInt() and 0xff
        val id = f[o + 1].toInt() and 0xff
        val end = minOf(f.size, o + u16(f, o + 2))
        when (code) {
            1 -> { // Challenge
                val vs = f[o + 4].toInt() and 0xff
                if (vs != 16 || o + 5 + 16 > end) throw SstpException("Unsupported CHAP challenge")
                val authChal = f.copyOfRange(o + 5, o + 21)
                val peerChal = ByteArray(16).also { rnd.nextBytes(it) }
                val nt = MsChapV2.ntResponse(authChal, peerChal, cfg.user, cfg.password)
                ntResp = nt
                expectedAuthResponse = MsChapV2.authenticatorResponse(cfg.password, nt, peerChal, authChal, cfg.user)
                val value = peerChal + ByteArray(8) + nt + byteArrayOf(0)
                val data = byteArrayOf(49) + value + cfg.user.toByteArray(Charsets.UTF_8)
                sendPpp(PROTO_CHAP, cp(2, id, data))
                log("Authenticating as ${cfg.user} (MS-CHAPv2)…")
            }
            3 -> { // Success: "S=<40 hex> M=..."
                val msg = String(f, o + 4, end - o - 4, Charsets.ISO_8859_1)
                val s = Regex("S=([0-9A-Fa-f]{40})").find(msg)?.value
                if (s == null || !s.equals(expectedAuthResponse, ignoreCase = true)) {
                    throw SstpException("Server authenticator response mismatch (not the real server?)", fatal = true)
                }
                if (!authDone) {
                    authDone = true
                    log("Authentication OK")
                    sendCallConnected()
                }
            }
            4 -> {
                val msg = String(f, o + 4, end - o - 4, Charsets.ISO_8859_1)
                throw SstpException("Authentication failed: wrong username or password ($msg)", fatal = true)
            }
        }
    }

    // ---- IPCP

    private fun sendIpcpConfReq() {
        ipcpReqCount++
        if (ipcpReqCount > 10) throw SstpException("IPCP negotiation failed: no IP address from server")
        val opts = ArrayList<Opt>()
        opts.add(Opt(3, myIp))
        if (129 !in ipcpRejected) opts.add(Opt(129, dns1))
        if (131 !in ipcpRejected) opts.add(Opt(131, dns2))
        ipcpId = (ipcpId + 1) and 0xff
        sendPpp(PROTO_IPCP, cp(1, ipcpId, buildOpts(opts)))
        lastIpcpReq = System.currentTimeMillis()
    }

    private fun handleIpcp(f: ByteArray, o: Int) {
        if (o + 4 > f.size) return
        val code = f[o].toInt() and 0xff
        val id = f[o + 1].toInt() and 0xff
        val end = minOf(f.size, o + u16(f, o + 2))
        when (code) {
            1 -> {
                val opts = parseOpts(f, o + 4, end)
                val rej = opts.filter { it.type != 3 }
                if (rej.isNotEmpty()) {
                    sendPpp(PROTO_IPCP, cp(4, id, buildOpts(rej)))
                } else {
                    opts.firstOrNull { it.type == 3 && it.data.size == 4 }?.let { peerIp = it.data }
                    sendPpp(PROTO_IPCP, cp(2, id, f.copyOfRange(o + 4, end)))
                    ipcpPeerAcked = true
                }
            }
            2 -> if (id == ipcpId) {
                if (myIp.all { it == 0.toByte() }) throw SstpException("Server did not assign an IP address")
                ipcpOurAcked = true
            }
            3 -> if (id == ipcpId) {
                for (op in parseOpts(f, o + 4, end)) if (op.data.size == 4) when (op.type) {
                    3 -> myIp = op.data
                    129 -> dns1 = op.data
                    131 -> dns2 = op.data
                }
                sendIpcpConfReq()
            }
            4 -> if (id == ipcpId) {
                for (op in parseOpts(f, o + 4, end)) {
                    if (op.type == 3) throw SstpException("Server rejected IP address negotiation")
                    ipcpRejected.add(op.type)
                }
                sendIpcpConfReq()
            }
            5 -> {
                sendPpp(PROTO_IPCP, cp(6, id, ByteArray(0)))
                throw SstpException("Server closed the IP layer")
            }
        }
    }

    // ------------------------------------------------------------------ utils

    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)

    private fun int32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private object TrustAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
