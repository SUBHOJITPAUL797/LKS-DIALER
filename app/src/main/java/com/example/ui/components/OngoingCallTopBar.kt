package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CallDto
import com.example.data.model.CallType
import com.example.ui.theme.GreenCall
import com.example.ui.theme.RedEndCall
import com.example.webrtc.WebRtcEngine
import com.example.webrtc.WebRtcState

/**
 * OngoingCallTopBar
 * WhatsApp-style persistent in-app ongoing call banner displayed at the top of the app
 * when an audio or video call is active and minimized, allowing the user to multitask.
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
    val label = displayName.ifBlank { displayNumber }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding(),
        color = Color(0xFF0F172A), // Deep dark surface matching WhatsApp dark style
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Mute / Unmute Button
            Surface(
                onClick = { webRtcEngine.toggleMute() },
                shape = CircleShape,
                color = if (rtcState.isMuted) Color(0xFFEF4444).copy(alpha = 0.25f) else Color.White.copy(alpha = 0.12f),
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (rtcState.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = if (rtcState.isMuted) "Unmute Microphone" else "Mute Microphone",
                        tint = if (rtcState.isMuted) Color(0xFFEF4444) else Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Center: Interactive Call Info & Timer (Clicking returns to full-screen call)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable(onClick = onExpand)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = if (isVideo) Icons.Default.Videocam else Icons.Default.Call,
                    contentDescription = null,
                    tint = GreenCall,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "$label - $durationText",
                    color = GreenCall,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Right: Red End Call Button
            Surface(
                onClick = onEndCall,
                shape = CircleShape,
                color = RedEndCall,
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.CallEnd,
                        contentDescription = "End Call",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}
