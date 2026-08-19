package com.example.ui.screens.contacts

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
import com.example.data.model.CallType
import com.example.data.model.ContactDto
import com.example.data.repository.FirebaseManager
import com.example.ui.theme.GreenCall
import com.example.ui.theme.LocalThemeColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    firebaseManager: FirebaseManager,
    onStartCall: (number: String, name: String, callType: CallType) -> Unit
) {
    val themeColor = LocalThemeColor.current
    val syncedContacts by firebaseManager.syncedContacts.collectAsState()
    val registeredUsers by firebaseManager.registeredUsers.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    val filteredContacts = remember(syncedContacts, searchQuery) {
        if (searchQuery.isBlank()) syncedContacts else {
            syncedContacts.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                        it.phoneNumber.contains(searchQuery)
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
                    text = "LKS Contacts",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        )

        // Modern Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search synced contacts...") },
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
                .padding(horizontal = 16.dp, vertical = 6.dp),
            shape = RoundedCornerShape(16.dp),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = themeColor.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            )
        )

        if (filteredContacts.isEmpty()) {
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
                        text = if (searchQuery.isBlank()) "No LKS Dialer users found in your contacts" else "No matching contacts found",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(filteredContacts, key = { it.phoneNumber }) { contact ->
                    val userInfo = registeredUsers.find { it.phoneNumber == contact.phoneNumber }
                    val isOnline = userInfo?.isOnline == true

                    SwipeableContactItem(
                        contact = contact.copy(
                            statusMessage = userInfo?.statusMessage ?: "Available on LKS DIALER"
                        ),
                        isOnline = isOnline,
                        onAudioCall = {
                            coroutineScope.launch {
                                delay(300)
                                onStartCall(contact.phoneNumber, contact.name, CallType.AUDIO)
                            }
                        },
                        onVideoCall = {
                            coroutineScope.launch {
                                delay(300)
                                onStartCall(contact.phoneNumber, contact.name, CallType.VIDEO)
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
                    SwipeToDismissBoxValue.StartToEnd -> GreenCall.copy(alpha = 0.8f)
                    SwipeToDismissBoxValue.EndToStart -> themeColor.primary.copy(alpha = 0.8f)
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
        ContactItemContent(
            contact = contact,
            isOnline = isOnline,
            onAudioCall = onAudioCall,
            onVideoCall = onVideoCall
        )
    }
}

@Composable
private fun ContactItemContent(
    contact: ContactDto,
    isOnline: Boolean,
    onAudioCall: () -> Unit,
    onVideoCall: () -> Unit
) {
    val themeColor = LocalThemeColor.current
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                Surface(
                    modifier = Modifier.size(50.dp),
                    shape = CircleShape,
                    color = themeColor.primary.copy(alpha = 0.15f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = contact.name.take(1).uppercase(),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = themeColor.primary
                        )
                    }
                }
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
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
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
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "${contact.phoneNumber} • ${contact.statusMessage}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
