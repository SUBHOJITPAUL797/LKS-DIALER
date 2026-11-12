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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import com.example.ui.components.QuickStartGuideCard
import com.example.ui.components.QuickStartStep
import com.example.ui.components.TacticalStatusPill
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.BorderStroke
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
    onOpenChat: ((number: String, name: String) -> Unit)? = null,
    onNavigateToDialer: (() -> Unit)? = null,
    onOpenGroupCallModal: (() -> Unit)? = null
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

    // Samsung Dialer Automatic Swipe Demonstration State
    var lastInteractionTime by remember { mutableStateOf(System.currentTimeMillis()) }
    var lastCallClickTime by remember { mutableStateOf(0L) }
    var showSwipeHint by remember { mutableStateOf(false) }
    var demoPhase by remember { mutableIntStateOf(0) } // 0 = idle, 1 = swipe right (Call), 2 = swipe left (Message)
    val demoSwipeOffset = remember { Animatable(0f) }

    // Track inactivity and play a smooth Samsung Dialer-style swipe demonstration
    LaunchedEffect(lastInteractionTime, callLogs.size) {
        if (callLogs.isNotEmpty()) {
            delay(3800)
            if (System.currentTimeMillis() - lastInteractionTime >= 3700) {
                showSwipeHint = true
                // Phase 1: Smooth Samsung Right-Swipe to Call preview
                demoPhase = 1
                demoSwipeOffset.animateTo(
                    targetValue = 112f,
                    animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing)
                )
                delay(850)
                demoSwipeOffset.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
                delay(350)
                // Phase 2: Smooth Samsung Left-Swipe to Message preview
                demoPhase = 2
                demoSwipeOffset.animateTo(
                    targetValue = -112f,
                    animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing)
                )
                delay(850)
                demoSwipeOffset.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
                demoPhase = 0
            }
        }
    }

    val filteredLogs = remember(callLogs, selectedFilter) {
        when (selectedFilter) {
            "MISSED" -> callLogs.filter { it.direction == CallDirection.MISSED }
            "INCOMING" -> callLogs.filter { it.direction == CallDirection.INCOMING }
            "OUTGOING" -> callLogs.filter { it.direction == CallDirection.OUTGOING }
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
        val resolvedNumber = log.resolveOtherPartyNumber()
        val matchedUser = remember(resolvedNumber, registeredUsers) {
            registeredUsers.find { ContactsHelper.numbersMatch(it.phoneNumber, resolvedNumber) }
        }
        val isBlocked = remember(resolvedNumber, blockedNumbers, spamNumbers) {
            firebaseManager.isNumberBlocked(resolvedNumber)
        }
        val displayName = matchedUser?.displayName?.ifBlank { null }
            ?: log.resolveOtherPartyName().ifBlank { null }
            ?: resolvedNumber.ifBlank { "Unknown" }

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
                    name = displayName,
                    profilePicBase64 = matchedUser?.profilePictureUrl ?: log.otherPartyProfilePic,
                    size = 76.dp,
                    fontSize = 28.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = resolvedNumber.ifBlank { "Unknown" },
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
                                onStartCall(resolvedNumber, displayName, CallType.AUDIO)
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
                                onStartCall(resolvedNumber, displayName, CallType.VIDEO)
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
                                onOpenChat(resolvedNumber, displayName)
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

                val rec = currentRecording
                if (rec != null && rec.exists()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    CallRecordingPlayerCard(
                        file = rec,
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
                            firebaseManager.unblockNumber(resolvedNumber)
                            firebaseManager.unmarkSpam(resolvedNumber)
                            Toast.makeText(context, "Unblocked $displayName", Toast.LENGTH_SHORT).show()
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
        val targetLog = selectedLogForDetail ?: return
        val targetNumber = targetLog.resolveOtherPartyNumber()
        val targetUser = registeredUsers.find { ContactsHelper.numbersMatch(it.phoneNumber, targetNumber) }
        val targetName = targetUser?.displayName?.ifBlank { null }
            ?: targetLog.resolveOtherPartyName().ifBlank { null }
            ?: targetNumber.ifBlank { "Unknown" }
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
                Text("Calls from ${targetNumber.ifBlank { "this number" }} will be automatically declined. You will not receive notifications for calls from this number.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        firebaseManager.blockNumber(targetNumber)
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
        val targetLog = selectedLogForDetail ?: return
        val targetNumber = targetLog.resolveOtherPartyNumber()
        val targetUser = registeredUsers.find { ContactsHelper.numbersMatch(it.phoneNumber, targetNumber) }
        val targetName = targetUser?.displayName?.ifBlank { null }
            ?: targetLog.resolveOtherPartyName().ifBlank { null }
            ?: targetNumber.ifBlank { "Unknown" }
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
                Text("This number (${targetNumber.ifBlank { "this number" }}) will be blocked, future calls will be suppressed, and a spam report will be submitted to protect the community.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        firebaseManager.reportSpam(targetNumber, "Reported from Call History")
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "RECENTS LOG",
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp,
                        color = HudTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    TacticalStatusPill(
                        text = "${callLogs.size} LOGS",
                        dotColor = if (callLogs.isNotEmpty()) CyberMint else HudTextSecondary
                    )
                }
            },
            actions = {
                if (onOpenGroupCallModal != null) {
                    Surface(
                        onClick = onOpenGroupCallModal,
                        shape = RoundedCornerShape(50),
                        color = CyberMint.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, CyberMint.copy(alpha = 0.55f)),
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Groups,
                                contentDescription = "Start Group Call",
                                tint = CyberMint,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "Group Call",
                                fontFamily = SpaceGroteskFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = HudTextPrimary
                            )
                        }
                    }
                }
                if (callLogs.isNotEmpty()) {
                    IconButton(
                        onClick = { showClearDialog = true },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "Clear History",
                            tint = HudTextSecondary
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = HudBackgroundPrimary
            )
        )

        // ZERO CLUTTER RULE: Only show filter chips when callLogs is NOT empty!
        if (callLogs.isNotEmpty()) {
            val missedCount = remember(callLogs) { callLogs.count { it.direction == CallDirection.MISSED } }
            val incomingCount = remember(callLogs) { callLogs.count { it.direction == CallDirection.INCOMING } }
            val outgoingCount = remember(callLogs) { callLogs.count { it.direction == CallDirection.OUTGOING } }
            val recordedCount = remember(callLogs) {
                callLogs.count {
                    com.example.util.CallAudioRecorder.getInstance(context).findRecordingFile(it.callId, it.recordingPath) != null
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 1. All Calls
                FilterChip(
                    selected = selectedFilter == "ALL",
                    onClick = {
                        selectedFilter = "ALL"
                        lastInteractionTime = System.currentTimeMillis()
                    },
                    label = {
                        Text(
                            text = "All Calls (${callLogs.size})",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = selectedFilter == "ALL",
                        borderColor = HudBorderSubtle,
                        selectedBorderColor = SignalAmber,
                        selectedBorderWidth = 1.5.dp
                    ),
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = HudSurfaceContainer,
                        selectedContainerColor = SignalAmber.copy(alpha = 0.2f),
                        labelColor = HudTextSecondary,
                        selectedLabelColor = HudTextPrimary
                    )
                )

                // 2. Missed
                FilterChip(
                    selected = selectedFilter == "MISSED",
                    onClick = {
                        selectedFilter = "MISSED"
                        lastInteractionTime = System.currentTimeMillis()
                    },
                    label = {
                        Text(
                            text = "Missed ($missedCount)",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.CallMissed,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = if (selectedFilter == "MISSED") CrimsonAlert else HudTextSecondary
                        )
                    },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = selectedFilter == "MISSED",
                        borderColor = HudBorderSubtle,
                        selectedBorderColor = CrimsonAlert,
                        selectedBorderWidth = 1.5.dp
                    ),
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = HudSurfaceContainer,
                        selectedContainerColor = CrimsonAlert.copy(alpha = 0.18f),
                        labelColor = HudTextSecondary,
                        selectedLabelColor = CrimsonAlert
                    )
                )

                // 3. Incoming
                FilterChip(
                    selected = selectedFilter == "INCOMING",
                    onClick = {
                        selectedFilter = "INCOMING"
                        lastInteractionTime = System.currentTimeMillis()
                    },
                    label = {
                        Text(
                            text = "Incoming ($incomingCount)",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.CallReceived,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = if (selectedFilter == "INCOMING") CyberMint else HudTextSecondary
                        )
                    },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = selectedFilter == "INCOMING",
                        borderColor = HudBorderSubtle,
                        selectedBorderColor = CyberMint,
                        selectedBorderWidth = 1.5.dp
                    ),
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = HudSurfaceContainer,
                        selectedContainerColor = CyberMint.copy(alpha = 0.18f),
                        labelColor = HudTextSecondary,
                        selectedLabelColor = CyberMint
                    )
                )

                // 4. Outgoing
                FilterChip(
                    selected = selectedFilter == "OUTGOING",
                    onClick = {
                        selectedFilter = "OUTGOING"
                        lastInteractionTime = System.currentTimeMillis()
                    },
                    label = {
                        Text(
                            text = "Outgoing ($outgoingCount)",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.CallMade,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = if (selectedFilter == "OUTGOING") TelemetryBlue else HudTextSecondary
                        )
                    },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = selectedFilter == "OUTGOING",
                        borderColor = HudBorderSubtle,
                        selectedBorderColor = TelemetryBlue,
                        selectedBorderWidth = 1.5.dp
                    ),
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = HudSurfaceContainer,
                        selectedContainerColor = TelemetryBlue.copy(alpha = 0.18f),
                        labelColor = HudTextSecondary,
                        selectedLabelColor = TelemetryBlue
                    )
                )

                // 5. Recordings
                if (recordedCount > 0) {
                    FilterChip(
                        selected = selectedFilter == "RECORDINGS",
                        onClick = {
                            selectedFilter = "RECORDINGS"
                            lastInteractionTime = System.currentTimeMillis()
                        },
                        label = {
                            Text(
                                text = "Rec ($recordedCount)",
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = if (selectedFilter == "RECORDINGS") PurplePrimary else HudTextSecondary
                            )
                        },
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selectedFilter == "RECORDINGS",
                            borderColor = HudBorderSubtle,
                            selectedBorderColor = PurplePrimary,
                            selectedBorderWidth = 1.5.dp
                        ),
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = HudSurfaceContainer,
                            selectedContainerColor = PurplePrimary.copy(alpha = 0.2f),
                            labelColor = HudTextSecondary,
                            selectedLabelColor = PurplePrimary
                        )
                    )
                }
            }
        }

        // Samsung Dialer-Style Animated Dual-Pill Gesture Indicator
        AnimatedVisibility(
            visible = showSwipeHint && filteredLogs.isNotEmpty(),
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically()
        ) {
            Surface(
                color = HudSurfaceContainer,
                border = BorderStroke(
                    1.dp,
                    when (demoPhase) {
                        1 -> Color(0xFF10B981).copy(alpha = 0.7f)
                        2 -> Color(0xFF0284C7).copy(alpha = 0.7f)
                        else -> HudBorderSubtle
                    }
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Right Swipe Pill (Call)
                    Surface(
                        color = if (demoPhase == 1) Color(0xFF10B981).copy(alpha = 0.22f) else Color(0xFF10B981).copy(alpha = 0.10f),
                        border = BorderStroke(1.dp, if (demoPhase == 1) Color(0xFF10B981) else Color(0xFF10B981).copy(alpha = 0.35f)),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                Icons.Default.Call,
                                contentDescription = null,
                                tint = Color(0xFF10B981),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "Swipe Right • Call",
                                fontFamily = SpaceGroteskFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = if (demoPhase == 1) Color(0xFF10B981) else HudTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "››",
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 12.sp,
                                color = Color(0xFF10B981)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Left Swipe Pill (Message)
                    Surface(
                        color = if (demoPhase == 2) Color(0xFF0284C7).copy(alpha = 0.22f) else Color(0xFF0284C7).copy(alpha = 0.10f),
                        border = BorderStroke(1.dp, if (demoPhase == 2) Color(0xFF0284C7) else Color(0xFF0284C7).copy(alpha = 0.35f)),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "‹‹",
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 12.sp,
                                color = Color(0xFF0284C7)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                Icons.Default.ChatBubbleOutline,
                                contentDescription = null,
                                tint = Color(0xFF0284C7),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "Swipe Left • Message",
                                fontFamily = SpaceGroteskFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = if (demoPhase == 2) Color(0xFF0284C7) else HudTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        if (callLogs.isEmpty()) {
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
                        modifier = Modifier.size(56.dp),
                        tint = HudTextSecondary.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "No recent calls",
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = HudTextPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Your incoming, outgoing, and missed calls will appear here",
                        fontSize = 13.sp,
                        color = HudTextSecondary
                    )
                    if (onNavigateToDialer != null) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = onNavigateToDialer,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CyberMint)
                        ) {
                            Icon(Icons.Default.Dialpad, contentDescription = null, tint = Color(0xFF0A101E), modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Open Dialer",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF0A101E)
                            )
                        }
                    }
                }
            }
        } else if (filteredLogs.isEmpty()) {
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
                        modifier = Modifier.size(56.dp),
                        tint = HudTextSecondary.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = when (selectedFilter) {
                            "MISSED" -> "No missed calls recorded"
                            "RECORDINGS" -> "No recorded calls yet"
                            else -> "No matching call logs"
                        },
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = HudTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                itemsIndexed(filteredLogs, key = { _, log -> log.id }) { index, log ->
                    val resolvedNumber = log.resolveOtherPartyNumber()
                    val matchedUser = remember(resolvedNumber, registeredUsers) {
                        registeredUsers.find { ContactsHelper.numbersMatch(it.phoneNumber, resolvedNumber) }
                    }
                    val isBlocked = remember(resolvedNumber, blockedNumbers, spamNumbers) {
                        firebaseManager.isNumberBlocked(resolvedNumber)
                    }
                    val isSpam = remember(resolvedNumber, spamNumbers) {
                        firebaseManager.isNumberSpam(resolvedNumber)
                    }
                    val targetName = matchedUser?.displayName?.ifBlank { null }
                        ?: log.resolveOtherPartyName().ifBlank { null }
                        ?: resolvedNumber.ifBlank { "Unknown" }
                    val isFirstItem = index == 0
                    val currentOffset = if (isFirstItem && showSwipeHint) demoSwipeOffset.value else 0f

                    SwipeableCallLogItem(
                        log = log,
                        profilePicBase64 = matchedUser?.profilePictureUrl ?: log.otherPartyProfilePic,
                        displayName = targetName,
                        displayNumber = resolvedNumber,
                        isBlocked = isBlocked,
                        isSpam = isSpam,
                        demoOffset = currentOffset,
                        onItemClick = {
                            lastInteractionTime = System.currentTimeMillis()
                            selectedLogForDetail = log
                        },
                        onAudioCall = {
                            val now = System.currentTimeMillis()
                            if (now - lastCallClickTime > 1200L) {
                                lastCallClickTime = now
                                lastInteractionTime = now
                                onStartCall(resolvedNumber, targetName, CallType.AUDIO)
                            }
                        },
                        onMessage = {
                            val now = System.currentTimeMillis()
                            lastInteractionTime = now
                            if (onOpenChat != null) {
                                onOpenChat(resolvedNumber, targetName)
                            } else if (now - lastCallClickTime > 1200L) {
                                lastCallClickTime = now
                                onStartCall(resolvedNumber, targetName, CallType.VIDEO)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SwipeableCallLogItem(
    log: CallLogDto,
    profilePicBase64: String,
    displayName: String = "",
    displayNumber: String = "",
    isBlocked: Boolean = false,
    isSpam: Boolean = false,
    demoOffset: Float,
    onItemClick: () -> Unit,
    onAudioCall: () -> Unit,
    onMessage: () -> Unit
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val userDragOffsetPx = remember { Animatable(0f) }
    var thresholdCrossed by remember { mutableStateOf(false) }

    val maxDragPx = with(density) { 150.dp.toPx() }
    val triggerThresholdPx = with(density) { 88.dp.toPx() }
    val demoOffsetPx = with(density) { demoOffset.dp.toPx() }

    // Combine user drag + demo offset smoothly in a single unified layout
    val activeOffsetPx = if (userDragOffsetPx.value != 0f) userDragOffsetPx.value else demoOffsetPx
    val progress = (kotlin.math.abs(activeOffsetPx) / triggerThresholdPx).coerceIn(0f, 1.25f)
    val isSwipingRight = activeOffsetPx > 0f
    val isSwipingLeft = activeOffsetPx < 0f

    // Animated chevron pulse for active swipe or demo
    val infiniteTransition = rememberInfiniteTransition(label = "samsung_swipe_chevron")
    val chevronShift by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(
            animation = tween(550, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "chevronShift"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(16.dp))
    ) {
        // Samsung One UI Style Rich Swipe Track Background
        if (kotlin.math.abs(activeOffsetPx) > 2f) {
            val trackBrush = if (isSwipingRight) {
                androidx.compose.ui.graphics.Brush.horizontalGradient(
                    colors = listOf(
                        Color(0xFF059669),
                        Color(0xFF10B981)
                    )
                )
            } else {
                androidx.compose.ui.graphics.Brush.horizontalGradient(
                    colors = listOf(
                        Color(0xFF0284C7),
                        Color(0xFF2563EB)
                    )
                )
            }

            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(trackBrush)
                    .padding(horizontal = 20.dp),
                contentAlignment = if (isSwipingRight) Alignment.CenterStart else Alignment.CenterEnd
            ) {
                val badgeScale = (0.75f + 0.32f * progress.coerceAtMost(1f))
                if (isSwipingRight) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = Color.White.copy(alpha = 0.24f),
                            border = BorderStroke(1.5.dp, Color.White.copy(alpha = 0.85f)),
                            modifier = Modifier
                                .size(40.dp)
                                .scale(badgeScale)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Call,
                                    contentDescription = "Call",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Call",
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width((6f + chevronShift).dp))
                        Text(
                            text = "››",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 16.sp,
                            color = Color.White.copy(alpha = 0.9f)
                        )
                    }
                } else if (isSwipingLeft) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "‹‹",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 16.sp,
                            color = Color.White.copy(alpha = 0.9f)
                        )
                        Spacer(modifier = Modifier.width((6f + chevronShift).dp))
                        Text(
                            text = "Message",
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Surface(
                            shape = CircleShape,
                            color = Color.White.copy(alpha = 0.24f),
                            border = BorderStroke(1.5.dp, Color.White.copy(alpha = 0.85f)),
                            modifier = Modifier
                                .size(40.dp)
                                .scale(badgeScale)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.ChatBubble,
                                    contentDescription = "Message",
                                    tint = Color.White,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Foreground Call Log Card with 120fps Horizontal Drag Gesture
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .offset { androidx.compose.ui.unit.IntOffset(activeOffsetPx.toInt(), 0) }
                .pointerInput(log.id) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            thresholdCrossed = false
                        },
                        onDragEnd = {
                            val finalOffset = userDragOffsetPx.value
                            scope.launch {
                                if (finalOffset >= triggerThresholdPx) {
                                    userDragOffsetPx.animateTo(
                                        targetValue = 0f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMedium
                                        )
                                    )
                                    onAudioCall()
                                } else if (finalOffset <= -triggerThresholdPx) {
                                    userDragOffsetPx.animateTo(
                                        targetValue = 0f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMedium
                                        )
                                    )
                                    onMessage()
                                } else {
                                    userDragOffsetPx.animateTo(
                                        targetValue = 0f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMedium
                                        )
                                    )
                                }
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                userDragOffsetPx.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    )
                                )
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val nextVal = (userDragOffsetPx.value + dragAmount).coerceIn(-maxDragPx, maxDragPx)
                            val crossedNow = kotlin.math.abs(nextVal) >= triggerThresholdPx
                            if (crossedNow && !thresholdCrossed) {
                                thresholdCrossed = true
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            } else if (!crossedNow && thresholdCrossed) {
                                thresholdCrossed = false
                            }
                            scope.launch {
                                userDragOffsetPx.snapTo(nextVal)
                            }
                        }
                    )
                }
        ) {
            CallLogItemContent(
                log = log,
                profilePicBase64 = profilePicBase64,
                displayName = displayName,
                displayNumber = displayNumber,
                isBlocked = isBlocked,
                isSpam = isSpam,
                onItemClick = onItemClick
            )
        }
    }
}

