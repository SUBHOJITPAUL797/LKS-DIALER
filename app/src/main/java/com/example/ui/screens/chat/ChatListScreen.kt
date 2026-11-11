package com.example.ui.screens.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.local.ChatMediaType
import com.example.data.local.ConversationEntity
import com.example.data.local.MessageStatus
import com.example.data.model.CallType
import com.example.data.model.ContactDto
import com.example.data.model.UserDto
import com.example.data.repository.ChatRepository
import com.example.data.repository.FirebaseManager
import com.example.ui.theme.*
import com.example.ui.components.QuickStartGuideCard
import com.example.ui.components.QuickStartStep
import com.example.ui.components.TacticalStatusPill
import androidx.compose.foundation.BorderStroke
import kotlinx.coroutines.launch
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    firebaseManager: FirebaseManager,
    activeCallNumber: String? = null,
    activeCallType: CallType? = null,
    onOpenConversation: (phoneNumber: String, contactName: String, avatarUrl: String) -> Unit,
    onNavigateToStorage: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToLinkedDevices: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val chatRepository = remember { ChatRepository.getInstance(context) }
    val conversations by chatRepository.getConversationsFlow().collectAsState(initial = emptyList())
    val registeredUsers by firebaseManager.registeredUsers.collectAsState()
    val syncedContacts by firebaseManager.syncedContacts.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var showNewChatDialog by remember { mutableStateOf(false) }
    var showNewGroupDialog by remember { mutableStateOf(false) }
    var showOptionsMenu by remember { mutableStateOf(false) }
    var showClearAllConfirm by remember { mutableStateOf(false) }
    var selectedConversationForOptions by remember { mutableStateOf<ConversationEntity?>(null) }
    var showQuickCamera by remember { mutableStateOf(false) }
    var pendingCapturedFile by remember { mutableStateOf<java.io.File?>(null) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showQuickCamera = true
        } else {
            Toast.makeText(context, "Camera permission required", Toast.LENGTH_SHORT).show()
        }
    }

    val filteredConversations = remember(conversations, searchQuery) {
        if (searchQuery.isBlank()) conversations
        else {
            conversations.filter {
                it.contactName.contains(searchQuery, ignoreCase = true) ||
                it.phoneNumber.contains(searchQuery)
            }
        }
    }

    Scaffold(
        containerColor = HudBackgroundPrimary,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "SECURE CHATS",
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 19.sp,
                            color = HudTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        TacticalStatusPill(
                            text = "E2EE • P2P",
                            dotColor = CyberMint
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val hasPerm = androidx.core.content.ContextCompat.checkSelfPermission(
                                context,
                                android.Manifest.permission.CAMERA
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            if (hasPerm) {
                                showQuickCamera = true
                            } else {
                                cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                            }
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Default.CameraAlt,
                            contentDescription = "Open Camera",
                            tint = CyberMint
                        )
                    }
                    Box {
                        Surface(
                            onClick = { showOptionsMenu = true },
                            shape = CircleShape,
                            color = if (showOptionsMenu) SignalAmber.copy(alpha = 0.16f) else Color.Transparent,
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = SignalAmber)
                            }
                        }
                        val isDarkMenu = com.example.ui.theme.LocalIsDarkTheme.current
                        DropdownMenu(
                            expanded = showOptionsMenu,
                            onDismissRequest = { showOptionsMenu = false },
                            shape = RoundedCornerShape(22.dp),
                            containerColor = if (isDarkMenu) Color(0xFF182229) else Color.White,
                            tonalElevation = if (isDarkMenu) 8.dp else 2.dp,
                            shadowElevation = 14.dp,
                            border = BorderStroke(
                                1.dp,
                                if (isDarkMenu) Color.White.copy(alpha = 0.09f) else Color(0xFFE2E8F0)
                            ),
                            modifier = Modifier
                                .widthIn(min = 248.dp, max = 276.dp)
                                .padding(vertical = 4.dp)
                        ) {
                            WhatsAppStyledMenuItem(
                                title = "New group",
                                subtitle = "Create a group chat",
                                icon = Icons.Default.People,
                                iconTint = Color(0xFF059669),
                                onClick = {
                                    showOptionsMenu = false
                                    showNewGroupDialog = true
                                }
                            )
                            WhatsAppStyledMenuItem(
                                title = "Linked devices",
                                subtitle = "Pair or manage devices",
                                icon = Icons.Default.QrCodeScanner,
                                iconTint = Color(0xFF0284C7),
                                onClick = {
                                    showOptionsMenu = false
                                    onNavigateToLinkedDevices()
                                }
                            )
                            WhatsAppStyledMenuItem(
                                title = "Manage storage",
                                subtitle = "Inspect media & free space",
                                icon = Icons.Default.Storage,
                                iconTint = Color(0xFF9333EA),
                                onClick = {
                                    showOptionsMenu = false
                                    onNavigateToStorage()
                                }
                            )
                            WhatsAppStyledMenuItem(
                                title = "Chat wallpaper & theme",
                                subtitle = "Customize colors & backgrounds",
                                icon = Icons.Default.Wallpaper,
                                iconTint = Color(0xFFF59E0B),
                                onClick = {
                                    showOptionsMenu = false
                                    onNavigateToSettings()
                                }
                            )
                            HorizontalDivider(
                                color = if (isDarkMenu) Color.White.copy(alpha = 0.08f) else Color(0xFFE2E8F0),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                            WhatsAppStyledMenuItem(
                                title = "Clear all chats",
                                subtitle = "Remove all message history",
                                icon = Icons.Default.DeleteSweep,
                                iconTint = CrimsonAlert,
                                isDestructive = true,
                                onClick = {
                                    showOptionsMenu = false
                                    showClearAllConfirm = true
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = HudBackgroundPrimary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showNewChatDialog = true },
                containerColor = SignalAmber,
                contentColor = HudTextPrimary,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.defaultMinSize(minWidth = 56.dp, minHeight = 56.dp)
            ) {
                Icon(Icons.Default.Chat, contentDescription = "New Chat")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(HudBackgroundPrimary)
                .padding(innerPadding)
        ) {
            // ZERO CLUTTER RULE: Hide search bar when conversations is empty (0 items)
            if (conversations.isNotEmpty()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("Search chats or numbers...", color = HudTextSecondary) },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = "Search", tint = HudTextSecondary)
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = { searchQuery = "" },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search", tint = HudTextSecondary)
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = HudSurfaceContainer,
                        focusedContainerColor = HudSurfaceContainer,
                        unfocusedBorderColor = HudBorderSubtle,
                        focusedBorderColor = SignalAmber
                    )
                )
            }

            if (conversations.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.ChatBubbleOutline,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = HudTextSecondary.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "No conversations yet",
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = HudTextPrimary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Start an end-to-end encrypted chat with any contact",
                            fontSize = 13.sp,
                            color = HudTextSecondary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { showNewChatDialog = true },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CyberMint)
                        ) {
                            Icon(Icons.Default.AddComment, contentDescription = null, tint = Color(0xFF0A101E), modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Start New Chat",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF0A101E)
                            )
                        }
                    }
                }
            } else if (filteredConversations.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.SearchOff,
                            contentDescription = null,
                            tint = HudTextSecondary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No matching conversations",
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
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredConversations, key = { it.phoneNumber }) { conv ->
                        val matchedUser = remember(registeredUsers, conv.phoneNumber) {
                            registeredUsers.find { com.example.util.ContactsHelper.numbersMatch(it.phoneNumber, conv.phoneNumber) }
                        }
                        val matchedContact = remember(syncedContacts, conv.phoneNumber) {
                            syncedContacts.find { com.example.util.ContactsHelper.numbersMatch(it.phoneNumber, conv.phoneNumber) }
                        }
                        val isPeerOnline = com.example.data.repository.FirebaseManager.isUserOnline(matchedUser)
                        val resolvedAvatar = remember(conv.profilePicUrl, matchedUser, matchedContact) {
                            matchedUser?.profilePictureUrl?.takeIf { it.isNotBlank() }
                                ?: matchedContact?.profilePictureUrl?.takeIf { it.isNotBlank() }
                                ?: conv.profilePicUrl
                        }
                        val isInActiveCall = remember(activeCallNumber, conv.phoneNumber) {
                            activeCallNumber != null && com.example.util.ContactsHelper.numbersMatch(conv.phoneNumber, activeCallNumber)
                        }
                        ConversationItem(
                            conversation = conv,
                            avatarPic = resolvedAvatar,
                            isOnline = isPeerOnline,
                            isInActiveCall = isInActiveCall,
                            activeCallType = activeCallType,
                            onClick = { onOpenConversation(conv.phoneNumber, conv.contactName, resolvedAvatar) },
                            onLongClick = { selectedConversationForOptions = conv }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 76.dp, end = 16.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                    }
                }
            }
        }
    }

    if (showQuickCamera) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showQuickCamera = false },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            WhatsAppCameraScreen(
                onPhotoCaptured = { capturedFile ->
                    showQuickCamera = false
                    pendingCapturedFile = capturedFile
                    Toast.makeText(context, "Captured! Select a contact to send or dismiss to keep.", Toast.LENGTH_SHORT).show()
                    showNewChatDialog = true
                },
                onPhotosSelectedFromGallery = { files ->
                    showQuickCamera = false
                    val first = files.firstOrNull()
                    if (first != null) {
                        pendingCapturedFile = first
                        showNewChatDialog = true
                    }
                },
                onClose = { showQuickCamera = false }
            )
        }
    }

    if (showNewChatDialog) {
        NewChatPickerModal(
            registeredUsers = registeredUsers,
            syncedContacts = syncedContacts,
            onUserSelected = { phone, name, avatar ->
                showNewChatDialog = false
                val fileToSend = pendingCapturedFile
                pendingCapturedFile = null
                if (fileToSend != null) {
                    coroutineScope.launch {
                        val ext = fileToSend.extension.lowercase(Locale.getDefault())
                        val mediaType = if (ext in listOf("mp4", "mkv", "webm", "mov", "3gp")) {
                            ChatMediaType.DOCUMENT
                        } else {
                            ChatMediaType.IMAGE
                        }
                        chatRepository.sendMessage(
                            recipientNumber = phone,
                            recipientName = name,
                            text = if (mediaType == ChatMediaType.DOCUMENT) fileToSend.name else "",
                            mediaType = mediaType,
                            mediaFile = fileToSend
                        )
                    }
                }
                onOpenConversation(phone, name, avatar)
            },
            onOpenNewGroup = {
                showNewChatDialog = false
                showNewGroupDialog = true
            },
            onDismiss = {
                showNewChatDialog = false
                pendingCapturedFile = null
            }
        )
    }

    if (showNewGroupDialog) {
        NewGroupDialog(
            registeredUsers = registeredUsers,
            syncedContacts = syncedContacts,
            onCreateGroup = { name, selectedMembers, iconUrl ->
                showNewGroupDialog = false
                coroutineScope.launch {
                    val result = chatRepository.createGroup(name, selectedMembers, iconUrl)
                    result.onSuccess { group ->
                        onOpenConversation(group.groupId, group.name, group.iconUrl)
                    }.onFailure { err ->
                        Toast.makeText(context, "Failed to create group: ${err.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDismiss = { showNewGroupDialog = false }
        )
    }

    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            icon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Clear All Chats?") },
            text = { Text("Are you sure you want to delete all messages and media files across all chats from this device?") },
            confirmButton = {
                Button(
                    onClick = {
                        showClearAllConfirm = false
                        coroutineScope.launch {
                            chatRepository.clearAllChats()
                            Toast.makeText(context, "All chats cleared", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear All", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Conversation Options Bottom Sheet (Pin, Unpin, Delete)
    val selectedConv = selectedConversationForOptions
    if (selectedConv != null) {
        ModalBottomSheet(
            onDismissRequest = { selectedConversationForOptions = null }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            ) {
                // Pin / Unpin
                ListItem(
                    headlineContent = {
                        Text(if (selectedConv.isPinned) "Unpin chat" else "Pin chat")
                    },
                    leadingContent = {
                        Icon(
                            if (selectedConv.isPinned) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = null,
                            tint = if (selectedConv.isPinned) Color(0xFFFFC107) else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    modifier = Modifier.clickable {
                        val willPin = !selectedConv.isPinned
                        selectedConversationForOptions = null
                        coroutineScope.launch {
                            chatRepository.pinConversation(selectedConv.phoneNumber, willPin)
                            val msg = if (willPin) "Chat pinned to top" else "Chat unpinned"
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                )

                // Delete Conversation
                ListItem(
                    headlineContent = {
                        Text("Delete chat", color = MaterialTheme.colorScheme.error)
                    },
                    leadingContent = {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    },
                    modifier = Modifier.clickable {
                        val phoneToDelete = selectedConv.phoneNumber
                        selectedConversationForOptions = null
                        coroutineScope.launch {
                            chatRepository.deleteConversation(phoneToDelete)
                            Toast.makeText(context, "Chat deleted", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationItem(
    conversation: ConversationEntity,
    avatarPic: String,
    isOnline: Boolean,
    isInActiveCall: Boolean = false,
    activeCallType: CallType? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                }
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar with Online dot
        Box(modifier = Modifier.size(52.dp)) {
            ChatAvatar(
                name = conversation.contactName,
                profilePic = avatarPic,
                phoneNumber = conversation.phoneNumber,
                size = 52.dp,
                fontSize = 20.sp
            )

            // Green online indicator
            if (isOnline) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .align(Alignment.BottomEnd)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(2.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(GreenCall)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        // Name, preview, time, unread
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (conversation.isGroup) {
                        Icon(
                            Icons.Default.People,
                            contentDescription = "Group",
                            tint = TealPrimary,
                            modifier = Modifier
                                .size(18.dp)
                                .padding(end = 4.dp)
                        )
                    }
                    Text(
                        text = conversation.contactName,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = if (conversation.unreadCount > 0 || isInActiveCall) FontWeight.Bold else FontWeight.SemiBold
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (conversation.disappearingDuration > 0L) {
                        Icon(
                            Icons.Default.Schedule,
                            contentDescription = "Disappearing messages on",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                    }
                    if (conversation.isPinned) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = "Pinned",
                            tint = Color(0xFFFFC107),
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = if (isInActiveCall) "IN CALL" else formatChatTimestamp(conversation.lastMessageTimestamp),
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = if (isInActiveCall || conversation.unreadCount > 0) CyberMint else HudTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isInActiveCall) {
                        Icon(
                            imageVector = if (activeCallType == CallType.VIDEO) Icons.Default.Videocam else Icons.Default.Call,
                            contentDescription = null,
                            tint = CyberMint,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = if (activeCallType == CallType.VIDEO) "Video call • In call" else "Voice call • In call",
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = CyberMint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        if (conversation.lastMessageIsOutgoing) {
                            StatusTickIcon(status = conversation.lastMessageStatus)
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(
                            text = ChatRepository.extractCleanText(conversation.lastMessageText),
                            fontFamily = SpaceGroteskFontFamily,
                            fontSize = 13.sp,
                            color = if (conversation.unreadCount > 0) HudTextPrimary else HudTextSecondary,
                            fontWeight = if (conversation.unreadCount > 0) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (conversation.unreadCount > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = CyberMint,
                        shape = CircleShape,
                        modifier = Modifier.height(20.dp).defaultMinSize(minWidth = 20.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 6.dp)
                        ) {
                            Text(
                                text = if (conversation.unreadCount > 99) "99+" else conversation.unreadCount.toString(),
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                color = HudBackgroundPrimary,
                                fontSize = 10.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatusTickIcon(status: String, isOutgoingBubble: Boolean = false) {
    val tickColor = when (status) {
        MessageStatus.READ.name -> if (isOutgoingBubble) Color(0xFFB3E5FC) else Color(0xFF34B7F1)
        MessageStatus.DELIVERED.name -> if (isOutgoingBubble) Color(0xFFE2E8F0) else Color.Gray
        MessageStatus.SENT.name -> if (isOutgoingBubble) Color(0xFFCBD5E1) else Color.Gray
        else -> Color.LightGray
    }

    when (status) {
        MessageStatus.READ.name, MessageStatus.DELIVERED.name -> {
            Icon(
                Icons.Default.DoneAll,
                contentDescription = status,
                tint = tickColor,
                modifier = Modifier.size(16.dp)
            )
        }
        MessageStatus.SENT.name -> {
            Icon(
                Icons.Default.Check,
                contentDescription = status,
                tint = tickColor,
                modifier = Modifier.size(16.dp)
            )
        }
        MessageStatus.FAILED.name -> {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = "Failed",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp)
            )
        }
        else -> {
            Icon(
                Icons.Default.Schedule,
                contentDescription = "Pending",
                tint = Color.LightGray,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewChatPickerModal(
    registeredUsers: List<UserDto>,
    syncedContacts: List<ContactDto>,
    onUserSelected: (phone: String, name: String, avatarUrl: String) -> Unit,
    onOpenNewGroup: () -> Unit = {},
    onDismiss: () -> Unit
) {
    var manualPhone by remember { mutableStateOf("") }
    var filterQuery by remember { mutableStateOf("") }

    val eligibleContacts = remember(syncedContacts, filterQuery) {
        val merged = mutableMapOf<String, String>() // normalizedPhone -> Name
        syncedContacts.forEach { contact ->
            if (contact.phoneNumber.isNotBlank() && !merged.containsKey(contact.phoneNumber)) {
                merged[contact.phoneNumber] = contact.name.ifBlank { contact.phoneNumber }
            }
        }
        if (filterQuery.isBlank()) merged.toList()
        else merged.toList().filter { (phone, name) ->
            name.contains(filterQuery, ignoreCase = true) || phone.contains(filterQuery)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "Start New Chat",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = TealPrimary
            )
            Spacer(modifier = Modifier.height(14.dp))

            // Direct Number Input — no avatar available for manual entry
            OutlinedTextField(
                value = manualPhone,
                onValueChange = { manualPhone = it.filter { ch -> ch.isDigit() || ch == '+' } },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Enter phone number with country code...") },
                leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null, tint = TealPrimary) },
                trailingIcon = {
                    if (manualPhone.length >= 7) {
                        IconButton(onClick = { onUserSelected(manualPhone, manualPhone, "") }) {
                            Icon(Icons.Default.ArrowForward, contentDescription = "Start", tint = GreenCall)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Filter Contacts
            OutlinedTextField(
                value = filterQuery,
                onValueChange = { filterQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search your contacts...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // New Group Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onOpenNewGroup() }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    color = TealPrimary.copy(alpha = 0.15f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.People, contentDescription = null, tint = TealPrimary, modifier = Modifier.size(22.dp))
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text("New Group", fontWeight = FontWeight.Bold, color = TealPrimary, style = MaterialTheme.typography.titleMedium)
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Contacts (${eligibleContacts.size})",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 350.dp)
            ) {
                items(eligibleContacts, key = { it.first }) { (phone, name) ->
                    val contactUser = remember(phone, registeredUsers) {
                        registeredUsers.find { com.example.util.ContactsHelper.numbersMatch(it.phoneNumber, phone) }
                    }
                    val contactObj = remember(phone, syncedContacts) {
                        syncedContacts.find { com.example.util.ContactsHelper.numbersMatch(it.phoneNumber, phone) }
                    }
                    val pic = remember(contactUser, contactObj) {
                        contactUser?.profilePictureUrl?.ifBlank { null } ?: contactObj?.profilePictureUrl ?: ""
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onUserSelected(phone, name, pic) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ChatAvatar(
                            name = name,
                            profilePic = pic,
                            size = 40.dp,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(text = phone, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Default.ChatBubbleOutline, contentDescription = null, tint = GreenCall, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

fun formatChatTimestamp(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val now = Calendar.getInstance()
    val msgTime = Calendar.getInstance().apply { timeInMillis = timestamp }

    return when {
        now.get(Calendar.YEAR) == msgTime.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == msgTime.get(Calendar.DAY_OF_YEAR) -> {
            SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestamp))
        }
        now.get(Calendar.YEAR) == msgTime.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) - msgTime.get(Calendar.DAY_OF_YEAR) == 1 -> {
            "Yesterday"
        }
        else -> {
            SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(Date(timestamp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewGroupDialog(
    registeredUsers: List<UserDto>,
    syncedContacts: List<ContactDto>,
    onCreateGroup: (name: String, members: List<String>, iconUrl: String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var groupName by remember { mutableStateOf("") }
    var groupIconBase64 by remember { mutableStateOf<String?>(null) }
    val selectedMembers = remember { mutableStateListOf<String>() }
    var filterQuery by remember { mutableStateOf("") }

    val iconPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val base64 = com.example.util.ImageUtils.compressUriToBase64(context, uri)
            if (base64 != null) {
                groupIconBase64 = base64
            }
        }
    }

    val eligibleContacts = remember(syncedContacts, filterQuery) {
        val merged = mutableMapOf<String, String>() // normalizedPhone -> Name
        syncedContacts.forEach { contact ->
            if (contact.phoneNumber.isNotBlank() && !merged.containsKey(contact.phoneNumber)) {
                merged[contact.phoneNumber] = contact.name.ifBlank { contact.phoneNumber }
            }
        }
        if (filterQuery.isBlank()) merged.toList()
        else merged.toList().filter { (phone, name) ->
            name.contains(filterQuery, ignoreCase = true) || phone.contains(filterQuery)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "New Group",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = TealPrimary
                )
                Button(
                    onClick = {
                        if (groupName.isNotBlank() && selectedMembers.isNotEmpty()) {
                            onCreateGroup(groupName.trim(), selectedMembers.toList(), groupIconBase64 ?: "")
                        }
                    },
                    enabled = groupName.isNotBlank() && selectedMembers.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = GreenCall)
                ) {
                    Text("Create (${selectedMembers.size})", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Group Subject & Avatar Selector Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(TealPrimary.copy(alpha = 0.12f))
                        .clickable { iconPickerLauncher.launch("image/*") },
                    contentAlignment = Alignment.Center
                ) {
                    val iconBase64 = groupIconBase64
                    if (!iconBase64.isNullOrBlank()) {
                        val bitmap = remember(iconBase64) {
                            com.example.util.ImageUtils.decodeBase64ToImageBitmap(iconBase64)
                        }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = "Group icon",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(Icons.Default.CameraAlt, contentDescription = "Group icon", tint = TealPrimary)
                        }
                    } else {
                        Icon(
                            Icons.Default.CameraAlt,
                            contentDescription = "Add group photo",
                            tint = TealPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Group subject (e.g. Family, Team)...") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = filterQuery,
                onValueChange = { filterQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search contacts to add...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Add participants (${selectedMembers.size} selected)",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
            ) {
                items(eligibleContacts, key = { it.first }) { (phone, name) ->
                    val isSelected = selectedMembers.contains(phone)
                    val contactUser = remember(phone, registeredUsers) {
                        registeredUsers.find { com.example.util.ContactsHelper.numbersMatch(it.phoneNumber, phone) }
                    }
                    val contactObj = remember(phone, syncedContacts) {
                        syncedContacts.find { com.example.util.ContactsHelper.numbersMatch(it.phoneNumber, phone) }
                    }
                    val pic = remember(contactUser, contactObj) {
                        contactUser?.profilePictureUrl?.ifBlank { null } ?: contactObj?.profilePictureUrl ?: ""
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (isSelected) selectedMembers.remove(phone)
                                else selectedMembers.add(phone)
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ChatAvatar(
                            name = name,
                            profilePic = pic,
                            size = 40.dp,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(text = phone, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { checked ->
                                if (checked) selectedMembers.add(phone)
                                else selectedMembers.remove(phone)
                            },
                            colors = CheckboxDefaults.colors(checkedColor = TealPrimary)
                        )
                    }
                }
            }
        }
    }
}
