package com.example.ui.screens.chat

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.example.data.local.ChatMediaType
import com.example.data.local.MessageEntity
import com.example.data.model.CallType
import com.example.data.repository.ChatRepository
import com.example.data.repository.FirebaseManager
import com.example.ui.theme.GreenCall
import com.example.ui.theme.TealPrimary
import com.example.util.ContactsHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatConversationScreen(
    peerPhoneNumber: String,
    peerDisplayName: String,
    firebaseManager: FirebaseManager,
    onBackClick: () -> Unit,
    onStartCall: (number: String, name: String, callType: CallType) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val chatRepository = remember { ChatRepository.getInstance(context) }
    val voiceHelper = remember { VoiceRecorderHelper(context) }

    val normPeer = remember(peerPhoneNumber) { ContactsHelper.normalizePhoneNumber(peerPhoneNumber) }
    val messages by chatRepository.getMessagesFlow(normPeer).collectAsState(initial = emptyList())
    val typingMap by chatRepository.typingStatus.collectAsState()
    val isPeerTyping = typingMap[normPeer] ?: false

    val registeredUsers by firebaseManager.registeredUsers.collectAsState()
    val syncedContacts by firebaseManager.contacts.collectAsState()
    val peerUser = remember(registeredUsers, normPeer) {
        registeredUsers.find { ContactsHelper.numbersMatch(it.phoneNumber, normPeer) }
    }
    val peerContact = remember(syncedContacts, normPeer) {
        syncedContacts.find { ContactsHelper.numbersMatch(it.phoneNumber, normPeer) }
    }
    val peerProfilePic = remember(peerUser, peerContact) {
        val userPic = peerUser?.profilePictureUrl ?: ""
        if (userPic.isNotBlank()) userPic else (peerContact?.profilePictureUrl ?: "")
    }
    val isPeerOnline = peerUser?.isOnline ?: false

    var inputText by remember { mutableStateOf("") }
    var selectedImagePreviewPath by remember { mutableStateOf<String?>(null) }
    var showOptionsMenu by remember { mutableStateOf(false) }

    val isRecording by voiceHelper.isRecording.collectAsState()
    val recordingDurationMs by voiceHelper.recordingDurationMs.collectAsState()
    val isPlaying by voiceHelper.isPlaying.collectAsState()
    val currentPlayingPath by voiceHelper.currentPlayingPath.collectAsState()
    val playbackProgress by voiceHelper.playbackProgress.collectAsState()

    val listState = rememberLazyListState()

    // Mark as active chat on open, clear on dispose
    DisposableEffect(normPeer) {
        chatRepository.setActiveChatPeer(normPeer)
        onDispose {
            chatRepository.setActiveChatPeer(null)
            voiceHelper.release()
        }
    }

    // Scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Image Picker Launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                try {
                    val tempFile = File(context.cacheDir, "picked_${System.currentTimeMillis()}.jpg")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                    }
                    if (tempFile.exists() && tempFile.length() > 0) {
                        chatRepository.sendMessage(
                            recipientNumber = normPeer,
                            recipientName = peerDisplayName,
                            text = "",
                            mediaType = ChatMediaType.IMAGE,
                            mediaFile = tempFile
                        )
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Failed to load image: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Mic Permission Launcher for Voice Notes
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            voiceHelper.startRecording()
        } else {
            Toast.makeText(context, "Microphone permission is required to record voice notes", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Avatar
                        ChatAvatar(
                            name = peerDisplayName,
                            profilePic = peerProfilePic,
                            size = 40.dp,
                            fontSize = 16.sp
                        )

                        Spacer(modifier = Modifier.width(10.dp))

                        // Name & Status Subtitle
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = peerDisplayName,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = when {
                                    isPeerTyping -> "typing..."
                                    isPeerOnline -> "online"
                                    else -> normPeer
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = when {
                                    isPeerTyping || isPeerOnline -> GreenCall
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Audio Call
                    IconButton(onClick = { onStartCall(normPeer, peerDisplayName, CallType.AUDIO) }) {
                        Icon(Icons.Default.Call, contentDescription = "Audio Call", tint = GreenCall)
                    }
                    // Video Call
                    IconButton(onClick = { onStartCall(normPeer, peerDisplayName, CallType.VIDEO) }) {
                        Icon(Icons.Default.Videocam, contentDescription = "Video Call", tint = TealPrimary)
                    }
                    // Overflow menu
                    Box {
                        IconButton(onClick = { showOptionsMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Options")
                        }
                        DropdownMenu(
                            expanded = showOptionsMenu,
                            onDismissRequest = { showOptionsMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Clear chat") },
                                onClick = {
                                    showOptionsMenu = false
                                    coroutineScope.launch {
                                        chatRepository.clearChat(normPeer)
                                        Toast.makeText(context, "Chat cleared", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Block contact") },
                                onClick = {
                                    showOptionsMenu = false
                                    firebaseManager.blockNumber(normPeer)
                                    Toast.makeText(context, "Contact blocked", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(if (isSystemInDarkTheme()) Color(0xFF0B141A) else Color(0xFFEFEAE2))
        ) {
            // Messages List
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item {
                    // E2EE Info banner
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            color = if (isSystemInDarkTheme()) Color(0xFF182229) else Color(0xFFFFF3C4),
                            shape = RoundedCornerShape(12.dp),
                            shadowElevation = 1.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Lock,
                                    contentDescription = null,
                                    modifier = Modifier.size(13.dp),
                                    tint = if (isSystemInDarkTheme()) Color(0xFFFFD279) else Color(0xFF856404)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Messages are End-to-End Encrypted and deleted from server once delivered.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isSystemInDarkTheme()) Color(0xFFFFD279) else Color(0xFF856404),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }

                items(messages, key = { it.id }) { msg ->
                    MessageBubble(
                        message = msg,
                        isPlaying = (isPlaying && currentPlayingPath == msg.mediaPath),
                        playbackProgress = if (currentPlayingPath == msg.mediaPath) playbackProgress else 0f,
                        onPlayAudio = { path -> voiceHelper.playAudio(path) },
                        onImageClick = { path -> selectedImagePreviewPath = path }
                    )
                }
            }

            // Bottom Input Bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isRecording) {
                        // Voice Recording UI
                        IconButton(
                            onClick = { voiceHelper.cancelRecording() },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Cancel", tint = MaterialTheme.colorScheme.error)
                        }

                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(Color.Red)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            val seconds = (recordingDurationMs / 1000) % 60
                            val minutes = (recordingDurationMs / 1000) / 60
                            Text(
                                text = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.Red
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Recording voice note...",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        IconButton(
                            onClick = {
                                val result = voiceHelper.stopRecording()
                                if (result != null) {
                                    val (audioFile, duration) = result
                                    coroutineScope.launch {
                                        chatRepository.sendMessage(
                                            recipientNumber = normPeer,
                                            recipientName = peerDisplayName,
                                            text = "",
                                            mediaType = ChatMediaType.AUDIO,
                                            mediaFile = audioFile,
                                            mediaDurationMs = duration
                                        )
                                    }
                                }
                            },
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(GreenCall)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send voice note", tint = Color.White)
                        }
                    } else {
                        // Standard Input UI
                        IconButton(onClick = { imagePickerLauncher.launch("image/*") }) {
                            Icon(Icons.Default.AttachFile, contentDescription = "Attach image", tint = TealPrimary)
                        }

                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { text ->
                                inputText = text
                                chatRepository.setTyping(normPeer, text.isNotBlank())
                            },
                            placeholder = { Text("Message...") },
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp),
                            shape = RoundedCornerShape(24.dp),
                            maxLines = 4,
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                unfocusedBorderColor = Color.Transparent,
                                focusedBorderColor = TealPrimary
                            )
                        )

                        if (inputText.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    val textToSend = inputText.trim()
                                    if (textToSend.isNotEmpty()) {
                                        inputText = ""
                                        chatRepository.setTyping(normPeer, false)
                                        coroutineScope.launch {
                                            chatRepository.sendMessage(
                                                recipientNumber = normPeer,
                                                recipientName = peerDisplayName,
                                                text = textToSend,
                                                mediaType = ChatMediaType.TEXT
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(GreenCall)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.White)
                            }
                        } else {
                            // Mic for voice note
                            IconButton(
                                onClick = {
                                    val hasMicPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                                        context,
                                        android.Manifest.permission.RECORD_AUDIO
                                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                                    if (hasMicPermission) {
                                        voiceHelper.startRecording()
                                    } else {
                                        micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                    }
                                },
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(TealPrimary)
                            ) {
                                Icon(Icons.Default.Mic, contentDescription = "Record Voice Note", tint = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }

    // Fullscreen Image Preview
    if (selectedImagePreviewPath != null) {
        Dialog(onDismissRequest = { selectedImagePreviewPath = null }) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.95f))
                    .clickable { selectedImagePreviewPath = null },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = selectedImagePreviewPath,
                    contentDescription = "Full Image Preview",
                    modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                    contentScale = ContentScale.Fit
                )
                IconButton(
                    onClick = { selectedImagePreviewPath = null },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: MessageEntity,
    isPlaying: Boolean,
    playbackProgress: Float,
    onPlayAudio: (path: String) -> Unit,
    onImageClick: (path: String) -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val isOutgoing = message.isOutgoing

    val bubbleColor = when {
        isOutgoing && isDark -> Color(0xFF005D4B) // WhatsApp dark green
        isOutgoing && !isDark -> Color(0xFFE7FFDB) // WhatsApp light green
        !isOutgoing && isDark -> Color(0xFF1F2C34) // WhatsApp dark incoming
        else -> Color.White // WhatsApp light incoming
    }

    val bubbleShape = if (isOutgoing) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        contentAlignment = if (isOutgoing) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Surface(
            color = bubbleColor,
            shape = bubbleShape,
            shadowElevation = 1.dp,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                // Media (Image)
                if (message.mediaType == ChatMediaType.IMAGE.name && !message.mediaPath.isNullOrBlank()) {
                    AsyncImage(
                        model = message.mediaPath,
                        contentDescription = "Photo",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onImageClick(message.mediaPath) },
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // Media (Audio Voice Note)
                if (message.mediaType == ChatMediaType.AUDIO.name && !message.mediaPath.isNullOrBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { onPlayAudio(message.mediaPath) },
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(if (isOutgoing) TealPrimary else GreenCall)
                        ) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Play/Pause",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            LinearProgressIndicator(
                                progress = { playbackProgress },
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                color = if (isOutgoing) TealPrimary else GreenCall,
                                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            val totalSeconds = (message.mediaDurationMs / 1000)
                            Text(
                                text = String.format(Locale.getDefault(), "%02d:%02d", totalSeconds / 60, totalSeconds % 60),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Text Content
                if (message.text.isNotBlank()) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                // Timestamp & Ticks
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val timeString = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(message.timestamp))
                    Text(
                        text = timeString,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                    if (isOutgoing) {
                        Spacer(modifier = Modifier.width(4.dp))
                        StatusTickIcon(status = message.status)
                    }
                }
            }
        }
    }
}

@Composable
fun ChatAvatar(
    name: String,
    profilePic: String,
    size: androidx.compose.ui.unit.Dp = 40.dp,
    fontSize: androidx.compose.ui.unit.TextUnit = 16.sp
) {
    val bitmap = remember(profilePic) {
        if (profilePic.isNotBlank() && !profilePic.startsWith("http")) {
            try {
                val cleanBase64 = if (profilePic.contains(",")) profilePic.substringAfter(",") else profilePic
                val decoded = Base64.decode(cleanBase64, Base64.DEFAULT)
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
    } else if (profilePic.startsWith("http")) {
        AsyncImage(
            model = profilePic,
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
            color = TealPrimary.copy(alpha = 0.15f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = name.take(1).uppercase().ifBlank { "?" },
                    fontSize = fontSize,
                    fontWeight = FontWeight.Bold,
                    color = TealPrimary
                )
            }
        }
    }
}

