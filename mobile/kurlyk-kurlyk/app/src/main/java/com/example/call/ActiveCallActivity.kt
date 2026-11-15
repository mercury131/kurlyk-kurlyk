package com.example.call

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.ui.components.AvatarView
import com.example.ui.theme.AuroraMessengerTheme
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.OnlineGreen
import dagger.hilt.android.AndroidEntryPoint
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import javax.inject.Inject
import kotlin.math.roundToInt

@AndroidEntryPoint
class ActiveCallActivity : ComponentActivity() {
    @Inject lateinit var callManager: WebRtcCallManager

    private val isInPipState = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AuroraMessengerTheme {
                val state by callManager.callState.collectAsStateWithLifecycle()
                val inPip by isInPipState
                val ctx = LocalContext.current

                val remoteView = remember { SurfaceViewRenderer(ctx) }
                val localView = remember { SurfaceViewRenderer(ctx) }

                DisposableEffect(state.isVideo, state.phase) {
                    if (state.isVideo && state.phase != CallPhase.IDLE && state.phase != CallPhase.ENDED) {
                        callManager.attachRenderers(localView, remoteView)
                    }
                    onDispose {
                        callManager.detachRenderers(localView, remoteView)
                    }
                }

                LaunchedEffect(state.phase) {
                    if (state.phase == CallPhase.IDLE) {
                        finish()
                    }
                }

                var dragX by remember { mutableFloatStateOf(0f) }
                var dragY by remember { mutableFloatStateOf(0f) }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(listOf(Color(0xFF101D2C), DarkBackground))
                        ),
                ) {
                    if (state.isVideo && state.phase == CallPhase.CONNECTED) {
                        AndroidView(
                            factory = {
                                remoteView.apply {
                                    setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                                    setEnableHardwareScaler(true)
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(bottom = 80.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            AvatarView(
                                id = state.peerPhone,
                                name = state.peerName.ifBlank { state.peerPhone },
                                avatarLocalPath = state.peerAvatarPath,
                                size = if (inPip) 64.dp else 124.dp,
                            )
                            if (!inPip) {
                                Spacer(Modifier.height(20.dp))
                                Text(
                                    text = state.peerName.ifBlank { state.peerPhone },
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = Color.White,
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = when (state.phase) {
                                        CallPhase.OUTGOING_CALLING -> stringResource(R.string.calling)
                                        CallPhase.CONNECTING -> stringResource(R.string.connecting)
                                        CallPhase.CONNECTED -> formatCallDuration(state.durationSec)
                                        CallPhase.ENDED -> state.endReason ?: stringResource(R.string.call_ended)
                                        else -> ""
                                    },
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Color.White.copy(alpha = 0.78f),
                                )
                            }
                        }
                    }

                    if (!inPip) {
                        // Top bar: timer, quality RTT, PiP button
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .systemBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                color = Color.Black.copy(alpha = 0.45f),
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    val qualityColor = when (state.quality) {
                                        ConnectionQuality.GOOD -> OnlineGreen
                                        ConnectionQuality.FAIR -> Color(0xFFFBBF24)
                                        ConnectionQuality.POOR -> ErrorRed
                                    }
                                    Icon(
                                        Icons.Filled.SignalCellularAlt,
                                        contentDescription = "Connection quality",
                                        tint = qualityColor,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    val rttLabel = state.rttMs?.let { "${it} ms" }
                                        ?: if (state.phase == CallPhase.CONNECTED) formatCallDuration(state.durationSec) else ""
                                    if (rttLabel.isNotBlank()) {
                                        Text(rttLabel, color = Color.White, style = MaterialTheme.typography.labelMedium)
                                    }
                                    if (state.phase == CallPhase.CONNECTED && state.isVideo) {
                                        Text(
                                            "• ${formatCallDuration(state.durationSec)}",
                                            color = Color.White,
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    }
                                }
                            }

                            if (state.isVideo) {
                                IconButton(
                                    onClick = { enterPip() },
                                    colors = IconButtonDefaults.iconButtonColors(
                                        containerColor = Color.Black.copy(alpha = 0.45f),
                                        contentColor = Color.White,
                                    ),
                                ) {
                                    Icon(Icons.Filled.PictureInPictureAlt, contentDescription = "Picture in picture")
                                }
                            }
                        }

                        // Draggable local video preview
                        if (state.isVideo && state.isCameraEnabled && state.phase != CallPhase.ENDED) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .systemBarsPadding()
                                    .padding(top = 64.dp, end = 16.dp)
                                    .offset { IntOffset(dragX.roundToInt(), dragY.roundToInt()) }
                                    .size(width = 108.dp, height = 152.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color.Black)
                                    .pointerInput(Unit) {
                                        detectDragGestures { change, dragAmount ->
                                            change.consume()
                                            dragX += dragAmount.x
                                            dragY += dragAmount.y
                                        }
                                    },
                            ) {
                                AndroidView(
                                    factory = {
                                        localView.apply {
                                            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                                            setEnableHardwareScaler(true)
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }

                        // Bottom controls
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                .systemBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 28.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CallControlButton(
                                icon = if (state.isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                                active = state.isMuted,
                                contentDescription = "Mute",
                                onClick = { callManager.toggleMute() },
                            )

                            if (state.isVideo) {
                                CallControlButton(
                                    icon = if (state.isCameraEnabled) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                                    active = !state.isCameraEnabled,
                                    contentDescription = "Camera",
                                    onClick = { callManager.toggleCamera() },
                                )
                                CallControlButton(
                                    icon = Icons.Filled.Cameraswitch,
                                    active = false,
                                    contentDescription = "Switch camera",
                                    onClick = { callManager.switchCamera() },
                                )
                            }

                            CallControlButton(
                                icon = when (state.audioRoute) {
                                    AudioRoute.SPEAKER -> Icons.Filled.VolumeUp
                                    AudioRoute.BLUETOOTH -> Icons.Filled.BluetoothAudio
                                    AudioRoute.EARPIECE -> Icons.Filled.Hearing
                                },
                                active = state.audioRoute != AudioRoute.EARPIECE,
                                contentDescription = "Audio route",
                                onClick = { callManager.cycleAudioRoute() },
                            )

                            FloatingActionButton(
                                onClick = {
                                    callManager.hangUp()
                                    finish()
                                },
                                containerColor = ErrorRed,
                                contentColor = Color.White,
                                shape = CircleShape,
                                modifier = Modifier.size(64.dp).testTag("hangup_call_button"),
                            ) {
                                Icon(
                                    Icons.Filled.CallEnd,
                                    contentDescription = stringResource(R.string.hang_up),
                                    modifier = Modifier.size(28.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (callManager.callState.value.isVideo && callManager.callState.value.phase == CallPhase.CONNECTED) {
            enterPip()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipState.value = isInPictureInPictureMode
    }

    private fun enterPip() {
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(9, 16))
            .build()
        enterPictureInPictureMode(params)
    }
}

@androidx.compose.runtime.Composable
private fun CallControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (active) Color.White else Color.White.copy(alpha = 0.16f),
            contentColor = if (active) Color.Black else Color.White,
        ),
        modifier = Modifier.size(52.dp),
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(24.dp))
    }
}

fun formatCallDuration(seconds: Int): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return "%02d:%02d".format(mins, secs)
}
