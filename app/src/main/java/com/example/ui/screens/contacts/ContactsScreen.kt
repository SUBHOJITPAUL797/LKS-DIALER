package com.example.ui.screens.contacts

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

    val totalContactsCount = syncedContacts.size + nonLksContacts.size

    fun shareInvite(contact: LocalContact) {
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
            },
            actions = {
                IconButton(onClick = { firebaseManager.syncNativeContacts() }) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Sync Contacts",
                        tint = themeColor.primary
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        )

        // Modern Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search name or number...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = themeColor.primary) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            shape = RoundedCornerShape(16.dp),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = themeColor.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            )
        )

        // Filter Pills Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedFilter == ContactFilterTab.ALL,
                onClick = { selectedFilter = ContactFilterTab.ALL },
                label = { Text("All (${filteredLksContacts.size + filteredNonLksContacts.size})") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = themeColor.primary,
                    selectedLabelColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp)
            )

            FilterChip(
                selected = selectedFilter == ContactFilterTab.ON_LKS,
                onClick = { selectedFilter = ContactFilterTab.ON_LKS },
                label = { Text("On LKS (${filteredLksContacts.size})") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = themeColor.primary,
                    selectedLabelColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp)
            )

            FilterChip(
                selected = selectedFilter == ContactFilterTab.INVITE,
                onClick = { selectedFilter = ContactFilterTab.INVITE },
                label = { Text("Invite (${filteredNonLksContacts.size})") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = themeColor.primary,
                    selectedLabelColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp)
            )
        }

        // Contact List Content
        val hasLks = filteredLksContacts.isNotEmpty() && (selectedFilter == ContactFilterTab.ALL || selectedFilter == ContactFilterTab.ON_LKS)
        val hasNonLks = filteredNonLksContacts.isNotEmpty() && (selectedFilter == ContactFilterTab.ALL || selectedFilter == ContactFilterTab.INVITE)

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
                        text = if (searchQuery.isBlank()) "No contacts found on device" else "No matching contacts found",
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

                    itemsIndexed(filteredLksContacts, key = { index, it -> "lks_${it.phoneNumber}_$index" }) { _, contact ->
                        val userInfo = registeredUsers.find { it.phoneNumber == contact.phoneNumber }
                        val isOnline = userInfo?.isOnline == true

                        SwipeableContactItem(
                            contact = contact.copy(
                                statusMessage = userInfo?.statusMessage ?: contact.statusMessage.ifBlank { "Available on LKS DIALER" }
                            ),
                            isOnline = isOnline,
                            onAudioCall = {
                                coroutineScope.launch {
                                    delay(200)
                                    onStartCall(contact.phoneNumber, contact.name, CallType.AUDIO)
                                }
                            },
                            onVideoCall = {
                                coroutineScope.launch {
                                    delay(200)
                                    onStartCall(contact.phoneNumber, contact.name, CallType.VIDEO)
                                }
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
                            onInvite = { shareInvite(contact) }
                        )
                    }
                }
            }
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
                text = title,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "$count",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
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

                Spacer(modifier = Modifier.width(4.dp))

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
    }
}

@Composable
private fun InviteContactItem(
    contact: LocalContact,
    onInvite: () -> Unit
) {
    val themeColor = LocalThemeColor.current

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
    profilePicBase64: String
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
                .size(48.dp)
                .clip(CircleShape),
            contentScale = ContentScale.Crop
        )
    } else {
        Surface(
            modifier = Modifier.size(48.dp),
            shape = CircleShape,
            color = themeColor.primary.copy(alpha = 0.15f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = name.take(1).uppercase().ifBlank { "?" },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = themeColor.primary
                )
            }
        }
    }
}
