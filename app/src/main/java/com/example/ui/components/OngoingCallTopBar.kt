package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CallDto
import com.example.data.model.CallType
import com.example.ui.theme.CyberMint
import com.example.ui.theme.GreenCall
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalSurfaceStyle
import com.example.ui.theme.RedEndCall
import com.example.ui.theme.SpaceGroteskFontFamily
import com.example.webrtc.WebRtcEngine
import com.example.webrtc.WebRtcState
import kotlin.math.roundToInt

/**
 * OngoingCallTopBar
 * Floating in-app ongoing call pop-up bubble displayed at the top of the screen
 * when an audio or video call is active and minimized.
 * Supports smooth Drag-Up-to-Hide gesture and quick hide button so it never blocks the UI.
 */
@Composable
fun OngoingCallTopBar(
    activeCall: CallDto,
    rtcState: WebRtcState,
    webRtcEngine: WebRtcEngine,
    displayName: String,
    displayNumber: String,
    onExpand: () -> Unit,
    onEndCall: () -> Unit,
    modifier: Modifier = Modifier
) {
    val durationText = webRtcEngine.formatDuration(rtcState.callDurationSeconds)
    val isVideo = activeCall.callType == CallType.VIDEO || rtcState.callType == CallType.VIDEO
    val label = displayName.takeIf { it.isNotBlank() && !it.equals("Me", ignoreCase = true) }
        ?: displayNumber.ifBlank { "Active Call" }
    val surfaceStyle = LocalSurfaceStyle.current

    var isHiddenByDragUp by remember(activeCall.callId) { mutableStateOf(false) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val animatedDragY by animateFloatAsState(
        targetValue = dragOffsetY,
        animationSpec = spring(),
        label = "top_bubble_drag_y"
    )

    AnimatedContent(
        targetState = isHiddenByDragUp,
        transitionSpec = {
            (slideInVertically { -it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut())
        },
        label = "ongoing_call_bubble_visibility"
    ) { hidden ->
        if (hidden) {
            // Compact unobtrusive restore tab tucked at the top edge when dragged up to hide
            Box(
                modifier = modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(top = 2.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Surface(
                    onClick = {
                        isHiddenByDragUp = false
                        dragOffsetY = 0f
                    },
                    shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
                    color = Color(0xFF0F172A).copy(alpha = 0.90f),
                    border = BorderStroke(1.dp, CyberMint.copy(alpha = 0.55f)),
                    shadowElevation = 6.dp,
                    modifier = Modifier.pointerInput(Unit) {
                        detectVerticalDragGestures { change, dragAmount ->
                            change.consume()
                            if (dragAmount > 12f) {
                                isHiddenByDragUp = false
                                dragOffsetY = 0f
                            }
                        }
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(GreenCall)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "$label • $durationText",
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Show Call Pop-up",
                            tint = CyberMint,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        } else {
            val dragAlpha = (1f - (-animatedDragY / 130f)).coerceIn(0.25f, 1f)
            Box(
                modifier = modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .offset { IntOffset(0, animatedDragY.roundToInt().coerceAtMost(0)) }
                    .alpha(dragAlpha)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                if (dragOffsetY < -28f) {
                                    isHiddenByDragUp = true
                                }
                                dragOffsetY = 0f
                            },
                            onDragCancel = {
                                dragOffsetY = 0f
                            },
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                dragOffsetY = (dragOffsetY + dragAmount).coerceIn(-160f, 12f)
                            }
                        )
                    }
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .surfaceFinishOverlay(surfaceStyle, 24.dp, CyberMint),
                    shape = RoundedCornerShape(24.dp),
                    color = Color(0xD90F172A),
                    border = BorderStroke(1.2.dp, CyberMint.copy(alpha = 0.55f)),
                    shadowElevation = 10.dp
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Left: Mute / Unmute Button
                            Surface(
                                onClick = { webRtcEngine.toggleMute() },
                                shape = CircleShape,
                                color = if (rtcState.isMuted) Color(0xFFEF4444).copy(alpha = 0.28f) else Color.White.copy(alpha = 0.14f),
                                border = BorderStroke(
                                    1.dp,
                                    if (rtcState.isMuted) Color(0xFFEF4444) else Color.White.copy(alpha = 0.25f)
                                ),
                                modifier = Modifier.size(38.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (rtcState.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                        contentDescription = if (rtcState.isMuted) "Unmute Microphone" else "Mute Microphone",
                                        tint = if (rtcState.isMuted) Color(0xFFEF4444) else Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            // Center: Interactive Call Info & Timer (Clicking returns to full-screen call)
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable(onClick = onExpand)
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = if (isVideo) Icons.Default.Videocam else Icons.Default.Call,
                                        contentDescription = null,
                                        tint = GreenCall,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = label,
                                        fontFamily = SpaceGroteskFontFamily,
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = durationText,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        color = GreenCall,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                }
                                Text(
                                    text = "Swipe ↑ to hide • Tap to expand",
                                    fontFamily = SpaceGroteskFontFamily,
                                    fontSize = 10.sp,
                                    color = Color.White.copy(alpha = 0.65f),
                                    maxLines = 1
                                )
                            }

                            // Hide Pop-up Button
                            IconButton(
                                onClick = { isHiddenByDragUp = true },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowUp,
                                    contentDescription = "Hide Call Pop-up",
                                    tint = Color.White.copy(alpha = 0.85f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(4.dp))

                            // Right: Red End Call Button
                            Surface(
                                onClick = onEndCall,
                                shape = CircleShape,
                                color = RedEndCall,
                                modifier = Modifier.size(38.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.CallEnd,
                                        contentDescription = "End Call",
                                        tint = Color.White,
                                        modifier = Modifier.size(19.dp)
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
