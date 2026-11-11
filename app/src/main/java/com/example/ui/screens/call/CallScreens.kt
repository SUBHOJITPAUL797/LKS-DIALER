package com.example.ui.screens.call

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.zIndex
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
import androidx.compose.ui.graphics.Brush
import com.example.ui.theme.*
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
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0A101E),
                        Color(0xFF0F172A),
                        Color(0xFF1E293B)
                    )
                )
            )
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
            // Dark translucent gradient overlay so white text & controls remain ultra-clear
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.72f),
                                Color.Black.copy(alpha = 0.55f),
                                Color.Black.copy(alpha = 0.85f)
                            )
                        )
                    )
            )
        }

        if (onMinimize != null) {
            IconButton(
                onClick = onMinimize,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(top = 16.dp, start = 16.dp)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1E293B).copy(alpha = 0.85f))
                    .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape)
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

            // Profile & Call Info (Crisp, High-Contrast Typography)
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
                    color = Color.White.copy(alpha = 0.78f)
                )

                Spacer(modifier = Modifier.height(14.dp))

                val isRinging = statusText.contains("Ringing", ignoreCase = true)
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (isRinging) CyberMint.copy(alpha = 0.18f) else SignalAmber.copy(alpha = 0.18f),
                    border = BorderStroke(1.5.dp, if (isRinging) CyberMint else SignalAmber)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = if (callType == CallType.VIDEO) Icons.Default.Videocam else Icons.Default.Call,
                            contentDescription = null,
                            tint = if (isRinging) CyberMint else SignalAmber,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = if (isRinging) CyberMint else SignalAmber
                        )
                    }
                }
            }

            // Central Pulsing Avatar
            Box(
                modifier = Modifier.size(190.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(190.dp)
                        .graphicsLayer {
                            scaleX = pulseAlpha * 0.22f + 0.95f
                            scaleY = pulseAlpha * 0.22f + 0.95f
                            alpha = pulseAlpha * 0.45f
                        }
                        .clip(CircleShape)
                        .background(CyberMint.copy(alpha = 0.25f))
                )
                Surface(
                    modifier = Modifier.size(150.dp),
                    shape = CircleShape,
                    color = Color(0xFF1E293B),
                    border = BorderStroke(2.5.dp, CyberMint),
                    shadowElevation = 12.dp
                ) {
                    if (decodedBitmap != null) {
                        Image(
                            bitmap = decodedBitmap,
                            contentDescription = calleeName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        val avatarRes = remember(calleeName, calleeNumber) {
                            Avatar3dHelper.getAvatarResId(calleeName, calleeNumber)
                        }
                        Image(
                            painter = painterResource(id = avatarRes),
                            contentDescription = calleeName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            // Bottom Docked Controls Card
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color(0xE60F172A),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                shadowElevation = 16.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(vertical = 20.dp, horizontal = 16.dp)
                ) {
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
                                .padding(horizontal = 16.dp),
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

                            // End Call Button centered between Mute and Speaker
                            Button(
                                onClick = { if (!endCallClicked) { endCallClicked = true; onEndCall() } },
                                shape = CircleShape,
                                modifier = Modifier.size(68.dp),
                                contentPadding = PaddingValues(0.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = RedEndCall)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CallEnd,
                                    contentDescription = "End Call",
                                    tint = Color.White,
                                    modifier = Modifier.size(32.dp)
                                )
                            }

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
                    } else {
                        Button(
                            onClick = { if (!endCallClicked) { endCallClicked = true; onEndCall() } },
                            shape = CircleShape,
                            modifier = Modifier.size(72.dp),
                            contentPadding = PaddingValues(0.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = RedEndCall)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CallEnd,
                                contentDescription = "End Call",
                                tint = Color.White,
                                modifier = Modifier.size(34.dp)
                            )
                        }
                    }
                }
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val themeManager = remember { ThemeManager.getInstance(context) }
    val incomingCallStyle by themeManager.incomingCallStyle.collectAsState()
    val incomingSwipeDirection by themeManager.incomingSwipeDirection.collectAsState()
    var actionTaken by remember { mutableStateOf(false) }

    val triggerAnswer = {
        if (!actionTaken) {
            actionTaken = true
            try {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            } catch (_: Throwable) {}
            onAnswer()
        }
    }

    val triggerDecline = {
        if (!actionTaken) {
            actionTaken = true
            try {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            } catch (_: Throwable) {}
            onDecline()
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "incoming_call_liquid")
    val pulsePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_phase"
    )
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_offset"
    )
    val floatBreath by infiniteTransition.animateFloat(
        initialValue = -6f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "float_breath"
    )

    val isSamsungStyle = incomingCallStyle == IncomingCallUiStyle.SAMSUNG_ONE_UI ||
        incomingSwipeDirection == IncomingSwipeDirection.SAMSUNG_EXPAND_RING

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = if (isSamsungStyle) {
                        listOf(
                            Color(0xFF06152D),
                            Color(0xFF0B2545),
                            Color(0xFF091326)
                        )
                    } else {
                        listOf(
                            Color(0xFF0A1428),
                            Color(0xFF10203B),
                            Color(0xFF070E1C)
                        )
                    }
                )
            )
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
                modifier = Modifier
                    .fillMaxSize()
                    .scale(1.08f)
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xFF060D1A).copy(alpha = 0.65f),
                                Color(0xFF0A182E).copy(alpha = 0.52f),
                                Color(0xFF050B14).copy(alpha = 0.84f)
                            )
                        )
                    )
            )
        } else {
            // Ambient Liquid Glass refractive aura orbs in background
            Box(
                modifier = Modifier
                    .offset(x = (-50).dp, y = (70 + floatBreath).dp)
                    .size(280.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color(0xFF38BDF8).copy(alpha = 0.24f),
                                Color(0xFF6366F1).copy(alpha = 0.08f),
                                Color.Transparent
                            )
                        )
                    )
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 60.dp, y = (-90 - floatBreath).dp)
                    .size(300.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                CyberMint.copy(alpha = 0.22f),
                                Color(0xFF0EA5E9).copy(alpha = 0.08f),
                                Color.Transparent
                            )
                        )
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // ── TOP: Quick Style Switcher + Caller Identity Liquid Glass Card ──
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Live Style Switcher Pill (iPhone Liquid Glass vs Samsung One UI)
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color.White.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.28f))
                ) {
                    Row(
                        modifier = Modifier.padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IncomingCallUiStyle.entries.forEach { styleOption ->
                            val selected = incomingCallStyle == styleOption
                            Surface(
                                onClick = {
                                    themeManager.setIncomingCallStyle(styleOption)
                                    if (styleOption == IncomingCallUiStyle.SAMSUNG_ONE_UI &&
                                        incomingSwipeDirection == IncomingSwipeDirection.IPHONE_SLIDE_RIGHT
                                    ) {
                                        themeManager.setIncomingSwipeDirection(IncomingSwipeDirection.ANY_DIRECTION)
                                    }
                                },
                                shape = RoundedCornerShape(50),
                                color = if (selected) Color.White.copy(alpha = 0.24f) else Color.Transparent,
                                border = if (selected) BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)) else null
                            ) {
                                Text(
                                    text = if (styleOption == IncomingCallUiStyle.IPHONE_LIQUID_GLASS) {
                                        "iPhone Liquid Glass"
                                    } else {
                                        "Samsung One UI"
                                    },
                                    fontFamily = SpaceGroteskFontFamily,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 11.sp,
                                    color = if (selected) Color.White else Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Refractive Liquid Glass Caller Header Card
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = Color.White.copy(alpha = 0.13f),
                    border = BorderStroke(
                        1.2.dp,
                        Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.65f),
                                Color(0xFF7DD3FC).copy(alpha = 0.35f),
                                Color.White.copy(alpha = 0.20f)
                            )
                        )
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0.20f),
                                        Color.White.copy(alpha = 0.04f)
                                    )
                                )
                            )
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (isSpam) CrimsonAlert.copy(alpha = 0.22f) else CyberMint.copy(alpha = 0.20f),
                            border = BorderStroke(1.dp, if (isSpam) CrimsonAlert else CyberMint.copy(alpha = 0.7f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (callType == CallType.VIDEO) Icons.Default.Videocam else Icons.Default.PhoneInTalk,
                                    contentDescription = null,
                                    tint = if (isSpam) CrimsonAlert else CyberMint,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "INCOMING ${callType.name} CALL",
                                    fontFamily = SpaceGroteskFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    letterSpacing = 1.1.sp,
                                    color = if (isSpam) CrimsonAlert else CyberMint
                                )
                            }
                        }

                        if (isSpam) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "⚠ Suspected Spam Caller",
                                color = CrimsonAlert,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = callerName,
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 28.sp,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = callerNumber,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 14.sp,
                            color = Color.White.copy(alpha = 0.82f)
                        )
                    }
                }
            }

            // ── CENTER: Pulsing Refractive Avatar Ring ──
            Box(
                modifier = Modifier.size(200.dp),
                contentAlignment = Alignment.Center
            ) {
                // Expanding concentric Samsung / Liquid Glass rings
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .scale(0.82f + pulsePhase * 0.26f)
                        .alpha((1f - pulsePhase).coerceIn(0f, 1f))
                        .clip(CircleShape)
                        .border(2.dp, CyberMint.copy(alpha = 0.65f), CircleShape)
                        .background(CyberMint.copy(alpha = 0.10f))
                )
                Box(
                    modifier = Modifier
                        .size(172.dp)
                        .scale(0.88f + ((pulsePhase + 0.5f) % 1f) * 0.20f)
                        .alpha((1f - ((pulsePhase + 0.5f) % 1f)).coerceIn(0f, 1f))
                        .clip(CircleShape)
                        .border(1.5.dp, Color(0xFF38BDF8).copy(alpha = 0.6f), CircleShape)
                )

                Surface(
                    modifier = Modifier.size(138.dp),
                    shape = CircleShape,
                    color = Color(0xFF1E293B),
                    border = BorderStroke(
                        3.dp,
                        Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.9f),
                                CyberMint,
                                Color(0xFF38BDF8)
                            )
                        )
                    ),
                    shadowElevation = 16.dp
                ) {
                    if (decodedBitmap != null) {
                        Image(
                            bitmap = decodedBitmap,
                            contentDescription = callerName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        val avatarRes = remember(callerName, callerNumber) {
                            Avatar3dHelper.getAvatarResId(callerName, callerNumber)
                        }
                        Image(
                            painter = painterResource(id = avatarRes),
                            contentDescription = callerName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            // ── BOTTOM: Multi-Directional Swipe Pickup Deck (iPhone Liquid Glass & Samsung One UI) ──
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Mode selector chips so user can switch gesture style right on the call screen if desired
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    IncomingSwipeDirection.entries.forEach { dirOption ->
                        val isDirSelected = incomingSwipeDirection == dirOption
                        Surface(
                            onClick = { themeManager.setIncomingSwipeDirection(dirOption) },
                            shape = RoundedCornerShape(50),
                            color = if (isDirSelected) CyberMint.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f),
                            border = BorderStroke(
                                1.dp,
                                if (isDirSelected) CyberMint else Color.White.copy(alpha = 0.20f)
                            )
                        ) {
                            val shortLabel = when (dirOption) {
                                IncomingSwipeDirection.ANY_DIRECTION -> "↑ ← → Omni Swipe"
                                IncomingSwipeDirection.IPHONE_SLIDE_RIGHT -> "iPhone Slide"
                                IncomingSwipeDirection.SWIPE_UP -> "Swipe Up"
                                IncomingSwipeDirection.SAMSUNG_EXPAND_RING -> "Samsung Rings"
                            }
                            Text(
                                text = shortLabel,
                                fontFamily = SpaceGroteskFontFamily,
                                fontWeight = if (isDirSelected) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 10.sp,
                                color = if (isDirSelected) Color.White else Color.White.copy(alpha = 0.72f),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                when {
                    incomingSwipeDirection == IncomingSwipeDirection.IPHONE_SLIDE_RIGHT && !isSamsungStyle -> {
                        // ── 1. iPhone Liquid Glass Horizontal Slide Capsule + Multi-Directional Support ──
                        var dragX by remember { mutableStateOf(0f) }
                        var dragY by remember { mutableStateOf(0f) }
                        val animatedX by animateFloatAsState(
                            targetValue = dragX,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMedium
                            ),
                            label = "iphone_slide_x"
                        )
                        val animatedY by animateFloatAsState(
                            targetValue = dragY,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMedium
                            ),
                            label = "iphone_slide_y"
                        )
                        val thresholdPx = with(density) { 105.dp.toPx() }
                        val upThresholdPx = with(density) { 80.dp.toPx() }

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "↑ Swipe Up, Left, or Slide Right to Answer",
                                fontFamily = SpaceGroteskFontFamily,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.padding(bottom = 10.dp)
                            )

                            // Liquid Glass Capsule Track
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = Color.White.copy(alpha = 0.14f),
                                border = BorderStroke(
                                    1.5.dp,
                                    Brush.linearGradient(
                                        colors = listOf(
                                            Color.White.copy(alpha = 0.75f),
                                            Color(0xFF7DD3FC).copy(alpha = 0.45f),
                                            CyberMint.copy(alpha = 0.55f)
                                        )
                                    )
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(78.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            Brush.horizontalGradient(
                                                colors = listOf(
                                                    RedEndCall.copy(alpha = 0.16f),
                                                    Color.White.copy(alpha = 0.10f),
                                                    GreenCall.copy(alpha = 0.24f)
                                                )
                                            )
                                        )
                                        .padding(horizontal = 8.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    // Shimmering prompt label inside capsule
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "slide or swipe ↑ ← → to answer",
                                            fontFamily = SpaceGroteskFontFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            color = Color.White.copy(alpha = (0.65f + 0.35f * kotlin.math.abs(shimmerOffset)).coerceIn(0.4f, 1f))
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Icon(
                                            Icons.Default.KeyboardDoubleArrowRight,
                                            contentDescription = null,
                                            tint = CyberMint,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    // Draggable Liquid Glass Orb inside track
                                    Surface(
                                        shape = CircleShape,
                                        color = GreenCall,
                                        border = BorderStroke(2.dp, Color.White.copy(alpha = 0.85f)),
                                        shadowElevation = 12.dp,
                                        modifier = Modifier
                                            .offset {
                                                IntOffset(
                                                    animatedX.roundToInt().coerceAtLeast(0),
                                                    animatedY.roundToInt().coerceAtMost(0)
                                                )
                                            }
                                            .size(62.dp)
                                            .pointerInput(Unit) {
                                                detectDragGestures(
                                                    onDragEnd = {
                                                        if (dragX > thresholdPx || dragY < -upThresholdPx) {
                                                            triggerAnswer()
                                                        } else {
                                                            dragX = 0f
                                                            dragY = 0f
                                                        }
                                                    },
                                                    onDragCancel = {
                                                        dragX = 0f
                                                        dragY = 0f
                                                    },
                                                    onDrag = { change, dragAmount ->
                                                        change.consume()
                                                        dragX = (dragX + dragAmount.x).coerceIn(0f, thresholdPx * 1.6f)
                                                        dragY = (dragY + dragAmount.y).coerceIn(-upThresholdPx * 1.4f, 20f)
                                                    }
                                                )
                                            }
                                            .clickable { triggerAnswer() }
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = if (callType == CallType.VIDEO) Icons.Default.Videocam else Icons.Default.Call,
                                                contentDescription = "Answer",
                                                tint = Color.White,
                                                modifier = Modifier.size(28.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Clean Liquid Glass Decline Pill
                            Surface(
                                onClick = { triggerDecline() },
                                shape = RoundedCornerShape(50),
                                color = RedEndCall.copy(alpha = 0.85f),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.45f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.CallEnd,
                                        contentDescription = "Decline",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Decline Call",
                                        fontFamily = SpaceGroteskFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }

                    isSamsungStyle -> {
                        // ── 2. Samsung One UI Dual Expanding Swipe Rings (Answer & Decline) ──
                        var answerDragX by remember { mutableStateOf(0f) }
                        var answerDragY by remember { mutableStateOf(0f) }
                        var declineDragX by remember { mutableStateOf(0f) }
                        var declineDragY by remember { mutableStateOf(0f) }

                        val answerDist = kotlin.math.sqrt(answerDragX * answerDragX + answerDragY * answerDragY)
                        val declineDist = kotlin.math.sqrt(declineDragX * declineDragX + declineDragY * declineDragY)
                        val ringThresholdPx = with(density) { 78.dp.toPx() }

                        val animAnswerX by animateFloatAsState(answerDragX, spring(stiffness = Spring.StiffnessMediumLow), label = "ans_x")
                        val animAnswerY by animateFloatAsState(answerDragY, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "ans_y")
                        val animDeclineX by animateFloatAsState(declineDragX, spring(stiffness = Spring.StiffnessMediumLow), label = "dec_x")
                        val animDeclineY by animateFloatAsState(declineDragY, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "dec_y")

                        val answerProgress = (answerDist / ringThresholdPx).coerceIn(0f, 1.25f)
                        val declineProgress = (declineDist / ringThresholdPx).coerceIn(0f, 1.25f)

                        Surface(
                            shape = RoundedCornerShape(32.dp),
                            color = Color.White.copy(alpha = 0.12f),
                            border = BorderStroke(
                                1.2.dp,
                                Brush.linearGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0.55f),
                                        Color.White.copy(alpha = 0.18f)
                                    )
                                )
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Swipe Answer Ring ↑ Up, ← Left, or → Right",
                                    fontFamily = SpaceGroteskFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = Color.White.copy(alpha = 0.90f)
                                )
                                Spacer(modifier = Modifier.height(14.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Samsung Expanding Answer Ring (Green - Swipe Up, Left, or Right)
                                    Box(
                                        modifier = Modifier.size(128.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        // Outer target boundary ring
                                        Box(
                                            modifier = Modifier
                                                .size(120.dp)
                                                .scale(0.85f + 0.25f * answerProgress + 0.08f * pulsePhase)
                                                .clip(CircleShape)
                                                .border(
                                                    width = if (answerProgress > 0.7f) 2.5.dp else 1.5.dp,
                                                    color = GreenCall.copy(alpha = (0.45f + 0.5f * answerProgress).coerceIn(0f, 1f)),
                                                    shape = CircleShape
                                                )
                                                .background(GreenCall.copy(alpha = 0.12f + 0.18f * answerProgress))
                                        )

                                        // Directional arrows around the ring
                                        Icon(
                                            Icons.Default.KeyboardArrowUp,
                                            contentDescription = null,
                                            tint = Color.White.copy(alpha = 0.75f),
                                            modifier = Modifier
                                                .align(Alignment.TopCenter)
                                                .padding(top = 4.dp)
                                                .size(18.dp)
                                        )
                                        Icon(
                                            Icons.Default.KeyboardArrowLeft,
                                            contentDescription = null,
                                            tint = Color.White.copy(alpha = 0.75f),
                                            modifier = Modifier
                                                .align(Alignment.CenterStart)
                                                .padding(start = 4.dp)
                                                .size(18.dp)
                                        )
                                        Icon(
                                            Icons.Default.KeyboardArrowRight,
                                            contentDescription = null,
                                            tint = Color.White.copy(alpha = 0.75f),
                                            modifier = Modifier
                                                .align(Alignment.CenterEnd)
                                                .padding(end = 4.dp)
                                                .size(18.dp)
                                        )

                                        // Draggable Green Answer Orb
                                        Surface(
                                            shape = CircleShape,
                                            color = GreenCall,
                                            border = BorderStroke(2.dp, Color.White),
                                            shadowElevation = 12.dp,
                                            modifier = Modifier
                                                .offset { IntOffset(animAnswerX.roundToInt(), animAnswerY.roundToInt()) }
                                                .size(72.dp)
                                                .pointerInput(Unit) {
                                                    detectDragGestures(
                                                        onDragEnd = {
                                                            // Answer if dragged beyond threshold Up, Left, or Right
                                                            if (answerDist >= ringThresholdPx && answerDragY <= ringThresholdPx * 0.6f) {
                                                                triggerAnswer()
                                                            } else {
                                                                answerDragX = 0f
                                                                answerDragY = 0f
                                                            }
                                                        },
                                                        onDragCancel = {
                                                            answerDragX = 0f
                                                            answerDragY = 0f
                                                        },
                                                        onDrag = { change, dragAmount ->
                                                            change.consume()
                                                            answerDragX = (answerDragX + dragAmount.x).coerceIn(-ringThresholdPx * 1.35f, ringThresholdPx * 1.35f)
                                                            answerDragY = (answerDragY + dragAmount.y).coerceIn(-ringThresholdPx * 1.35f, ringThresholdPx * 0.5f)
                                                        }
                                                    )
                                                }
                                                .clickable { triggerAnswer() }
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = if (callType == CallType.VIDEO) Icons.Default.Videocam else Icons.Default.Call,
                                                    contentDescription = "Answer",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(32.dp)
                                                )
                                            }
                                        }
                                    }

                                    // Samsung Expanding Decline Ring (Red - Swipe Any Direction)
                                    Box(
                                        modifier = Modifier.size(128.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(120.dp)
                                                .scale(0.85f + 0.25f * declineProgress + 0.08f * pulsePhase)
                                                .clip(CircleShape)
                                                .border(
                                                    width = if (declineProgress > 0.7f) 2.5.dp else 1.5.dp,
                                                    color = RedEndCall.copy(alpha = (0.45f + 0.5f * declineProgress).coerceIn(0f, 1f)),
                                                    shape = CircleShape
                                                )
                                                .background(RedEndCall.copy(alpha = 0.12f + 0.18f * declineProgress))
                                        )

                                        Surface(
                                            shape = CircleShape,
                                            color = RedEndCall,
                                            border = BorderStroke(2.dp, Color.White),
                                            shadowElevation = 12.dp,
                                            modifier = Modifier
                                                .offset { IntOffset(animDeclineX.roundToInt(), animDeclineY.roundToInt()) }
                                                .size(72.dp)
                                                .pointerInput(Unit) {
                                                    detectDragGestures(
                                                        onDragEnd = {
                                                            if (declineDist >= ringThresholdPx) {
                                                                triggerDecline()
                                                            } else {
                                                                declineDragX = 0f
                                                                declineDragY = 0f
                                                            }
                                                        },
                                                        onDragCancel = {
                                                            declineDragX = 0f
                                                            declineDragY = 0f
                                                        },
                                                        onDrag = { change, dragAmount ->
                                                            change.consume()
                                                            declineDragX = (declineDragX + dragAmount.x).coerceIn(-ringThresholdPx * 1.35f, ringThresholdPx * 1.35f)
                                                            declineDragY = (declineDragY + dragAmount.y).coerceIn(-ringThresholdPx * 1.35f, ringThresholdPx * 1.35f)
                                                        }
                                                    )
                                                }
                                                .clickable { triggerDecline() }
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    Icons.Default.CallEnd,
                                                    contentDescription = "Decline",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(32.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    else -> {
                        // ── 3. Default iPhone Liquid Glass Omni-Directional Pickup (Swipe Up, Left, or Right) ──
                        var orbDragX by remember { mutableStateOf(0f) }
                        var orbDragY by remember { mutableStateOf(0f) }
                        val swipeThresholdPx = with(density) { 78.dp.toPx() }
                        val allowHorizontal = incomingSwipeDirection == IncomingSwipeDirection.ANY_DIRECTION

                        val animOrbX by animateFloatAsState(
                            targetValue = orbDragX,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMedium
                            ),
                            label = "omni_orb_x"
                        )
                        val animOrbY by animateFloatAsState(
                            targetValue = orbDragY,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMedium
                            ),
                            label = "omni_orb_y"
                        )

                        val isSwipingAnswer = orbDragY < -swipeThresholdPx * 0.45f ||
                            (allowHorizontal && kotlin.math.abs(orbDragX) > swipeThresholdPx * 0.45f)
                        val isSwipingDecline = orbDragY > swipeThresholdPx * 0.45f

                        Surface(
                            shape = RoundedCornerShape(34.dp),
                            color = Color.White.copy(alpha = 0.14f),
                            border = BorderStroke(
                                1.4.dp,
                                Brush.linearGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0.80f),
                                        Color(0xFF7DD3FC).copy(alpha = 0.45f),
                                        CyberMint.copy(alpha = 0.50f),
                                        Color.White.copy(alpha = 0.25f)
                                    )
                                )
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        Brush.verticalGradient(
                                            colors = listOf(
                                                Color.White.copy(alpha = 0.22f),
                                                Color.White.copy(alpha = 0.05f)
                                            )
                                        )
                                    )
                                    .padding(horizontal = 20.dp, vertical = 18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                // Animated directional guidance banner
                                Text(
                                    text = when {
                                        isSwipingAnswer -> "Release to Answer Call"
                                        isSwipingDecline -> "Release to Decline Call"
                                        allowHorizontal -> "Swipe ↑ Up, ← Left, or → Right to Answer"
                                        else -> "Swipe ↑ Up to Answer • ↓ Down to Decline"
                                    },
                                    fontFamily = SpaceGroteskFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = when {
                                        isSwipingAnswer -> CyberMint
                                        isSwipingDecline -> CrimsonAlert
                                        else -> Color.White.copy(alpha = 0.92f)
                                    }
                                )

                                Spacer(modifier = Modifier.height(14.dp))

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(140.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    // Side Decline Quick Button (Left)
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.align(Alignment.CenterStart)
                                    ) {
                                        Surface(
                                            onClick = { triggerDecline() },
                                            shape = CircleShape,
                                            color = RedEndCall.copy(alpha = 0.88f),
                                            border = BorderStroke(1.5.dp, Color.White.copy(alpha = 0.65f)),
                                            shadowElevation = 8.dp,
                                            modifier = Modifier.size(60.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    Icons.Default.CallEnd,
                                                    contentDescription = "Decline",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(26.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = "Decline",
                                            fontFamily = SpaceGroteskFontFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            color = Color.White.copy(alpha = 0.85f)
                                        )
                                    }

                                    // Directional Shimmer Chevrons Around Central Liquid Glass Orb
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier
                                            .align(Alignment.TopCenter)
                                            .offset(y = (floatBreath * 0.6f).dp)
                                    ) {
                                        Icon(
                                            Icons.Default.KeyboardDoubleArrowUp,
                                            contentDescription = "Swipe Up",
                                            tint = CyberMint.copy(alpha = 0.9f),
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }

                                    if (allowHorizontal) {
                                        Icon(
                                            Icons.Default.KeyboardDoubleArrowLeft,
                                            contentDescription = "Swipe Left",
                                            tint = CyberMint.copy(alpha = 0.8f),
                                            modifier = Modifier
                                                .align(Alignment.Center)
                                                .offset(x = (-64).dp)
                                                .size(22.dp)
                                        )
                                        Icon(
                                            Icons.Default.KeyboardDoubleArrowRight,
                                            contentDescription = "Swipe Right",
                                            tint = CyberMint.copy(alpha = 0.8f),
                                            modifier = Modifier
                                                .align(Alignment.Center)
                                                .offset(x = 64.dp)
                                                .size(22.dp)
                                        )
                                    }

                                    // Central Refractive Liquid Glass Answer Orb (Swipe Up, Left, or Right!)
                                    Surface(
                                        shape = CircleShape,
                                        color = if (isSwipingDecline) RedEndCall else GreenCall,
                                        border = BorderStroke(
                                            2.5.dp,
                                            Brush.linearGradient(
                                                colors = listOf(
                                                    Color.White,
                                                    Color(0xFFBAE6FD),
                                                    CyberMint
                                                )
                                            )
                                        ),
                                        shadowElevation = 16.dp,
                                        modifier = Modifier
                                            .offset {
                                                IntOffset(
                                                    animOrbX.roundToInt(),
                                                    animOrbY.roundToInt()
                                                )
                                            }
                                            .size(80.dp)
                                            .pointerInput(allowHorizontal) {
                                                detectDragGestures(
                                                    onDragEnd = {
                                                        val answeredUp = orbDragY < -swipeThresholdPx
                                                        val answeredLeftOrRight = allowHorizontal &&
                                                            kotlin.math.abs(orbDragX) > swipeThresholdPx
                                                        val declinedDown = orbDragY > swipeThresholdPx

                                                        when {
                                                            answeredUp || answeredLeftOrRight -> triggerAnswer()
                                                            declinedDown -> triggerDecline()
                                                            else -> {
                                                                orbDragX = 0f
                                                                orbDragY = 0f
                                                            }
                                                        }
                                                    },
                                                    onDragCancel = {
                                                        orbDragX = 0f
                                                        orbDragY = 0f
                                                    },
                                                    onDrag = { change, dragAmount ->
                                                        change.consume()
                                                        if (allowHorizontal) {
                                                            orbDragX = (orbDragX + dragAmount.x).coerceIn(
                                                                -swipeThresholdPx * 1.5f,
                                                                swipeThresholdPx * 1.5f
                                                            )
                                                        }
                                                        orbDragY = (orbDragY + dragAmount.y).coerceIn(
                                                            -swipeThresholdPx * 1.5f,
                                                            swipeThresholdPx * 1.3f
                                                        )
                                                    }
                                                )
                                            }
                                            .clickable { triggerAnswer() }
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(
                                                    Brush.radialGradient(
                                                        colors = listOf(
                                                            Color.White.copy(alpha = 0.35f),
                                                            Color.Transparent
                                                        )
                                                    )
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = when {
                                                    isSwipingDecline -> Icons.Default.CallEnd
                                                    callType == CallType.VIDEO -> Icons.Default.Videocam
                                                    else -> Icons.Default.Call
                                                },
                                                contentDescription = "Swipe to Answer",
                                                tint = Color.White,
                                                modifier = Modifier.size(34.dp)
                                            )
                                        }
                                    }

                                    // Side Direct Answer Button (Right)
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.align(Alignment.CenterEnd)
                                    ) {
                                        Surface(
                                            onClick = { triggerAnswer() },
                                            shape = CircleShape,
                                            color = GreenCall.copy(alpha = 0.88f),
                                            border = BorderStroke(1.5.dp, Color.White.copy(alpha = 0.65f)),
                                            shadowElevation = 8.dp,
                                            modifier = Modifier.size(60.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = if (callType == CallType.VIDEO) Icons.Default.Videocam else Icons.Default.Call,
                                                    contentDescription = "Answer",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(26.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = "Answer",
                                            fontFamily = SpaceGroteskFontFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            color = Color.White.copy(alpha = 0.85f)
                                        )
                                    }
                                }
                            }
                        }
                    }
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
    onAddPersonToGroup: (() -> Unit)? = null,
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

        if (onAddPersonToGroup != null) {
            Surface(
                onClick = onAddPersonToGroup,
                shape = RoundedCornerShape(50),
                color = CyberMint.copy(alpha = 0.22f),
                border = BorderStroke(1.dp, CyberMint.copy(alpha = 0.65f)),
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(top = 16.dp, end = 16.dp)
                    .align(Alignment.TopEnd)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.PersonAdd,
                        contentDescription = "Add to Group Call",
                        tint = CyberMint,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Group Call",
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = Color.White
                    )
                }
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
                        fontFamily = com.example.ui.theme.JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = GreenCall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    val qualityColor = when {
                        state.networkQualityBars >= 4 -> com.example.ui.theme.CyberMint
                        state.networkQualityBars == 3 -> com.example.ui.theme.SignalAmber
                        else -> com.example.ui.theme.CrimsonAlert
                    }
                    val qualityText = when {
                        state.callQualityBadge.isNotBlank() && state.callQualityBadge != "Connecting..." -> state.callQualityBadge
                        state.networkQualityBars >= 4 -> "HD Audio"
                        state.networkQualityBars == 3 -> "Good"
                        else -> "Weak Network"
                    }
                    Surface(
                        color = qualityColor.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, qualityColor.copy(alpha = 0.45f))
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
                                fontFamily = com.example.ui.theme.JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = qualityColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        color = GreenCall.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = if (state.selectedAudioDevice == com.example.webrtc.AudioDeviceType.SPEAKERPHONE) "Voice Focus (SPK)" else "Voice Focus (HD)",
                            fontFamily = com.example.ui.theme.JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            color = GreenCall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
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
                            text = "Weak network — audio may be affected",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFFCA5A5),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
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
                                color = Color(0xFFFBBF24),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
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
                                fontFamily = com.example.ui.theme.JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = Color(0xFFEF4444),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
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

            // Bottom In-Call Controls Docked Card (Always dark obsidian frosted glass so controls are 100% visible)
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color(0xE60F172A),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                shadowElevation = 16.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(16.dp)
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

                    Spacer(modifier = Modifier.height(14.dp))

                    // Row 2: Secondary Call Tools + End Call
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
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

                        // End Call Button
                        Button(
                            onClick = { if (!endCallClicked) { endCallClicked = true; onEndCall() } },
                            shape = CircleShape,
                            modifier = Modifier.size(68.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = RedEndCall)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CallEnd,
                                contentDescription = "End Call",
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        // Hold Call Toggle
                        InCallControlButton(
                            icon = if (state.isOnHold) Icons.Default.PlayArrow else Icons.Default.Pause,
                            label = if (state.isOnHold) "Resume" else "Hold",
                            isActive = state.isOnHold,
                            onClick = { webRtcEngine.toggleHold() }
                        )
                    }
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
    onAddPersonToGroup: (() -> Unit)? = null,
    onEndCall: () -> Unit
) {
    val filterContext = androidx.compose.ui.platform.LocalContext.current
    val videoFilterManager = remember { VideoFilterManager.getInstance(filterContext) }
    val localFilterConfig by videoFilterManager.filterConfig.collectAsState()
    var showFilterStudioSheet by remember { mutableStateOf(false) }

    var endCallClicked by remember { mutableStateOf(false) }
    var areControlsVisible by remember { mutableStateOf(true) }
    var isControlsPinned by remember { mutableStateOf(false) }
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var isSwapped by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showAudioDialog by remember { mutableStateOf(false) }
    var isRemoteFirstFrameRendered by remember(state.remoteVideoTrack) { mutableStateOf(false) }

    // Auto-hide controls after 5.5s of inactivity unless pinned by the user
    LaunchedEffect(areControlsVisible, isControlsPinned, lastInteractionTime) {
        if (areControlsVisible && !isControlsPinned) {
            kotlinx.coroutines.delay(5500L)
            areControlsVisible = false
        }
    }

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
                    lastInteractionTime = System.currentTimeMillis()
                }
            }
    ) {
        // Fullscreen Video Surface or Dedicated On-Hold Screen (Z-INDEX 0f)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(0f)
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
                // Fullscreen Video Renderer with Real-Time Optical Video Filter & Studio Lighting
                if (fullscreenTrack != null) {
                    FilteredVideoViewport(
                        videoTrack = fullscreenTrack,
                        eglBaseContext = webRtcEngine.eglBaseContext,
                        filterConfig = localFilterConfig,
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
                .zIndex(10f)
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
                    FilteredVideoViewport(
                        videoTrack = pipTrack,
                        eglBaseContext = webRtcEngine.eglBaseContext,
                        filterConfig = localFilterConfig,
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
                .zIndex(30f)
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
                        Surface(
                            color = Color.Black.copy(alpha = 0.50f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = webRtcEngine.formatDuration(state.callDurationSeconds),
                                    fontFamily = com.example.ui.theme.JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = com.example.ui.theme.CyberMint,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (state.networkQualityBars in 1..4) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    val qualityColor = when {
                                        state.networkQualityBars >= 4 -> com.example.ui.theme.CyberMint
                                        state.networkQualityBars == 3 -> com.example.ui.theme.SignalAmber
                                        else -> com.example.ui.theme.CrimsonAlert
                                    }
                                    val qualityText = when {
                                        state.callQualityBadge.isNotBlank() && state.callQualityBadge != "Connecting..." -> state.callQualityBadge
                                        state.networkQualityBars >= 4 -> "HD"
                                        state.networkQualityBars == 3 -> "Good"
                                        else -> "Weak"
                                    }
                                    Surface(
                                        color = qualityColor.copy(alpha = 0.22f),
                                        shape = RoundedCornerShape(6.dp),
                                        border = BorderStroke(1.dp, qualityColor.copy(alpha = 0.5f))
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
                                                fontFamily = com.example.ui.theme.JetBrainsMonoFontFamily,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 10.sp,
                                                color = qualityColor,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                                if (isControlsPinned) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        color = SignalAmber.copy(alpha = 0.28f),
                                        shape = RoundedCornerShape(6.dp),
                                        border = BorderStroke(1.dp, SignalAmber.copy(alpha = 0.6f))
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.PushPin,
                                                contentDescription = null,
                                                tint = SignalAmber,
                                                modifier = Modifier.size(10.dp)
                                            )
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text(
                                                text = "PINNED",
                                                fontFamily = com.example.ui.theme.JetBrainsMonoFontFamily,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 9.sp,
                                                color = SignalAmber
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Right: Video Filter Studio, Add Person (+), Controls Pin, Switch to Audio & More Options Menu
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Video Filter Studio Button
                        IconButton(
                            onClick = {
                                lastInteractionTime = System.currentTimeMillis()
                                showFilterStudioSheet = true
                            },
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(if (!localFilterConfig.isDefault) CyberMint.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.55f))
                                .border(1.dp, if (!localFilterConfig.isDefault) CyberMint else Color.White.copy(alpha = 0.20f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = "Video Filters & Studio Lighting",
                                tint = if (!localFilterConfig.isDefault) CyberMint else Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        if (onAddPersonToGroup != null) {
                            IconButton(
                                onClick = {
                                    lastInteractionTime = System.currentTimeMillis()
                                    onAddPersonToGroup()
                                },
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.55f))
                                    .border(1.dp, CyberMint.copy(alpha = 0.5f), CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PersonAdd,
                                    contentDescription = "Add Person to Group Call",
                                    tint = CyberMint,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // Pin / Unpin Controls Toggle Button
                        IconButton(
                            onClick = {
                                isControlsPinned = !isControlsPinned
                                lastInteractionTime = System.currentTimeMillis()
                                val msg = if (isControlsPinned) "Controls pinned (always visible)" else "Controls unpinned (auto-hides in 5s)"
                                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(if (isControlsPinned) SignalAmber.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.55f))
                                .border(1.dp, if (isControlsPinned) SignalAmber else Color.White.copy(alpha = 0.20f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PushPin,
                                contentDescription = if (isControlsPinned) "Unpin Controls" else "Pin Controls",
                                tint = if (isControlsPinned) SignalAmber else Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                lastInteractionTime = System.currentTimeMillis()
                                webRtcEngine.revertToAudioCall("Switched to Audio Call")
                            },
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.55f))
                                .border(1.dp, Color.White.copy(alpha = 0.20f), CircleShape)
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
                                onClick = {
                                    lastInteractionTime = System.currentTimeMillis()
                                    showMoreMenu = true
                                },
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.55f))
                                    .border(1.dp, Color.White.copy(alpha = 0.20f), CircleShape)
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
                                    text = { Text("Video Filters & Studio Lighting", color = Color.White) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.AutoAwesome,
                                            contentDescription = null,
                                            tint = CyberMint
                                        )
                                    },
                                    onClick = {
                                        showMoreMenu = false
                                        showFilterStudioSheet = true
                                    }
                                )
                                if (onAddPersonToGroup != null) {
                                    DropdownMenuItem(
                                        text = { Text("Add Person (Group Conference)", color = Color.White) },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Groups,
                                                contentDescription = null,
                                                tint = CyberMint
                                            )
                                        },
                                        onClick = {
                                            showMoreMenu = false
                                            onAddPersonToGroup()
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(if (isControlsPinned) "Unpin Controls (Auto-Hide)" else "Pin Controls (Always Visible)", color = Color.White) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.PushPin,
                                            contentDescription = null,
                                            tint = if (isControlsPinned) SignalAmber else Color.White
                                        )
                                    },
                                    onClick = {
                                        showMoreMenu = false
                                        isControlsPinned = !isControlsPinned
                                        lastInteractionTime = System.currentTimeMillis()
                                    }
                                )
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
                .zIndex(20f)
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
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Recording Indicator Banner
        androidx.compose.animation.AnimatedVisibility(
            visible = isVideoRec && !isPipMode,
            modifier = Modifier
                .zIndex(20f)
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
                        fontFamily = com.example.ui.theme.JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Z-Index Aware Bottom Controls Dock in Translucent Glass-morphism Container
        AnimatedVisibility(
            visible = areControlsVisible && !isPipMode,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier
                .zIndex(30f)
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .displayCutoutPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 20.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color(0xD90F172A),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.20f)),
                    shadowElevation = 16.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 520.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. Flip Camera
                        Surface(
                            onClick = {
                                lastInteractionTime = System.currentTimeMillis()
                                webRtcEngine.switchCamera()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = Color(0x33FFFFFF),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f))
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Cameraswitch,
                                    contentDescription = "Flip Camera",
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        // 2. Camera On / Off
                        val camActiveBorder = if (state.isCameraOn) com.example.ui.theme.CyberMint else com.example.ui.theme.CrimsonAlert
                        val camBg = if (state.isCameraOn) com.example.ui.theme.CyberMint.copy(alpha = 0.25f) else com.example.ui.theme.CrimsonAlert.copy(alpha = 0.30f)
                        val camTint = if (state.isCameraOn) com.example.ui.theme.CyberMint else Color.White
                        Surface(
                            onClick = {
                                lastInteractionTime = System.currentTimeMillis()
                                webRtcEngine.toggleCamera()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = camBg,
                            border = BorderStroke(1.dp, camActiveBorder)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    if (state.isCameraOn) Icons.Default.Videocam else Icons.Default.VideocamOff,
                                    contentDescription = "Camera Toggle",
                                    tint = camTint,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        // 3. Mic Mute / Unmute
                        val micBorder = if (state.isMuted) com.example.ui.theme.CrimsonAlert else Color.White.copy(alpha = 0.25f)
                        val micBg = if (state.isMuted) com.example.ui.theme.CrimsonAlert.copy(alpha = 0.30f) else Color(0x33FFFFFF)
                        val micTint = if (state.isMuted) com.example.ui.theme.CrimsonAlert else Color.White
                        Surface(
                            onClick = {
                                lastInteractionTime = System.currentTimeMillis()
                                webRtcEngine.toggleMute()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = micBg,
                            border = BorderStroke(1.dp, micBorder)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    if (state.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                    contentDescription = "Mute Toggle",
                                    tint = micTint,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        // 4. Audio Output Switcher
                        val audioBorder = if (isAudioHighlighted) SwiggyOrange else Color.White.copy(alpha = 0.25f)
                        val audioBg = if (isAudioHighlighted) SwiggyOrange.copy(alpha = 0.30f) else Color(0x33FFFFFF)
                        val audioTint = if (isAudioHighlighted) SwiggyOrange else Color.White
                        Surface(
                            onClick = {
                                lastInteractionTime = System.currentTimeMillis()
                                webRtcEngine.refreshAvailableAudioDevices()
                                showAudioDialog = true
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = audioBg,
                            border = BorderStroke(1.dp, audioBorder)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    audioIcon,
                                    contentDescription = audioLabel,
                                    tint = audioTint,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        // 4.5. Video Filter Studio Button in Dock
                        Surface(
                            onClick = {
                                lastInteractionTime = System.currentTimeMillis()
                                showFilterStudioSheet = true
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = if (!localFilterConfig.isDefault) CyberMint.copy(alpha = 0.28f) else Color(0x33FFFFFF),
                            border = BorderStroke(
                                1.dp,
                                if (!localFilterConfig.isDefault) CyberMint else Color.White.copy(alpha = 0.25f)
                            )
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.AutoAwesome,
                                    contentDescription = "Video Filters",
                                    tint = if (!localFilterConfig.isDefault) CyberMint else Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        // 5. End Call Button
                        Button(
                            onClick = {
                                lastInteractionTime = System.currentTimeMillis()
                                if (!endCallClicked) {
                                    endCallClicked = true
                                    onEndCall()
                                }
                            },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1.15f)
                                .height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = RedEndCall),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Icon(
                                Icons.Default.CallEnd,
                                contentDescription = "End Call",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }

        // Video Filter & Studio Lighting Bottom Drawer inside 1-to-1 Video Call
        if (showFilterStudioSheet) {
            Box(
                modifier = Modifier
                    .zIndex(50f)
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable { showFilterStudioSheet = false },
                contentAlignment = Alignment.BottomCenter
            ) {
                Box(modifier = Modifier.clickable(enabled = false) {}) {
                    VideoFilterStudioSheet(
                        filterConfig = localFilterConfig,
                        onSelectPreset = { videoFilterManager.selectPreset(it) },
                        onUpdateConfig = { videoFilterManager.updateConfig(it) },
                        onReset = { videoFilterManager.resetToDefault() },
                        onDismiss = { showFilterStudioSheet = false }
                    )
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
                                    color = if (isSelected) SwiggyOrange else Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
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
                .background(if (isActive) activeColor.copy(alpha = 0.28f) else Color(0xFF1E293B))
                .border(
                    width = if (isActive) 1.5.dp else 1.dp,
                    color = if (isActive) activeColor else Color.White.copy(alpha = 0.28f),
                    shape = CircleShape
                )
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isActive) activeColor else Color.White,
                modifier = Modifier.size((size.value * 0.44f).dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            fontFamily = com.example.ui.theme.SpaceGroteskFontFamily,
            fontWeight = FontWeight.Bold,
            color = if (isActive) activeColor else Color(0xFFF1F5F9),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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
                try {
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
                    setTag(videoTrack)
                    videoTrack.addSink(this)
                } catch (_: Throwable) {
                    // Guard against race if track or EGL context is already tearing down
                }
            }
        },
        update = { view ->
            try {
                view.setMirror(mirror)
                val oldTrack = view.getTag() as? org.webrtc.VideoTrack
                if (oldTrack != videoTrack) {
                    if (oldTrack != null) {
                        try {
                            oldTrack.removeSink(view)
                        } catch (_: Throwable) {}
                    }
                    view.setTag(videoTrack)
                    try {
                        videoTrack.addSink(view)
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        },
        onRelease = { view ->
            val oldTrack = view.getTag() as? org.webrtc.VideoTrack
            view.setTag(null)
            if (oldTrack != null) {
                try {
                    oldTrack.removeSink(view)
                } catch (_: Throwable) {
                    // Native VideoTrack may already be disposed during rapid hangup
                }
            }
            try {
                view.clearImage()
            } catch (_: Throwable) {}
            try {
                view.release()
            } catch (_: Throwable) {}
        },
        modifier = modifier
    )
}
