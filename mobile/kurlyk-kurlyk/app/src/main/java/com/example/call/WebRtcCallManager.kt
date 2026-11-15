package com.example.call

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.example.R
import com.example.data.api.WsRawEvent
import com.example.data.repository.MessagingRepository
import com.example.service.MessengerForegroundService
import com.example.service.NotificationHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton

enum class CallPhase {
    IDLE,
    OUTGOING_CALLING,
    INCOMING_RINGING,
    CONNECTING,
    CONNECTED,
    ENDED,
}

enum class AudioRoute {
    EARPIECE,
    SPEAKER,
    BLUETOOTH,
}

enum class ConnectionQuality {
    GOOD,
    FAIR,
    POOR,
}

data class CallUiState(
    val phase: CallPhase = CallPhase.IDLE,
    val callId: String = "",
    val peerPhone: String = "",
    val peerName: String = "",
    val peerAvatarPath: String? = null,
    val isVideo: Boolean = false,
    val isCaller: Boolean = false,
    val isMuted: Boolean = false,
    val isCameraEnabled: Boolean = true,
    val isFrontCamera: Boolean = true,
    val audioRoute: AudioRoute = AudioRoute.EARPIECE,
    val durationSec: Int = 0,
    val rttMs: Int? = null,
    val quality: ConnectionQuality = ConnectionQuality.GOOD,
    val endReason: String? = null,
)

