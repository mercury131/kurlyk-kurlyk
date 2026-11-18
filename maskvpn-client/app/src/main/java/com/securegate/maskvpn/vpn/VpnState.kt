package com.securegate.maskvpn.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class Status { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

/** Global, observable VPN connection state (single active tunnel). */
object VpnState {
    data class Snapshot(
        val status: Status = Status.DISCONNECTED,
        val tunnel: String? = null,
        val address: String? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state

    fun connecting(tunnel: String) { _state.value = Snapshot(Status.CONNECTING, tunnel) }
    fun connected(tunnel: String, address: String) { _state.value = Snapshot(Status.CONNECTED, tunnel, address) }
    fun error(tunnel: String?, msg: String) { _state.value = Snapshot(Status.ERROR, tunnel, null, msg) }
    fun disconnected() { _state.value = Snapshot(Status.DISCONNECTED) }
}
