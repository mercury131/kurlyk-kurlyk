package com.securegate.sstp.vpn

import com.securegate.sstp.core.Stats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Phase { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, ERROR }

data class VpnStatus(
    val phase: Phase = Phase.DISCONNECTED,
    val message: String = "",
    val server: String = "",
    val ip: String? = null,
    val dns: List<String> = emptyList(),
    val connectedSince: Long = 0L,
)

/** Process-wide VPN state shared between the service and the UI. */
object VpnState {
    private val _status = MutableStateFlow(VpnStatus())
    val status: StateFlow<VpnStatus> = _status.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    @Volatile var stats = Stats()

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun set(status: VpnStatus) {
        _status.value = status
    }

    fun log(line: String) {
        val entry = "${fmt.format(Date())}  $line"
        synchronized(this) {
            val cur = _log.value
            _log.value = (if (cur.size >= 300) cur.drop(cur.size - 299) else cur) + entry
        }
        android.util.Log.i("SstpVpn", line)
    }

    fun clearLog() {
        _log.value = emptyList()
    }
}
