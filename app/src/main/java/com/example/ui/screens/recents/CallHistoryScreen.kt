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
import com.example.util.ContactsHelper
import com.example.ui.theme.*
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import android.media.MediaPlayer
import androidx.core.content.FileProvider
import android.content.Intent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallHistoryScreen(
    firebaseManager: FirebaseManager,
    onStartCall: (number: String, name: String, callType: CallType) -> Unit,
    onOpenChat: ((number: String, name: String) -> Unit)? = null
) {
    val context = LocalContext.current
    val themeColor = LocalThemeColor.current
    val callLogs by firebaseManager.callLogs.collectAsState()
    val registeredUsers by firebaseManager.registeredUsers.collectAsState()
    val blockedNumbers by firebaseManager.blockedNumbers.collectAsState()
    val spamNumbers by firebaseManager.spamNumbers.collectAsState()
    var selectedFilter by remember { mutableStateOf("ALL") }
    var selectedLogForDetail by remember { mutableStateOf<CallLogDto?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }
    var showBlockConfirmDialog by remember { mutableStateOf(false) }
    var showSpamConfirmDialog by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // 5-Second Inactivity Swipe Demo State
    var lastInteractionTime by remember { mutableStateOf(System.currentTimeMillis()) }
    var lastCallClickTime by remember { mutableStateOf(0L) }
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
        when (selectedFilter) {
            "MISSED" -> callLogs.filter { it.direction == CallDirection.MISSED }
            "RECORDINGS" -> callLogs.filter {
                com.example.util.CallAudioRecorder.getInstance(context).findRecordingFile(it.callId, it.recordingPath) != null
            }
            else -> callLogs
        }
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
        val isBlocked = remember(log.otherPartyNumber, blockedNumbers, spamNumbers) {
            firebaseManager.isNumberBlocked(log.otherPartyNumber)
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

                Spacer(modifier = Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = log.otherPartyNumber,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isBlocked) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Block,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Blocked",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }

                if (matchedUser != null && matchedUser.statusMessage.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = matchedUser.statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = themeColor.primary
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(
                        onClick = {
                            selectedLogForDetail = null
                            val now = System.currentTimeMillis()
                            if (now - lastCallClickTime > 1500L) {
                                lastCallClickTime = now
                                onStartCall(log.otherPartyNumber, log.otherPartyName, CallType.AUDIO)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = GreenCall),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.Call, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Audio")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            selectedLogForDetail = null
                            val now = System.currentTimeMillis()
                            if (now - lastCallClickTime > 1500L) {
                                lastCallClickTime = now
                                onStartCall(log.otherPartyNumber, log.otherPartyName, CallType.VIDEO)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = themeColor.primary),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.Videocam, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Video")
                    }
                    if (onOpenChat != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                selectedLogForDetail = null
                                onOpenChat(log.otherPartyNumber, log.otherPartyName)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = TealPrimary),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.ChatBubbleOutline, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Chat")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Call metadata card
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Call Type",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${if (log.callType == CallType.VIDEO) "Video" else "Audio"} • ${log.direction.name.lowercase().replaceFirstChar { it.uppercase() }}",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Time & Duration",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${formatTime(log.startedAt)} (${if (log.durationSeconds > 0) formatDuration(log.durationSeconds) else "Missed"})",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                    }
                }

                val recordingFile = remember(log.callId, log.recordingPath) {
                    com.example.util.CallAudioRecorder.getInstance(context).findRecordingFile(log.callId, log.recordingPath)
                }
                var currentRecording by remember(recordingFile) { mutableStateOf(recordingFile) }

                if (currentRecording != null && currentRecording!!.exists()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    CallRecordingPlayerCard(
                        file = currentRecording!!,
                        callId = log.callId,
                        onDeleted = {
                            currentRecording = null
                        }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Block / Unblock / Spam Action Button
                if (isBlocked) {
                    OutlinedButton(
                        onClick = {
                            firebaseManager.unblockNumber(log.otherPartyNumber)
                            firebaseManager.unmarkSpam(log.otherPartyNumber)
                            Toast.makeText(context, "Unblocked ${matchedUser?.displayName ?: log.otherPartyName}", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = themeColor.primary)
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Unblock Caller", fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                showBlockConfirmDialog = true
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Block", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                        }

                        OutlinedButton(
                            onClick = {
                                showSpamConfirmDialog = true
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF9800))
                        ) {
                            Icon(Icons.Default.Report, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFFFF9800))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Report Spam", color = Color(0xFFFF9800), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }

    if (showBlockConfirmDialog && selectedLogForDetail != null) {
        val targetLog = selectedLogForDetail!!
        val targetUser = registeredUsers.find { ContactsHelper.numbersMatch(it.phoneNumber, targetLog.otherPartyNumber) }
        val targetName = targetUser?.displayName ?: targetLog.otherPartyName
        AlertDialog(
            onDismissRequest = { showBlockConfirmDialog = false },
            icon = {
                Icon(
                    Icons.Default.Block,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = { Text("Block $targetName?") },
            text = {
                Text("Calls from ${targetLog.otherPartyNumber} will be automatically declined. You will not receive notifications for calls from this number.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        firebaseManager.blockNumber(targetLog.otherPartyNumber)
                        Toast.makeText(context, "Blocked $targetName", Toast.LENGTH_SHORT).show()
                        showBlockConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Block", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBlockConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showSpamConfirmDialog && selectedLogForDetail != null) {
        val targetLog = selectedLogForDetail!!
        val targetUser = registeredUsers.find { ContactsHelper.numbersMatch(it.phoneNumber, targetLog.otherPartyNumber) }
        val targetName = targetUser?.displayName ?: targetLog.otherPartyName
        AlertDialog(
            onDismissRequest = { showSpamConfirmDialog = false },
            icon = {
                Icon(
                    Icons.Default.Report,
                    contentDescription = null,
                    tint = Color(0xFFFF9800),
                    modifier = Modifier.size(32.dp)
                )
            },
            title = { Text("Report $targetName as Spam?") },
            text = {
                Text("This number (${targetLog.otherPartyNumber}) will be blocked, future calls will be suppressed, and a spam report will be submitted to protect the community.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        firebaseManager.reportSpam(targetLog.otherPartyNumber, "Reported from Call History")
                        Toast.makeText(context, "Reported $targetName as spam and blocked", Toast.LENGTH_SHORT).show()
                        showSpamConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                ) {
                    Text("Report & Block", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSpamConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
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
            Spacer(modifier = Modifier.width(8.dp))
            val recordedCount = remember(callLogs) {
                callLogs.count {
                    com.example.util.CallAudioRecorder.getInstance(context).findRecordingFile(it.callId, it.recordingPath) != null
                }
            }
            FilterChip(
                selected = selectedFilter == "RECORDINGS",
                onClick = {
                    selectedFilter = "RECORDINGS"
                    lastInteractionTime = System.currentTimeMillis()
                },
                label = { Text("Recordings ($recordedCount)") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                },
                shape = RoundedCornerShape(16.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = GreenCall.copy(alpha = 0.2f),
                    selectedLabelColor = GreenCall
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
                        if (selectedFilter == "RECORDINGS") Icons.Default.Mic else Icons.Default.History,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = when (selectedFilter) {
                            "MISSED" -> "No missed calls"
                            "RECORDINGS" -> "No recorded calls yet\nTap Record during any call to save audio"
                            else -> "No recent call history"
                        },
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
                    val isBlocked = remember(log.otherPartyNumber, blockedNumbers, spamNumbers) {
                        firebaseManager.isNumberBlocked(log.otherPartyNumber)
                    }
                    val isSpam = remember(log.otherPartyNumber, spamNumbers) {
                        firebaseManager.isNumberSpam(log.otherPartyNumber)
                    }
                    val isFirstItem = index == 0
                    val currentOffset = if (isFirstItem && showSwipeHint) demoSwipeOffset.value else 0f

                    SwipeableCallLogItem(
                        log = log,
                        profilePicBase64 = matchedUser?.profilePictureUrl ?: "",
                        isBlocked = isBlocked,
                        isSpam = isSpam,
                        demoOffset = currentOffset,
                        onItemClick = {
                            lastInteractionTime = System.currentTimeMillis()
                            selectedLogForDetail = log
                        },
                        onAudioCall = {
                            val now = System.currentTimeMillis()
                            if (now - lastCallClickTime > 1500L) {
                                lastCallClickTime = now
                                lastInteractionTime = now
                                coroutineScope.launch {
                                    delay(200)
                                    onStartCall(log.otherPartyNumber, matchedUser?.displayName ?: log.otherPartyName, CallType.AUDIO)
                                }
                            }
                        },
                        onVideoCall = {
                            val now = System.currentTimeMillis()
                            if (now - lastCallClickTime > 1500L) {
                                lastCallClickTime = now
                                lastInteractionTime = now
                                coroutineScope.launch {
                                    delay(200)
                                    onStartCall(log.otherPartyNumber, matchedUser?.displayName ?: log.otherPartyName, CallType.VIDEO)
                                }
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
    isBlocked: Boolean = false,
    isSpam: Boolean = false,
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
                    isBlocked = isBlocked,
                    isSpam = isSpam,
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
                        isBlocked = isBlocked,
                        isSpam = isSpam,
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
    isBlocked: Boolean = false,
    isSpam: Boolean = false,
    onItemClick: () -> Unit
) {
    val themeColor = LocalThemeColor.current
    val context = LocalContext.current
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

                val recFile = remember(log.callId, log.recordingPath) {
                    com.example.util.CallAudioRecorder.getInstance(context).findRecordingFile(log.callId, log.recordingPath)
                }
                if (recFile != null && recFile.exists()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        color = GreenCall.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "Recorded",
                                tint = GreenCall,
                                modifier = Modifier.size(11.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "Recorded",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = GreenCall
                            )
                        }
                    }
                }

                if (isBlocked && !isSpam) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "Blocked",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }

                if (isSpam) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        color = Color(0xFFFF9800).copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "Spam",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color(0xFFFF9800),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
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

@Composable
fun CallRecordingPlayerCard(
    file: File,
    callId: String,
    onDeleted: () -> Unit
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableStateOf(0) }
    var totalDurationMs by remember { mutableStateOf(0) }
    var playbackSpeed by remember { mutableStateOf(1.0f) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    DisposableEffect(file) {
        val player = MediaPlayer().apply {
            try {
                setDataSource(file.absolutePath)
                prepare()
                totalDurationMs = duration
            } catch (e: Exception) {
                android.util.Log.w("CallRecPlayer", "Failed to prepare MediaPlayer: ${e.message}")
            }
            setOnCompletionListener {
                isPlaying = false
                currentPositionMs = 0
            }
        }
        mediaPlayer = player

        onDispose {
            try {
                if (player.isPlaying) player.stop()
                player.release()
            } catch (_: Exception) {}
            mediaPlayer = null
        }
    }

    // Position tracking loop while playing
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    currentPositionMs = player.currentPosition
                }
            }
            delay(200)
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Call Recording?") },
            text = { Text("This will permanently remove the recorded audio file (${file.name}).") },
            confirmButton = {
                TextButton(onClick = {
                    com.example.util.CallAudioRecorder.getInstance(context).deleteRecording(file)
                    showDeleteConfirm = false
                    onDeleted()
                    Toast.makeText(context, "Recording deleted", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Delete", color = RedEndCall, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = null,
                        tint = GreenCall,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Call Recording",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Playback Speed Button (1.0x -> 1.5x -> 2.0x)
                    Surface(
                        onClick = {
                            val newSpeed = when (playbackSpeed) {
                                1.0f -> 1.5f
                                1.5f -> 2.0f
                                else -> 1.0f
                            }
                            playbackSpeed = newSpeed
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                                mediaPlayer?.let { player ->
                                    try {
                                        val params = player.playbackParams
                                        params.speed = newSpeed
                                        player.playbackParams = params
                                    } catch (_: Exception) {}
                                }
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = "${playbackSpeed}x",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Share Button
                    IconButton(
                        onClick = {
                            try {
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file
                                )
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "audio/m4a"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share Call Recording"))
                            } catch (e: Exception) {
                                Toast.makeText(context, "Could not share: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = "Share",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Delete Button
                    IconButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Player controls row: Play/Pause button + Slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        mediaPlayer?.let { player ->
                            if (isPlaying) {
                                player.pause()
                                isPlaying = false
                            } else {
                                player.start()
                                isPlaying = true
                            }
                        }
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(GreenCall)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Slider(
                        value = if (totalDurationMs > 0) (currentPositionMs.toFloat() / totalDurationMs).coerceIn(0f, 1f) else 0f,
                        onValueChange = { fraction ->
                            val target = (fraction * totalDurationMs).toInt()
                            currentPositionMs = target
                            mediaPlayer?.seekTo(target)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor = GreenCall,
                            activeTrackColor = GreenCall
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatMs(currentPositionMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = formatMs(totalDurationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun formatMs(ms: Int): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format("%02d:%02d", min, sec)
}
