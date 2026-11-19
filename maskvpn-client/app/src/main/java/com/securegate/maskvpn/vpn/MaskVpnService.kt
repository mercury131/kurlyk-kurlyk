package com.securegate.maskvpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.securegate.maskvpn.MainActivity
import com.securegate.maskvpn.R
import com.securegate.maskvpn.core.MaskVpnSession
import com.securegate.maskvpn.core.Op
import com.securegate.maskvpn.core.TunnelConfig
import com.securegate.maskvpn.store.TunnelStore
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.concurrent.thread

/**
 * Runs the masquerading VPN tunnel: establishes a device TUN from the address the server assigns and
 * pumps IPv4 packets between it and [MaskVpnSession]. The tunnel's own TCP socket is protect()ed so
 * it is not routed back into the VPN. Reconnects with backoff until the user disconnects.
 */
class MaskVpnService : VpnService() {
    @Volatile private var userStop = false
    @Volatile private var worker: Thread? = null
    @Volatile private var session: MaskVpnSession? = null
    @Volatile private var tun: ParcelFileDescriptor? = null
    // Generation of the current worker: an old worker that is still unwinding must not touch the state
    // of a newer one (reconnect / switching tunnels).
    @Volatile private var gen = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> { stopTunnel(); return START_NOT_STICKY }
            else -> {
                val name = intent?.getStringExtra(EXTRA_NAME) ?: return START_NOT_STICKY
                val cfg = TunnelStore(this).get(name)
                if (cfg == null) { VpnState.error(name, "tunnel not found"); stopSelf(); return START_NOT_STICKY }
                startTunnel(cfg, TunnelStore(this).allowSelfSigned)
            }
        }
        return START_STICKY
    }

    private fun startTunnel(cfg: TunnelConfig, allowSelfSigned: Boolean) {
        // Switching/reconnecting: tear down the old worker WITHOUT stopSelf() (that destroyed the service,
        // and onDestroy then stopped the new worker too).
        if (worker != null) teardown()
        userStop = false
        val my = ++gen
        startForeground(NOTIF_ID, notification(getString(R.string.status_connecting), cfg.name))
        VpnState.connecting(cfg.name)
        worker = thread(name = "maskvpn") { runLoop(cfg, allowSelfSigned, my) }
    }

    private fun alive(my: Int) = !userStop && gen == my

    private fun runLoop(cfg: TunnelConfig, allowSelfSigned: Boolean, my: Int) {
        var backoff = 1000L
        while (alive(my)) {
            try {
                VpnState.connecting(cfg.name)
                runOnce(cfg, allowSelfSigned, my)
                backoff = 1000L // a clean end still reconnects unless the user stopped
            } catch (e: Throwable) {
                if (!alive(my)) break
                VpnState.error(cfg.name, e.message ?: e.javaClass.simpleName)
                notify(getString(R.string.status_error), "${cfg.name}: ${e.message ?: e.javaClass.simpleName}")
            }
            if (!alive(my)) break
            try { Thread.sleep(backoff) } catch (_: InterruptedException) { break }
            backoff = (backoff * 2).coerceAtMost(30000L)
        }
        if (gen == my) VpnState.disconnected()
    }

    private fun runOnce(cfg: TunnelConfig, allowSelfSigned: Boolean, my: Int) {
        val s = MaskVpnSession(cfg, allowSelfSigned = allowSelfSigned, protect = { sock -> protect(sock) })
        session = s
        val sc = s.connect()
        val (addr, prefix) = sc.address.split("/").let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: 32) }
        val b = Builder().setSession(cfg.name).setMtu(sc.mtu).addAddress(addr, prefix)
        sc.dns.forEach { runCatching { b.addDnsServer(it) } }
        (if (sc.routes.isEmpty()) listOf("0.0.0.0/0") else sc.routes).forEach { r ->
            val p = r.split("/"); runCatching { b.addRoute(p[0], p.getOrNull(1)?.toIntOrNull() ?: 0) }
        }
        b.setBlocking(true)
        val pfd = b.establish() ?: throw IllegalStateException("VPN not permitted")
        tun = pfd
        VpnState.connected(cfg.name, sc.address)
        notify(getString(R.string.status_connected), "${cfg.name} · ${sc.address}")

        val fin = FileInputStream(pfd.fileDescriptor)
        val fout = FileOutputStream(pfd.fileDescriptor)
        // device -> server
        val up = thread(name = "maskvpn-up") {
            val buf = ByteArray(32767)
            try {
                while (alive(my)) {
                    val n = fin.read(buf)
                    if (n <= 0) break
                    s.writePacket(buf.copyOf(n))
                }
            } catch (_: Throwable) {} finally { runCatching { s.close() } }
        }
        // keepalive
        val ka = thread(name = "maskvpn-ka") {
            try { while (alive(my)) { Thread.sleep(20000); s.keepAlive() } } catch (_: Throwable) {}
        }
        // server -> device (this thread)
        try {
            while (alive(my)) {
                val (op, pkt) = s.readFrame()
                if (op == Op.IP && pkt.isNotEmpty()) fout.write(pkt)
            }
        } finally {
            runCatching { s.close() }
            up.interrupt(); ka.interrupt()
            runCatching { pfd.close() }
            if (tun === pfd) tun = null
        }
    }

    private fun teardown() {
        userStop = true
        gen++
        runCatching { session?.close() }
        runCatching { tun?.close() }
        worker?.interrupt()
        worker = null
    }

    private fun stopTunnel() {
        teardown()
        VpnState.disconnected()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() { stopTunnel() }
    override fun onDestroy() { userStop = true; runCatching { session?.close() }; super.onDestroy() }

    // ---- notification ----
    private fun notification(title: String, text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MaskVpnService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title).setContentText(text).setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.disconnect), stop).build())
            .build()
    }

    private fun notify(title: String, text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(title, text))

    companion object {
        const val ACTION_CONNECT = "com.securegate.maskvpn.CONNECT"
        const val ACTION_DISCONNECT = "com.securegate.maskvpn.DISCONNECT"
        const val EXTRA_NAME = "tunnel_name"
        private const val CHANNEL = "vpn"
        private const val NOTIF_ID = 1

        fun connect(ctx: Context, name: String) {
            val i = Intent(ctx, MaskVpnService::class.java).setAction(ACTION_CONNECT).putExtra(EXTRA_NAME, name)
            ctx.startForegroundService(i)
        }

        fun disconnect(ctx: Context) {
            ctx.startService(Intent(ctx, MaskVpnService::class.java).setAction(ACTION_DISCONNECT))
        }
    }
}
