package com.example.ui.screens.call

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.layout.ContentScale
import com.example.util.ImageUtils
import com.example.data.model.CallType
import com.example.ui.theme.BackgroundDark
import com.example.ui.theme.GreenCall
import com.example.ui.theme.RedEndCall
import com.example.ui.theme.TealPrimary
import com.example.webrtc.WebRtcEngine
import com.example.webrtc.WebRtcState

@Composable
fun OutgoingCallScreen(
    calleeName: String,
    calleeNumber: String,
    profilePicUrl: String,
    callType: CallType,
    statusText: String,
    onEndCall: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition()
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
    ) {
        val decodedBitmap = remember(profilePicUrl) {
            if (profilePicUrl.isNotBlank()) ImageUtils.decodeBase64ToImageBitmap(profilePicUrl) else null
        }
        
        if (decodedBitmap != null) {
            Image(
                bitmap = decodedBitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // Dim overlay so text stays readable
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(40.dp))

            // Profile & Call Info
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(
                    modifier = Modifier.size(120.dp),
                    shape = CircleShape,
                    color = TealPrimary.copy(alpha = pulseAlpha)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = calleeName.take(1).uppercase(),
                            fontSize = 54.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = calleeName,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = calleeNumber,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.7f)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (callType == CallType.VIDEO) Icons.Default.Videocam else Icons.Default.Call,
                        contentDescription = null,
                        tint = GreenCall,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                }
            }

            // End Call Button
            Button(
                onClick = onEndCall,
                shape = CircleShape,
                modifier = Modifier.size(76.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RedEndCall)
            ) {
                Icon(
                    imageVector = Icons.Default.CallEnd,
                    contentDescription = "End Call",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }
        }
    }
}

