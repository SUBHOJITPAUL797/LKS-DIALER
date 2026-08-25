package com.example.ui.screens.contacts

import android.content.Context
import android.content.Intent
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CallType
import com.example.data.model.ContactDto
import com.example.data.repository.FirebaseManager
import com.example.ui.theme.GreenCall
import com.example.ui.theme.LocalThemeColor
import com.example.util.LocalContact
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import android.media.RingtoneManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.util.LksRingtoneManager

private enum class ContactFilterTab(val title: String) {
    ALL("All"),
    ON_LKS("On LKS"),
    INVITE("Invite")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    firebaseManager: FirebaseManager,
    onStartCall: (number: String, name: String, callType: CallType) -> Unit
) {
    val themeColor = LocalThemeColor.current
    val context = LocalContext.current
    val syncedContacts by firebaseManager.syncedContacts.collectAsState()
    val nonLksContacts by firebaseManager.nonLksContacts.collectAsState()
    val registeredUsers by firebaseManager.registeredUsers.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(ContactFilterTab.ALL) }
    val coroutineScope = rememberCoroutineScope()

    // 5-Second Inactivity Swipe Demo State
    var lastInteractionTime by remember { mutableStateOf(System.currentTimeMillis()) }
    var showSwipeHint by remember { mutableStateOf(false) }
    val demoSwipeOffset = remember { Animatable(0f) }

    // Per-Contact Ringtone Picker State
    var ringtoneModalContact by remember { mutableStateOf<Pair<String, String>?>(null) } // Pair(Name, PhoneNumber)
    var contactRingtoneState by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    var isPreviewPlaying by remember { mutableStateOf(false) }

    LaunchedEffect(ringtoneModalContact) {
        ringtoneModalContact?.let {
            contactRingtoneState = LksRingtoneManager.getContactRingtone(context, it.second)
        } ?: run {
            LksRingtoneManager.stopPreview()
            isPreviewPlaying = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            LksRingtoneManager.stopPreview()
        }
    }

    // System Ringtone Picker for Selected Contact
    val contactSystemRingtoneLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val pickedUri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            val contact = ringtoneModalContact
            if (pickedUri != null && contact != null) {
                LksRingtoneManager.setContactRingtone(context, contact.second, pickedUri)
                contactRingtoneState = LksRingtoneManager.getContactRingtone(context, contact.second)
                Toast.makeText(context, "Ringtone set for ${contact.first}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Song Trimmer Dialog state — opened after user picks a song file
    var trimmerUri by remember { mutableStateOf<Uri?>(null) }
    var trimmerSongTitle by remember { mutableStateOf("") }
    var trimmerTarget by remember { mutableStateOf("contact") } // "contact" or "app"

    // Audio File / Song Picker for Selected Contact — opens trimmer
    val contactAudioFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { pickedUri ->
        if (pickedUri != null && ringtoneModalContact != null) {
            trimmerTarget = "contact"
            trimmerUri = pickedUri
            trimmerSongTitle = LksRingtoneManager.getRingtoneTitle(context, pickedUri)
        }
    }


    // Trigger contacts sync when screen is launched
    LaunchedEffect(Unit) {
        firebaseManager.syncNativeContacts()
    }

    // Filter synced (LKS) contacts based on search query
    val filteredLksContacts = remember(syncedContacts, searchQuery) {
        if (searchQuery.isBlank()) syncedContacts else {
            syncedContacts.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                        it.phoneNumber.contains(searchQuery)
            }
        }
    }

    // Filter non-LKS contacts based on search query
    val filteredNonLksContacts = remember(nonLksContacts, searchQuery) {
        if (searchQuery.isBlank()) nonLksContacts else {
            nonLksContacts.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                        it.phoneNumber.contains(searchQuery) ||
                        it.normalizedNumber.contains(searchQuery)
            }
        }
    }

    // Track 5 seconds of inactivity
    LaunchedEffect(lastInteractionTime, filteredLksContacts.size) {
        if (filteredLksContacts.isNotEmpty()) {
            delay(5000)
            if (System.currentTimeMillis() - lastInteractionTime >= 4900) {
                showSwipeHint = true
                // Run smooth demonstration swipe sequence on the first contact
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

    val totalContactsCount = syncedContacts.size + nonLksContacts.size

    fun shareInvite(contact: LocalContact) {
        lastInteractionTime = System.currentTimeMillis()
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(
                Intent.EXTRA_TEXT,
                "Hey ${contact.name}! Let's talk on LKS Dialer with HD audio & video calls. Download it here: https://github.com/SUBHOJITPAUL797/LKS-DIALER/releases/latest"
            )
        }
        val shareIntent = Intent.createChooser(sendIntent, "Invite ${contact.name} to LKS Dialer")
        context.startActivity(shareIntent)
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
        // WhatsApp-Style Top App Bar
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Contacts",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = themeColor.primary
                    )
                    if (totalContactsCount > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = themeColor.primary.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "$totalContactsCount",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = themeColor.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            },
            actions = {
                IconButton(onClick = {
                    lastInteractionTime = System.currentTimeMillis()
                    firebaseManager.syncNativeContacts()
                }) {
                    Icon(
                        Icons.Default.Sync,
                        contentDescription = "Sync Contacts",
                        tint = themeColor.primary
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        )

        // Real-time Search Field
        OutlinedTextField(
            value = searchQuery,
            onValueChange = {
                searchQuery = it
                lastInteractionTime = System.currentTimeMillis()
            },
            placeholder = { Text("Search by name or number...") },
            leadingIcon = {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = {
                        searchQuery = ""
                        lastInteractionTime = System.currentTimeMillis()
                    }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Clear search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                focusedBorderColor = themeColor.primary,
                unfocusedBorderColor = Color.Transparent
            ),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(4.dp))

        // WhatsApp-Style Category Filter Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedFilter == ContactFilterTab.ALL,
                onClick = {
                    selectedFilter = ContactFilterTab.ALL
                    lastInteractionTime = System.currentTimeMillis()
                },
                label = { Text("All ($totalContactsCount)") },
                shape = RoundedCornerShape(16.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = themeColor.primary.copy(alpha = 0.2f),
                    selectedLabelColor = themeColor.primary
                )
            )

            FilterChip(
                selected = selectedFilter == ContactFilterTab.ON_LKS,
                onClick = {
                    selectedFilter = ContactFilterTab.ON_LKS
                    lastInteractionTime = System.currentTimeMillis()
                },
                label = { Text("On LKS (${syncedContacts.size})") },
                shape = RoundedCornerShape(16.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = GreenCall.copy(alpha = 0.2f),
                    selectedLabelColor = GreenCall
                )
            )

            FilterChip(
                selected = selectedFilter == ContactFilterTab.INVITE,
                onClick = {
                    selectedFilter = ContactFilterTab.INVITE
                    lastInteractionTime = System.currentTimeMillis()
                },
                label = { Text("Invite (${nonLksContacts.size})") },
                shape = RoundedCornerShape(16.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = themeColor.primary.copy(alpha = 0.2f),
                    selectedLabelColor = themeColor.primary
                )
            )
        }

        // Animated Swipe Feature Tip Banner
        AnimatedVisibility(
            visible = showSwipeHint && filteredLksContacts.isNotEmpty(),
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

        // Contact List Content
        val showLks = selectedFilter == ContactFilterTab.ALL || selectedFilter == ContactFilterTab.ON_LKS
        val showNonLks = selectedFilter == ContactFilterTab.ALL || selectedFilter == ContactFilterTab.INVITE

        val hasLks = showLks && filteredLksContacts.isNotEmpty()
        val hasNonLks = showNonLks && filteredNonLksContacts.isNotEmpty()

        if (!hasLks && !hasNonLks) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Contacts,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (searchQuery.isNotEmpty()) "No matching contacts found" else "No contacts found",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                // Section 1: Contacts Available on LKS Dialer
                if (hasLks) {
                    item {
                        SectionHeader(
                            title = "AVAILABLE ON LKS DIALER",
                            count = filteredLksContacts.size,
                            accentColor = GreenCall
                        )
                    }

                    itemsIndexed(filteredLksContacts, key = { index, it -> "lks_${it.phoneNumber}_$index" }) { index, contact ->
                        val userInfo = registeredUsers.find { it.phoneNumber == contact.phoneNumber }
                        val isOnline = userInfo?.isOnline == true
                        val isFirstItem = index == 0
                        val currentOffset = if (isFirstItem && showSwipeHint) demoSwipeOffset.value else 0f

                        SwipeableContactItem(
                            contact = contact.copy(
                                statusMessage = userInfo?.statusMessage ?: contact.statusMessage.ifBlank { "Available on LKS DIALER" }
                            ),
                            isOnline = isOnline,
                            demoOffset = currentOffset,
                            onAudioCall = {
                                lastInteractionTime = System.currentTimeMillis()
                                coroutineScope.launch {
                                    delay(200)
                                    onStartCall(contact.phoneNumber, contact.name, CallType.AUDIO)
                                }
                            },
                            onVideoCall = {
                                lastInteractionTime = System.currentTimeMillis()
                                coroutineScope.launch {
                                    delay(200)
                                    onStartCall(contact.phoneNumber, contact.name, CallType.VIDEO)
                                }
                            },
                            onRingtoneClick = {
                                lastInteractionTime = System.currentTimeMillis()
                                ringtoneModalContact = Pair(contact.name, contact.phoneNumber)
                            }
                        )
                    }
                }

                // Section 2: Contacts to Invite to LKS Dialer
                if (hasNonLks) {
                    item {
                        SectionHeader(
                            title = "INVITE TO LKS DIALER",
                            count = filteredNonLksContacts.size,
                            accentColor = themeColor.primary
                        )
                    }

                    itemsIndexed(filteredNonLksContacts, key = { index, it -> "nonlks_${it.normalizedNumber}_${it.name}_$index" }) { _, contact ->
                        InviteContactItem(
                            contact = contact,
                            onInvite = { shareInvite(contact) },
                            onRingtoneClick = {
                                lastInteractionTime = System.currentTimeMillis()
                                ringtoneModalContact = Pair(contact.name, contact.phoneNumber)
                            }
                        )
                    }
                }
            }
        }

        // Contact Custom Ringtone Bottom Sheet
        ringtoneModalContact?.let { contactPair ->
            // Look up profile picture from synced contacts list
            val contactDto = syncedContacts.find { it.phoneNumber == contactPair.second }
            ContactRingtoneBottomSheet(
                contactName = contactPair.first,
                phoneNumber = contactPair.second,
                profilePicBase64 = contactDto?.profilePictureUrl ?: "",
                customRingtone = contactRingtoneState,
                isPreviewPlaying = isPreviewPlaying,
                onPreviewToggle = {
                    val targetUri = contactRingtoneState?.first ?: LksRingtoneManager.getAppRingtone(context).first
                    if (isPreviewPlaying) {
                        LksRingtoneManager.stopPreview()
                        isPreviewPlaying = false
                    } else {
                        isPreviewPlaying = LksRingtoneManager.playPreview(context, targetUri) {
                            isPreviewPlaying = false
                        }
                    }
                },
                onPickSystemRingtone = {
                    val currentUri = contactRingtoneState?.first ?: LksRingtoneManager.getAppRingtone(context).first
                    val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Ringtone for ${contactPair.first}")
                        putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, currentUri)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                    }
                    contactSystemRingtoneLauncher.launch(intent)
                },
                onPickAudioFile = {
                    contactAudioFileLauncher.launch(arrayOf("audio/*"))
                },
                onResetToDefault = {
                    LksRingtoneManager.stopPreview()
                    isPreviewPlaying = false
                    LksRingtoneManager.clearContactRingtone(context, contactPair.second)
                    contactRingtoneState = null
                    Toast.makeText(context, "Reverted to App Default Ringtone", Toast.LENGTH_SHORT).show()
                },
                onDismiss = {
                    LksRingtoneManager.stopPreview()
                    isPreviewPlaying = false
                    ringtoneModalContact = null
                }
            )
        }

        // Song Trimmer Dialog — shown after picking an audio file for contact ringtone
        trimmerUri?.let { uri ->
            com.example.ui.components.SongTrimmerDialog(
                uri = uri,
                songTitle = trimmerSongTitle,
                onSave = { trimmedUri ->
                    if (trimmerTarget == "contact") {
                        val contact = ringtoneModalContact
                        if (contact != null) {
                            LksRingtoneManager.setContactRingtone(context, contact.second, trimmedUri)
                            contactRingtoneState = LksRingtoneManager.getContactRingtone(context, contact.second)
                            Toast.makeText(context, "Custom ringtone set for ${contact.first}", Toast.LENGTH_SHORT).show()
                        }
                    }
                    trimmerUri = null
                },
                onDismiss = { trimmerUri = null }
            )
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    count: Int,
    accentColor: Color
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(accentColor)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "$title ($count)",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableContactItem(
    contact: ContactDto,
    isOnline: Boolean,
    demoOffset: Float,
    onAudioCall: () -> Unit,
    onVideoCall: () -> Unit,
    onRingtoneClick: () -> Unit
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

    val isDemoActive = demoOffset != 0f

    Box(modifier = Modifier.fillMaxWidth()) {
        if (isDemoActive) {
            // Visual background during automatic demo
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
                ContactItemRow(
                    contact = contact,
                    isOnline = isOnline,
                    onAudioCall = onAudioCall,
                    onVideoCall = onVideoCall,
                    onRingtoneClick = onRingtoneClick
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
                    ContactItemRow(
                        contact = contact,
                        isOnline = isOnline,
                        onAudioCall = onAudioCall,
                        onVideoCall = onVideoCall,
                        onRingtoneClick = onRingtoneClick
                    )
                }
            }
        }
    }
}

