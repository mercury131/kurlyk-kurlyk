package com.securegate.maskvpn.core

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLParameters
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Opcodes inside the AEAD plaintext (first byte) - must match the server. */
object Op {
    const val CONFIG: Int = 0x01
    const val IP: Int = 0x02
    const val KEEPALIVE: Int = 0x03
}

/**
 * Client side of the masquerading VPN transport. It connects exactly like a browser opening the
 * cover website (TLS + a WebSocket upgrade), proves the user's secret, then runs the encrypted
 * tunnel. Pure JVM: Android's VpnService feeds [writePacket]/[readFrame] with TUN packets.
 */
class MaskVpnSession(
    private val cfg: TunnelConfig,
    private val allowSelfSigned: Boolean = false,
    private val userAgent: String = DEFAULT_UA,
    // Called on the raw socket before connecting so the tunnel's own TCP is not routed back into the
    // VPN (Android VpnService.protect). Null outside Android (tests, desktop).
    private val protect: ((Socket) -> Unit)? = null,
) {
    private lateinit var sock: SSLSocket
    private lateinit var input: DataInputStream
    private lateinit var output: OutputStream
    private lateinit var rx: AeadKey
    private lateinit var tx: AeadKey

    var sessionConfig: SessionConfig? = null
        private set

    /** Connect, authenticate, upgrade and perform the key exchange; returns the assigned config. */
    fun connect(connectTimeoutMs: Int = 15000): SessionConfig {
        val ctx = SSLContext.getInstance("TLS")
        if (allowSelfSigned) ctx.init(null, arrayOf<TrustManager>(TRUST_ALL), java.security.SecureRandom())
        else ctx.init(null, null, null)
        val raw = Socket()
        protect?.invoke(raw)
        raw.connect(InetSocketAddress(cfg.host, cfg.port), connectTimeoutMs)
        raw.tcpNoDelay = true
        val s = ctx.socketFactory.createSocket(raw, cfg.sni, cfg.port, true) as SSLSocket
        // SNI = the configured name (masquerade as a browser visiting the cover site).
        s.sslParameters = SSLParameters().apply { serverNames = listOf(javax.net.ssl.SNIHostName(cfg.sni)) }
        s.startHandshake()
        sock = s
        input = DataInputStream(s.inputStream.buffered())
        output = s.outputStream

        val proof = Crypto.makeProof(Crypto.authKey(cfg.secret))
        val wsKey = Base64.getEncoder().encodeToString(Crypto.random(16))
        val req = buildString {
            append("GET ").append(cfg.path).append(" HTTP/1.1\r\n")
            append("Host: ").append(cfg.sni).append("\r\n")
            append("User-Agent: ").append(userAgent).append("\r\n")
            append("Accept: */*\r\n")
            append("Upgrade: websocket\r\nConnection: Upgrade\r\n")
            append("Sec-WebSocket-Key: ").append(wsKey).append("\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("Cookie: __Host-sess=").append(proof).append("\r\n\r\n")
        }
        output.write(req.toByteArray(Charsets.ISO_8859_1)); output.flush()

        val status = readHttpResponse(input)
        require(status.startsWith("HTTP/1.1 101")) { "tunnel upgrade failed: $status" }

        val cRand = Crypto.random(32)
        output.write(cRand); output.flush()
        val sRand = ByteArray(32); input.readFully(sRand)
        val km = Crypto.hkdf(Crypto.tunKey(cfg.secret), cRand + sRand, "securegate-maskvpn-v1".toByteArray(), 64)
        tx = Crypto.chacha(km.copyOfRange(0, 32))   // client -> server
        rx = Crypto.chacha(km.copyOfRange(32, 64))  // server -> client

        val (op, data) = readFrame()
        require(op == Op.CONFIG) { "expected config frame, got op=$op" }
        return SessionConfig.parse(String(data, Charsets.UTF_8)).also { sessionConfig = it }
    }

    /** Read one decrypted frame: returns (opcode, payload). Blocks. */
    @Synchronized
    fun readFrame(): Pair<Int, ByteArray> {
        val len = (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
        val ct = ByteArray(len); input.readFully(ct)
        val pt = rx.open(ct)
        require(pt.isNotEmpty()) { "empty frame" }
        return Pair(pt[0].toInt() and 0xFF, pt.copyOfRange(1, pt.size))
    }

    private val writeLock = Any()
    private fun writeFrame(op: Int, payload: ByteArray) {
        val pt = ByteArray(1 + payload.size)
        pt[0] = op.toByte(); System.arraycopy(payload, 0, pt, 1, payload.size)
        val ct = tx.seal(pt)
        val frame = ByteArray(2 + ct.size)
        frame[0] = (ct.size ushr 8).toByte(); frame[1] = ct.size.toByte()
        System.arraycopy(ct, 0, frame, 2, ct.size)
        synchronized(writeLock) { output.write(frame); output.flush() }
    }

    /** Send one IPv4 packet into the tunnel. */
    fun writePacket(pkt: ByteArray) = writeFrame(Op.IP, pkt)

    /** Send a keepalive (call every ~20 s when idle). */
    fun keepAlive() = writeFrame(Op.KEEPALIVE, ByteArray(0))

    fun close() {
        try { sock.close() } catch (_: Exception) {}
    }

    companion object {
        const val DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"

        private val TRUST_ALL = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        private fun readHttpResponse(input: InputStream): String {
            val sb = StringBuilder()
            var state = 0
            while (state < 4) {
                val b = input.read()
                if (b < 0) break
                sb.append(b.toChar())
                state = when (b) {
                    '\r'.code -> if (state == 0 || state == 2) state + 1 else 0
                    '\n'.code -> if (state == 1 || state == 3) state + 1 else 0
                    else -> 0
                }
            }
            return sb.toString().lineSequence().firstOrNull()?.trim() ?: ""
        }
    }
}
