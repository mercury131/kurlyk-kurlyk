package com.securegate.sstp.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.securegate.sstp.MainActivity
import com.securegate.sstp.R
import com.securegate.sstp.core.IpConfig
import com.securegate.sstp.core.SstpConfig
import com.securegate.sstp.core.SstpException
import com.securegate.sstp.core.SstpSession
import com.securegate.sstp.core.Stats
import java.io.FileDescriptor
import java.net.Inet4Address
import java.net.Socket
import kotlin.concurrent.thread

class SstpVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.securegate.sstp.CONNECT"
        const val ACTION_DISCONNECT = "com.securegate.sstp.DISCONNECT"
        private const val CHANNEL = "vpn"
        private const val NOTIF_ID = 1

        fun connect(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, SstpVpnService::class.java).setAction(ACTION_CONNECT))
        }

        fun disconnect(ctx: Context) {
            ctx.startService(Intent(ctx, SstpVpnService::class.java).setAction(ACTION_DISCONNECT))
        }
    }

    @Volatile private var worker: Thread? = null
    @Volatile private var stopping = false
    @Volatile private var session: SstpSession? = null
    @Volatile private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            stopVpn("Disconnected by user")
            return START_NOT_STICKY
        }
        // ACTION_CONNECT, system "always-on VPN" start (android.net.VpnService) or sticky restart
        val profile = Profiles.load(this)
        startForegroundCompat(getString(R.string.notif_connecting, profile.hostPort().first))
        if (!profile.isValid) {
            VpnState.set(VpnStatus(Phase.ERROR, getString(R.string.err_no_profile)))
            stopSelf()
            return START_NOT_STICKY
        }
        if (worker?.isAlive == true) return START_STICKY
        stopping = false
        worker = thread(name = "sstp-worker") { workerLoop(profile) }
        return START_STICKY
    }

    override fun onRevoke() {
        stopVpn("VPN permission revoked (another VPN started?)")
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn(null)
        super.onDestroy()
    }

    private fun stopVpn(reason: String?) {
        stopping = true
        reason?.let { VpnState.log(it) }
        val s = session
        val t = tun
        thread(name = "sstp-stop") {
            runCatching { s?.close() }
            runCatching { t?.close() }
        }
        worker?.interrupt()
        if (worker?.isAlive != true) {
            VpnState.set(VpnStatus(Phase.DISCONNECTED))
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun workerLoop(profile: Profile) {
        val (host, port) = profile.hostPort()
        var attempt = 0
        var finalStatus = VpnStatus(Phase.DISCONNECTED)
        while (!stopping) {
            VpnState.set(VpnStatus(if (attempt == 0) Phase.CONNECTING else Phase.RECONNECTING, server = host))
            updateNotification(getString(R.string.notif_connecting, host))
            val stats = Stats().also { VpnState.stats = it }
            val s = SstpSession(
                SstpConfig(host, port, profile.user, profile.password, profile.trustAll),
                VpnState::log,
                stats,
            ) {
                // Our own app is excluded from the VPN (addDisallowedApplication), protect() is extra safety.
                Socket().also { protect(it) }
            }
            session = s
            var pfd: ParcelFileDescriptor? = null
            try {
                val ip = s.connect()
                if (stopping) break
                pfd = establishTun(ip, profile, host) ?: throw SstpException("VPN permission not granted", fatal = true)
                tun = pfd
                attempt = 0
                VpnState.set(
                    VpnStatus(
                        Phase.CONNECTED, server = host, ip = ip.address.hostAddress,
                        dns = ip.dns.mapNotNull { it.hostAddress }, connectedSince = System.currentTimeMillis(),
                    ),
                )
                updateNotification(getString(R.string.notif_connected, host, ip.address.hostAddress ?: ""))
                val fd = pfd.fileDescriptor
                val reader = thread(name = "tun-reader") { tunReader(fd, s) }
                try {
                    s.run { buf, off, len -> writeTun(fd, buf, off, len) }
                } finally {
                    s.close()
                    reader.join(2000)
                }
            } catch (e: Exception) {
                if (!stopping) {
                    val msg = e.message ?: e.javaClass.simpleName
                    VpnState.log("Error: $msg")
                    if (e is SstpException && e.fatal) {
                        finalStatus = VpnStatus(Phase.ERROR, msg, server = host)
                        break
                    }
                    finalStatus = VpnStatus(Phase.ERROR, msg, server = host)
                }
            } finally {
                runCatching { s.close() }
                runCatching { pfd?.close() }
                tun = null
                session = null
            }
            if (stopping || !profile.autoReconnect) break
            attempt++
            val delaySec = minOf(30, 1 shl minOf(attempt, 5))
            VpnState.log("Reconnecting in $delaySec s…")
            VpnState.set(VpnStatus(Phase.RECONNECTING, "Reconnecting in $delaySec s", server = host))
            updateNotification(getString(R.string.notif_reconnecting, host))
            val until = System.currentTimeMillis() + delaySec * 1000L
            try {
                while (!stopping && System.currentTimeMillis() < until) Thread.sleep(250)
            } catch (_: InterruptedException) {
            }
        }
        VpnState.set(if (stopping) VpnStatus(Phase.DISCONNECTED) else finalStatus)
        if (stopping) VpnState.log("Disconnected")
        worker = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun establishTun(ip: IpConfig, profile: Profile, host: String): ParcelFileDescriptor? {
        val b = Builder()
            .setSession(host)
            .setMtu(ip.mtu)
            .addAddress(ip.address, 32)
        if (profile.fullTunnel) {
            b.addRoute("0.0.0.0", 0)
            // Block IPv6 leaks: route IPv6 into the tunnel too (the server is IPv4-only, packets are dropped).
            runCatching {
                b.addAddress("fd53:5354:5000::1", 128)
                b.addRoute("::", 0)
            }
        } else {
            val a = ip.address.address
            b.addRoute(Inet4Address.getByAddress(byteArrayOf(a[0], a[1], a[2], 0)), 24)
            ip.peer?.let { b.addRoute(it, 32) }
        }
        val dns = ip.dns.ifEmpty { if (profile.fullTunnel) listOf(Inet4Address.getByName("8.8.8.8"), Inet4Address.getByName("1.1.1.1")) else emptyList() }
        dns.forEach { b.addDnsServer(it) }
        runCatching { b.addDisallowedApplication(packageName) }
        b.setBlocking(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) b.setMetered(false)
        b.setConfigureIntent(
            PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
        )
        return b.establish()
    }

    /** tun -> server. Non-blocking fd + poll so the thread can stop promptly. */
    private fun tunReader(fd: FileDescriptor, s: SstpSession) {
        val buf = ByteArray(32767)
        val pfd = StructPollfd().apply { this.fd = fd; events = OsConstants.POLLIN.toShort() }
        try {
            while (!stopping && s.established) {
                val r = try {
                    Os.poll(arrayOf(pfd), 1000)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINTR) continue else throw e
                }
                if (r <= 0) continue
                while (true) {
                    val n = try {
                        Os.read(fd, buf, 0, buf.size)
                    } catch (e: ErrnoException) {
                        if (e.errno == OsConstants.EAGAIN) break else throw e
                    }
                    if (n <= 0) break
                    if ((buf[0].toInt() ushr 4) == 4) s.sendIp(buf, 0, n) // IPv4 only
                }
            }
        } catch (e: Exception) {
            if (!stopping) VpnState.log("tun read stopped: ${e.message}")
            runCatching { s.close() }
        }
    }

    private fun writeTun(fd: FileDescriptor, buf: ByteArray, off: Int, len: Int) {
        try {
            Os.write(fd, buf, off, len)
        } catch (e: ErrnoException) {
            if (e.errno != OsConstants.EAGAIN) throw java.io.IOException("tun write: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ notification

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel_vpn), NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SstpVpnService::class.java).setAction(ACTION_DISCONNECT), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.disconnect), stop)
            .build()
    }

    private fun startForegroundCompat(text: String) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(text), type)
    }

    private fun updateNotification(text: String) {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text)) }
    }
}