@Composable
private fun ContactItemRow(
    contact: ContactDto,
    isOnline: Boolean,
    onAudioCall: () -> Unit,
    onVideoCall: () -> Unit,
    onRingtoneClick: () -> Unit
) {
    val themeColor = LocalThemeColor.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onRingtoneClick() }
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar with online status badge
        Box {
            ContactAvatar(name = contact.name, profilePicBase64 = contact.profilePictureUrl)
            if (isOnline) {
                Box(
                    modifier = Modifier
                        .size(13.dp)
                        .clip(CircleShape)
                        .background(GreenCall)
                        .align(Alignment.BottomEnd)
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = contact.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                    color = themeColor.primary.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "LKS",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = themeColor.primary,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${contact.phoneNumber} • ${contact.statusMessage}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Custom Ringtone button
        IconButton(
            onClick = onRingtoneClick,
            modifier = Modifier.size(34.dp)
        ) {
            Icon(
                Icons.Default.MusicNote,
                contentDescription = "Custom Ringtone",
                tint = themeColor.primary.copy(alpha = 0.8f),
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(modifier = Modifier.width(2.dp))

        // Quick audio call shortcut
        IconButton(
            onClick = onAudioCall,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                Icons.Default.Call,
                contentDescription = "Audio Call",
                tint = GreenCall,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(2.dp))

        // Quick video call shortcut
        IconButton(
            onClick = onVideoCall,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                Icons.Default.Videocam,
                contentDescription = "Video Call",
                tint = themeColor.primary,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun InviteContactItem(
    contact: LocalContact,
    onInvite: () -> Unit,
    onRingtoneClick: () -> Unit
) {
    val themeColor = LocalThemeColor.current

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onRingtoneClick() }
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Initial Avatar
            ContactAvatar(name = contact.name, profilePicBase64 = "")

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = contact.name.ifBlank { "Contact" },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = contact.phoneNumber,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Custom Ringtone button
            IconButton(
                onClick = onRingtoneClick,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = "Custom Ringtone",
                    tint = themeColor.primary.copy(alpha = 0.8f),
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Sleek "Invite" Pill Button
            OutlinedButton(
                onClick = onInvite,
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = themeColor.primary
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, themeColor.primary.copy(alpha = 0.6f)),
                modifier = Modifier.height(34.dp)
            ) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = themeColor.primary
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "Invite",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = themeColor.primary
                )
            }
        }
    }
}

@Composable
private fun ContactAvatar(
    name: String,
    profilePicBase64: String,
    size: Int = 48
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
                .size(size.dp)
                .clip(CircleShape),
            contentScale = ContentScale.Crop
        )
    } else {
        Surface(
            modifier = Modifier.size(size.dp),
            shape = CircleShape,
            color = themeColor.primary.copy(alpha = 0.15f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = name.take(1).uppercase().ifBlank { "?" },
                    fontSize = (size * 0.375).sp,
                    fontWeight = FontWeight.Bold,
                    color = themeColor.primary
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactRingtoneBottomSheet(
    contactName: String,
    phoneNumber: String,
    profilePicBase64: String,
    customRingtone: Pair<Uri, String>?,
    isPreviewPlaying: Boolean,
    onPreviewToggle: () -> Unit,
    onPickSystemRingtone: () -> Unit,
    onPickAudioFile: () -> Unit,
    onResetToDefault: () -> Unit,
    onDismiss: () -> Unit
) {
    val themeColor = LocalThemeColor.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp)
        ) {
            // Header: Avatar (real photo or initials) + Contact Name + Number
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Use the same ContactAvatar that shows real profile photo on the contact list
                Box(modifier = Modifier.size(52.dp)) {
                    ContactAvatar(
                        name = contactName,
                        profilePicBase64 = profilePicBase64,
                        size = 52
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = contactName.ifBlank { "Contact" },
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = phoneNumber,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Current Ringtone Card
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (customRingtone != null) GreenCall.copy(alpha = 0.15f) else themeColor.primary.copy(alpha = 0.15f),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = if (customRingtone != null) GreenCall else themeColor.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (customRingtone != null) "Custom Ringtone" else "App Default Ringtone",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = if (customRingtone != null) GreenCall else themeColor.primary
                        )
                        Text(
                            text = customRingtone?.second ?: "Using default global ringtone",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Preview Toggle
                    IconButton(onClick = onPreviewToggle) {
                        Icon(
                            if (isPreviewPlaying) Icons.Default.StopCircle else Icons.Default.PlayCircle,
                            contentDescription = if (isPreviewPlaying) "Stop Preview" else "Play Preview",
                            tint = if (isPreviewPlaying) MaterialTheme.colorScheme.error else GreenCall,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Buttons: System Ringtone & Pick Song
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onPickSystemRingtone,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.NotificationsActive, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("System Ringtone", fontSize = 12.sp)
                }

                Button(
                    onClick = onPickAudioFile,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = themeColor.primary)
                ) {
                    Icon(Icons.Default.AudioFile, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Pick Song File", fontSize = 12.sp, color = Color.White)
                }
            }

            if (customRingtone != null) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = onResetToDefault,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text(
                        "Reset to App Default Ringtone",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}

