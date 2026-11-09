package com.example.ui.screens.call

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import com.example.util.ImageUtils
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.input.pointer.pointerInput
import com.example.data.model.CallType
import com.example.data.model.CallStatus
import com.example.ui.theme.BackgroundDark
import com.example.ui.theme.GreenCall
import com.example.ui.theme.RedEndCall
import com.example.ui.theme.TealPrimary
import com.example.ui.theme.Avatar3dHelper
import com.example.ui.theme.SwiggyOrange
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Shadow

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
    onMinimize: (() -> Unit)? = null,
    onEndCall: () -> Unit
) {
    var endCallClicked by remember { mutableStateOf(false) }
    val infiniteTransition = rememberInfiniteTransition()
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    val context = androidx.compose.ui.platform.LocalContext.current
    val rtcState = webRtcEngine?.state?.collectAsState()?.value
    if (rtcState != null) {
        DisposableEffect(rtcState.callType, rtcState.selectedAudioDevice) {
            val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            var wakeLock: android.os.PowerManager.WakeLock? = null
            val proximityLockLevel = 32
            if (powerManager.isWakeLockLevelSupported(proximityLockLevel)) {
                wakeLock = powerManager.newWakeLock(proximityLockLevel, "LksDialer:ProximitySensor").apply {
                    setReferenceCounted(false)
                }
                if (callType == CallType.AUDIO && rtcState.selectedAudioDevice == com.example.webrtc.AudioDeviceType.EARPIECE) {
                    wakeLock.acquire()
                }
            }
            onDispose {
                try {
                    if (wakeLock?.isHeld == true) wakeLock.release()
                } catch (_: Exception) {}
            }
        }
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

        if (onMinimize != null) {
            IconButton(
                onClick = onMinimize,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(top = 16.dp, start = 16.dp)
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .align(Alignment.TopStart)
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Minimize Call",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .displayCutoutPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(36.dp))

            // Profile & Call Info
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = calleeName,
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
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
                        tint = SwiggyOrange,
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

            // Central Pulsing Avatar with 3D Character Fallback
            Box(
                modifier = Modifier.size(150.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(150.dp)
                        .graphicsLayer {
                            scaleX = pulseAlpha * 0.25f + 0.95f
                            scaleY = pulseAlpha * 0.25f + 0.95f
                            alpha = pulseAlpha * 0.5f
                        }
                        .clip(CircleShape)
                        .background(SwiggyOrange.copy(alpha = 0.35f))
                )
                Surface(
                    modifier = Modifier.size(118.dp),
                    shape = CircleShape,
                    color = Color(0xFF1E293B),
                    border = BorderStroke(2.dp, Color.White.copy(alpha = 0.25f)),
                    shadowElevation = 10.dp
                ) {
                    if (decodedBitmap != null) {
                        Image(
                            bitmap = decodedBitmap,
                            contentDescription = calleeName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        val avatar3dRes = remember(calleeName, calleeNumber) {
                            Avatar3dHelper.getAvatarResId(calleeName, calleeNumber)
                        }
                        Image(
                            painter = painterResource(avatar3dRes),
                            contentDescription = calleeName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            // In-Call Controls (Mute & Speakerphone while Calling / Ringing)
            if (webRtcEngine != null && rtcState != null) {
                val state = rtcState
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
                        activeColor = Color(0xFFEF4444),
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
                            webRtcEngine.refreshAvailableAudioDevices()
                            if (state.availableAudioDevices.size > 2 || state.availableAudioDevices.any { it.type == com.example.webrtc.AudioDeviceType.BLUETOOTH } || state.selectedAudioDevice == com.example.webrtc.AudioDeviceType.BLUETOOTH) {
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
                onClick = { if (!endCallClicked) { endCallClicked = true; onEndCall() } },
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
    isSpam: Boolean = false,
    onAnswer: () -> Unit,
    onDecline: () -> Unit
) {
    var actionTaken by remember { mutableStateOf(false) }
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
                    color = if (isSpam) Color(0xFFFF9800) else GreenCall
                )

                if (isSpam) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = Color(0xFFFF9800).copy(alpha = 0.25f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Report, contentDescription = null, tint = Color(0xFFFF9800), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Suspected Spam",
                                color = Color(0xFFFF9800),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

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

            // Central Pulsing Avatar with 3D Character Fallback
            Box(
                modifier = Modifier.size(150.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(150.dp)
                        .clip(CircleShape)
                        .background(GreenCall.copy(alpha = 0.22f))
                )
                Surface(
                    modifier = Modifier.size(118.dp),
                    shape = CircleShape,
                    color = Color(0xFF1E293B),
                    border = BorderStroke(2.dp, Color.White.copy(alpha = 0.25f)),
                    shadowElevation = 10.dp
                ) {
                    if (decodedBitmap != null) {
                        Image(
                            bitmap = decodedBitmap,
                            contentDescription = callerName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        val avatar3dRes = remember(callerName, callerNumber) {
                            Avatar3dHelper.getAvatarResId(callerName, callerNumber)
                        }
                        Image(
                            painter = painterResource(avatar3dRes),
                            contentDescription = callerName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
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
                        onClick = { if (!actionTaken) { actionTaken = true; onDecline() } },
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
                        onClick = { if (!actionTaken) { actionTaken = true; onAnswer() } },
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
    onMinimize: (() -> Unit)? = null,
    onEndCall: () -> Unit
) {
    var endCallClicked by remember { mutableStateOf(false) }
    // BUG-16 FIX: Never use early 'return' in a Composable - it violates Compose state contract.
    // The calling site in MainActivity already guards this with 'if (activeCall != null)'
    val activeCall = state.activeCall
    if (activeCall == null) {
        Box(modifier = androidx.compose.ui.Modifier.fillMaxSize().background(BackgroundDark))
        return
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val isCallRecording by com.example.util.CallAudioRecorder.getInstance(context).isRecording.collectAsState()
    val recordingDuration by com.example.util.CallAudioRecorder.getInstance(context).recordingDurationSeconds.collectAsState()
    
    // Proximity Sensor Logic for Screen Blackout (only active when using Phone Earpiece)
    DisposableEffect(state.callStatus, state.callType, state.selectedAudioDevice) {
        val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        var wakeLock: android.os.PowerManager.WakeLock? = null
        
        // PROXIMITY_SCREEN_OFF_WAKE_LOCK is 32 (added in API 21)
        val proximityLockLevel = 32
        if (powerManager.isWakeLockLevelSupported(proximityLockLevel)) {
            wakeLock = powerManager.newWakeLock(proximityLockLevel, "LksDialer:ProximitySensor").apply {
                setReferenceCounted(false)
            }
            if ((state.callStatus == CallStatus.ANSWERED || state.callStatus == CallStatus.CALLING || state.callStatus == CallStatus.RINGING) && 
                state.callType == CallType.AUDIO && 
                state.selectedAudioDevice == com.example.webrtc.AudioDeviceType.EARPIECE) {
                wakeLock.acquire()
            }
        }
        
        onDispose {
            try {
                if (wakeLock?.isHeld == true) {
                    wakeLock.release()
                }
            } catch (_: Exception) {}
        }
    }

    if (state.isVideoUpgradeRequested) {
        AlertDialog(
            onDismissRequest = { webRtcEngine.declineVideoUpgrade() },
            title = { Text("Video Call Request") },
            text = { Text("The other person wants to switch to a video call. Do you accept?") },
            confirmButton = {
                TextButton(onClick = { try { webRtcEngine.acceptVideoUpgrade() } catch (_: Exception) {} }) {
                    Text("Accept", color = TealPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { try { webRtcEngine.declineVideoUpgrade() } catch (_: Exception) {} }) {
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

        if (onMinimize != null) {
            IconButton(
                onClick = onMinimize,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(top = 16.dp, start = 16.dp)
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .align(Alignment.TopStart)
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Minimize Call",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .displayCutoutPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Header: Name, Duration, Quality (Safe padding for deep notches like POCO F1)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 28.dp)
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
                    Spacer(modifier = Modifier.width(10.dp))
                    val qualityColor = when {
                        state.networkQualityBars >= 4 -> Color(0xFF22C55E)
                        state.networkQualityBars == 3 -> Color(0xFFF59E0B)
                        else -> Color(0xFFEF4444)
                    }
                    val qualityText = when {
                        state.callQualityBadge.isNotBlank() && state.callQualityBadge != "Connecting..." -> state.callQualityBadge
                        state.networkQualityBars >= 4 -> "HD Audio"
                        state.networkQualityBars == 3 -> "Good"
                        else -> "Weak Network"
                    }
                    Surface(
                        color = qualityColor.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(qualityColor, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = qualityText,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = qualityColor
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        color = GreenCall.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = if (state.selectedAudioDevice == com.example.webrtc.AudioDeviceType.SPEAKERPHONE) "🎙️ Voice Focus (Speaker)" else "🎙️ Voice Focus (HD)",
                            style = MaterialTheme.typography.labelSmall,
                            color = GreenCall,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                }
                AnimatedVisibility(
                    visible = state.networkQualityBars < 2,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Surface(
                        color = Color(0xFFEF4444).copy(alpha = 0.2f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(top = 6.dp)
                    ) {
                        Text(
                            text = "⚠️ Weak network — audio may be affected",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFFCA5A5),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                AnimatedVisibility(
                    visible = state.isOnHold,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Surface(
                        color = Color(0xFFF59E0B).copy(alpha = 0.2f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                Icons.Default.PauseCircle,
                                contentDescription = null,
                                tint = Color(0xFFF59E0B),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (state.isHeldLocally) "Call On Hold" else "Other party placed call on hold",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color(0xFFFBBF24)
                            )
                        }
                    }
                }

                // In-Call Audio Recording Indicator Badge
                AnimatedVisibility(
                    visible = isCallRecording,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    val infiniteTransition = rememberInfiniteTransition(label = "rec_pulse")
                    val pulseAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.35f,
                        targetValue = 1.0f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(800, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "pulseAlpha"
                    )
                    Surface(
                        color = Color(0xFFEF4444).copy(alpha = 0.2f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .graphicsLayer { alpha = pulseAlpha }
                                    .clip(CircleShape)
                                    .background(Color(0xFFEF4444))
                            )
                            Spacer(modifier = Modifier.width(7.dp))
                            Text(
                                text = "REC  ${com.example.util.CallAudioRecorder.formatDuration(recordingDuration)}",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = Color(0xFFEF4444)
                            )
                        }
                    }
                }
            }

            // Central Avatar (Profile Picture or 3D Character)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(150.dp)
                            .clip(CircleShape)
                            .background(SwiggyOrange.copy(alpha = 0.18f))
                    )
                    Surface(
                        modifier = Modifier.size(118.dp),
                        shape = CircleShape,
                        color = Color(0xFF1E293B),
                        border = BorderStroke(2.dp, Color.White.copy(alpha = 0.25f)),
                        shadowElevation = 12.dp
                    ) {
                        if (decodedBitmap != null) {
                            Image(
                                bitmap = decodedBitmap,
                                contentDescription = displayName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            val avatar3dRes = remember(displayName, displayNumber) {
                                Avatar3dHelper.getAvatarResId(displayName, displayNumber)
                            }
                            Image(
                                painter = painterResource(avatar3dRes),
                                contentDescription = displayName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
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

            // Bottom In-Call Controls Grid (Organized in 2 clean, spacious rows)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 24.dp)
            ) {
                // Row 1: Core Audio / Video Communication Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // Mute Mic Toggle
                    InCallControlButton(
                        icon = if (state.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        label = if (state.isMuted) "Muted" else "Mute",
                        isActive = state.isMuted,
                        activeColor = Color(0xFFEF4444),
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
                            webRtcEngine.refreshAvailableAudioDevices()
                            if (state.availableAudioDevices.size > 2 || state.availableAudioDevices.any { it.type == com.example.webrtc.AudioDeviceType.BLUETOOTH } || state.selectedAudioDevice == com.example.webrtc.AudioDeviceType.BLUETOOTH) {
                                showAudioDialog = true
                            } else {
                                webRtcEngine.toggleSpeaker()
                            }
                        }
                    )

                    // Switch to Video Button
                    InCallControlButton(
                        icon = Icons.Default.Videocam,
                        label = if (state.didIRequestVideoUpgrade) "Requesting..." else "Video",
                        isActive = state.didIRequestVideoUpgrade,
                        activeColor = TealPrimary,
                        onClick = {
                            if (!state.didIRequestVideoUpgrade) {
                                try {
                                    webRtcEngine.requestVideoUpgrade()
                                } catch (e: Exception) {
                                    android.util.Log.e("CallScreens", "Error requesting video upgrade: ${e.message}")
                                }
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Row 2: Secondary Call Tools (Record Call & Hold Call)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    // Record Call Button
                    InCallControlButton(
                        icon = if (isCallRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                        label = if (isCallRecording) "Stop Rec" else "Record",
                        isActive = isCallRecording,
                        activeColor = Color(0xFFEF4444),
                        onClick = {
                            val started = webRtcEngine.toggleCallRecording()
                            android.widget.Toast.makeText(
                                context,
                                if (started) "Call recording started" else "Call recording saved to Call History",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    )

                    Spacer(modifier = Modifier.width(42.dp))

                    // Hold Call Toggle
                    InCallControlButton(
                        icon = if (state.isOnHold) Icons.Default.PlayArrow else Icons.Default.Pause,
                        label = if (state.isOnHold) "Resume" else "Hold",
                        isActive = state.isOnHold,
                        onClick = { webRtcEngine.toggleHold() }
                    )
                }

                Spacer(modifier = Modifier.height(26.dp))

                // End Call Button
                Button(
                    onClick = { if (!endCallClicked) { endCallClicked = true; onEndCall() } },
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
    onMinimize: (() -> Unit)? = null,
    onEndCall: () -> Unit
) {
    var endCallClicked by remember { mutableStateOf(false) }
    var areControlsVisible by remember { mutableStateOf(true) }
    var isSwapped by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showAudioDialog by remember { mutableStateOf(false) }
    var isRemoteFirstFrameRendered by remember(state.remoteVideoTrack) { mutableStateOf(false) }

    val activeCall = state.activeCall
    if (activeCall == null) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black))
        return
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val isPipMode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        context.findActivity()?.isInPictureInPictureMode == true
    } else false

    val avatarImageBitmap = remember(profilePicUrl) {
        if (profilePicUrl.isNotBlank()) ImageUtils.decodeBase64ToImageBitmap(profilePicUrl) else null
    }
    val avatar3dRes = remember(displayName, displayNumber) {
        Avatar3dHelper.getAvatarResId(displayName, displayNumber)
    }

    val isVideoRec by com.example.util.CallAudioRecorder.getInstance(context).isRecording.collectAsState()
    val recordingDuration by com.example.util.CallAudioRecorder.getInstance(context).recordingDurationSeconds.collectAsState()

    val infiniteTransition = rememberInfiniteTransition(label = "pulseTransition")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    if (showAudioDialog) {
        AudioOutputSelectionDialog(
            state = state,
            onSelectDevice = { webRtcEngine.selectAudioDevice(it) },
            onDismiss = { showAudioDialog = false }
        )
    }

    // Proximity Sensor Logic for Screen Blackout (only active when user switches video call to Phone Earpiece)
    DisposableEffect(state.callStatus, state.selectedAudioDevice) {
        val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        var wakeLock: android.os.PowerManager.WakeLock? = null
        val proximityLockLevel = 32
        if (powerManager.isWakeLockLevelSupported(proximityLockLevel)) {
            wakeLock = powerManager.newWakeLock(proximityLockLevel, "LksDialer:VideoProximitySensor").apply {
                setReferenceCounted(false)
            }
            if (state.callStatus == CallStatus.ANSWERED &&
                state.selectedAudioDevice == com.example.webrtc.AudioDeviceType.EARPIECE) {
                wakeLock.acquire()
            }
        }
        onDispose {
            try {
                if (wakeLock?.isHeld == true) {
                    wakeLock.release()
                }
            } catch (_: Exception) {}
        }
    }

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
    val isAudioHighlighted = state.selectedAudioDevice == com.example.webrtc.AudioDeviceType.BLUETOOTH ||
        state.selectedAudioDevice == com.example.webrtc.AudioDeviceType.SPEAKERPHONE

    // Dynamic Video Tracks for Fullscreen vs Floating PiP (Tap-to-Swap)
    val fullscreenTrack = if (!isSwapped) state.remoteVideoTrack else (if (state.isCameraOn) state.localVideoTrack else null)
    val pipTrack = if (!isSwapped) (if (state.isCameraOn) state.localVideoTrack else null) else state.remoteVideoTrack
    val isFullscreenMirrored = if (!isSwapped) false else state.isFrontCamera
    val isPipMirrored = if (!isSwapped) state.isFrontCamera else false

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures {
                    areControlsVisible = !areControlsVisible
                }
            }
    ) {
        // Fullscreen Video Surface or Dedicated On-Hold Screen
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0F172A)),
            contentAlignment = Alignment.Center
        ) {
            if (state.isOnHold) {
                // Dedicated On-Hold Screen
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0F172A).copy(alpha = 0.94f))
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Surface(
                        modifier = Modifier.size(96.dp),
                        shape = CircleShape,
                        color = if (state.isHeldLocally) Color(0xFFF59E0B) else Color(0xFF3B82F6)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Pause,
                                contentDescription = "Call On Hold",
                                tint = Color.White,
                                modifier = Modifier.size(44.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = if (state.isHeldLocally) "Call Placed On Hold" else "Call On Hold",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (state.isHeldLocally)
                            "Microphone and camera are paused"
                        else
                            "${activeCall.calleeName.ifBlank { activeCall.callerName }} placed the call on hold",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.75f),
                        textAlign = TextAlign.Center
                    )

                    if (state.isHeldLocally) {
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = { webRtcEngine.putCallOnHold(false) },
                            colors = ButtonDefaults.buttonColors(containerColor = GreenCall),
                            shape = RoundedCornerShape(24.dp),
                            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp)
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = "Resume",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Resume Call",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                        }
                    }
                }
            } else {
                // Fullscreen Video Renderer
                if (fullscreenTrack != null) {
                    WebRtcVideoRenderer(
                        videoTrack = fullscreenTrack,
                        eglBaseContext = webRtcEngine.eglBaseContext,
                        modifier = Modifier.fillMaxSize(),
                        mirror = isFullscreenMirrored,
                        onFirstFrameRendered = {
                            if (!isSwapped) {
                                isRemoteFirstFrameRendered = true
                            }
                        }
                    )
                }

                // Connecting Overlay (fades out smoothly when video starts rendering)
                AnimatedVisibility(
                    visible = (!isSwapped && (!isRemoteFirstFrameRendered || state.remoteVideoTrack == null)) ||
                        (isSwapped && (!state.isCameraOn || state.localVideoTrack == null)),
                    enter = fadeIn(tween(200)),
                    exit = fadeOut(tween(400)),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    listOf(
                                        Color(0xFF0B141B),
                                        Color(0xFF111B21),
                                        Color(0xFF0B141B)
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            // Pulsing Avatar with 3D Character Fallback
                            Box(contentAlignment = Alignment.Center) {
                                Box(
                                    modifier = Modifier
                                        .size(140.dp)
                                        .graphicsLayer {
                                            scaleX = pulseScale
                                            scaleY = pulseScale
                                            alpha = pulseAlpha
                                        }
                                        .clip(CircleShape)
                                        .background(SwiggyOrange.copy(alpha = 0.4f))
                                )
                                Surface(
                                    modifier = Modifier.size(108.dp),
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shadowElevation = 8.dp
                                ) {
                                    if (avatarImageBitmap != null) {
                                        Image(
                                            bitmap = avatarImageBitmap,
                                            contentDescription = displayName,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Image(
                                            painter = painterResource(avatar3dRes),
                                            contentDescription = displayName,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(20.dp))

                            Text(
                                text = displayName.ifBlank { displayNumber },
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            // Glassmorphic "Connecting video..." pill badge
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = Color.White.copy(alpha = 0.12f),
                                modifier = Modifier.padding(horizontal = 16.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(13.dp),
                                        strokeWidth = 2.dp,
                                        color = SwiggyOrange
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (isSwapped) "Your camera is off" else "Connecting video...",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                        color = Color.White.copy(alpha = 0.9f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Floating PiP Window (Local or Remote Preview, Draggable, Tap-to-Swap)
        var localPipOffsetX by remember { mutableFloatStateOf(0f) }
        var localPipOffsetY by remember { mutableFloatStateOf(0f) }

        Surface(
            modifier = Modifier
                .then(
                    if (isPipMode) {
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                    } else {
                        Modifier
                            .align(Alignment.TopEnd)
                            .statusBarsPadding()
                            .displayCutoutPadding()
                            .padding(top = 76.dp, end = 16.dp)
                            .offset { IntOffset(localPipOffsetX.roundToInt(), localPipOffsetY.roundToInt()) }
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    localPipOffsetX += dragAmount.x
                                    localPipOffsetY += dragAmount.y
                                }
                            }
                    }
                )
                .size(
                    width = if (isPipMode) 46.dp else 100.dp,
                    height = if (isPipMode) 70.dp else 145.dp
                )
                .clip(RoundedCornerShape(if (isPipMode) 8.dp else 18.dp)),
            shape = RoundedCornerShape(if (isPipMode) 8.dp else 18.dp),
            border = if (!isPipMode) BorderStroke(1.5.dp, Color.White.copy(alpha = 0.35f)) else null,
            shadowElevation = if (isPipMode) 4.dp else 12.dp,
            color = Color.Black
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures {
                            isSwapped = !isSwapped
                        }
                    }
            ) {
                if (pipTrack != null) {
                    WebRtcVideoRenderer(
                        videoTrack = pipTrack,
                        eglBaseContext = webRtcEngine.eglBaseContext,
                        modifier = Modifier.fillMaxSize(),
                        mirror = isPipMirrored,
                        isOverlay = true
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF1E293B)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.VideocamOff,
                                contentDescription = "Cam Off",
                                tint = Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.size(if (isPipMode) 14.dp else 24.dp)
                            )
                            if (!isPipMode) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Cam Off",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                    color = Color.White.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Top Header Overlay (Animated with tap-to-hide)
        AnimatedVisibility(
            visible = areControlsVisible && !isPipMode,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.85f),
                                Color.Black.copy(alpha = 0.45f),
                                Color.Transparent
                            )
                        )
                    )
                    .statusBarsPadding()
                    .displayCutoutPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Left: Minimize button
                    if (onMinimize != null) {
                        IconButton(
                            onClick = onMinimize,
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.50f))
                                .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Minimize Call",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    // Center: Contact Name & Clean Call Duration / HD Quality
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = displayName.ifBlank { displayNumber },
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                shadow = androidx.compose.ui.graphics.Shadow(
                                    color = Color.Black.copy(alpha = 0.7f),
                                    blurRadius = 8f
                                )
                            ),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = webRtcEngine.formatDuration(state.callDurationSeconds),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = Color.White.copy(alpha = 0.9f)
                            )
                            if (state.networkQualityBars in 1..4) {
                                Spacer(modifier = Modifier.width(6.dp))
                                val qualityColor = when {
                                    state.networkQualityBars >= 4 -> Color(0xFF22C55E)
                                    state.networkQualityBars == 3 -> Color(0xFFF59E0B)
                                    else -> Color(0xFFEF4444)
                                }
                                val qualityText = when {
                                    state.callQualityBadge.isNotBlank() && state.callQualityBadge != "Connecting..." -> state.callQualityBadge
                                    state.networkQualityBars >= 4 -> "HD"
                                    state.networkQualityBars == 3 -> "Good"
                                    else -> "Weak"
                                }
                                Surface(
                                    color = qualityColor.copy(alpha = 0.25f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(5.dp)
                                                .background(qualityColor, CircleShape)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = qualityText,
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 10.sp),
                                            color = qualityColor
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Right: Switch to Audio & More Options Menu
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = { webRtcEngine.revertToAudioCall("Switched to Audio Call") },
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.50f))
                                .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Phone,
                                contentDescription = "Switch to Audio",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Box {
                            IconButton(
                                onClick = { showMoreMenu = true },
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.50f))
                                    .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "More Options",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            DropdownMenu(
                                expanded = showMoreMenu,
                                onDismissRequest = { showMoreMenu = false },
                                modifier = Modifier.background(Color(0xFF1E293B))
                            ) {
                                DropdownMenuItem(
                                    text = { Text(if (state.isOnHold) "Resume Call" else "Put On Hold", color = Color.White) },
                                    leadingIcon = {
                                        Icon(
                                            if (state.isOnHold) Icons.Default.PlayArrow else Icons.Default.Pause,
                                            contentDescription = null,
                                            tint = Color.White
                                        )
                                    },
                                    onClick = {
                                        showMoreMenu = false
                                        if (state.isOnHold) {
                                            if (state.isHeldLocally) webRtcEngine.putCallOnHold(false)
                                        } else {
                                            webRtcEngine.putCallOnHold(true)
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (isVideoRec) "Stop Recording" else "Record Call", color = Color.White) },
                                    leadingIcon = {
                                        Icon(
                                            if (isVideoRec) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                                            contentDescription = null,
                                            tint = if (isVideoRec) Color(0xFFEF4444) else Color.White
                                        )
                                    },
                                    onClick = {
                                        showMoreMenu = false
                                        val started = webRtcEngine.toggleCallRecording()
                                        android.widget.Toast.makeText(
                                            context,
                                            if (started) "Call recording started" else "Call recording saved to Call History",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                )
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                    DropdownMenuItem(
                                        text = { Text("Floating Window (PiP)", color = Color.White) },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.PictureInPictureAlt,
                                                contentDescription = null,
                                                tint = Color.White
                                            )
                                        },
                                        onClick = {
                                            showMoreMenu = false
                                            (context.findActivity() as? com.example.MainActivity)?.enterPipMode()
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Call On Hold Banner
        androidx.compose.animation.AnimatedVisibility(
            visible = state.isOnHold && !isPipMode,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .displayCutoutPadding()
                .padding(top = 74.dp)
        ) {
            Surface(
                color = Color(0xFFE53935).copy(alpha = 0.88f),
                shape = RoundedCornerShape(20.dp),
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Pause,
                        contentDescription = "Hold",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (state.isHeldLocally) "Call On Hold" else "Call On Hold (Other Party)",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                }
            }
        }

        // Recording Indicator Banner
        androidx.compose.animation.AnimatedVisibility(
            visible = isVideoRec && !isPipMode,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .displayCutoutPadding()
                .padding(top = if (state.isOnHold) 124.dp else 74.dp)
        ) {
            Surface(
                color = Color(0xFFEF4444).copy(alpha = 0.9f),
                shape = RoundedCornerShape(20.dp),
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(Color.White, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "REC ${com.example.util.CallAudioRecorder.formatDuration(recordingDuration)}",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                }
            }
        }

        // Bottom Controls Dock: Single Sleek Horizontal Glassmorphic Capsule
        AnimatedVisibility(
            visible = areControlsVisible && !isPipMode,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(38.dp),
                    color = Color(0xDD111827),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                    shadowElevation = 16.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. Flip Camera
                        IconButton(
                            onClick = { webRtcEngine.switchCamera() },
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.12f))
                        ) {
                            Icon(
                                Icons.Default.Cameraswitch,
                                contentDescription = "Flip Camera",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        // 2. Camera On / Off
                        val camBg = if (state.isCameraOn) Color.White.copy(alpha = 0.12f) else Color(0xFFEF4444).copy(alpha = 0.25f)
                        val camTint = if (state.isCameraOn) Color.White else Color(0xFFEF4444)
                        IconButton(
                            onClick = { webRtcEngine.toggleCamera() },
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(camBg)
                        ) {
                            Icon(
                                if (state.isCameraOn) Icons.Default.Videocam else Icons.Default.VideocamOff,
                                contentDescription = "Camera Toggle",
                                tint = camTint,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        // 3. Mic Mute / Unmute
                        val micBg = if (state.isMuted) Color(0xFFEF4444) else Color.White.copy(alpha = 0.12f)
                        IconButton(
                            onClick = { webRtcEngine.toggleMute() },
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(micBg)
                        ) {
                            Icon(
                                if (state.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                contentDescription = "Mute Toggle",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        // 4. Audio Output Switcher
                        val audioBg = if (isAudioHighlighted) SwiggyOrange.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.12f)
                        val audioTint = if (isAudioHighlighted) SwiggyOrange else Color.White
                        IconButton(
                            onClick = {
                                webRtcEngine.refreshAvailableAudioDevices()
                                showAudioDialog = true
                            },
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(audioBg)
                        ) {
                            Icon(
                                audioIcon,
                                contentDescription = audioLabel,
                                tint = audioTint,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        // 5. End Call Button
                        Button(
                            onClick = {
                                if (!endCallClicked) {
                                    endCallClicked = true
                                    onEndCall()
                                }
                            },
                            shape = CircleShape,
                            modifier = Modifier.size(54.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = RedEndCall),
                            contentPadding = PaddingValues(0.dp),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp)
                        ) {
                            Icon(
                                Icons.Default.CallEnd,
                                contentDescription = "End Call",
                                tint = Color.White,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
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
                        color = if (isSelected) SwiggyOrange.copy(alpha = 0.22f) else Color.Transparent,
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
                                    tint = if (isSelected) SwiggyOrange else Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(
                                    text = device.name,
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    ),
                                    color = if (isSelected) SwiggyOrange else Color.White
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = SwiggyOrange,
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
                Text("Cancel", color = SwiggyOrange)
            }
        }
    )
}

@Composable
private fun InCallControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isActive: Boolean,
    activeColor: Color = GreenCall,
    size: androidx.compose.ui.unit.Dp = 60.dp,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(if (isActive) activeColor else Color.White.copy(alpha = 0.2f))
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isActive && activeColor == GreenCall) Color.Black else Color.White,
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
    isOverlay: Boolean = false,
    onFirstFrameRendered: (() -> Unit)? = null
) {
    AndroidView(
        factory = { context ->
            org.webrtc.SurfaceViewRenderer(context).apply {
                init(eglBaseContext, object : org.webrtc.RendererCommon.RendererEvents {
                    override fun onFirstFrameRendered() {
                        post { onFirstFrameRendered?.invoke() }
                    }
                    override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) {}
                })
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
