package com.example.data.ws

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.example.data.api.ApiException
import com.example.data.api.AuroraApiClient
import com.example.data.api.AuroraJson
import com.example.data.api.OkHttpProvider
import com.example.data.api.WsOutAck
import com.example.data.api.WsOutCall
import com.example.data.api.WsOutMsg
import com.example.data.api.WsOutPing
import com.example.data.api.WsOutRead
import com.example.data.api.WsOutTyping
import com.example.data.api.WsRawEvent
import com.example.data.prefs.SessionPrefs
import com.example.domain.ConnectionState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebSocketManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpProvider: OkHttpProvider,
    private val sessionPrefs: SessionPrefs,
    private val apiClient: AuroraApiClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<WsRawEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<WsRawEvent> = _events.asSharedFlow()

    private val _serverClockOffsetMs = MutableStateFlow(0L)
    val serverClockOffsetMs: StateFlow<Long> = _serverClockOffsetMs.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    @Volatile
    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private val backoffAttempt = AtomicInteger(0)
    @Volatile
    private var manuallyDisconnected = false
    private var networkCallbackRegistered = false

    private val ackMutex = Mutex()
    private val pendingAcks = mutableListOf<String>()
    private var ackFlushJob: Job? = null

    private var eventProcessor: (suspend (WsRawEvent) -> Unit)? = null
    private var onConnectedCallback: (suspend () -> Unit)? = null
    private var callSignalHandler: ((WsRawEvent) -> Unit)? = null

    fun setEventProcessor(processor: suspend (WsRawEvent) -> Unit) {
        eventProcessor = processor
    }

    fun setOnConnectedCallback(callback: suspend () -> Unit) {
        onConnectedCallback = callback
    }

    fun setCallSignalHandler(handler: (WsRawEvent) -> Unit) {
        callSignalHandler = handler
    }

    // All connection state transitions are guarded by this lock. Only the socket stored in
    // [webSocket] is "current"; callbacks from older (replaced/cancelled) sockets are ignored,
    // otherwise a stale onFailure/onClosed would wipe the reference to the live socket and a
    // stale "replaced" error would stop the client from sending anything at all.
    private val lock = Any()

    fun connect(forceReconnect: Boolean = false) {
        val session = sessionPrefs.session.value
        if (session == null || !session.isLoggedIn) {
            disconnect()
            return
        }
        synchronized(lock) {
            if (_connectionState.value == ConnectionState.REPLACED && !forceReconnect) {
                return
            }
            if (forceReconnect) {
                backoffAttempt.set(0)
                _lastError.value = null
                if (_connectionState.value == ConnectionState.REPLACED) {
                    _connectionState.value = ConnectionState.DISCONNECTED
                }
            }
            manuallyDisconnected = false
        }
        registerNetworkCallback()

        synchronized(lock) {
            if (!isNetworkAvailable()) {
                _connectionState.value = ConnectionState.WAITING_FOR_NETWORK
                return
            }
            if (webSocket != null &&
                (_connectionState.value == ConnectionState.CONNECTED || _connectionState.value == ConnectionState.CONNECTING)
            ) {
                return
            }
            openSocketLocked()
        }
    }

    fun disconnect() {
        synchronized(lock) {
            manuallyDisconnected = true
            reconnectJob?.cancel()
            reconnectJob = null
            val ws = webSocket
            webSocket = null
            ws?.close(1000, "Client disconnected")
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    private fun isCurrent(ws: WebSocket): Boolean = synchronized(lock) { webSocket === ws }

    private fun openSocketLocked() {
        val session = sessionPrefs.session.value ?: return
        reconnectJob?.cancel()
        reconnectJob = null
        _connectionState.value = ConnectionState.CONNECTING

        val request = Request.Builder()
            .url("wss://${session.domain}/chat/ws")
            .header("Authorization", "Bearer ${session.token}")
            .build()

        val old = webSocket
        webSocket = null
        old?.cancel()
        webSocket = okHttpProvider.getClient().newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!isCurrent(webSocket)) {
                        webSocket.cancel()
                        return
                    }
                    backoffAttempt.set(0)
                    _lastError.value = null
                    _connectionState.value = ConnectionState.CONNECTED
                    scope.launch {
                        flushAcks()
                        onConnectedCallback?.invoke()
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (!isCurrent(webSocket)) return
                    val event = runCatching {
                        AuroraJson.decodeFromString<WsRawEvent>(text)
                    }.getOrNull() ?: return

                    if (event.type == "hello" && event.serverTime != null) {
                        _serverClockOffsetMs.value = event.serverTime - System.currentTimeMillis()
                    }

                    if (event.type == "error" && event.code == "replaced") {
                        synchronized(lock) {
                            if (this@WebSocketManager.webSocket !== webSocket) return
                            manuallyDisconnected = true
                            reconnectJob?.cancel()
                            this@WebSocketManager.webSocket = null
                            _connectionState.value = ConnectionState.REPLACED
                            _lastError.value = event.message ?: "Signed in on another device"
                        }
                        webSocket.close(1000, "Replaced")
                        return
                    }

                    if (event.type == "call") {
                        callSignalHandler?.invoke(event)
                    }

                    scope.launch {
                        // never let one bad event kill the coroutine (an uncaught exception here
                        // would crash the app); the event is not acked and the server re-sends it.
                        runCatching { eventProcessor?.invoke(event) }
                            .onFailure { android.util.Log.w("WebSocketManager", "event ${event.type} failed", it) }
                        _events.emit(event)
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    onSocketGone(webSocket)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (response?.code == 401) {
                        synchronized(lock) {
                            if (this@WebSocketManager.webSocket !== webSocket) return
                            this@WebSocketManager.webSocket = null
                            if (manuallyDisconnected || _connectionState.value == ConnectionState.REPLACED) return
                            _connectionState.value = ConnectionState.CONNECTING
                        }
                        scope.launch { handleUnauthorized() }
                        return
                    }
                    onSocketGone(webSocket)
                }
            },
        )
    }

    /** A socket closed or failed: reconnect only if it was the current one. */
    private fun onSocketGone(ws: WebSocket) {
        synchronized(lock) {
            if (webSocket !== ws) return
            webSocket = null
            if (manuallyDisconnected || _connectionState.value == ConnectionState.REPLACED) {
                if (_connectionState.value != ConnectionState.REPLACED) {
                    _connectionState.value = ConnectionState.DISCONNECTED
                }
                return
            }
            scheduleReconnectLocked()
        }
    }

    private suspend fun handleUnauthorized() {
        try {
            apiClient.reAuthenticateCurrentSession()
            backoffAttempt.set(0)
            synchronized(lock) {
                if (!manuallyDisconnected && webSocket == null) openSocketLocked()
            }
        } catch (e: ApiException) {
            if (e.code == 401 || e.code == 403) {
                // Invalid secret key or banned phone -> do NOT retry in loop
                synchronized(lock) {
                    manuallyDisconnected = true
                    _connectionState.value = ConnectionState.DISCONNECTED
                    _lastError.value = e.message
                }
            } else {
                synchronized(lock) { scheduleReconnectLocked() }
            }
        } catch (_: Throwable) {
            synchronized(lock) { scheduleReconnectLocked() }
        }
    }

    private fun scheduleReconnectLocked() {
        if (manuallyDisconnected || _connectionState.value == ConnectionState.REPLACED) return
        if (sessionPrefs.session.value == null) return
        if (webSocket != null) return

        if (!isNetworkAvailable()) {
            _connectionState.value = ConnectionState.WAITING_FOR_NETWORK
            return
        }

        _connectionState.value = ConnectionState.CONNECTING
        val attempt = backoffAttempt.getAndIncrement()
        val delaySec = (1L shl attempt.coerceAtMost(6)).coerceAtMost(60L)
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delaySec * 1000L)
            synchronized(lock) {
                if (manuallyDisconnected || _connectionState.value == ConnectionState.REPLACED || webSocket != null) {
                    return@launch
                }
                if (isNetworkAvailable()) {
                    openSocketLocked()
                } else {
                    _connectionState.value = ConnectionState.WAITING_FOR_NETWORK
                }
            }
        }
    }

    /** A network became available: reconnect right away instead of waiting for the backoff. */
    private fun onNetworkAvailable() {
        synchronized(lock) {
            if (manuallyDisconnected || _connectionState.value == ConnectionState.REPLACED) return
            if (sessionPrefs.session.value == null) return
            if (webSocket != null) return // a socket is already open or being opened
            backoffAttempt.set(0)
            openSocketLocked()
        }
    }

    /** The socket that may be written to right now (null while connecting/disconnected). */
    private fun currentSocket(): WebSocket? = synchronized(lock) {
        if (_connectionState.value == ConnectionState.CONNECTED) webSocket else null
    }

    fun sendMessage(id: String, to: String, payload: String): Boolean {
        val json = AuroraJson.encodeToString(WsOutMsg(id = id, to = to, payload = payload))
        return currentSocket()?.send(json) == true
    }

    fun sendReadReceipt(to: String, ids: List<String>): Boolean {
        if (ids.isEmpty()) return true
        val json = AuroraJson.encodeToString(WsOutRead(to = to, ids = ids))
        return currentSocket()?.send(json) == true
    }

    fun sendTyping(to: String): Boolean {
        val json = AuroraJson.encodeToString(WsOutTyping(to = to))
        return currentSocket()?.send(json) == true
    }

    fun sendCallSignal(to: String, callId: String, action: String, data: JsonObject? = null): Boolean {
        val json = AuroraJson.encodeToString(WsOutCall(to = to, callId = callId, action = action, data = data))
        return currentSocket()?.send(json) == true
    }

    fun sendPing(): Boolean {
        val json = AuroraJson.encodeToString(WsOutPing())
        return currentSocket()?.send(json) == true
    }

    fun queueAck(qid: String) {
        scope.launch {
            var flushNow = false
            ackMutex.withLock {
                if (!pendingAcks.contains(qid)) {
                    pendingAcks.add(qid)
                }
                if (pendingAcks.size >= 50) {
                    flushNow = true
                }
            }
            if (flushNow) {
                flushAcks()
            } else if (ackFlushJob?.isActive != true) {
                ackFlushJob = scope.launch {
                    delay(300L)
                    flushAcks()
                }
            }
        }
    }

    private suspend fun flushAcks() {
        val batch = ackMutex.withLock {
            if (pendingAcks.isEmpty()) return
            pendingAcks.toList().also { pendingAcks.clear() }
        }
        val json = AuroraJson.encodeToString(WsOutAck(qids = batch))
        val sent = currentSocket()?.send(json) == true
        if (!sent) {
            ackMutex.withLock {
                pendingAcks.addAll(0, batch)
            }
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun registerNetworkCallback() {
        synchronized(lock) {
            if (networkCallbackRegistered) return
            networkCallbackRegistered = true
        }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching {
            cm.registerNetworkCallback(
                req,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        onNetworkAvailable()
                    }

                    override fun onLost(network: Network) {
                        synchronized(lock) {
                            if (!isNetworkAvailable() && webSocket == null && !manuallyDisconnected &&
                                _connectionState.value != ConnectionState.REPLACED
                            ) {
                                _connectionState.value = ConnectionState.WAITING_FOR_NETWORK
                            }
                        }
                    }
                },
            )
        }
    }
}
