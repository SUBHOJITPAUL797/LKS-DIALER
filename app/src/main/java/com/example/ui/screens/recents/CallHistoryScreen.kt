package com.example.ui.screens.recents

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CallDirection
import com.example.data.model.CallLogDto
import com.example.data.model.CallType
import com.example.data.model.UserDto
import com.example.data.repository.FirebaseManager
import com.example.ui.theme.*
import com.example.util.ContactsHelper
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
    val themeColor = LocalThemeColor.current
    val callLogs by firebaseManager.callLogs.collectAsState()
    val registeredUsers by firebaseManager.registeredUsers.collectAsState()
    var selectedFilter by remember { mutableStateOf("ALL") }
    var selectedLogForDetail by remember { mutableStateOf<CallLogDto?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // 5-Second Inactivity Swipe Demo State
    var lastInteractionTime by remember { mutableStateOf(System.currentTimeMillis()) }
    var showSwipeHint by remember { mutableStateOf(false) }
    val demoSwipeOffset = remember { Animatable(0f) }

    // Track 5 seconds of inactivity
    LaunchedEffect(lastInteractionTime, callLogs.size) {
        if (callLogs.isNotEmpty()) {
            delay(5000)
            if (System.currentTimeMillis() - lastInteractionTime >= 4900) {
                showSwipeHint = true
                // Run smooth demonstration swipe sequence
                demoSwipeOffset.animateTo(75f, animationSpec = tween(700, easing = FastOutSlowInEasing))
                delay(1000)
                demoSwipeOffset.animateTo(0f, animationSpec = tween(400, easing = FastOutSlowInEasing))
                delay(300)
                demoSwipeOffset.animateTo(-75f, animationSpec = tween(700, easing = FastOutSlowInEasing))
                delay(1000)
                demoSwipeOffset.animateTo(0f, animationSpec = tween(400, easing = FastOutSlowInEasing))
            }
        }
    }

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
        val matchedUser = remember(log.otherPartyNumber, registeredUsers) {
            registeredUsers.find { ContactsHelper.numbersMatch(it.phoneNumber, log.otherPartyNumber) }
        }

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
                CallAvatar(
                    name = log.otherPartyName,
                    profilePicBase64 = matchedUser?.profilePictureUrl ?: "",
                    size = 76.dp,
                    fontSize = 28.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = matchedUser?.displayName ?: log.otherPartyName,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = log.otherPartyNumber,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (matchedUser != null && matchedUser.statusMessage.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = matchedUser.statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = themeColor.primary
                    )
                }

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
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
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
                        colors = ButtonDefaults.buttonColors(containerColor = themeColor.primary),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
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
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    lastInteractionTime = System.currentTimeMillis()
                    showSwipeHint = false
                    coroutineScope.launch { demoSwipeOffset.snapTo(0f) }
                }
            }
    ) {
        TopAppBar(
            title = {
                Text(
                    text = "Recents",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = themeColor.primary
                )
            },
            actions = {
                if (callLogs.isNotEmpty()) {
                    IconButton(onClick = { showClearDialog = true }) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "Clear History",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        )

        // Filter chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.Start
        ) {
            FilterChip(
                selected = selectedFilter == "ALL",
                onClick = {
                    selectedFilter = "ALL"
                    lastInteractionTime = System.currentTimeMillis()
                },
                label = { Text("All Calls (${callLogs.size})") },
                shape = RoundedCornerShape(16.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = themeColor.primary.copy(alpha = 0.2f),
                    selectedLabelColor = themeColor.primary
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            FilterChip(
                selected = selectedFilter == "MISSED",
                onClick = {
                    selectedFilter = "MISSED"
                    lastInteractionTime = System.currentTimeMillis()
                },
                label = { Text("Missed (${callLogs.count { it.direction == CallDirection.MISSED }})") },
                shape = RoundedCornerShape(16.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MissedCallRed.copy(alpha = 0.15f),
                    selectedLabelColor = MissedCallRed
                )
            )
        }

        // Animated Swipe Feature Tip Banner
        AnimatedVisibility(
            visible = showSwipeHint && filteredLogs.isNotEmpty(),
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically()
        ) {
            Surface(
                color = themeColor.primary.copy(alpha = 0.15f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Swipe,
                        contentDescription = null,
                        tint = themeColor.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Tip: Swipe Right to Audio Call • Swipe Left to Video Call",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                        color = themeColor.primary
                    )
                }
            }
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
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (selectedFilter == "MISSED") "No missed calls" else "No recent call history",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                itemsIndexed(filteredLogs, key = { _, log -> log.id }) { index, log ->
                    val matchedUser = remember(log.otherPartyNumber, registeredUsers) {
                        registeredUsers.find { ContactsHelper.numbersMatch(it.phoneNumber, log.otherPartyNumber) }
                    }
                    val isFirstItem = index == 0
                    val currentOffset = if (isFirstItem && showSwipeHint) demoSwipeOffset.value else 0f

                    SwipeableCallLogItem(
                        log = log,
                        profilePicBase64 = matchedUser?.profilePictureUrl ?: "",
                        demoOffset = currentOffset,
                        onItemClick = {
                            lastInteractionTime = System.currentTimeMillis()
                            selectedLogForDetail = log
                        },
                        onAudioCall = {
                            lastInteractionTime = System.currentTimeMillis()
                            coroutineScope.launch {
                                delay(200)
                                onStartCall(log.otherPartyNumber, matchedUser?.displayName ?: log.otherPartyName, CallType.AUDIO)
                            }
                        },
                        onVideoCall = {
                            lastInteractionTime = System.currentTimeMillis()
                            coroutineScope.launch {
                                delay(200)
                                onStartCall(log.otherPartyNumber, matchedUser?.displayName ?: log.otherPartyName, CallType.VIDEO)
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
    profilePicBase64: String,
    demoOffset: Float,
    onItemClick: () -> Unit,
    onAudioCall: () -> Unit,
    onVideoCall: () -> Unit
) {
    val themeColor = LocalThemeColor.current
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { dismissValue ->
            when (dismissValue) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    onAudioCall()
                    false
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    onVideoCall()
                    false
                }
                else -> false
            }
        },
        positionalThreshold = { it * 0.4f }
    )

    // Handle demo animation visual background
    val isDemoActive = demoOffset != 0f
    val demoDirection = when {
        demoOffset > 0f -> SwipeToDismissBoxValue.StartToEnd
        demoOffset < 0f -> SwipeToDismissBoxValue.EndToStart
        else -> SwipeToDismissBoxValue.Settled
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        if (isDemoActive) {
            // Background for demo swipe
            val demoBgColor = if (demoOffset > 0f) GreenCall.copy(alpha = 0.85f) else themeColor.primary.copy(alpha = 0.85f)
            val demoAlign = if (demoOffset > 0f) Alignment.CenterStart else Alignment.CenterEnd
            val demoIcon = if (demoOffset > 0f) Icons.Default.Call else Icons.Default.Videocam

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(demoBgColor)
                    .padding(horizontal = 24.dp),
                contentAlignment = demoAlign
            ) {
                Icon(
                    imageVector = demoIcon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            // Foreground with demo offset
            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(x = demoOffset.dp)
            ) {
                CallLogItemContent(
                    log = log,
                    profilePicBase64 = profilePicBase64,
                    onItemClick = onItemClick
                )
            }
        } else {
            SwipeToDismissBox(
                state = dismissState,
                backgroundContent = {
                    val direction = dismissState.dismissDirection
                    val color by animateColorAsState(
                        when (dismissState.targetValue) {
                            SwipeToDismissBoxValue.StartToEnd -> GreenCall.copy(alpha = 0.85f)
                            SwipeToDismissBoxValue.EndToStart -> themeColor.primary.copy(alpha = 0.85f)
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
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CallLogItemContent(
                        log = log,
                        profilePicBase64 = profilePicBase64,
                        onItemClick = onItemClick
                    )
                }
            }
        }
    }
}

@Composable
private fun CallLogItemContent(
    log: CallLogDto,
    profilePicBase64: String,
    onItemClick: () -> Unit
) {
    val themeColor = LocalThemeColor.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onItemClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // User Profile Picture Avatar
        CallAvatar(
            name = log.otherPartyName,
            profilePicBase64 = profilePicBase64,
            size = 48.dp,
            fontSize = 18.sp,
            isMissed = log.direction == CallDirection.MISSED
        )

        Spacer(modifier = Modifier.width(14.dp))

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
                    CallDirection.OUTGOING -> Icons.AutoMirrored.Filled.CallMade
                    CallDirection.INCOMING -> Icons.AutoMirrored.Filled.CallReceived
                    CallDirection.MISSED -> Icons.AutoMirrored.Filled.CallMissed
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Details Action Button
        IconButton(onClick = onItemClick) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = "Details",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun CallAvatar(
    name: String,
    profilePicBase64: String,
    size: androidx.compose.ui.unit.Dp,
    fontSize: androidx.compose.ui.unit.TextUnit,
    isMissed: Boolean = false
) {
    val themeColor = LocalThemeColor.current
    val bitmap = remember(profilePicBase64) {
        if (profilePicBase64.isNotBlank()) {
            try {
                val decoded = Base64.decode(profilePicBase64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(decoded, 0, decoded.size)?.asImageBitmap()
            } catch (_: Exception) {
                null
            }
        } else null
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = name,
            modifier = Modifier
                .size(size)
                .clip(CircleShape),
            contentScale = ContentScale.Crop
        )
    } else {
        Surface(
            modifier = Modifier.size(size),
            shape = CircleShape,
            color = if (isMissed) MissedCallRed.copy(alpha = 0.15f) else themeColor.primary.copy(alpha = 0.15f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = name.take(1).uppercase().ifBlank { "?" },
                    fontSize = fontSize,
                    fontWeight = FontWeight.Bold,
                    color = if (isMissed) MissedCallRed else themeColor.primary
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
    return if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
}
