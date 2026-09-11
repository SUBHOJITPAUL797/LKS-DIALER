package com.example.ui.screens.chat

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.example.data.local.ChatMediaType
import com.example.data.local.MessageEntity
import com.example.data.local.MessageStatus
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
import kotlin.math.absoluteValue

// ──────────────────────────────────────────────────────────────────────────────
// Data class for reply context
// ──────────────────────────────────────────────────────────────────────────────
data class ReplyContext(
    val messageId: String,    // message.id is String in MessageEntity
    val text: String,         // preview text / "(Photo)" / "(Voice)"
    val senderLabel: String,  // "You" or peer display name
    val isOutgoing: Boolean   // direction of the ORIGINAL message being replied to
)

// ──────────────────────────────────────────────────────────────────────────────
// Main Screen
// ──────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatConversationScreen(
    peerPhoneNumber: String,
    peerDisplayName: String,
    peerInitialAvatar: String = "",   // pre-resolved avatar passed from ChatListScreen
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
    // ── Avatar resolution: use peerInitialAvatar immediately, then upgrade from registeredUsers/contacts ──
    val peerProfilePic = remember(peerUser, peerContact, peerInitialAvatar) {
        val userPic     = peerUser?.profilePictureUrl?.takeIf { it.isNotBlank() }
        val contactPic  = peerContact?.profilePictureUrl?.takeIf { it.isNotBlank() }
        // Priority: live Firestore user > synced contact > avatar passed from ChatListScreen
        userPic ?: contactPic ?: peerInitialAvatar
    }
    val isPeerOnline = peerUser?.isOnline ?: false

    var inputText by remember { mutableStateOf("") }
    var selectedImagePreviewPath by remember { mutableStateOf<String?>(null) }
    var showOptionsMenu by remember { mutableStateOf(false) }

    // Swipe-to-reply state
    var replyingTo by remember { mutableStateOf<ReplyContext?>(null) }
    // One-time swipe gesture hint — persisted via SharedPreferences
    val prefs = remember { context.getSharedPreferences("lks_chat_prefs", android.content.Context.MODE_PRIVATE) }
    val hintAlreadySeen = remember { prefs.getBoolean("swipe_hint_seen", false) }
    var showSwipeHint by remember { mutableStateOf(false) }

    val isRecording by voiceHelper.isRecording.collectAsState()
    val recordingDurationMs by voiceHelper.recordingDurationMs.collectAsState()
    val amplitudeSamples by voiceHelper.amplitudeSamples.collectAsState()
    val isPlaying by voiceHelper.isPlaying.collectAsState()
    val currentPlayingPath by voiceHelper.currentPlayingPath.collectAsState()
    val playbackProgress by voiceHelper.playbackProgress.collectAsState()

    var showAttachmentMenu by remember { mutableStateOf(false) }
    var imageToEditFile by remember { mutableStateOf<File?>(null) }
    var cameraTempFile by remember { mutableStateOf<File?>(null) }

    val listState = rememberLazyListState()

    // Mark as active chat on open, clear on dispose
    DisposableEffect(normPeer) {
        chatRepository.setActiveChatPeer(normPeer)
        onDispose {
            chatRepository.setTyping(normPeer, false)   // always clear typing on screen exit
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

    // Auto-clear typing after 3 seconds of no keystrokes
    LaunchedEffect(inputText) {
        if (inputText.isNotBlank()) {
            delay(3000)
            chatRepository.setTyping(normPeer, false)
        }
    }

    // Show swipe hint only once ever (persisted in SharedPreferences)
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && !hintAlreadySeen && !showSwipeHint) {
            delay(900)
            showSwipeHint = true
            prefs.edit().putBoolean("swipe_hint_seen", true).apply()
            delay(3500)
            showSwipeHint = false
        }
    }

    // Camera Launcher
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && cameraTempFile != null && cameraTempFile!!.exists() && cameraTempFile!!.length() > 0) {
            imageToEditFile = cameraTempFile
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            try {
                val tempFile = File(context.cacheDir, "camera_${System.currentTimeMillis()}.jpg")
                cameraTempFile = tempFile
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", tempFile)
                cameraLauncher.launch(uri)
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to start camera: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Camera permission is required", Toast.LENGTH_SHORT).show()
        }
    }

    // Gallery Picker Launcher (opens ImageEditorDialog before sending)
    val galleryPickerLauncher = rememberLauncherForActivityResult(
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
                        imageToEditFile = tempFile
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Failed to load image: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Document Picker Launcher (PDFs, Word docs, Excel, TXT, etc.)
    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                try {
                    var displayName = "document.pdf"
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (nameIdx != -1) displayName = cursor.getString(nameIdx) ?: "document.pdf"
                        }
                    }
                    val ext = displayName.substringAfterLast('.', "bin")
                    val tempFile = File(context.cacheDir, "doc_${System.currentTimeMillis()}.$ext")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                    }
                    if (tempFile.exists() && tempFile.length() > 0) {
                        chatRepository.sendMessage(
                            recipientNumber = normPeer,
                            recipientName = peerDisplayName,
                            text = displayName,
                            mediaType = ChatMediaType.DOCUMENT,
                            mediaFile = tempFile
                        )
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Failed to send document: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Mic Permission Launcher
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            voiceHelper.startRecording()
        } else {
            Toast.makeText(context, "Microphone permission is required", Toast.LENGTH_SHORT).show()
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
                        ChatAvatar(
                            name = peerDisplayName,
                            profilePic = peerProfilePic,
                            size = 40.dp,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
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
                    IconButton(onClick = { onStartCall(normPeer, peerDisplayName, CallType.AUDIO) }) {
                        Icon(Icons.Default.Call, contentDescription = "Audio Call", tint = GreenCall)
                    }
                    IconButton(onClick = { onStartCall(normPeer, peerDisplayName, CallType.VIDEO) }) {
                        Icon(Icons.Default.Videocam, contentDescription = "Video Call", tint = TealPrimary)
                    }
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
                // ── KEY FIX: push content up when the IME (soft keyboard) appears
                .imePadding()
                .background(if (isSystemInDarkTheme()) Color(0xFF0B141A) else Color(0xFFEFEAE2))
        ) {
            // ── Messages List ────────────────────────────────────────────────
            Box(modifier = Modifier.weight(1f)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
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
                        val myDisplayName = firebaseManager.currentUser.collectAsState().value?.displayName ?: "Me"
                        SwipeableMessageWrapper(
                            message = msg,
                            peerDisplayName = peerDisplayName,
                            myDisplayName = myDisplayName,
                            onReply = { replyCtx -> replyingTo = replyCtx }
                        ) {
                            MessageBubble(
                                message = msg,
                                isPlaying = (isPlaying && currentPlayingPath == msg.mediaPath),
                                playbackProgress = if (currentPlayingPath == msg.mediaPath) playbackProgress else 0f,
                                onPlayAudio = { path -> voiceHelper.playAudio(path) },
                                onImageClick = { path -> selectedImagePreviewPath = path },
                                onMarkMessageRead = { id -> chatRepository.markMessageRead(id, normPeer) }
                            )
                        }
                    }
                }

                // ── One-shot swipe hint overlay ──────────────────────────────
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                ) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showSwipeHint,
                        enter = fadeIn() + slideInVertically(initialOffsetY = { -20 }),
                        exit = fadeOut()
                    ) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.78f),
                            shape = RoundedCornerShape(24.dp),
                            shadowElevation = 4.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Reply,
                                    contentDescription = null,
                                    tint = Color(0xFFFFCC00),
                                    modifier = Modifier.size(20.dp)
                                )
                                Column {
                                    Text(
                                        "Swipe right on received messages to reply",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        "Swipe left on your own messages to reply",
                                        color = Color.White.copy(alpha = 0.75f),
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── Reply Preview Bar ────────────────────────────────────────────
            AnimatedVisibility(visible = replyingTo != null) {
                if (replyingTo != null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = if (isSystemInDarkTheme()) Color(0xFF1F2C34) else Color(0xFFE0F7FA),
                        tonalElevation = 2.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Accent bar
                            Box(
                                modifier = Modifier
                                    .width(4.dp)
                                    .height(40.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(if (replyingTo!!.isOutgoing) TealPrimary else GreenCall)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Replying to ${replyingTo!!.senderLabel}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = if (replyingTo!!.isOutgoing) TealPrimary else GreenCall
                                    )
                                )
                                Text(
                                    text = replyingTo!!.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = { replyingTo = null },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Cancel reply", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }

            // ── Bottom Input Bar ─────────────────────────────────────────────
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
                                .padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Pulsing recording indicator dot
                            val infiniteTransition = rememberInfiniteTransition(label = "rec_pulse")
                            val pulseAlpha by infiniteTransition.animateFloat(
                                initialValue = 0.35f,
                                targetValue = 1f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(550, easing = LinearEasing),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "pulse"
                            )
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(Color.Red.copy(alpha = pulseAlpha))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            val seconds = (recordingDurationMs / 1000) % 60
                            val minutes = (recordingDurationMs / 1000) / 60
                            Text(
                                text = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.Red
                            )
                            Spacer(modifier = Modifier.width(8.dp))

                            // Animated live audio waveform spikes
                            Canvas(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(28.dp)
                            ) {
                                val barWidth = 3.dp.toPx()
                                val barGap = 2.dp.toPx()
                                val totalBarWidth = barWidth + barGap
                                val maxBars = (size.width / totalBarWidth).toInt().coerceAtLeast(1)
                                val samples = amplitudeSamples.takeLast(maxBars)
                                val centerY = size.height / 2f

                                samples.forEachIndexed { index, amp ->
                                    val x = size.width - (samples.size - index) * totalBarWidth
                                    val barHeight = (size.height * amp.coerceIn(0.12f, 1f)).coerceAtLeast(4.dp.toPx())
                                    drawRoundRect(
                                        color = GreenCall,
                                        topLeft = androidx.compose.ui.geometry.Offset(x, centerY - barHeight / 2f),
                                        size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx(), 2.dp.toPx())
                                    )
                                }
                            }
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
                        // Standard Input UI: Attachment button opens options (Camera, Gallery, Document)
                        IconButton(onClick = { showAttachmentMenu = true }) {
                            Icon(Icons.Default.AttachFile, contentDescription = "Attach", tint = TealPrimary)
                        }

                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { text ->
                                inputText = text
                                chatRepository.setTyping(normPeer, text.isNotBlank())
                            },
                            placeholder = {
                                Text(
                                    if (replyingTo != null) "Reply to ${replyingTo!!.senderLabel}…"
                                    else "Message…"
                                )
                            },
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
                                    val currentReply = replyingTo
                                    if (textToSend.isNotEmpty()) {
                                        inputText = ""
                                        replyingTo = null
                                        chatRepository.setTyping(normPeer, false)
                                        coroutineScope.launch {
                                            // Embed reply metadata in message text as JSON if replying
                                            val payload = if (currentReply != null) {
                                                """{"text":${escapeJson(textToSend)},"replyTo":{"id":${currentReply.messageId},"text":${escapeJson(currentReply.text)},"senderLabel":${escapeJson(currentReply.senderLabel)}}}"""
                                            } else {
                                                textToSend
                                            }
                                            chatRepository.sendMessage(
                                                recipientNumber = normPeer,
                                                recipientName = peerDisplayName,
                                                text = payload,
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

    // ── Attachment Picker Bottom Sheet ───────────────────────────
    if (showAttachmentMenu) {
        ModalBottomSheet(
            onDismissRequest = { showAttachmentMenu = false },
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 36.dp, top = 8.dp)
            ) {
                Text(
                    text = "Share Content",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 20.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Camera
                    AttachmentOptionItem(
                        icon = Icons.Default.PhotoCamera,
                        label = "Camera",
                        backgroundColor = Color(0xFFE91E63),
                        onClick = {
                            showAttachmentMenu = false
                            val hasCamPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                                context,
                                android.Manifest.permission.CAMERA
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            if (hasCamPermission) {
                                try {
                                    val tempFile = File(context.cacheDir, "camera_${System.currentTimeMillis()}.jpg")
                                    cameraTempFile = tempFile
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", tempFile)
                                    cameraLauncher.launch(uri)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Failed to launch camera: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                            }
                        }
                    )

                    // Gallery
                    AttachmentOptionItem(
                        icon = Icons.Default.Image,
                        label = "Gallery",
                        backgroundColor = Color(0xFF9C27B0),
                        onClick = {
                            showAttachmentMenu = false
                            galleryPickerLauncher.launch("image/*")
                        }
                    )

                    // Document
                    AttachmentOptionItem(
                        icon = Icons.Default.InsertDriveFile,
                        label = "Document",
                        backgroundColor = Color(0xFF5E35B1),
                        onClick = {
                            showAttachmentMenu = false
                            documentPickerLauncher.launch(arrayOf(
                                "application/pdf",
                                "application/msword",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                "application/vnd.ms-excel",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                "text/plain",
                                "*/*"
                            ))
                        }
                    )
                }
            }
        }
    }

    // ── Image Editor Dialog (crop/rotate/draw/caption) ─────────────
    if (imageToEditFile != null) {
        ImageEditorDialog(
            imageFile = imageToEditFile!!,
            onDismiss = { imageToEditFile = null },
            onSendImage = { finalFile, caption ->
                val fileToSend = finalFile
                imageToEditFile = null
                coroutineScope.launch {
                    chatRepository.sendMessage(
                        recipientNumber = normPeer,
                        recipientName = peerDisplayName,
                        text = caption,
                        mediaType = ChatMediaType.IMAGE,
                        mediaFile = fileToSend
                    )
                }
            }
        )
    }
}

@Composable
private fun AttachmentOptionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    backgroundColor: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Surface(
            modifier = Modifier.size(56.dp),
            shape = CircleShape,
            color = backgroundColor
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = label, style = MaterialTheme.typography.labelMedium)
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Swipeable wrapper composable
// ──────────────────────────────────────────────────────────────────────────────
@Composable
private fun SwipeableMessageWrapper(
    message: MessageEntity,
    peerDisplayName: String,
    myDisplayName: String,
    onReply: (ReplyContext) -> Unit,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    // THRESHOLD in dp -> px
    val thresholdPx = with(density) { 64.dp.toPx() }
    val maxDragPx   = with(density) { 84.dp.toPx() }

    var offsetX by remember { mutableFloatStateOf(0f) }
    var triggered by remember { mutableStateOf(false) }

    // Icon opacity / scale based on progress
    val progress = (offsetX.absoluteValue / thresholdPx).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(message.id) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        triggered = false
                    },
                    onDragEnd = {
                        if (triggered) {
                            val previewText = when {
                                message.mediaType == ChatMediaType.IMAGE.name -> "📷 Photo"
                                message.mediaType == ChatMediaType.AUDIO.name -> "🎤 Voice message"
                                else -> run {
                                    // Strip JSON reply wrapper if present
                                    try {
                                        val obj = org.json.JSONObject(message.text)
                                        obj.optString("text", message.text)
                                    } catch (_: Exception) { message.text }
                                }
                            }
                            // Use absolute names so both sender and receiver see the correct name
                            val senderLabel = if (message.isOutgoing) myDisplayName else peerDisplayName
                            onReply(
                                ReplyContext(
                                    messageId = message.id,
                                    text = previewText,
                                    senderLabel = senderLabel,
                                    isOutgoing = message.isOutgoing
                                )
                            )
                        }
                        offsetX = 0f
                        triggered = false
                    },
                    onDragCancel = {
                        offsetX = 0f
                        triggered = false
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        // Incoming (isOutgoing=false) → right swipe → positive drag
                        // Outgoing (isOutgoing=true)  → left swipe  → negative drag
                        val correctDirection = if (message.isOutgoing) dragAmount < 0 else dragAmount > 0
                        if (!correctDirection) return@detectHorizontalDragGestures

                        val newOffset = offsetX + dragAmount
                        offsetX = newOffset.coerceIn(-maxDragPx, maxDragPx)

                        if (offsetX.absoluteValue >= thresholdPx && !triggered) {
                            triggered = true
                        }
                    }
                )
            }
    ) {
        // Reply icon — shows on the appropriate side
        if (!message.isOutgoing) {
            // Incoming: icon appears on the left as user swipes right
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 4.dp)
                    .size(36.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Reply,
                    contentDescription = "Reply",
                    tint = TealPrimary.copy(alpha = progress),
                    modifier = Modifier.size((16 + 8 * progress).dp)
                )
            }
        } else {
            // Outgoing: icon appears on the right as user swipes left
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp)
                    .size(36.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Reply,
                    contentDescription = "Reply",
                    tint = GreenCall.copy(alpha = progress),
                    modifier = Modifier
                        .size((16 + 8 * progress).dp)
                )
            }
        }

        // Message bubble, translated by drag
        Box(modifier = Modifier.offset(x = with(density) { offsetX.toDp() })) {
            content()
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Message Bubble
// ──────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: MessageEntity,
    isPlaying: Boolean,
    playbackProgress: Float,
    onPlayAudio: (path: String) -> Unit,
    onImageClick: (path: String) -> Unit,
    onMarkMessageRead: (messageId: String) -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val isOutgoing = message.isOutgoing

    val bubbleColor = when {
        isOutgoing && isDark  -> Color(0xFF005D4B)
        isOutgoing && !isDark -> Color(0xFFE7FFDB)
        !isOutgoing && isDark -> Color(0xFF1F2C34)
        else                   -> Color.White
    }

    val bubbleShape = if (isOutgoing) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp)
    }

    // Try to parse reply context from message text
    val parsedReply: Pair<String, String>? = remember(message.text) {
        if (message.mediaType == ChatMediaType.TEXT.name) {
            try {
                val obj = org.json.JSONObject(message.text)
                if (obj.has("replyTo")) {
                    val replyTo = obj.getJSONObject("replyTo")
                    Pair(replyTo.optString("senderLabel", ""), replyTo.optString("text", ""))
                } else null
            } catch (_: Exception) { null }
        } else null
    }

    val displayText = remember(message.text) {
        if (message.mediaType == ChatMediaType.TEXT.name) {
            try {
                val obj = org.json.JSONObject(message.text)
                obj.optString("text", message.text)
            } catch (_: Exception) { message.text }
        } else message.text
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

                // ── Reply Quote ──────────────────────────────────────────────
                if (parsedReply != null) {
                    val (senderLbl, replyText) = parsedReply
                    Surface(
                        color = if (isOutgoing)
                            TealPrimary.copy(alpha = 0.12f)
                        else
                            GreenCall.copy(alpha = 0.10f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .fillMaxHeight()
                                    .background(if (isOutgoing) TealPrimary else GreenCall)
                            )
                            Column(
                                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 2.dp)
                            ) {
                                if (senderLbl.isNotBlank()) {
                                    Text(
                                        senderLbl,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.ExtraBold,
                                            color = if (isOutgoing) TealPrimary else GreenCall
                                        )
                                    )
                                }
                                Text(
                                    replyText,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // ── Image (Dynamic aspect ratio so image is never cut off) ──
                if (message.mediaType == ChatMediaType.IMAGE.name && !message.mediaPath.isNullOrBlank()) {
                    val imageRatio = remember(message.mediaPath) {
                        try {
                            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(message.mediaPath, opts)
                            if (opts.outWidth > 0 && opts.outHeight > 0) {
                                (opts.outWidth.toFloat() / opts.outHeight.toFloat()).coerceIn(0.55f, 1.85f)
                            } else null
                        } catch (_: Exception) { null }
                    }

                    AsyncImage(
                        model = message.mediaPath,
                        contentDescription = "Photo",
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(imageRatio ?: 1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                if (!message.isOutgoing && message.status != MessageStatus.READ.name) {
                                    onMarkMessageRead(message.id)
                                }
                                onImageClick(message.mediaPath)
                            },
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // ── Audio Note ────────────────────────────────────────────────
                if (message.mediaType == ChatMediaType.AUDIO.name && !message.mediaPath.isNullOrBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                onPlayAudio(message.mediaPath)
                                if (!message.isOutgoing && message.status != MessageStatus.READ.name) {
                                    onMarkMessageRead(message.id)
                                }
                            },
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

                // ── Document Card ─────────────────────────────────────────────
                if (message.mediaType == ChatMediaType.DOCUMENT.name && !message.mediaPath.isNullOrBlank()) {
                    val context = LocalContext.current
                    val docFile = remember(message.mediaPath) { File(message.mediaPath) }
                    val ext = remember(docFile) { docFile.extension.uppercase(Locale.getDefault()).ifBlank { "DOC" } }
                    val fileSizeFormatted = remember(docFile) {
                        if (docFile.exists()) {
                            val bytes = docFile.length()
                            if (bytes < 1024) "$bytes B"
                            else if (bytes < 1024 * 1024) "${bytes / 1024} KB"
                            else String.format(Locale.getDefault(), "%.1f MB", bytes / (1024f * 1024f))
                        } else ""
                    }

                    Surface(
                        color = if (isOutgoing) TealPrimary.copy(alpha = 0.15f) else GreenCall.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (!message.isOutgoing && message.status != MessageStatus.READ.name) {
                                    onMarkMessageRead(message.id)
                                }
                                try {
                                    val fileUri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        docFile
                                    )
                                    val mime = android.webkit.MimeTypeMap.getSingleton()
                                        .getMimeTypeFromExtension(docFile.extension.lowercase(Locale.getDefault())) ?: "*/*"
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                        setDataAndType(fileUri, mime)
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "No app found to open $ext file", Toast.LENGTH_SHORT).show()
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                color = when (ext) {
                                    "PDF" -> Color(0xFFE53935)
                                    "DOC", "DOCX" -> Color(0xFF1E88E5)
                                    "XLS", "XLSX" -> Color(0xFF43A047)
                                    else -> TealPrimary
                                },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = ext.take(4),
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = displayText.ifBlank { docFile.name },
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (fileSizeFormatted.isNotBlank()) {
                                    Text(
                                        text = fileSizeFormatted,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                Icons.Default.FileDownload,
                                contentDescription = "Open Document",
                                tint = if (isOutgoing) TealPrimary else GreenCall,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // ── Text ─────────────────────────────────────────────────────
                if (displayText.isNotBlank() && message.mediaType != ChatMediaType.DOCUMENT.name) {
                    Text(
                        text = displayText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                // ── Timestamp & Ticks ────────────────────────────────────────
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

// ──────────────────────────────────────────────────────────────────────────────
// Avatar composable
// ──────────────────────────────────────────────────────────────────────────────
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

// ──────────────────────────────────────────────────────────────────────────────
// Helpers
// ──────────────────────────────────────────────────────────────────────────────
/** Minimal JSON string escaper — avoids needing Gson/Moshi for a simple string. */
private fun escapeJson(s: String): String {
    val sb = StringBuilder("\"")
    for (ch in s) {
        when (ch) {
            '"'  -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> sb.append(ch)
        }
    }
    sb.append("\"")
    return sb.toString()
}