@Composable
private fun CallLogItemContent(
    log: CallLogDto,
    profilePicBase64: String,
    displayName: String = "",
    displayNumber: String = "",
    isBlocked: Boolean = false,
    isSpam: Boolean = false,
    onItemClick: () -> Unit
) {
    val themeColor = LocalThemeColor.current
    val context = LocalContext.current
    val resolvedNumber = displayNumber.ifBlank { log.resolveOtherPartyNumber() }
    val resolvedName = displayName.ifBlank {
        log.resolveOtherPartyName().ifBlank {
            resolvedNumber.ifBlank { "Unknown" }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onItemClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // User Profile Picture Avatar
        CallAvatar(
            name = resolvedName,
            profilePicBase64 = profilePicBase64,
            size = 48.dp,
            fontSize = 18.sp,
            isMissed = log.direction == CallDirection.MISSED
        )

        Spacer(modifier = Modifier.width(14.dp))

        // Info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = resolvedName,
                fontFamily = SpaceGroteskFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = if (log.direction == CallDirection.MISSED) CrimsonAlert else HudTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(3.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                val icon = when (log.direction) {
                    CallDirection.OUTGOING -> Icons.AutoMirrored.Filled.CallMade
                    CallDirection.INCOMING -> Icons.AutoMirrored.Filled.CallReceived
                    CallDirection.MISSED -> Icons.AutoMirrored.Filled.CallMissed
                }
                val tint = when (log.direction) {
                    CallDirection.OUTGOING -> TelemetryBlue
                    CallDirection.INCOMING -> CyberMint
                    CallDirection.MISSED -> CrimsonAlert
                }

                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(15.dp)
                )

                Spacer(modifier = Modifier.width(6.dp))

                Text(
                    text = "${formatTime(log.startedAt)} • ${if (log.durationSeconds > 0) formatDuration(log.durationSeconds) else "MISSED"}",
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = HudTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                val recFile = remember(log.callId, log.recordingPath) {
                    com.example.util.CallAudioRecorder.getInstance(context).findRecordingFile(log.callId, log.recordingPath)
                }
                if (recFile != null && recFile.exists()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        color = CyberMint.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "Recorded",
                                tint = CyberMint,
                                modifier = Modifier.size(11.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "REC",
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp,
                                color = CyberMint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                if (isBlocked && !isSpam) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        color = CrimsonAlert.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "BLOCKED",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp,
                            color = CrimsonAlert,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }

                if (isSpam) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        color = SignalAmber.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "SPAM",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp,
                            color = SignalAmber,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
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
                val initial = name.trim().trimStart('+', '0').take(1).uppercase().ifBlank {
                    name.trim().take(1).uppercase().ifBlank { "?" }
                }
                Text(
                    text = initial,
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