@Composable
fun IncomingCallOverlay(
    callerName: String,
    callerNumber: String,
    profilePicUrl: String,
    callType: CallType,
    onAnswer: () -> Unit,
    onDecline: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
    ) {
        val decodedBitmap = remember(profilePicUrl) {
            if (profilePicUrl.isNotBlank()) ImageUtils.decodeBase64ToImageBitmap(profilePicUrl) else null
        }
        
        if (decodedBitmap != null) {
            Image(
                bitmap = decodedBitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // Dim overlay so text stays readable
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(40.dp))

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "LKS DIALER ${callType.name} CALL",
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 2.sp),
                    color = GreenCall
                )

                Spacer(modifier = Modifier.height(24.dp))

                Surface(
                    modifier = Modifier.size(110.dp),
                    shape = CircleShape,
                    color = TealPrimary
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = callerName.take(1).uppercase(),
                            fontSize = 48.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = callerName,
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = callerNumber,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }

            // Answer & Decline Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Decline (Red)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(
                        onClick = onDecline,
                        shape = CircleShape,
                        modifier = Modifier.size(72.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = RedEndCall)
                    ) {
                        Icon(Icons.Default.CallEnd, contentDescription = "Decline", tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Decline", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                }

                // Answer (Green)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(
                        onClick = onAnswer,
                        shape = CircleShape,
                        modifier = Modifier.size(72.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GreenCall)
                    ) {
                        Icon(
                            imageVector = if (callType == CallType.VIDEO) Icons.Default.Videocam else Icons.Default.Call,
                            contentDescription = "Answer",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Answer", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
fun ActiveAudioCallScreen(
    state: WebRtcState,
    profilePicUrl: String,
    displayName: String,
    displayNumber: String,
    webRtcEngine: WebRtcEngine,
    onEndCall: () -> Unit
) {
    // BUG-16 FIX: Never use early 'return' in a Composable — it violates Compose state contract.
    // The calling site in MainActivity already guards this with 'if (activeCall != null)'
    val activeCall = state.activeCall
    if (activeCall == null) {
        Box(modifier = androidx.compose.ui.Modifier.fillMaxSize().background(BackgroundDark))
        return
    }

    if (state.isVideoUpgradeRequested) {
        AlertDialog(
            onDismissRequest = { webRtcEngine.declineVideoUpgrade() },
            title = { Text("Video Call Request") },
            text = { Text("The other person wants to switch to a video call. Do you accept?") },
            confirmButton = {
                TextButton(onClick = { webRtcEngine.acceptVideoUpgrade() }) {
                    Text("Accept", color = TealPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { webRtcEngine.declineVideoUpgrade() }) {
                    Text("Decline", color = RedEndCall)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
    ) {
        val decodedBitmap = remember(profilePicUrl) {
            if (profilePicUrl.isNotBlank()) ImageUtils.decodeBase64ToImageBitmap(profilePicUrl) else null
        }
        
        if (decodedBitmap != null) {
            Image(
                bitmap = decodedBitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // Dim overlay so text stays readable
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Header: Name, Duration, Quality
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 24.dp)
            ) {
                Text(
                    // BUG-26 FIX: Show the other party's name (passed from MainActivity based on direction)
                    text = displayName.ifBlank { displayNumber },
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
                Text(
                    text = displayNumber,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.7f)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = webRtcEngine.formatDuration(state.callDurationSeconds),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = GreenCall
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Surface(
                        color = Color.White.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "📶 Quality ${state.networkQualityBars}/5",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // Center Contact Avatar
            Surface(
                modifier = Modifier.size(130.dp),
                shape = CircleShape,
                color = TealPrimary
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        // Use displayName for avatar initial too
                        text = displayName.take(1).uppercase().ifBlank { "?" },
                        fontSize = 56.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            // Bottom In-Call Controls Grid
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 24.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // Mute Mic Toggle
                    InCallControlButton(
                        icon = if (state.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        label = if (state.isMuted) "Muted" else "Mute",
                        isActive = state.isMuted,
                        onClick = { webRtcEngine.toggleMute() }
                    )

                    // Switch to Video Button
                    InCallControlButton(
                        icon = Icons.Default.Videocam,
                        label = "Video",
                        isActive = false,
                        onClick = { webRtcEngine.requestVideoUpgrade() }
                    )

                    // Speakerphone Toggle
                    InCallControlButton(
                        icon = if (state.isSpeakerOn) Icons.Default.VolumeUp else Icons.Default.VolumeDown,
                        label = if (state.isSpeakerOn) "Speaker" else "Earpiece",
                        isActive = state.isSpeakerOn,
                        onClick = { webRtcEngine.toggleSpeaker() }
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                // End Call Button
                Button(
                    onClick = onEndCall,
                    shape = CircleShape,
                    modifier = Modifier.size(76.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = RedEndCall)
                ) {
                    Icon(
                        imageVector = Icons.Default.CallEnd,
                        contentDescription = "End Call",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ActiveVideoCallScreen(
    state: WebRtcState,
    profilePicUrl: String,
    displayName: String,
    displayNumber: String,
    webRtcEngine: WebRtcEngine,
    onEndCall: () -> Unit
) {
    val activeCall = state.activeCall
    if (activeCall == null) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black))
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Remote Video Preview
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF1C2826)),
            contentAlignment = Alignment.Center
        ) {
            if (state.remoteVideoTrack != null) {
                WebRtcVideoRenderer(
                    videoTrack = state.remoteVideoTrack,
                    eglBaseContext = webRtcEngine.eglBaseContext,
                    modifier = Modifier.fillMaxSize(),
                    mirror = false
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.4f),
                        modifier = Modifier.size(80.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Waiting for P2P Video...",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 14.sp
                    )
                }
            }
        }

        // Local PiP Hardware Camera Preview (Top Right)
        if (state.isCameraOn && state.localVideoTrack != null) {
            Surface(
                modifier = Modifier
                    .padding(top = 80.dp, end = 16.dp)
                    .size(width = 120.dp, height = 160.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .align(Alignment.TopEnd),
                color = Color.Black,
                border = CardDefaults.outlinedCardBorder()
            ) {
                WebRtcVideoRenderer(
                    videoTrack = state.localVideoTrack,
                    eglBaseContext = webRtcEngine.eglBaseContext,
                    modifier = Modifier.fillMaxSize(),
                    mirror = state.isFrontCamera,
                    isOverlay = true
                )
            }
        }

        // Top Overlay Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = activeCall.calleeName.ifBlank { activeCall.callerName },
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
                Text(
                    text = webRtcEngine.formatDuration(state.callDurationSeconds) + " • " + state.connectionStatusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = GreenCall
                )
            }
        }

        // Bottom Controls Overlay
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Camera Flip (Front <-> Rear)
            InCallControlButton(
                icon = Icons.Default.Cameraswitch,
                label = if (state.isFrontCamera) "Front" else "Rear",
                isActive = false,
                onClick = { webRtcEngine.switchCamera() }
            )

            // Camera On/Off Toggle
            InCallControlButton(
                icon = if (state.isCameraOn) Icons.Default.Videocam else Icons.Default.VideocamOff,
                label = if (state.isCameraOn) "Cam On" else "Cam Off",
                isActive = state.isCameraOn,
                onClick = { webRtcEngine.toggleCamera() }
            )

            // Mute Mic
            InCallControlButton(
                icon = if (state.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                label = if (state.isMuted) "Muted" else "Mute",
                isActive = state.isMuted,
                onClick = { webRtcEngine.toggleMute() }
            )

            // Red End Call
            Button(
                onClick = onEndCall,
                shape = CircleShape,
                modifier = Modifier.size(68.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RedEndCall)
            ) {
                Icon(Icons.Default.CallEnd, contentDescription = "End", tint = Color.White)
            }
        }
    }
}

@Composable
private fun InCallControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(if (isActive) GreenCall else Color.White.copy(alpha = 0.2f))
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isActive) Color.Black else Color.White,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(text = label, color = Color.White, fontSize = 12.sp)
    }
}

@Composable
fun WebRtcVideoRenderer(
    videoTrack: org.webrtc.VideoTrack,
    eglBaseContext: org.webrtc.EglBase.Context,
    modifier: Modifier = Modifier,
    mirror: Boolean = false,
    isOverlay: Boolean = false
) {
    AndroidView(
        factory = { context ->
            org.webrtc.SurfaceViewRenderer(context).apply {
                init(eglBaseContext, null)
                setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                setMirror(mirror)
                if (isOverlay) {
                    setZOrderMediaOverlay(true)
                }
                // Tag the view with the current track so we can clean it up in update
                setTag(videoTrack)
                videoTrack.addSink(this)
            }
        },
        update = { view ->
            view.setMirror(mirror)
            val oldTrack = view.getTag() as? org.webrtc.VideoTrack
            if (oldTrack != videoTrack) {
                oldTrack?.removeSink(view)
                view.setTag(videoTrack)
                videoTrack.addSink(view)
            }
        },
        onRelease = { view ->
            val oldTrack = view.getTag() as? org.webrtc.VideoTrack
            oldTrack?.removeSink(view)
            view.release()
        },
        modifier = modifier
    )
}
