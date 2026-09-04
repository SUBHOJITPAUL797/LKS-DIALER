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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.example.data.model.CallType
import com.example.data.model.CallStatus
import com.example.ui.theme.BackgroundDark
import com.example.ui.theme.GreenCall
import com.example.ui.theme.RedEndCall
import com.example.ui.theme.TealPrimary
import com.example.webrtc.WebRtcEngine
import com.example.webrtc.WebRtcState

import android.content.Context
import android.content.ContextWrapper
import android.app.Activity

fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun OutgoingCallScreen(
    calleeName: String,
    calleeNumber: String,
    profilePicUrl: String,
    callType: CallType,
    statusText: String,
    webRtcEngine: WebRtcEngine? = null,
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
            .pointerInput(Unit) { detectTapGestures { } }
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

            // In-Call Controls (Mute & Speakerphone while Calling / Ringing)
            if (webRtcEngine != null) {
                val state by webRtcEngine.state.collectAsState()
                var showAudioDialog by remember { mutableStateOf(false) }

                if (showAudioDialog) {
                    AudioOutputSelectionDialog(
                        state = state,
                        onSelectDevice = { webRtcEngine.selectAudioDevice(it) },
                        onDismiss = { showAudioDialog = false }
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Mute Mic Toggle
                    InCallControlButton(
                        icon = if (state.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        label = if (state.isMuted) "Muted" else "Mute",
                        isActive = state.isMuted,
                        onClick = { webRtcEngine.toggleMute() }
                    )

                    // Audio Output Switcher / Speakerphone Toggle
                    val audioIcon = when (state.selectedAudioDevice) {
                        com.example.webrtc.AudioDeviceType.BLUETOOTH -> Icons.Default.BluetoothAudio
                        com.example.webrtc.AudioDeviceType.SPEAKERPHONE -> Icons.Default.VolumeUp
                        com.example.webrtc.AudioDeviceType.EARPIECE -> Icons.Default.PhoneInTalk
                        com.example.webrtc.AudioDeviceType.WIRED_HEADSET -> Icons.Default.Headphones
                    }
                    val audioLabel = when (state.selectedAudioDevice) {
                        com.example.webrtc.AudioDeviceType.BLUETOOTH -> "Bluetooth"
                        com.example.webrtc.AudioDeviceType.SPEAKERPHONE -> "Speaker"
                        com.example.webrtc.AudioDeviceType.EARPIECE -> "Earpiece"
                        com.example.webrtc.AudioDeviceType.WIRED_HEADSET -> "Headset"
                    }

                    InCallControlButton(
                        icon = audioIcon,
                        label = audioLabel,
                        isActive = state.selectedAudioDevice == com.example.webrtc.AudioDeviceType.SPEAKERPHONE || state.selectedAudioDevice == com.example.webrtc.AudioDeviceType.BLUETOOTH,
                        onClick = {
                            if (state.availableAudioDevices.size > 2 || state.availableAudioDevices.any { it.type == com.example.webrtc.AudioDeviceType.BLUETOOTH }) {
                                showAudioDialog = true
                            } else {
                                webRtcEngine.toggleSpeaker()
                            }
                        }
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
            .pointerInput(Unit) { detectTapGestures { } }
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
    // BUG-16 FIX: Never use early 'return' in a Composable - it violates Compose state contract.
    // The calling site in MainActivity already guards this with 'if (activeCall != null)'
    val activeCall = state.activeCall
    if (activeCall == null) {
        Box(modifier = androidx.compose.ui.Modifier.fillMaxSize().background(BackgroundDark))
        return
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    
    // Proximity Sensor Logic for Screen Blackout (only active when using Phone Earpiece)
    DisposableEffect(state.callStatus, state.callType, state.selectedAudioDevice) {
        val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        var wakeLock: android.os.PowerManager.WakeLock? = null
        
        // PROXIMITY_SCREEN_OFF_WAKE_LOCK is 32 (added in API 21)
        val proximityLockLevel = 32
        if (powerManager.isWakeLockLevelSupported(proximityLockLevel)) {
            wakeLock = powerManager.newWakeLock(proximityLockLevel, "LksDialer:ProximitySensor")
            if (state.callStatus == CallStatus.ANSWERED && 
                state.callType == CallType.AUDIO && 
                state.selectedAudioDevice == com.example.webrtc.AudioDeviceType.EARPIECE) {
                wakeLock.acquire(10 * 60 * 1000L /*10 minutes max*/)
            }
        }
        
        onDispose {
            if (wakeLock?.isHeld == true) {
                wakeLock.release()
            }
        }
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
            .pointerInput(Unit) { detectTapGestures { } }
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
            
            var showAudioDialog by remember { mutableStateOf(false) }

            if (showAudioDialog) {
                AudioOutputSelectionDialog(
                    state = state,
                    onSelectDevice = { webRtcEngine.selectAudioDevice(it) },
                    onDismiss = { showAudioDialog = false }
                )
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

                    // Audio Output Switcher / Speakerphone Toggle
                    val audioIcon = when (state.selectedAudioDevice) {
                        com.example.webrtc.AudioDeviceType.BLUETOOTH -> Icons.Default.BluetoothAudio
                        com.example.webrtc.AudioDeviceType.SPEAKERPHONE -> Icons.Default.VolumeUp
                        com.example.webrtc.AudioDeviceType.EARPIECE -> Icons.Default.PhoneInTalk
                        com.example.webrtc.AudioDeviceType.WIRED_HEADSET -> Icons.Default.Headphones
                    }
                    val audioLabel = when (state.selectedAudioDevice) {
                        com.example.webrtc.AudioDeviceType.BLUETOOTH -> "Bluetooth"
                        com.example.webrtc.AudioDeviceType.SPEAKERPHONE -> "Speaker"
                        com.example.webrtc.AudioDeviceType.EARPIECE -> "Earpiece"
                        com.example.webrtc.AudioDeviceType.WIRED_HEADSET -> "Headset"
                    }

                    InCallControlButton(
                        icon = audioIcon,
                        label = audioLabel,
                        isActive = state.selectedAudioDevice == com.example.webrtc.AudioDeviceType.SPEAKERPHONE || state.selectedAudioDevice == com.example.webrtc.AudioDeviceType.BLUETOOTH,
                        onClick = {
                            if (state.availableAudioDevices.size > 2 || state.availableAudioDevices.any { it.type == com.example.webrtc.AudioDeviceType.BLUETOOTH }) {
                                showAudioDialog = true
                            } else {
                                webRtcEngine.toggleSpeaker()
                            }
                        }
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val isPipMode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        context.findActivity()?.isInPictureInPictureMode == true
    } else false

    var showAudioDialog by remember { mutableStateOf(false) }

    if (showAudioDialog) {
        AudioOutputSelectionDialog(
            state = state,
            onSelectDevice = { webRtcEngine.selectAudioDevice(it) },
            onDismiss = { showAudioDialog = false }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { } }
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
                    Surface(
                        modifier = Modifier.size(100.dp),
                        shape = CircleShape,
                        color = TealPrimary
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = displayName.take(1).uppercase().ifBlank { "?" },
                                fontSize = 44.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Connecting video...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }
        }

        if (!isPipMode) {
            // Local PiP Hardware Camera Preview (Top Right)
            if (state.isCameraOn && state.localVideoTrack != null) {
                Surface(
                    modifier = Modifier
                        .size(width = 110.dp, height = 160.dp)
                        .padding(12.dp)
                        .align(Alignment.TopEnd)
                        .clip(RoundedCornerShape(16.dp)),
                    shadowElevation = 8.dp,
                    color = Color.Black
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
                        text = webRtcEngine.formatDuration(state.callDurationSeconds) + "   " + state.connectionStatusText,
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
                    .padding(horizontal = 12.dp, vertical = 24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Camera Flip (Front <-> Rear)
                InCallControlButton(
                    icon = Icons.Default.Cameraswitch,
                    label = if (state.isFrontCamera) "Front" else "Rear",
                    isActive = false,
                    size = 52.dp,
                    onClick = { webRtcEngine.switchCamera() }
                )

                // Camera On/Off Toggle
                InCallControlButton(
                    icon = if (state.isCameraOn) Icons.Default.Videocam else Icons.Default.VideocamOff,
                    label = if (state.isCameraOn) "Cam On" else "Cam Off",
                    isActive = state.isCameraOn,
                    size = 52.dp,
                    onClick = { webRtcEngine.toggleCamera() }
                )

                // Mute Mic
                InCallControlButton(
                    icon = if (state.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    label = if (state.isMuted) "Muted" else "Mute",
                    isActive = state.isMuted,
                    size = 52.dp,
                    onClick = { webRtcEngine.toggleMute() }
                )

                // Audio Output Switcher
                val audioIcon = when (state.selectedAudioDevice) {
                    com.example.webrtc.AudioDeviceType.BLUETOOTH -> Icons.Default.BluetoothAudio
                    com.example.webrtc.AudioDeviceType.SPEAKERPHONE -> Icons.Default.VolumeUp
                    com.example.webrtc.AudioDeviceType.EARPIECE -> Icons.Default.PhoneInTalk
                    com.example.webrtc.AudioDeviceType.WIRED_HEADSET -> Icons.Default.Headphones
                }
                val audioLabel = when (state.selectedAudioDevice) {
                    com.example.webrtc.AudioDeviceType.BLUETOOTH -> "Bluetooth"
                    com.example.webrtc.AudioDeviceType.SPEAKERPHONE -> "Speaker"
                    com.example.webrtc.AudioDeviceType.EARPIECE -> "Earpiece"
                    com.example.webrtc.AudioDeviceType.WIRED_HEADSET -> "Headset"
                }

                InCallControlButton(
                    icon = audioIcon,
                    label = audioLabel,
                    isActive = state.selectedAudioDevice == com.example.webrtc.AudioDeviceType.BLUETOOTH || state.selectedAudioDevice == com.example.webrtc.AudioDeviceType.SPEAKERPHONE,
                    size = 52.dp,
                    onClick = {
                        if (state.availableAudioDevices.size > 2 || state.availableAudioDevices.any { it.type == com.example.webrtc.AudioDeviceType.BLUETOOTH }) {
                            showAudioDialog = true
                        } else {
                            webRtcEngine.toggleSpeaker()
                        }
                    }
                )

                // Red End Call
                Button(
                    onClick = onEndCall,
                    shape = CircleShape,
                    modifier = Modifier.size(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = RedEndCall),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(Icons.Default.CallEnd, contentDescription = "End", tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }
        }
    }
}

@Composable
fun AudioOutputSelectionDialog(
    state: WebRtcState,
    onSelectDevice: (com.example.webrtc.AudioDeviceOption) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Audio Output",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )
        },
        containerColor = Color(0xFF1E293B),
        text = {
            val devices = if (state.availableAudioDevices.isNotEmpty()) {
                state.availableAudioDevices
            } else {
                listOf(
                    com.example.webrtc.AudioDeviceOption(id = "speaker", name = "Speaker", type = com.example.webrtc.AudioDeviceType.SPEAKERPHONE),
                    com.example.webrtc.AudioDeviceOption(id = "earpiece", name = "Phone Earpiece", type = com.example.webrtc.AudioDeviceType.EARPIECE)
                )
            }
            Column(modifier = Modifier.fillMaxWidth()) {
                devices.forEach { device ->
                    val isSelected = device.isSelected || device.type == state.selectedAudioDevice
                    val icon = when (device.type) {
                        com.example.webrtc.AudioDeviceType.BLUETOOTH -> Icons.Default.BluetoothAudio
                        com.example.webrtc.AudioDeviceType.SPEAKERPHONE -> Icons.Default.VolumeUp
                        com.example.webrtc.AudioDeviceType.EARPIECE -> Icons.Default.PhoneInTalk
                        com.example.webrtc.AudioDeviceType.WIRED_HEADSET -> Icons.Default.Headphones
                    }

                    Surface(
                        onClick = {
                            onSelectDevice(device)
                            onDismiss()
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) TealPrimary.copy(alpha = 0.25f) else Color.Transparent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = device.name,
                                    tint = if (isSelected) GreenCall else Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(
                                    text = device.name,
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    ),
                                    color = if (isSelected) GreenCall else Color.White
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = GreenCall,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = GreenCall)
            }
        }
    )
}

@Composable
private fun InCallControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isActive: Boolean,
    size: androidx.compose.ui.unit.Dp = 60.dp,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(if (isActive) GreenCall else Color.White.copy(alpha = 0.2f))
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isActive) Color.Black else Color.White,
                modifier = Modifier.size((size.value * 0.46f).dp)
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
