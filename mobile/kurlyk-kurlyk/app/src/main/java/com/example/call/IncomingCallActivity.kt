package com.example.call

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.service.NotificationHelper
import com.example.ui.components.AvatarView
import com.example.ui.theme.AuroraAccent
import com.example.ui.theme.AuroraMessengerTheme
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.OnlineGreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class IncomingCallActivity : ComponentActivity() {
    @Inject lateinit var callManager: WebRtcCallManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        val fallbackName = intent.getStringExtra(NotificationHelper.EXTRA_CALLER_NAME).orEmpty()
        val fallbackPhone = intent.getStringExtra(NotificationHelper.EXTRA_CALLER_PHONE).orEmpty()
        val fallbackVideo = intent.getBooleanExtra(NotificationHelper.EXTRA_IS_VIDEO, false)

        setContent {
            AuroraMessengerTheme {
                val state by callManager.callState.collectAsStateWithLifecycle()
                val isVideo = if (state.phase != CallPhase.IDLE) state.isVideo else fallbackVideo
                val callerName = state.peerName.ifBlank { fallbackName.ifBlank { fallbackPhone } }
                val callerPhone = state.peerPhone.ifBlank { fallbackPhone }

                val permLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    callManager.acceptIncomingCall()
                    finish()
                }

                LaunchedEffect(state.phase) {
                    if (state.phase == CallPhase.IDLE || state.phase == CallPhase.ENDED ||
                        state.phase == CallPhase.CONNECTING || state.phase == CallPhase.CONNECTED
                    ) {
                        if (state.phase != CallPhase.INCOMING_RINGING) {
                            finish()
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xFF101D2C), DarkBackground, Color(0xFF0A1018))
                            )
                        )
                        .systemBarsPadding()
                        .padding(32.dp),
                ) {
                    Column(
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        AvatarView(
                            id = callerPhone,
                            name = callerName,
                            avatarLocalPath = state.peerAvatarPath,
                            size = 128.dp,
                        )
                        Spacer(Modifier.height(24.dp))
                        Text(
                            text = callerName,
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.White,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(
                                if (isVideo) R.string.incoming_video_call else R.string.incoming_audio_call
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                            color = AuroraAccent,
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 40.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            FloatingActionButton(
                                onClick = {
                                    callManager.declineIncomingCall()
                                    finish()
                                },
                                containerColor = ErrorRed,
                                contentColor = Color.White,
                                shape = CircleShape,
                                modifier = Modifier.size(72.dp).testTag("decline_call_button"),
                            ) {
                                Icon(Icons.Filled.CallEnd, contentDescription = stringResource(R.string.decline), modifier = Modifier.size(32.dp))
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.decline), color = Color.White.copy(alpha = 0.8f))
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            FloatingActionButton(
                                onClick = {
                                    val needed = mutableListOf<String>()
                                    if (ContextCompat.checkSelfPermission(this@IncomingCallActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                        needed.add(Manifest.permission.RECORD_AUDIO)
                                    }
                                    if (isVideo && ContextCompat.checkSelfPermission(this@IncomingCallActivity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                                        needed.add(Manifest.permission.CAMERA)
                                    }
                                    if (needed.isEmpty()) {
                                        callManager.acceptIncomingCall()
                                        finish()
                                    } else {
                                        permLauncher.launch(needed.toTypedArray())
                                    }
                                },
                                containerColor = OnlineGreen,
                                contentColor = Color.White,
                                shape = CircleShape,
                                modifier = Modifier.size(72.dp).testTag("accept_call_button"),
                            ) {
                                Icon(
                                    imageVector = if (isVideo) Icons.Filled.Videocam else Icons.Filled.Call,
                                    contentDescription = stringResource(R.string.accept),
                                    modifier = Modifier.size(32.dp),
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.accept), color = Color.White.copy(alpha = 0.8f))
                        }
                    }
                }
            }
        }
    }
}