@Singleton
class WebRtcCallManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MessagingRepository,
    private val notificationHelper: NotificationHelper,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _callState = MutableStateFlow(CallUiState())
    val callState: StateFlow<CallUiState> = _callState.asStateFlow()

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private var eglBase: EglBase? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null

    private var pendingOfferSdp: String? = null
    @Volatile
    private var remoteDescSet = false
    private val queuedRemoteIce = ConcurrentLinkedQueue<IceCandidate>()

    private var outgoingTimeoutJob: Job? = null
    private var timerJob: Job? = null
    private var statsJob: Job? = null
    private var connectedAtMs: Long = 0L

    private var ringtonePlayer: MediaPlayer? = null
    private var toneGenerator: ToneGenerator? = null
    private var vibrator: Vibrator? = null
    private var proximityWakeLock: PowerManager.WakeLock? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    init {
        repository.setCallSignalHandler { event ->
            scope.launch {
                handleCallSignal(event)
            }
        }
    }

    fun getEglContext(): EglBase.Context? = eglBase?.eglBaseContext

    fun attachRenderers(local: SurfaceViewRenderer?, remote: SurfaceViewRenderer?) {
        localRenderer = local
        remoteRenderer = remote
        val ctx = eglBase?.eglBaseContext ?: return
        local?.let { renderer ->
            runCatching { renderer.init(ctx, null) }
            renderer.setMirror(_callState.value.isFrontCamera)
            renderer.setZOrderMediaOverlay(true)
            localVideoTrack?.addSink(renderer)
        }
        remote?.let { renderer ->
            runCatching { renderer.init(ctx, null) }
            remoteVideoTrack?.addSink(renderer)
        }
    }

    fun detachRenderers(local: SurfaceViewRenderer?, remote: SurfaceViewRenderer?) {
        local?.let {
            localVideoTrack?.removeSink(it)
            runCatching { it.release() }
        }
        remote?.let {
            remoteVideoTrack?.removeSink(it)
            runCatching { it.release() }
        }
        if (localRenderer === local) localRenderer = null
        if (remoteRenderer === remote) remoteRenderer = null
    }

    fun startOutgoingCall(peerPhone: String, isVideo: Boolean) {
        if (_callState.value.phase != CallPhase.IDLE) return
        queuedRemoteIce.clear()
        remoteDescSet = false
        scope.launch {
            val callId = UUID.randomUUID().toString()
            val peerUser = resolveUserInfo(peerPhone)
            _callState.value = CallUiState(
                phase = CallPhase.OUTGOING_CALLING,
                callId = callId,
                peerPhone = peerPhone,
                peerName = peerUser.first,
                peerAvatarPath = peerUser.second,
                isVideo = isVideo,
                isCaller = true,
                isCameraEnabled = isVideo,
                audioRoute = if (isVideo) AudioRoute.SPEAKER else AudioRoute.EARPIECE,
            )

            MessengerForegroundService.startCallMode(context, isVideo)
            launchActiveCallActivity()
            setupAudioForCall(isVideo)
            startRingbackTone()

            val initialized = initWebRtcAndPeerConnection(isVideo)
            if (!initialized) {
                finishCall(reason = "Failed to initialize call", sendHangup = false, logResult = null)
                return@launch
            }

            createAndSendOffer(callId, peerPhone, isVideo, iceRestart = false)

            outgoingTimeoutJob?.cancel()
            outgoingTimeoutJob = scope.launch {
                delay(45_000L)
                if (_callState.value.phase == CallPhase.OUTGOING_CALLING ||
                    _callState.value.phase == CallPhase.CONNECTING
                ) {
                    finishCall(
                        reason = context.getString(R.string.call_missed),
                        sendHangup = true,
                        logResult = "missed",
                    )
                }
            }
        }
    }

    private suspend fun resolveUserInfo(phone: String): Pair<String, String?> {
        var name = phone
        var avatar: String? = null
        val job = scope.launch {
            repository.observeUser(phone).collect { u ->
                if (u != null) {
                    name = u.displayName
                    avatar = u.avatarLocalPath
                }
            }
        }
        delay(30)
        job.cancel()
        return name to avatar
    }

    fun acceptIncomingCall() {
        val state = _callState.value
        if (state.phase != CallPhase.INCOMING_RINGING) return
        val offerSdp = pendingOfferSdp ?: return

        stopRingtoneAndVibration()
        notificationHelper.cancelCallNotification()

        _callState.value = state.copy(phase = CallPhase.CONNECTING)
        MessengerForegroundService.startCallMode(context, state.isVideo)
        launchActiveCallActivity()
        setupAudioForCall(state.isVideo)

        scope.launch {
            val initialized = initWebRtcAndPeerConnection(state.isVideo)
            if (!initialized) {
                finishCall(reason = "Call setup failed", sendHangup = true, logResult = null)
                return@launch
            }
            val pc = peerConnection ?: return@launch
            pc.setRemoteDescription(
                object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        remoteDescSet = true
                        drainQueuedRemoteIce()
                        val constraints = MediaConstraints()
                        pc.createAnswer(
                            object : SimpleSdpObserver() {
                                override fun onCreateSuccess(desc: SessionDescription) {
                                    pc.setLocalDescription(
                                        object : SimpleSdpObserver() {
                                            override fun onSetSuccess() {
                                                val data = buildJsonObject {
                                                    put("sdp", desc.description)
                                                }
                                                repository.sendCallSignal(state.peerPhone, state.callId, "answer", data)
                                            }
                                        },
                                        desc,
                                    )
                                }
                            },
                            constraints,
                        )
                    }
                },
                SessionDescription(SessionDescription.Type.OFFER, offerSdp),
            )
        }
    }

    fun declineIncomingCall() {
        val state = _callState.value
        if (state.phase != CallPhase.INCOMING_RINGING) return
        stopRingtoneAndVibration()
        notificationHelper.cancelCallNotification()
        repository.sendCallSignal(state.peerPhone, state.callId, "reject", null)
        finishCall(reason = context.getString(R.string.call_declined), sendHangup = false, logResult = null)
    }

    fun hangUp() {
        val state = _callState.value
        if (state.phase == CallPhase.IDLE) return
        val result = if (state.phase == CallPhase.CONNECTED) "ended" else "missed"
        finishCall(
            reason = context.getString(R.string.call_ended),
            sendHangup = true,
            logResult = if (state.isCaller) result else null,
        )
    }

    fun toggleMute() {
        val next = !_callState.value.isMuted
        localAudioTrack?.setEnabled(!next)
        _callState.value = _callState.value.copy(isMuted = next)
    }

    fun toggleCamera() {
        if (!_callState.value.isVideo) return
        val next = !_callState.value.isCameraEnabled
        localVideoTrack?.setEnabled(next)
        _callState.value = _callState.value.copy(isCameraEnabled = next)
    }

    fun switchCamera() {
        if (!_callState.value.isVideo) return
        videoCapturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                _callState.value = _callState.value.copy(isFrontCamera = isFrontCamera)
                localRenderer?.setMirror(isFrontCamera)
            }

            override fun onCameraSwitchError(errorDescription: String?) {}
        })
    }

    fun cycleAudioRoute() {
        val current = _callState.value.audioRoute
        val hasBt = hasBluetoothDevice()
        val next = when (current) {
            AudioRoute.EARPIECE -> AudioRoute.SPEAKER
            AudioRoute.SPEAKER -> if (hasBt) AudioRoute.BLUETOOTH else AudioRoute.EARPIECE
            AudioRoute.BLUETOOTH -> AudioRoute.EARPIECE
        }
        applyAudioRoute(next)
        _callState.value = _callState.value.copy(audioRoute = next)
    }

    private fun handleCallSignal(event: WsRawEvent) {
        val from = event.from ?: return
        val callId = event.callId ?: return
        val action = event.action ?: return
        val data = event.data
        val state = _callState.value

        when (action) {
            "offer" -> {
                val sdp = data?.get("sdp")?.jsonPrimitive?.content ?: return
                val isVideo = data["video"]?.jsonPrimitive?.booleanOrNull ?: false

                // ICE restart if same callId
                if (state.phase != CallPhase.IDLE && state.callId == callId) {
                    val pc = peerConnection ?: return
                    remoteDescSet = false
                    pc.setRemoteDescription(
                        object : SimpleSdpObserver() {
                            override fun onSetSuccess() {
                                remoteDescSet = true
                                drainQueuedRemoteIce()
                                pc.createAnswer(
                                    object : SimpleSdpObserver() {
                                        override fun onCreateSuccess(desc: SessionDescription) {
                                            pc.setLocalDescription(
                                                object : SimpleSdpObserver() {
                                                    override fun onSetSuccess() {
                                                        repository.sendCallSignal(
                                                            from,
                                                            callId,
                                                            "answer",
                                                            buildJsonObject { put("sdp", desc.description) },
                                                        )
                                                    }
                                                },
                                                desc,
                                            )
                                        }
                                    },
                                    MediaConstraints(),
                                )
                            }
                        },
                        SessionDescription(SessionDescription.Type.OFFER, sdp),
                    )
                    return
                }

                if (state.phase != CallPhase.IDLE) {
                    repository.sendCallSignal(from, callId, "busy", null)
                    return
                }

                // Switch to RINGING synchronously: the caller's ICE candidates follow the offer
                // within milliseconds and are dropped by the "ice" handler unless callId matches.
                queuedRemoteIce.clear()
                remoteDescSet = false
                pendingOfferSdp = sdp
                _callState.value = CallUiState(
                    phase = CallPhase.INCOMING_RINGING,
                    callId = callId,
                    peerPhone = from,
                    peerName = from,
                    isVideo = isVideo,
                    isCaller = false,
                    isCameraEnabled = isVideo,
                    audioRoute = if (isVideo) AudioRoute.SPEAKER else AudioRoute.EARPIECE,
                )
                repository.sendCallSignal(from, callId, "ringing", null)

                scope.launch {
                    val peerUser = resolveUserInfo(from)
                    if (_callState.value.callId != callId || _callState.value.phase != CallPhase.INCOMING_RINGING) {
                        return@launch
                    }
                    _callState.value = _callState.value.copy(
                        peerName = peerUser.first,
                        peerAvatarPath = peerUser.second,
                    )
                    startIncomingRingtoneAndVibrate()
                    notificationHelper.showIncomingCallNotification(
                        callId = callId,
                        callerPhone = from,
                        callerName = peerUser.first,
                        isVideo = isVideo,
                        avatarLocalPath = peerUser.second,
                    )
                    val intent = Intent(context, IncomingCallActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra(NotificationHelper.EXTRA_CALL_ID, callId)
                        putExtra(NotificationHelper.EXTRA_CALLER_PHONE, from)
                        putExtra(NotificationHelper.EXTRA_CALLER_NAME, peerUser.first)
                        putExtra(NotificationHelper.EXTRA_IS_VIDEO, isVideo)
                    }
                    runCatching { context.startActivity(intent) }
                }
            }

            "ringing" -> {
                // Peer is ringing
            }

            "answer" -> {
                if (state.callId != callId) return
                val sdp = data?.get("sdp")?.jsonPrimitive?.content ?: return
                stopRingbackTone()
                outgoingTimeoutJob?.cancel()
                _callState.value = state.copy(phase = CallPhase.CONNECTING)
                peerConnection?.setRemoteDescription(
                    object : SimpleSdpObserver() {
                        override fun onSetSuccess() {
                            remoteDescSet = true
                            drainQueuedRemoteIce()
                        }
                    },
                    SessionDescription(SessionDescription.Type.ANSWER, sdp),
                )
            }

            "ice" -> {
                if (state.callId != callId) return
                val candidateStr = data?.get("candidate")?.jsonPrimitive?.content ?: return
                val sdpMid = data["sdpMid"]?.jsonPrimitive?.content ?: "0"
                val mLineIndex = data["sdpMLineIndex"]?.jsonPrimitive?.intOrNull ?: 0
                val candidate = IceCandidate(sdpMid, mLineIndex, candidateStr)
                // Always queue first: candidates often arrive while the callee is still ringing
                // (no PeerConnection yet) or while setRemoteDescription is in flight.
                queuedRemoteIce.add(candidate)
                if (remoteDescSet && peerConnection != null) {
                    drainQueuedRemoteIce()
                }
            }

            "hangup" -> {
                if (state.callId != callId) return
                val wasConnected = state.phase == CallPhase.CONNECTED
                finishCall(
                    reason = context.getString(R.string.call_ended),
                    sendHangup = false,
                    logResult = if (state.isCaller) (if (wasConnected) "ended" else "missed") else null,
                )
            }

            "reject" -> {
                if (state.callId != callId) return
                finishCall(
                    reason = context.getString(R.string.call_declined),
                    sendHangup = false,
                    logResult = if (state.isCaller) "declined" else null,
                )
            }

            "busy" -> {
                if (state.callId != callId) return
                finishCall(
                    reason = context.getString(R.string.call_busy),
                    sendHangup = false,
                    logResult = if (state.isCaller) "missed" else null,
                )
            }

            "unavailable" -> {
                if (state.callId != callId) return
                finishCall(
                    reason = context.getString(R.string.call_unavailable),
                    sendHangup = false,
                    logResult = if (state.isCaller) "missed" else null,
                )
            }
        }
    }

    private suspend fun initWebRtcAndPeerConnection(isVideo: Boolean): Boolean {
        return runCatching {
            val turnResp = repository.fetchTurnServers()
            val iceServers = turnResp.iceServers.map { dto ->
                val b = PeerConnection.IceServer.builder(dto.urls)
                if (!dto.username.isNullOrBlank()) b.setUsername(dto.username)
                if (!dto.credential.isNullOrBlank()) b.setPassword(dto.credential)
                b.createIceServer()
            }

            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
            )
            val egl = EglBase.create().also { eglBase = it }
            val factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                .createPeerConnectionFactory()
                .also { peerConnectionFactory = it }

            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            }

            // NOTE: do not clear queuedRemoteIce here - on the callee side it already holds the
            // caller's candidates received while ringing; they are applied after setRemoteDescription.
            remoteDescSet = false

            val pc = factory.createPeerConnection(
                rtcConfig,
                object : PeerConnection.Observer {
                    override fun onIceCandidate(candidate: IceCandidate) {
                        val state = _callState.value
                        if (state.phase == CallPhase.IDLE) return
                        val payload = buildJsonObject {
                            put("candidate", candidate.sdp)
                            put("sdpMid", candidate.sdpMid ?: "0")
                            put("sdpMLineIndex", candidate.sdpMLineIndex)
                        }
                        repository.sendCallSignal(state.peerPhone, state.callId, "ice", payload)
                    }

                    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                        scope.launch {
                            when (newState) {
                                PeerConnection.IceConnectionState.CONNECTED,
                                PeerConnection.IceConnectionState.COMPLETED -> onPeerConnected()
                                PeerConnection.IceConnectionState.FAILED -> {
                                    val st = _callState.value
                                    if (st.isCaller && st.phase != CallPhase.IDLE) {
                                        createAndSendOffer(st.callId, st.peerPhone, st.isVideo, iceRestart = true)
                                    }
                                }
                                PeerConnection.IceConnectionState.DISCONNECTED -> {
                                    // Temporary drop; wait for ICE recovery or restart
                                }
                                else -> {}
                            }
                        }
                    }

                    override fun onTrack(transceiver: org.webrtc.RtpTransceiver) {
                        val track = transceiver.receiver.track()
                        if (track is VideoTrack) {
                            scope.launch {
                                remoteVideoTrack = track
                                remoteRenderer?.let { track.addSink(it) }
                            }
                        }
                    }

                    override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out org.webrtc.MediaStream>) {
                        val track = receiver.track()
                        if (track is VideoTrack) {
                            scope.launch {
                                remoteVideoTrack = track
                                remoteRenderer?.let { track.addSink(it) }
                            }
                        }
                    }

                    override fun onSignalingChange(s: PeerConnection.SignalingState) {}
                    override fun onIceConnectionReceivingChange(b: Boolean) {}
                    override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
                    override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
                    override fun onAddStream(s: org.webrtc.MediaStream) {}
                    override fun onRemoveStream(s: org.webrtc.MediaStream) {}
                    override fun onDataChannel(dc: org.webrtc.DataChannel) {}
                    override fun onRenegotiationNeeded() {}
                },
            ) ?: error("PeerConnection creation failed")
            peerConnection = pc

            // Audio track
            val audioConstraints = MediaConstraints()
            val audioSrc = factory.createAudioSource(audioConstraints).also { localAudioSource = it }
            val audioTrk = factory.createAudioTrack("ARDAMSa0", audioSrc).also { localAudioTrack = it }
            pc.addTrack(audioTrk, listOf("ARDAMS"))

            // Video track if video call
            if (isVideo) {
                val enumerator = Camera2Enumerator(context)
                val deviceName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                    ?: enumerator.deviceNames.firstOrNull()
                if (deviceName != null) {
                    val capturer = enumerator.createCapturer(deviceName, null)
                    videoCapturer = capturer
                    val texHelper = SurfaceTextureHelper.create("CaptureThread", egl.eglBaseContext)
                        .also { surfaceTextureHelper = it }
                    val videoSrc = factory.createVideoSource(capturer.isScreencast)
                        .also { localVideoSource = it }
                    capturer.initialize(texHelper, context, videoSrc.capturerObserver)
                    capturer.startCapture(1280, 720, 30)
                    val videoTrk = factory.createVideoTrack("ARDAMSv0", videoSrc)
                        .also { localVideoTrack = it }
                    pc.addTrack(videoTrk, listOf("ARDAMS"))
                    localRenderer?.let { renderer ->
                        runCatching { renderer.init(egl.eglBaseContext, null) }
                        renderer.setMirror(true)
                        renderer.setZOrderMediaOverlay(true)
                        videoTrk.addSink(renderer)
                    }
                    remoteRenderer?.let { renderer ->
                        runCatching { renderer.init(egl.eglBaseContext, null) }
                    }
                }
            }
            true
        }.getOrElse { false }
    }

    private fun createAndSendOffer(callId: String, peerPhone: String, isVideo: Boolean, iceRestart: Boolean) {
        val pc = peerConnection ?: return
        val constraints = MediaConstraints().apply {
            if (iceRestart) {
                mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
            }
        }
        pc.createOffer(
            object : SimpleSdpObserver() {
                override fun onCreateSuccess(desc: SessionDescription) {
                    pc.setLocalDescription(
                        object : SimpleSdpObserver() {
                            override fun onSetSuccess() {
                                val data = buildJsonObject {
                                    put("sdp", desc.description)
                                    put("video", isVideo)
                                }
                                repository.sendCallSignal(peerPhone, callId, "offer", data)
                            }
                        },
                        desc,
                    )
                }
            },
            constraints,
        )
    }

    private fun drainQueuedRemoteIce() {
        val pc = peerConnection ?: return
        while (true) {
            val candidate = queuedRemoteIce.poll() ?: break
            pc.addIceCandidate(candidate)
        }
    }

    private fun onPeerConnected() {
        val state = _callState.value
        if (state.phase == CallPhase.CONNECTED || state.phase == CallPhase.IDLE) return
        stopRingbackTone()
        stopRingtoneAndVibration()
        outgoingTimeoutJob?.cancel()
        connectedAtMs = System.currentTimeMillis()
        _callState.value = state.copy(phase = CallPhase.CONNECTED)
        startTimerAndStats()
    }

    private fun startTimerAndStats() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (_callState.value.phase == CallPhase.CONNECTED) {
                val secs = ((System.currentTimeMillis() - connectedAtMs) / 1000L).toInt().coerceAtLeast(0)
                _callState.value = _callState.value.copy(durationSec = secs)
                delay(1000L)
            }
        }
        statsJob?.cancel()
        statsJob = scope.launch {
            while (_callState.value.phase == CallPhase.CONNECTED) {
                peerConnection?.getStats { report ->
                    var rttMs: Int? = null
                    for (stats in report.statsMap.values) {
                        if (stats.type == "candidate-pair" && stats.members["state"] == "succeeded") {
                            val rttSec = (stats.members["currentRoundTripTime"] as? Number)?.toDouble()
                            if (rttSec != null) {
                                rttMs = (rttSec * 1000.0).toInt()
                                break
                            }
                        }
                    }
                    if (rttMs != null) {
                        val q = when {
                            rttMs < 150 -> ConnectionQuality.GOOD
                            rttMs < 350 -> ConnectionQuality.FAIR
                            else -> ConnectionQuality.POOR
                        }
                        _callState.value = _callState.value.copy(rttMs = rttMs, quality = q)
                    }
                }
                delay(2000L)
            }
        }
    }

    private fun finishCall(reason: String?, sendHangup: Boolean, logResult: String?) {
        val state = _callState.value
        if (state.phase == CallPhase.IDLE) return

        outgoingTimeoutJob?.cancel()
        timerJob?.cancel()
        statsJob?.cancel()
        stopRingbackTone()
        stopRingtoneAndVibration()
        notificationHelper.cancelCallNotification()

        if (sendHangup) {
            repository.sendCallSignal(state.peerPhone, state.callId, "hangup", null)
        }

        if (logResult != null && state.isCaller) {
            val dur = state.durationSec
            scope.launch {
                runCatching {
                    repository.sendCallLog(
                        peerPhone = state.peerPhone,
                        callId = state.callId,
                        video = state.isVideo,
                        result = logResult,
                        durationSec = dur,
                    )
                }
            }
        }

        disposeWebRtc()
        releaseAudioForCall()
        MessengerForegroundService.stopCallMode(context)

        _callState.value = state.copy(phase = CallPhase.ENDED, endReason = reason)
        scope.launch {
            delay(1500L)
            if (_callState.value.phase == CallPhase.ENDED) {
                _callState.value = CallUiState()
            }
        }
    }

    private fun disposeWebRtc() {
        runCatching { videoCapturer?.stopCapture() }
        runCatching { videoCapturer?.dispose() }
        videoCapturer = null

        localRenderer?.let { localVideoTrack?.removeSink(it) }
        remoteRenderer?.let { remoteVideoTrack?.removeSink(it) }
        localVideoTrack = null
        remoteVideoTrack = null
        localAudioTrack = null

        runCatching { localVideoSource?.dispose() }
        localVideoSource = null
        runCatching { localAudioSource?.dispose() }
        localAudioSource = null
        runCatching { surfaceTextureHelper?.dispose() }
        surfaceTextureHelper = null

        runCatching { peerConnection?.close() }
        runCatching { peerConnection?.dispose() }
        peerConnection = null

        runCatching { peerConnectionFactory?.dispose() }
        peerConnectionFactory = null

        runCatching { eglBase?.release() }
        eglBase = null
        pendingOfferSdp = null
        remoteDescSet = false
        queuedRemoteIce.clear()
    }

    @SuppressLint("WakelockTimeout")
    private fun setupAudioForCall(isVideo: Boolean) {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
        audioFocusRequest = focusReq
        audioManager.requestAudioFocus(focusReq)

        val initialRoute = if (isVideo) AudioRoute.SPEAKER else AudioRoute.EARPIECE
        applyAudioRoute(initialRoute)

        if (!isVideo && powerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proximityWakeLock = powerManager.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                "AuroraMessenger:CallProximity",
            ).apply {
                if (!isHeld) acquire()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun applyAudioRoute(route: AudioRoute) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val targetType = when (route) {
                AudioRoute.SPEAKER -> AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                AudioRoute.BLUETOOTH -> AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                AudioRoute.EARPIECE -> AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            }
            val device = audioManager.availableCommunicationDevices.firstOrNull { it.type == targetType }
            if (device != null) {
                audioManager.setCommunicationDevice(device)
            } else {
                audioManager.isSpeakerphoneOn = route == AudioRoute.SPEAKER
            }
        } else {
            if (route == AudioRoute.BLUETOOTH) {
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
                audioManager.isSpeakerphoneOn = false
            } else {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
                audioManager.isSpeakerphoneOn = route == AudioRoute.SPEAKER
            }
        }
    }

    private fun hasBluetoothDevice(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
        } else {
            audioManager.isBluetoothScoAvailableOffCall
        }
    }

    @Suppress("DEPRECATION")
    private fun releaseAudioForCall() {
        proximityWakeLock?.let { if (it.isHeld) it.release() }
        proximityWakeLock = null
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
            audioManager.isSpeakerphoneOn = false
        }
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    private fun startRingbackTone() {
        runCatching {
            toneGenerator = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70).apply {
                startTone(ToneGenerator.TONE_SUP_RINGTONE)
            }
        }
    }

    private fun stopRingbackTone() {
        runCatching { toneGenerator?.stopTone() }
        runCatching { toneGenerator?.release() }
        toneGenerator = null
    }

    @Suppress("DEPRECATION")
    private fun startIncomingRingtoneAndVibrate() {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtonePlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, uri)
                isLooping = true
                prepare()
                start()
            }
        }
        runCatching {
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator = vib
            vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0))
        }
    }

    private fun stopRingtoneAndVibration() {
        runCatching { ringtonePlayer?.stop() }
        runCatching { ringtonePlayer?.release() }
        ringtonePlayer = null
        runCatching { vibrator?.cancel() }
        vibrator = null
    }

    private fun launchActiveCallActivity() {
        val intent = Intent(context, ActiveCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        runCatching { context.startActivity(intent) }
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }
}
