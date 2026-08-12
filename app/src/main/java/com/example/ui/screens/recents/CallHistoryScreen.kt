package com.example.ui.screens.recents

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CallDirection
import com.example.data.model.CallLogDto
import com.example.data.model.CallType
import com.example.data.repository.FirebaseManager
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallHistoryScreen(
    firebaseManager: FirebaseManager,
    onStartCall: (number: String, name: String, callType: CallType) -> Unit
) {
    val callLogs by firebaseManager.callLogs.collectAsState()
    var selectedFilter by remember { mutableStateOf("ALL") } // "ALL" or "MISSED"
    var selectedLogForDetail by remember { mutableStateOf<CallLogDto?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val filteredLogs = remember(callLogs, selectedFilter) {
        if (selectedFilter == "MISSED") {
            callLogs.filter { it.direction == CallDirection.MISSED }
        } else callLogs
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear Call History?") },
            text = { Text("This will remove all recent call logs from your device.") },
            confirmButton = {
                TextButton(onClick = {
                    firebaseManager.clearCallLogs()
                    showClearDialog = false
                }) {
                    Text("Clear All", color = RedEndCall, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    selectedLogForDetail?.let { log ->
        ModalBottomSheet(
            onDismissRequest = { selectedLogForDetail = null }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    modifier = Modifier.size(72.dp),
                    shape = CircleShape,
                    color = TealPrimary
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = log.otherPartyName.take(1).uppercase(),
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = log.otherPartyName,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = log.otherPartyNumber,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(
                        onClick = {
                            selectedLogForDetail = null
                            onStartCall(log.otherPartyNumber, log.otherPartyName, CallType.AUDIO)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = GreenCall),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Call, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Audio Call")
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Button(
                        onClick = {
                            selectedLogForDetail = null
                            onStartCall(log.otherPartyNumber, log.otherPartyName, CallType.VIDEO)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = TealPrimary),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Videocam, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Video Call")
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            title = {
                Text(
                    text = "Recents",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
            },
            actions = {
                if (callLogs.isNotEmpty()) {
                    IconButton(onClick = { showClearDialog = true }) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Clear Logs", tint = RedEndCall)
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )

        // Filter chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Start
        ) {
            FilterChip(
                selected = selectedFilter == "ALL",
                onClick = { selectedFilter = "ALL" },
                label = { Text("All Calls (${callLogs.size})") }
            )
            Spacer(modifier = Modifier.width(8.dp))
            FilterChip(
                selected = selectedFilter == "MISSED",
                onClick = { selectedFilter = "MISSED" },
                label = { Text("Missed (${callLogs.count { it.direction == CallDirection.MISSED }})") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MissedCallRed.copy(alpha = 0.1f),
                    selectedLabelColor = MissedCallRed
                )
            )
        }

        if (filteredLogs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.History,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = Color.LightGray
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (selectedFilter == "MISSED") "No missed calls" else "No recent call history",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.Gray
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                items(filteredLogs, key = { it.id }) { log ->
                    SwipeableCallLogItem(
                        log = log,
                        onItemClick = { selectedLogForDetail = log },
                        onAudioCall = {
                            coroutineScope.launch {
                                delay(300)
                                onStartCall(log.otherPartyNumber, log.otherPartyName, CallType.AUDIO)
                            }
                        },
                        onVideoCall = {
                            coroutineScope.launch {
                                delay(300)
                                onStartCall(log.otherPartyNumber, log.otherPartyName, CallType.VIDEO)
                            }
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableCallLogItem(
    log: CallLogDto,
    onItemClick: () -> Unit,
    onAudioCall: () -> Unit,
    onVideoCall: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { dismissValue ->
            when (dismissValue) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    onAudioCall()
                    false // Return to center
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    onVideoCall()
                    false // Return to center
                }
                else -> false
            }
        },
        positionalThreshold = { it * 0.4f }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val direction = dismissState.dismissDirection
            val color by animateColorAsState(
                when (dismissState.targetValue) {
                    SwipeToDismissBoxValue.StartToEnd -> GreenCall.copy(alpha = 0.8f) // Right swipe = Audio
                    SwipeToDismissBoxValue.EndToStart -> TealPrimary.copy(alpha = 0.8f) // Left swipe = Video
                    SwipeToDismissBoxValue.Settled -> Color.Transparent
                }, label = "color"
            )

            val icon = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> Icons.Default.Call
                SwipeToDismissBoxValue.EndToStart -> Icons.Default.Videocam
                else -> Icons.Default.Call
            }

            val scale by animateFloatAsState(
                if (dismissState.targetValue == SwipeToDismissBoxValue.Settled) 0.75f else 1.2f,
                label = "scale"
            )

            val alignment = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                else -> Alignment.CenterStart
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .background(color)
                    .padding(horizontal = 24.dp),
                contentAlignment = alignment
            ) {
                if (direction != SwipeToDismissBoxValue.Settled) {
                    Icon(
                        imageVector = icon,
                        contentDescription = "Call Action",
                        modifier = Modifier.scale(scale),
                        tint = Color.White
                    )
                }
            }
        }
    ) {
        CallLogItemContent(log = log, onItemClick = onItemClick)
    }
}

@Composable
private fun CallLogItemContent(
    log: CallLogDto,
    onItemClick: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onItemClick() }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            Surface(
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                color = if (log.direction == CallDirection.MISSED) MissedCallRed.copy(alpha = 0.15f) else TealPrimary.copy(alpha = 0.15f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = log.otherPartyName.take(1).uppercase(),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (log.direction == CallDirection.MISSED) MissedCallRed else TealPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = log.otherPartyName,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = if (log.direction == CallDirection.MISSED) FontWeight.Bold else FontWeight.SemiBold
                    ),
                    color = if (log.direction == CallDirection.MISSED) MissedCallRed else MaterialTheme.colorScheme.onBackground
                )

                Spacer(modifier = Modifier.height(2.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    val icon = when (log.direction) {
                        CallDirection.OUTGOING -> Icons.Default.CallMade
                        CallDirection.INCOMING -> Icons.Default.CallReceived
                        CallDirection.MISSED -> Icons.Default.CallMissed
                    }
                    val tint = when (log.direction) {
                        CallDirection.OUTGOING -> OutgoingCallBlue
                        CallDirection.INCOMING -> IncomingCallGreen
                        CallDirection.MISSED -> MissedCallRed
                    }

                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(16.dp)
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    Text(
                        text = "${formatTime(log.startedAt)} • ${if (log.durationSeconds > 0) formatDuration(log.durationSeconds) else "Missed"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
            }

            // Quick Redial Action Button (Non-swipe alternative)
            IconButton(onClick = { onItemClick() }) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Details",
                    tint = Color.LightGray
                )
            }
        }
    }
}

private fun formatTime(millis: Long): String {
    val formatter = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault())
    return formatter.format(Date(millis))
}

private fun formatDuration(seconds: Int): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return String.format("%02d:%02d", mins, secs)
}
