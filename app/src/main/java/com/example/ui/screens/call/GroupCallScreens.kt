package com.example.ui.screens.call

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.*
import com.example.data.repository.FirebaseManager
import com.example.ui.theme.*
import com.example.util.ContactsHelper
import com.example.util.ImageUtils
import com.example.webrtc.GroupCallEngine
import com.example.webrtc.GroupCallUiState

/**
 * Persistent top banner shown when an active Group Conference Call is minimized.
 */
@Composable
fun MinimizedGroupCallTopBar(
    state: GroupCallUiState,
    onExpand: () -> Unit,
    onLeaveOrEnd: () -> Unit
) {
    val room = state.room ?: return
    val mins = state.callDurationSeconds / 60
    val secs = state.callDurationSeconds % 60
    val durationStr = "%02d:%02d".format(mins, secs)
    var isHiddenByDragUp by remember { mutableStateOf(false) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    if (isHiddenByDragUp) {
        Box(
            modifier = Modifier
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
                color = Color(0xFF064E3B).copy(alpha = 0.92f),
                border = BorderStroke(1.dp, CyberMint.copy(alpha = 0.55f)),
                shadowElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Groups,
                        contentDescription = null,
                        tint = CyberMint,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${room.title} • $durationStr",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Show Call Banner",
                        tint = CyberMint,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
        return
    }

    Surface(
        onClick = onExpand,
        color = Color(0xFF064E3B),
        contentColor = Color.White,
        shadowElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .offset { androidx.compose.ui.unit.IntOffset(0, dragOffsetY.toInt().coerceAtMost(0)) }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (dragOffsetY < -35f) {
                            isHiddenByDragUp = true
                        }
                        dragOffsetY = 0f
                    },
                    onDragCancel = { dragOffsetY = 0f },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        dragOffsetY = (dragOffsetY + dragAmount).coerceIn(-160f, 0f)
                    }
                )
            }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = CyberMint.copy(alpha = 0.24f),
                        modifier = Modifier.size(34.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (room.callType == CallType.VIDEO) Icons.Default.Groups else Icons.Default.PhoneInTalk,
                                contentDescription = null,
                                tint = CyberMint,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${room.title} (${state.joinedParticipants.size} in call)",
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Tap to return • Swipe ↑ to hide • $durationStr",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 10.sp,
                            color = CyberMint
                        )
                    }
                }

                IconButton(
                    onClick = { isHiddenByDragUp = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = "Hide Banner",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                Surface(
                    onClick = onLeaveOrEnd,
                    shape = RoundedCornerShape(50),
                    color = RedEndCall
                ) {
                    Text(
                        text = "Leave",
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

/**
 * Modal dialog to create a new Group Audio/Video Conference or invite additional contacts mid-call.
 */
@Composable
fun StartGroupCallModal(
    firebaseManager: FirebaseManager,
    isAddingToExistingCall: Boolean = false,
    existingTitle: String = "",
    preselectedContacts: List<ContactDto> = emptyList(),
    onStartGroupCall: (title: String, selected: List<ContactDto>, callType: CallType, waitingRoom: Boolean) -> Unit,
    onAddParticipants: ((List<ContactDto>) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val currentUser by firebaseManager.currentUser.collectAsState()
    val registeredUsers by firebaseManager.registeredUsers.collectAsState()
    val syncedContacts by firebaseManager.syncedContacts.collectAsState()

    // PRIVACY FIX: Strictly show ONLY the user's own device contacts (syncedContacts) + any explicitly preselected peer.
    // NEVER dump global registeredUsers from Firestore that are not in the user's phonebook!
    val availableContacts = remember(registeredUsers, syncedContacts, currentUser, preselectedContacts) {
        val myPhone = ContactsHelper.normalizePhoneNumber(currentUser?.phoneNumber ?: "")
        val map = LinkedHashMap<String, ContactDto>()
        syncedContacts.forEach { c ->
            val norm = ContactsHelper.normalizePhoneNumber(c.phoneNumber)
            if (norm.isNotBlank() && norm != myPhone) {
                val matchedReg = registeredUsers.find { ContactsHelper.numbersMatch(it.phoneNumber, norm) }
                map[norm] = c.copy(
                    phoneNumber = norm,
                    profilePictureUrl = c.profilePictureUrl.ifBlank { matchedReg?.profilePictureUrl ?: "" },
                    isVoiceLinkUser = c.isVoiceLinkUser || matchedReg != null
                )
            }
        }
        preselectedContacts.forEach { p ->
            val norm = ContactsHelper.normalizePhoneNumber(p.phoneNumber)
            if (norm.isNotBlank() && norm != myPhone && !map.containsKey(norm)) {
                map[norm] = p.copy(phoneNumber = norm)
            }
        }
        map.values.toList()
    }

    var meetingTitle by remember {
        mutableStateOf(
            existingTitle.ifBlank {
                val hostName = currentUser?.displayName?.ifBlank { "Team" } ?: "Team"
                "$hostName's Conference"
            }
        )
    }
    var searchQuery by remember { mutableStateOf("") }
    var manualNumberInput by remember { mutableStateOf("") }
    var waitingRoomEnabled by remember { mutableStateOf(false) }
    val selectedContacts = remember {
        mutableStateListOf<ContactDto>().apply {
            addAll(preselectedContacts.distinctBy { it.phoneNumber }.take(5))
        }
    }

    val filteredContacts = remember(availableContacts, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) availableContacts
        else availableContacts.filter {
            it.name.lowercase().contains(q) || it.phoneNumber.lowercase().contains(q)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = Color(0xFF0B1528).copy(alpha = 0.96f),
            border = BorderStroke(
                1.4.dp,
                Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.65f),
                        Color(0xFF38BDF8).copy(alpha = 0.45f),
                        CyberMint.copy(alpha = 0.45f)
                    )
                )
            ),
            shadowElevation = 24.dp,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 650.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = CyberMint.copy(alpha = 0.18f),
                            border = BorderStroke(1.dp, CyberMint.copy(alpha = 0.55f)),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Groups,
                                    contentDescription = null,
                                    tint = CyberMint,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = if (isAddingToExistingCall) "Invite to Group Call" else "Start Group Conference",
                                fontFamily = SpaceGroteskFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = Color.White
                            )
                            Text(
                                text = "Up to 6 participants • You are the Host",
                                fontFamily = SpaceGroteskFontFamily,
                                fontSize = 12.sp,
                                color = CyberMint
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White.copy(alpha = 0.8f))
                    }
                }

                if (!isAddingToExistingCall) {
                    OutlinedTextField(
                        value = meetingTitle,
                        onValueChange = { meetingTitle = it },
                        label = { Text("Meeting Title", color = Color.White.copy(alpha = 0.7f)) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = CyberMint,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.22f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Waiting Room Lobby Switch
                    Surface(
                        onClick = { waitingRoomEnabled = !waitingRoomEnabled },
                        shape = RoundedCornerShape(14.dp),
                        color = Color.White.copy(alpha = 0.07f),
                        border = BorderStroke(
                            1.dp,
                            if (waitingRoomEnabled) SignalAmber else Color.White.copy(alpha = 0.18f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(
                                    Icons.Default.VerifiedUser,
                                    contentDescription = null,
                                    tint = if (waitingRoomEnabled) SignalAmber else Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Waiting Room Lobby Approval",
                                        fontFamily = SpaceGroteskFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "Host admits each guest before they join",
                                        fontFamily = SpaceGroteskFontFamily,
                                        fontSize = 11.sp,
                                        color = Color.White.copy(alpha = 0.7f)
                                    )
                                }
                            }
                            Switch(
                                checked = waitingRoomEnabled,
                                onCheckedChange = { waitingRoomEnabled = it }
                            )
                        }
                    }
                }

                // Search or Add Number directly
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = {
                            searchQuery = it
                            manualNumberInput = it
                        },
                        placeholder = {
                            Text(
                                "Search name or enter phone number...",
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.6f)
                            )
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null, tint = CyberMint)
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = CyberMint,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.22f)
                        ),
                        modifier = Modifier.weight(1f)
                    )

                    if (manualNumberInput.trim().length >= 4 &&
                        selectedContacts.none { it.phoneNumber == manualNumberInput.trim() } &&
                        selectedContacts.size < 5
                    ) {
                        Button(
                            onClick = {
                                val num = manualNumberInput.trim()
                                selectedContacts.add(
                                    ContactDto(
                                        id = num,
                                        name = num,
                                        phoneNumber = num,
                                        isVoiceLinkUser = true
                                    )
                                )
                                searchQuery = ""
                                manualNumberInput = ""
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CyberMint)
                        ) {
                            Text("Add", color = Color(0xFF06152D), fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Selected Participant Chips
                if (selectedContacts.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        selectedContacts.forEach { selected ->
                            Surface(
                                onClick = { selectedContacts.remove(selected) },
                                shape = RoundedCornerShape(50),
                                color = CyberMint.copy(alpha = 0.20f),
                                border = BorderStroke(1.dp, CyberMint)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = selected.name.ifBlank { selected.phoneNumber },
                                        fontFamily = SpaceGroteskFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove",
                                        tint = CyberMint,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Contact List
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(min = 150.dp, max = 240.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filteredContacts, key = { it.phoneNumber }) { contact ->
                        val isSelected = selectedContacts.any { it.phoneNumber == contact.phoneNumber }
                        Surface(
                            onClick = {
                                if (isSelected) {
                                    selectedContacts.removeAll { it.phoneNumber == contact.phoneNumber }
                                } else if (selectedContacts.size < 5) {
                                    selectedContacts.add(contact)
                                }
                            },
                            shape = RoundedCornerShape(14.dp),
                            color = if (isSelected) CyberMint.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.06f),
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) CyberMint else Color.White.copy(alpha = 0.12f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = contact.name.ifBlank { contact.phoneNumber },
                                        fontFamily = SpaceGroteskFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = Color.White
                                    )
                                    Text(
                                        text = contact.phoneNumber,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontSize = 11.sp,
                                        color = Color.White.copy(alpha = 0.7f)
                                    )
                                }
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = {
                                        if (isSelected) {
                                            selectedContacts.removeAll { it.phoneNumber == contact.phoneNumber }
                                        } else if (selectedContacts.size < 5) {
                                            selectedContacts.add(contact)
                                        }
                                    },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = CyberMint,
                                        checkmarkColor = Color(0xFF06152D)
                                    )
                                )
                            }
                        }
                    }
                }

                // Bottom Action Buttons
                if (isAddingToExistingCall && onAddParticipants != null) {
                    Button(
                        onClick = {
                            if (selectedContacts.isNotEmpty()) {
                                onAddParticipants(selectedContacts.toList())
                                onDismiss()
                            }
                        },
                        enabled = selectedContacts.isNotEmpty(),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GreenCall),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Icon(Icons.Default.PersonAdd, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Invite ${selectedContacts.size} Selected to Call",
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                onStartGroupCall(
                                    meetingTitle,
                                    selectedContacts.toList(),
                                    CallType.AUDIO,
                                    waitingRoomEnabled
                                )
                                onDismiss()
                            },
                            enabled = selectedContacts.isNotEmpty(),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0EA5E9)),
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                        ) {
                            Icon(Icons.Default.Call, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Group Audio (${selectedContacts.size + 1})",
                                fontFamily = SpaceGroteskFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = Color.White
                            )
                        }

                        Button(
                            onClick = {
                                onStartGroupCall(
                                    meetingTitle,
                                    selectedContacts.toList(),
                                    CallType.VIDEO,
                                    waitingRoomEnabled
                                )
                                onDismiss()
                            },
                            enabled = selectedContacts.isNotEmpty(),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = GreenCall),
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                        ) {
                            Icon(Icons.Default.Videocam, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Group Video (${selectedContacts.size + 1})",
                                fontFamily = SpaceGroteskFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Full-featured Zoom-grade Group Conference Screen with:
 * - Adaptive Grid (1..6 participants) & Active/Spotlight Speaker View
 * - Host Moderation & Waiting Room Lobby Controls
 * - Real-Time Custom Video Filter & Studio Lighting Drawer
 * - Raise Hand & Floating Emoji Reactions
 */
@Composable
fun ActiveGroupCallScreen(
    state: GroupCallUiState,
    groupCallEngine: GroupCallEngine,
    firebaseManager: FirebaseManager,
    onMinimize: () -> Unit
) {
    val context = LocalContext.current
    val filterManager = remember { VideoFilterManager.getInstance(context) }
    val currentUser by firebaseManager.currentUser.collectAsState()
    val myPhone = ContactsHelper.normalizePhoneNumber(currentUser?.phoneNumber ?: "")
    val room = state.room ?: return
    val isHost = groupCallEngine.isHostOrCoHost(myPhone)

    var showParticipantsSheet by remember { mutableStateOf(false) }
    var showFilterStudioSheet by remember { mutableStateOf(false) }
    var showInviteMoreModal by remember { mutableStateOf(false) }
    var showReactionBar by remember { mutableStateOf(false) }
    var showHostEndConfirmSheet by remember { mutableStateOf(false) }

    val mins = state.callDurationSeconds / 60
    val secs = state.callDurationSeconds % 60
    val durationFormatted = "%02d:%02d".format(mins, secs)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF060E1E),
                        Color(0xFF0B192E),
                        Color(0xFF050B16)
                    )
                )
            )
    ) {
        if (state.isInWaitingLobby) {
            // ── WAITING ROOM LOBBY SCREEN FOR GUESTS ──────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = SignalAmber.copy(alpha = 0.18f),
                    border = BorderStroke(1.dp, SignalAmber)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.HourglassTop, contentDescription = null, tint = SignalAmber, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "WAITING ROOM LOBBY",
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = SignalAmber
                        )
                    }
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = room.title,
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 26.sp,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Hosted by ${room.hostName} • Waiting for Host to admit you...",
                        fontFamily = SpaceGroteskFontFamily,
                        fontSize = 14.sp,
                        color = Color.White.copy(alpha = 0.78f),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Local Camera Preview with Filter Studio inside Waiting Room
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = Color(0xFF111C33),
                        border = BorderStroke(2.dp, CyberMint.copy(alpha = 0.6f)),
                        modifier = Modifier
                            .size(width = 230.dp, height = 300.dp)
                    ) {
                        val localTrack = state.localVideoTrack
                        if (localTrack != null && state.isVideoEnabled) {
                            FilteredVideoViewport(
                                videoTrack = localTrack,
                                eglBaseContext = groupCallEngine.eglBase.eglBaseContext,
                                filterConfig = state.filterConfig,
                                mirror = state.isFrontCamera,
                                cornerRadius = 28.dp,
                                participantLabel = "You",
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Icon(
                                    Icons.Default.VideocamOff,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.5f),
                                    modifier = Modifier.size(48.dp)
                                )
                            }
                        }
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = { groupCallEngine.toggleMic() },
                        shape = CircleShape,
                        color = if (state.isMuted) RedEndCall else Color.White.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
                        modifier = Modifier.size(54.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (state.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                contentDescription = "Toggle Mic",
                                tint = Color.White
                            )
                        }
                    }

                    Surface(
                        onClick = { groupCallEngine.toggleVideo() },
                        shape = CircleShape,
                        color = if (!state.isVideoEnabled) RedEndCall else Color.White.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
                        modifier = Modifier.size(54.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (state.isVideoEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                                contentDescription = "Toggle Video",
                                tint = Color.White
                            )
                        }
                    }

                    Surface(
                        onClick = { showFilterStudioSheet = true },
                        shape = CircleShape,
                        color = CyberMint.copy(alpha = 0.22f),
                        border = BorderStroke(1.dp, CyberMint),
                        modifier = Modifier.size(54.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = "Video Filters",
                                tint = CyberMint
                            )
                        }
                    }

                    Button(
                        onClick = { groupCallEngine.leaveGroupCall() },
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(containerColor = RedEndCall),
                        modifier = Modifier.height(54.dp)
                    ) {
                        Icon(Icons.Default.CallEnd, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Leave Lobby", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        } else {
            // ── ACTIVE ZOOM-STYLE GROUP CONFERENCE STAGE ──────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // 1. TOP CONFERENCE HEADER BAR (Liquid Glass)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = Color.White.copy(alpha = 0.12f),
                        border = BorderStroke(
                            1.dp,
                            Brush.linearGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.55f),
                                    Color(0xFF38BDF8).copy(alpha = 0.30f)
                                )
                            )
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                IconButton(
                                    onClick = onMinimize,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Default.KeyboardArrowDown,
                                        contentDescription = "Minimize",
                                        tint = Color.White
                                    )
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = if (room.isLocked) Icons.Default.Lock else Icons.Default.VerifiedUser,
                                            contentDescription = null,
                                            tint = if (room.isLocked) SignalAmber else CyberMint,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(5.dp))
                                        Text(
                                            text = room.title,
                                            fontFamily = SpaceGroteskFontFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = durationFormatted,
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            color = CyberMint
                                        )
                                        Text(
                                            text = " • ${state.joinedParticipants.size} in call",
                                            fontFamily = SpaceGroteskFontFamily,
                                            fontSize = 11.sp,
                                            color = Color.White.copy(alpha = 0.78f)
                                        )
                                        if (isHost) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = SignalAmber.copy(alpha = 0.24f),
                                                border = BorderStroke(0.8.dp, SignalAmber)
                                            ) {
                                                Text(
                                                    text = "HOST",
                                                    fontFamily = JetBrainsMonoFontFamily,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 9.sp,
                                                    color = SignalAmber,
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // Layout Toggle (Grid vs Speaker) + Camera Flip + Filter Studio
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Surface(
                                    onClick = {
                                        val nextMode = if (state.layoutMode == GroupLayoutMode.GRID) {
                                            GroupLayoutMode.SPEAKER
                                        } else {
                                            GroupLayoutMode.GRID
                                        }
                                        groupCallEngine.setLayoutMode(nextMode)
                                    },
                                    shape = RoundedCornerShape(50),
                                    color = Color.White.copy(alpha = 0.14f),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.30f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = if (state.layoutMode == GroupLayoutMode.GRID) {
                                                Icons.Default.GridView
                                            } else {
                                                Icons.Default.CoPresent
                                            },
                                            contentDescription = "Switch View",
                                            tint = CyberMint,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (state.layoutMode == GroupLayoutMode.GRID) "Grid" else "Speaker",
                                            fontFamily = SpaceGroteskFontFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            color = Color.White
                                        )
                                    }
                                }

                                IconButton(
                                    onClick = { groupCallEngine.toggleSpeaker() },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(
                                        imageVector = if (state.isSpeakerOn) Icons.Default.VolumeUp else Icons.Default.Hearing,
                                        contentDescription = if (state.isSpeakerOn) "Speaker On" else "Earpiece",
                                        tint = if (state.isSpeakerOn) CyberMint else Color.White
                                    )
                                }

                                if (state.isVideoEnabled) {
                                    IconButton(
                                        onClick = { groupCallEngine.switchCamera() },
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Cameraswitch,
                                            contentDescription = "Flip Camera",
                                            tint = Color.White
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Host Waiting Room Admission Banner (appears automatically when guests are waiting)
                    if (isHost && state.waitingParticipants.isNotEmpty()) {
                        val firstWaiting = state.waitingParticipants.first()
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = SignalAmber.copy(alpha = 0.20f),
                            border = BorderStroke(1.2.dp, SignalAmber),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        Icons.Default.PersonAddAlt1,
                                        contentDescription = null,
                                        tint = SignalAmber,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (state.waitingParticipants.size == 1) {
                                            "${firstWaiting.displayName} is in Waiting Room"
                                        } else {
                                            "${state.waitingParticipants.size} guests waiting to join"
                                        },
                                        fontFamily = SpaceGroteskFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TextButton(
                                        onClick = {
                                            if (state.waitingParticipants.size == 1) {
                                                groupCallEngine.declineWaitingParticipant(firstWaiting.phoneNumber)
                                            } else {
                                                showParticipantsSheet = true
                                            }
                                        }
                                    ) {
                                        Text("Decline", color = CrimsonAlert, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Button(
                                        onClick = {
                                            if (state.waitingParticipants.size == 1) {
                                                groupCallEngine.admitWaitingParticipant(firstWaiting.phoneNumber)
                                            } else {
                                                groupCallEngine.admitAllWaitingParticipants()
                                            }
                                        },
                                        shape = RoundedCornerShape(50),
                                        colors = ButtonDefaults.buttonColors(containerColor = GreenCall),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Text(
                                            text = if (state.waitingParticipants.size == 1) "Admit" else "Admit All",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. CENTER PARTICIPANT VIDEO / AUDIO STAGE (Adaptive Grid 1..6 or Spotlight Speaker View)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = 8.dp)
                ) {
                    val joinedList = state.joinedParticipants.ifEmpty {
                        listOf(
                            GroupParticipantDto(
                                phoneNumber = myPhone,
                                displayName = currentUser?.displayName ?: "You",
                                profilePicUrl = currentUser?.profilePictureUrl ?: "",
                                role = "HOST",
                                joinState = "JOINED",
                                isMuted = state.isMuted,
                                isVideoEnabled = state.isVideoEnabled,
                                filterConfigStr = state.filterConfig.toCompactString()
                            )
                        )
                    }

                    val spotlightPhone = state.localPinnedPhone ?: room.spotlightPhone

                    if (state.layoutMode == GroupLayoutMode.SPEAKER || spotlightPhone != null) {
                        val primarySpeaker = joinedList.find { it.phoneNumber == spotlightPhone }
                            ?: joinedList.firstOrNull { it.phoneNumber != myPhone }
                            ?: joinedList.first()
                        val filmstripOthers = joinedList.filter { it.phoneNumber != primarySpeaker.phoneNumber }

                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Main Spotlight Stage
                            GroupParticipantTile(
                                participant = primarySpeaker,
                                isMe = primarySpeaker.phoneNumber == myPhone,
                                isSpotlight = true,
                                localVideoTrack = state.localVideoTrack,
                                remoteVideoTrack = state.remoteVideoTracks[primarySpeaker.phoneNumber],
                                eglBaseContext = groupCallEngine.eglBase.eglBaseContext,
                                isFrontCamera = state.isFrontCamera,
                                localFilterConfig = state.filterConfig,
                                onPinClick = { groupCallEngine.setLocalPinnedPhone(primarySpeaker.phoneNumber) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            )

                            // Bottom Filmstrip of remaining participants
                            if (filmstripOthers.isNotEmpty()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(126.dp)
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    filmstripOthers.forEach { other ->
                                        GroupParticipantTile(
                                            participant = other,
                                            isMe = other.phoneNumber == myPhone,
                                            isSpotlight = false,
                                            localVideoTrack = state.localVideoTrack,
                                            remoteVideoTrack = state.remoteVideoTracks[other.phoneNumber],
                                            eglBaseContext = groupCallEngine.eglBase.eglBaseContext,
                                            isFrontCamera = state.isFrontCamera,
                                            localFilterConfig = state.filterConfig,
                                            onPinClick = { groupCallEngine.setLocalPinnedPhone(other.phoneNumber) },
                                            modifier = Modifier
                                                .width(110.dp)
                                                .fillMaxHeight()
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        // Adaptive Balanced Grid for 1 to 6 Participants
                        val columns = if (joinedList.size <= 2) 1 else 2
                        val rows = joinedList.chunked(columns)
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rows.forEach { rowItems ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    rowItems.forEach { participant ->
                                        GroupParticipantTile(
                                            participant = participant,
                                            isMe = participant.phoneNumber == myPhone,
                                            isSpotlight = false,
                                            localVideoTrack = state.localVideoTrack,
                                            remoteVideoTrack = state.remoteVideoTracks[participant.phoneNumber],
                                            eglBaseContext = groupCallEngine.eglBase.eglBaseContext,
                                            isFrontCamera = state.isFrontCamera,
                                            localFilterConfig = state.filterConfig,
                                            onPinClick = { groupCallEngine.setLocalPinnedPhone(participant.phoneNumber) },
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 3. FLOATING EMOJI REACTIONS & RAISE HAND BAR (When Toggled)
                AnimatedVisibility(visible = showReactionBar) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Color(0xFF0F172A).copy(alpha = 0.92f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.28f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf("👍", "👏", "❤️", "🔥", "🎉").forEach { emoji ->
                                Surface(
                                    onClick = {
                                        groupCallEngine.sendReaction(emoji)
                                        showReactionBar = false
                                    },
                                    shape = CircleShape,
                                    color = Color.White.copy(alpha = 0.10f)
                                ) {
                                    Text(
                                        text = emoji,
                                        fontSize = 20.sp,
                                        modifier = Modifier.padding(8.dp)
                                    )
                                }
                            }

                            val myParticipant = state.participants.find { it.phoneNumber == myPhone }
                            val handRaised = myParticipant?.isHandRaised == true
                            Surface(
                                onClick = {
                                    groupCallEngine.toggleRaiseHand()
                                    showReactionBar = false
                                },
                                shape = RoundedCornerShape(50),
                                color = if (handRaised) SignalAmber.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.14f),
                                border = BorderStroke(1.dp, if (handRaised) SignalAmber else Color.White.copy(alpha = 0.3f))
                            ) {
                                Text(
                                    text = if (handRaised) "✋ Lower Hand" else "✋ Raise Hand",
                                    fontFamily = SpaceGroteskFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }

                // 4. BOTTOM LIQUID GLASS ZOOM CONTROL DOCK
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = Color.White.copy(alpha = 0.13f),
                    border = BorderStroke(
                        1.2.dp,
                        Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.65f),
                                Color(0xFF38BDF8).copy(alpha = 0.35f),
                                CyberMint.copy(alpha = 0.40f)
                            )
                        )
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Mic Mute/Unmute
                        GroupDockControlButton(
                            icon = if (state.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            label = if (state.isMuted) "Unmute" else "Mute",
                            isActive = !state.isMuted,
                            activeColor = Color.White.copy(alpha = 0.18f),
                            inactiveColor = RedEndCall,
                            onClick = { groupCallEngine.toggleMic() }
                        )

                        // Video On/Off
                        GroupDockControlButton(
                            icon = if (state.isVideoEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                            label = if (state.isVideoEnabled) "Video" else "Start Video",
                            isActive = state.isVideoEnabled,
                            activeColor = Color.White.copy(alpha = 0.18f),
                            inactiveColor = RedEndCall,
                            onClick = { groupCallEngine.toggleVideo() }
                        )

                        // Video Filters & Studio Lighting
                        GroupDockControlButton(
                            icon = Icons.Default.AutoAwesome,
                            label = "Filters",
                            isActive = !state.filterConfig.isDefault,
                            activeColor = CyberMint.copy(alpha = 0.28f),
                            inactiveColor = Color.White.copy(alpha = 0.16f),
                            onClick = { showFilterStudioSheet = true }
                        )

                        // Participants & Host Control Center
                        Box {
                            GroupDockControlButton(
                                icon = Icons.Default.People,
                                label = if (isHost) "Host (${state.joinedParticipants.size})" else "People (${state.joinedParticipants.size})",
                                isActive = state.waitingParticipants.isNotEmpty(),
                                activeColor = SignalAmber.copy(alpha = 0.28f),
                                inactiveColor = Color.White.copy(alpha = 0.16f),
                                onClick = { showParticipantsSheet = true }
                            )
                            if (state.waitingParticipants.isNotEmpty()) {
                                Badge(
                                    containerColor = SignalAmber,
                                    contentColor = Color.Black,
                                    modifier = Modifier.align(Alignment.TopEnd)
                                ) {
                                    Text("${state.waitingParticipants.size}", fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // Reactions & Raise Hand
                        GroupDockControlButton(
                            icon = Icons.Default.FrontHand,
                            label = "React",
                            isActive = showReactionBar,
                            activeColor = CyberMint.copy(alpha = 0.28f),
                            inactiveColor = Color.White.copy(alpha = 0.16f),
                            onClick = { showReactionBar = !showReactionBar }
                        )

                        // Invite (+)
                        GroupDockControlButton(
                            icon = Icons.Default.PersonAdd,
                            label = "Invite",
                            isActive = false,
                            activeColor = CyberMint.copy(alpha = 0.24f),
                            inactiveColor = Color.White.copy(alpha = 0.16f),
                            onClick = { showInviteMoreModal = true }
                        )

                        // End / Leave Call
                        GroupDockControlButton(
                            icon = Icons.Default.CallEnd,
                            label = if (isHost) "End" else "Leave",
                            isActive = false,
                            activeColor = RedEndCall,
                            inactiveColor = RedEndCall,
                            onClick = {
                                if (isHost && state.joinedParticipants.size > 1) {
                                    showHostEndConfirmSheet = true
                                } else {
                                    groupCallEngine.leaveGroupCall()
                                }
                            }
                        )
                    }
                }
            }
        }

        // ── OVERLAY SHEETS & MODALS ───────────────────────────────────────────────

        if (showFilterStudioSheet) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable { showFilterStudioSheet = false },
                contentAlignment = Alignment.BottomCenter
            ) {
                Box(modifier = Modifier.clickable(enabled = false) {}) {
                    VideoFilterStudioSheet(
                        filterConfig = state.filterConfig,
                        onSelectPreset = { filterManager.selectPreset(it) },
                        onUpdateConfig = { filterManager.updateConfig(it) },
                        onReset = { filterManager.resetToDefault() },
                        onDismiss = { showFilterStudioSheet = false }
                    )
                }
            }
        }

        if (showParticipantsSheet) {
            GroupParticipantsHostSheet(
                state = state,
                isHost = isHost,
                myPhone = myPhone,
                groupCallEngine = groupCallEngine,
                onInviteClick = {
                    showParticipantsSheet = false
                    showInviteMoreModal = true
                },
                onDismiss = { showParticipantsSheet = false }
            )
        }

        if (showInviteMoreModal) {
            StartGroupCallModal(
                firebaseManager = firebaseManager,
                isAddingToExistingCall = true,
                existingTitle = room.title,
                onStartGroupCall = { _, _, _, _ -> },
                onAddParticipants = { newGuests ->
                    groupCallEngine.inviteAdditionalParticipants(newGuests)
                },
                onDismiss = { showInviteMoreModal = false }
            )
        }

        // Host Prompt: End Meeting for All vs. Leave Meeting
        if (showHostEndConfirmSheet) {
            AlertDialog(
                onDismissRequest = { showHostEndConfirmSheet = false },
                containerColor = Color(0xFF0F172A),
                titleContentColor = Color.White,
                textContentColor = Color.White.copy(alpha = 0.85f),
                title = {
                    Text(
                        text = "End or Leave Conference?",
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = "As the Host, you can either end the meeting for all participants or leave the meeting and automatically transfer Host privileges to the next participant.",
                        fontFamily = SpaceGroteskFontFamily
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showHostEndConfirmSheet = false
                            groupCallEngine.endGroupCallForAll()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = RedEndCall)
                    ) {
                        Text("End Meeting for All", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = {
                            showHostEndConfirmSheet = false
                            groupCallEngine.leaveGroupCall()
                        },
                        border = BorderStroke(1.dp, CyberMint)
                    ) {
                        Text("Leave & Transfer Host", color = CyberMint, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }

        // Participant Prompt when Host requests Unmute
        if (state.unmuteRequestedByHost) {
            AlertDialog(
                onDismissRequest = { groupCallEngine.respondToHostUnmuteRequest(false) },
                containerColor = Color(0xFF0F172A),
                titleContentColor = Color.White,
                textContentColor = Color.White.copy(alpha = 0.85f),
                title = {
                    Text(
                        text = "Host Requested Unmute",
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = "The Host (${room.hostName}) would like you to unmute your microphone.",
                        fontFamily = SpaceGroteskFontFamily
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { groupCallEngine.respondToHostUnmuteRequest(true) },
                        colors = ButtonDefaults.buttonColors(containerColor = GreenCall)
                    ) {
                        Text("Unmute Now", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { groupCallEngine.respondToHostUnmuteRequest(false) }) {
                        Text("Stay Muted", color = Color.White.copy(alpha = 0.75f))
                    }
                }
            )
        }
    }
}

@Composable
private fun GroupParticipantTile(
    participant: GroupParticipantDto,
    isMe: Boolean,
    isSpotlight: Boolean,
    localVideoTrack: org.webrtc.VideoTrack?,
    remoteVideoTrack: org.webrtc.VideoTrack?,
    eglBaseContext: org.webrtc.EglBase.Context,
    isFrontCamera: Boolean,
    localFilterConfig: VideoFilterConfig,
    onPinClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeTrack = if (isMe) localVideoTrack else remoteVideoTrack
    val filterConfig = remember(participant.filterConfigStr, isMe, localFilterConfig) {
        if (isMe) localFilterConfig else VideoFilterConfig.fromCompactString(participant.filterConfigStr)
    }

    val isSpeakingActive = !participant.isMuted
    val borderColor = when {
        participant.isHandRaised -> SignalAmber
        isSpotlight -> Color(0xFF38BDF8)
        isSpeakingActive -> CyberMint.copy(alpha = 0.75f)
        else -> Color.White.copy(alpha = 0.22f)
    }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFF101C34),
        border = BorderStroke(if (isSpotlight || participant.isHandRaised) 2.2.dp else 1.4.dp, borderColor),
        shadowElevation = 10.dp,
        modifier = modifier.clip(RoundedCornerShape(24.dp))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (activeTrack != null && participant.isVideoEnabled) {
                FilteredVideoViewport(
                    videoTrack = activeTrack,
                    eglBaseContext = eglBaseContext,
                    filterConfig = filterConfig,
                    mirror = isMe && isFrontCamera,
                    cornerRadius = 24.dp,
                    participantLabel = if (isMe) "You" else participant.displayName,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // Audio-only or Camera-Off Liquid Glass Avatar Tile
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF1E293B),
                                    Color(0xFF0F172A)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    val decoded = remember(participant.profilePicUrl) {
                        if (participant.profilePicUrl.isNotBlank()) {
                            ImageUtils.decodeBase64ToImageBitmap(participant.profilePicUrl)
                        } else null
                    }
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFF1E293B),
                        border = BorderStroke(2.dp, if (isSpeakingActive) CyberMint else Color.White.copy(alpha = 0.3f)),
                        modifier = Modifier.size(if (isSpotlight) 96.dp else 68.dp)
                    ) {
                        if (decoded != null) {
                            Image(
                                bitmap = decoded,
                                contentDescription = participant.displayName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            val initials = remember(participant.displayName, participant.phoneNumber) {
                                participant.displayName.trim()
                                    .split(Regex("\\s+"))
                                    .filter { it.isNotBlank() }
                                    .take(2)
                                    .joinToString("") { it.first().uppercaseChar().toString() }
                                    .ifBlank { participant.phoneNumber.takeLast(2).ifBlank { "U" } }
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.radialGradient(
                                            colors = listOf(
                                                Color(0xFF1E3A5F),
                                                Color(0xFF0F172A)
                                            )
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = initials,
                                    fontFamily = SpaceGroteskFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = if (isSpotlight) 28.sp else 20.sp,
                                    color = CyberMint
                                )
                            }
                        }
                    }
                }
            }

            // Top-Right Pin / Spotlight & Raised Hand Badges
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (participant.isHandRaised) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = SignalAmber.copy(alpha = 0.90f)
                    ) {
                        Text(
                            text = "✋",
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                val recentReaction = participant.activeReaction.isNotBlank() &&
                    (System.currentTimeMillis() - participant.reactionTimestamp) < 8000L
                if (recentReaction) {
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.55f)
                    ) {
                        Text(
                            text = participant.activeReaction,
                            fontSize = 18.sp,
                            modifier = Modifier.padding(6.dp)
                        )
                    }
                }

                Surface(
                    onClick = onPinClick,
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.45f),
                    border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.3f)),
                    modifier = Modifier.size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.PushPin,
                            contentDescription = "Pin Speaker",
                            tint = if (isSpotlight) CyberMint else Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            // Bottom Liquid Glass Name, Role & Mic Pill
            Surface(
                shape = RoundedCornerShape(50),
                color = Color.Black.copy(alpha = 0.55f),
                border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.25f)),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (participant.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = null,
                        tint = if (participant.isMuted) CrimsonAlert else CyberMint,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = if (isMe) "${participant.displayName} (You)" else participant.displayName,
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (participant.role == "HOST") {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "HOST",
                            fontFamily = JetBrainsMonoFontFamily,
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

@Composable
private fun GroupDockControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isActive: Boolean,
    activeColor: Color,
    inactiveColor: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Surface(
            shape = CircleShape,
            color = if (isActive) activeColor else inactiveColor,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
            modifier = Modifier.size(46.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontFamily = SpaceGroteskFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 10.sp,
            color = Color.White.copy(alpha = 0.88f),
            maxLines = 1
        )
    }
}

/**
 * Zoom-Grade Participants & Host Moderation Sheet:
 * - Waiting Room queue (Admit / Decline / Admit All)
 * - Joined Participants with Host Controls (Mute, Ask to Unmute, Spotlight, Stop Video, Make Host, Remove)
 * - Global Host Footer (Mute All, Ask All to Unmute, Lock Room, Waiting Room Toggle)
 */
@Composable
private fun GroupParticipantsHostSheet(
    state: GroupCallUiState,
    isHost: Boolean,
    myPhone: String,
    groupCallEngine: GroupCallEngine,
    onInviteClick: () -> Unit,
    onDismiss: () -> Unit
) {
    val room = state.room ?: return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = Color(0xFF0B1528).copy(alpha = 0.96f),
            border = BorderStroke(
                1.4.dp,
                Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.65f),
                        Color(0xFF38BDF8).copy(alpha = 0.40f),
                        CyberMint.copy(alpha = 0.40f)
                    )
                )
            ),
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 640.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = if (isHost) "Host Control Center & Participants" else "Conference Participants (${state.joinedParticipants.size})",
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = Color.White
                        )
                        Text(
                            text = "Host: ${room.hostName}",
                            fontFamily = SpaceGroteskFontFamily,
                            fontSize = 12.sp,
                            color = SignalAmber
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                // 1. Waiting Room Queue (Host Only)
                if (isHost && state.waitingParticipants.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = SignalAmber.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, SignalAmber),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "WAITING ROOM (${state.waitingParticipants.size})",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    color = SignalAmber
                                )
                                TextButton(onClick = { groupCallEngine.admitAllWaitingParticipants() }) {
                                    Text("Admit All", color = CyberMint, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                            state.waitingParticipants.forEach { guest ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = guest.displayName,
                                        fontFamily = SpaceGroteskFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = Color.White,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        OutlinedButton(
                                            onClick = { groupCallEngine.declineWaitingParticipant(guest.phoneNumber) },
                                            border = BorderStroke(1.dp, CrimsonAlert),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Text("Remove", color = CrimsonAlert, fontSize = 11.sp)
                                        }
                                        Button(
                                            onClick = { groupCallEngine.admitWaitingParticipant(guest.phoneNumber) },
                                            colors = ButtonDefaults.buttonColors(containerColor = GreenCall),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Text("Admit", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. Joined Participants List with Per-User Host Actions
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(min = 160.dp, max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.joinedParticipants, key = { it.phoneNumber }) { p ->
                        var expandedMenu by remember { mutableStateOf(false) }
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color.White.copy(alpha = 0.08f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = if (p.phoneNumber == myPhone) "${p.displayName} (You)" else p.displayName,
                                            fontFamily = SpaceGroteskFontFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = Color.White
                                        )
                                        if (p.role == "HOST") {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = SignalAmber.copy(alpha = 0.22f)
                                            ) {
                                                Text(
                                                    text = "HOST",
                                                    fontFamily = JetBrainsMonoFontFamily,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 9.sp,
                                                    color = SignalAmber,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        if (room.spotlightPhone == p.phoneNumber) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = CyberMint.copy(alpha = 0.22f)
                                            ) {
                                                Text(
                                                    text = "SPOTLIGHT",
                                                    fontFamily = JetBrainsMonoFontFamily,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 9.sp,
                                                    color = CyberMint,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = p.phoneNumber,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontSize = 11.sp,
                                        color = Color.White.copy(alpha = 0.65f)
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (p.isHandRaised) {
                                        Text("✋", fontSize = 16.sp)
                                    }
                                    Icon(
                                        imageVector = if (p.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                        contentDescription = null,
                                        tint = if (p.isMuted) CrimsonAlert else CyberMint,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Icon(
                                        imageVector = if (p.isVideoEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                                        contentDescription = null,
                                        tint = if (p.isVideoEnabled) CyberMint else Color.White.copy(alpha = 0.5f),
                                        modifier = Modifier.size(18.dp)
                                    )

                                    if (isHost && p.phoneNumber != myPhone) {
                                        Box {
                                            IconButton(
                                                onClick = { expandedMenu = true },
                                                modifier = Modifier.size(30.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.MoreVert,
                                                    contentDescription = "Host Actions",
                                                    tint = Color.White
                                                )
                                            }
                                            DropdownMenu(
                                                expanded = expandedMenu,
                                                onDismissRequest = { expandedMenu = false }
                                            ) {
                                                if (!p.isMuted) {
                                                    DropdownMenuItem(
                                                        text = { Text("Mute Participant") },
                                                        leadingIcon = { Icon(Icons.Default.MicOff, contentDescription = null) },
                                                        onClick = {
                                                            expandedMenu = false
                                                            groupCallEngine.hostMuteParticipant(p.phoneNumber)
                                                        }
                                                    )
                                                } else {
                                                    DropdownMenuItem(
                                                        text = { Text("Ask to Unmute") },
                                                        leadingIcon = { Icon(Icons.Default.Mic, contentDescription = null) },
                                                        onClick = {
                                                            expandedMenu = false
                                                            groupCallEngine.hostRequestUnmute(p.phoneNumber)
                                                        }
                                                    )
                                                }
                                                DropdownMenuItem(
                                                    text = {
                                                        Text(
                                                            if (room.spotlightPhone == p.phoneNumber) "Remove Spotlight"
                                                            else "Spotlight for Everyone"
                                                        )
                                                    },
                                                    leadingIcon = { Icon(Icons.Default.PushPin, contentDescription = null) },
                                                    onClick = {
                                                        expandedMenu = false
                                                        groupCallEngine.hostSpotlightParticipant(p.phoneNumber)
                                                    }
                                                )
                                                if (p.isVideoEnabled) {
                                                    DropdownMenuItem(
                                                        text = { Text("Stop Participant Video") },
                                                        leadingIcon = { Icon(Icons.Default.VideocamOff, contentDescription = null) },
                                                        onClick = {
                                                            expandedMenu = false
                                                            groupCallEngine.hostStopParticipantVideo(p.phoneNumber)
                                                        }
                                                    )
                                                }
                                                DropdownMenuItem(
                                                    text = { Text("Make Host") },
                                                    leadingIcon = { Icon(Icons.Default.Stars, contentDescription = null) },
                                                    onClick = {
                                                        expandedMenu = false
                                                        groupCallEngine.hostTransferHostRole(p.phoneNumber, p.displayName)
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("Remove from Call", color = CrimsonAlert) },
                                                    leadingIcon = { Icon(Icons.Default.PersonRemove, contentDescription = null, tint = CrimsonAlert) },
                                                    onClick = {
                                                        expandedMenu = false
                                                        groupCallEngine.hostRemoveParticipant(p.phoneNumber)
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 3. Global Host Moderation Bar (Host Only)
                if (isHost) {
                    HorizontalDivider(color = Color.White.copy(alpha = 0.15f))
                    Text(
                        text = "HOST ROOM CONTROLS",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = CyberMint
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { groupCallEngine.hostMuteAllParticipants() },
                            border = BorderStroke(1.dp, CrimsonAlert),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Mute All", color = CrimsonAlert, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { groupCallEngine.hostRequestUnmuteAll() },
                            border = BorderStroke(1.dp, CyberMint),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Ask Unmute All", color = CyberMint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { groupCallEngine.hostToggleRoomLock() },
                            border = BorderStroke(1.dp, if (room.isLocked) SignalAmber else Color.White.copy(alpha = 0.3f)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                if (room.isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                contentDescription = null,
                                tint = if (room.isLocked) SignalAmber else Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (room.isLocked) "Unlock Room" else "Lock Room",
                                color = Color.White,
                                fontSize = 12.sp
                            )
                        }
                        Button(
                            onClick = onInviteClick,
                            colors = ButtonDefaults.buttonColors(containerColor = GreenCall),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.PersonAdd, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Invite (+)", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
