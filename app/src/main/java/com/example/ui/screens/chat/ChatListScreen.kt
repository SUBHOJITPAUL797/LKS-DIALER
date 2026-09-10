package com.example.ui.screens.chat

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
import com.example.data.model.ContactDto
import com.example.data.model.UserDto
import com.example.data.repository.ChatRepository
import com.example.data.repository.FirebaseManager
import com.example.ui.theme.GreenCall
import com.example.ui.theme.TealPrimary
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    firebaseManager: FirebaseManager,
    onOpenConversation: (phoneNumber: String, contactName: String) -> Unit
) {
    val context = LocalContext.current
    val chatRepository = remember { ChatRepository.getInstance(context) }
    val conversations by chatRepository.getConversationsFlow().collectAsState(initial = emptyList())
    val registeredUsers by firebaseManager.registeredUsers.collectAsState()
    val syncedContacts by firebaseManager.syncedContacts.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var showNewChatDialog by remember { mutableStateOf(false) }

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
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "LKS Chat",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = TealPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = GreenCall.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "E2EE",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = TealPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showNewChatDialog = true },
                containerColor = GreenCall,
                contentColor = Color.White,
                shape = CircleShape
            ) {
                Icon(Icons.Default.Chat, contentDescription = "New Chat")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search chats or contacts...") },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = "Search", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    unfocusedBorderColor = Color.Transparent,
                    focusedBorderColor = TealPrimary
                )
            )

            if (filteredConversations.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(
                            shape = CircleShape,
                            color = TealPrimary.copy(alpha = 0.1f),
                            modifier = Modifier.size(72.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.MarkChatRead,
                                    contentDescription = null,
                                    tint = TealPrimary,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (searchQuery.isBlank()) "No chats yet" else "No matching chats",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (searchQuery.isBlank())
                                "Your messages are end-to-end encrypted and deleted from the server once delivered."
                            else "Try searching for a different name or number.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = { showNewChatDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = GreenCall),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Start a New Chat")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredConversations, key = { it.phoneNumber }) { conv ->
                        val isPeerOnline = remember(registeredUsers, conv.phoneNumber) {
                            registeredUsers.find { com.example.util.ContactsHelper.numbersMatch(it.phoneNumber, conv.phoneNumber) }?.isOnline == true
                        }
                        ConversationItem(
                            conversation = conv,
                            isOnline = isPeerOnline,
                            onClick = { onOpenConversation(conv.phoneNumber, conv.contactName) }
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

    if (showNewChatDialog) {
        NewChatPickerModal(
            registeredUsers = registeredUsers,
            syncedContacts = syncedContacts,
            onUserSelected = { phone, name ->
                showNewChatDialog = false
                onOpenConversation(phone, name)
            },
            onDismiss = { showNewChatDialog = false }
        )
    }
}

@Composable
private fun ConversationItem(
    conversation: ConversationEntity,
    isOnline: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar with Online dot
        Box(modifier = Modifier.size(52.dp)) {
            if (conversation.profilePicUrl.isNotBlank()) {
                val imageModel = if (conversation.profilePicUrl.startsWith("data:image")) {
                    try {
                        val base64Data = conversation.profilePicUrl.substringAfter(",")
                        android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                    } catch (_: Exception) { conversation.profilePicUrl }
                } else conversation.profilePicUrl

                AsyncImage(
                    model = imageModel,
                    contentDescription = conversation.contactName,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentScale = ContentScale.Crop
                )
            } else {
                Surface(
                    shape = CircleShape,
                    color = TealPrimary.copy(alpha = 0.15f),
                    modifier = Modifier.size(52.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = conversation.contactName.take(1).uppercase(),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TealPrimary
                        )
                    }
                }
            }

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
                Text(
                    text = conversation.contactName,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (conversation.unreadCount > 0) FontWeight.Bold else FontWeight.SemiBold
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = formatChatTimestamp(conversation.lastMessageTimestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (conversation.unreadCount > 0) GreenCall else MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                    if (conversation.lastMessageIsOutgoing) {
                        StatusTickIcon(status = conversation.lastMessageStatus)
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = conversation.lastMessageText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (conversation.unreadCount > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (conversation.unreadCount > 0) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (conversation.unreadCount > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = GreenCall,
                        shape = CircleShape,
                        modifier = Modifier.height(20.dp).defaultMinSize(minWidth = 20.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 6.dp)
                        ) {
                            Text(
                                text = if (conversation.unreadCount > 99) "99+" else conversation.unreadCount.toString(),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = Color.White,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatusTickIcon(status: String) {
    val tickColor = when (status) {
        MessageStatus.READ.name -> Color(0xFF34B7F1) // WhatsApp blue
        MessageStatus.DELIVERED.name -> Color.Gray
        MessageStatus.SENT.name -> Color.Gray
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
    onUserSelected: (phone: String, name: String) -> Unit,
    onDismiss: () -> Unit
) {
    var manualPhone by remember { mutableStateOf("") }
    var filterQuery by remember { mutableStateOf("") }

    val eligibleContacts = remember(registeredUsers, syncedContacts, filterQuery) {
        val merged = mutableMapOf<String, String>() // normalizedPhone -> Name
        registeredUsers.forEach { user ->
            if (user.phoneNumber.isNotBlank()) {
                merged[user.phoneNumber] = user.displayName.ifBlank { user.phoneNumber }
            }
        }
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

            // Direct Number Input
            OutlinedTextField(
                value = manualPhone,
                onValueChange = { manualPhone = it.filter { ch -> ch.isDigit() || ch == '+' } },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Enter phone number with country code...") },
                leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null, tint = TealPrimary) },
                trailingIcon = {
                    if (manualPhone.length >= 7) {
                        IconButton(onClick = { onUserSelected(manualPhone, manualPhone) }) {
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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onUserSelected(phone, name) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = TealPrimary.copy(alpha = 0.12f),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = name.take(1).uppercase(),
                                    fontWeight = FontWeight.Bold,
                                    color = TealPrimary
                                )
                            }
                        }
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
