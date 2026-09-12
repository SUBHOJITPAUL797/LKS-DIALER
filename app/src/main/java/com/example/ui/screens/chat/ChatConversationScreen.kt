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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
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
import org.json.JSONObject
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
    var pageSize by remember { mutableIntStateOf(50) }
    val totalMessageCount by chatRepository.getMessageCountFlow(normPeer).collectAsState(initial = 0)
    val messages by remember(normPeer, pageSize) {
        chatRepository.getMessagesPagedFlow(normPeer, pageSize)
    }.collectAsState(initial = emptyList())
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
    var currentTimeTick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000L)
            currentTimeTick = System.currentTimeMillis()
        }
    }

    val isPeerOnline = remember(peerUser, currentTimeTick) {
        FirebaseManager.isUserOnline(peerUser)
    }

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
    val playbackSpeed by voiceHelper.playbackSpeed.collectAsState()
    val playbackDurationMs by voiceHelper.playbackDurationMs.collectAsState()

    // Active file transfer progress (P2P or relay): messageId → FileTransferProgress
    val activeTransfers by chatRepository.activeTransfers.collectAsState()

    var showAttachmentMenu by remember { mutableStateOf(false) }
    var showNativeCamera by remember { mutableStateOf(false) }
    var photosToPreview by remember { mutableStateOf<List<File>?>(null) }
    var selectedMessageForOptions by remember { mutableStateOf<MessageEntity?>(null) }
    var editingMessage by remember { mutableStateOf<MessageEntity?>(null) }

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

    // Auto-scroll to bottom (index 0 in reverseLayout) ONLY when a new message arrives and user is already near bottom
    var previousLatestMessageId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(messages.firstOrNull()?.id) {
        val currentLatestId = messages.firstOrNull()?.id
        if (previousLatestMessageId != null && currentLatestId != null && currentLatestId != previousLatestMessageId) {
            if (listState.firstVisibleItemIndex <= 2) {
                listState.animateScrollToItem(0)
            }
        }
        previousLatestMessageId = currentLatestId
    }

    // Lazy loading pagination trigger: when user scrolls up near the top of loaded messages
    val shouldLoadMore by remember {
        derivedStateOf {
            val totalLoaded = messages.size
            if (totalLoaded >= totalMessageCount || totalLoaded == 0) {
                false
            } else {
                val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                // When within 8 items of the end of current page
                lastVisibleIndex >= totalLoaded - 8
            }
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && messages.size < totalMessageCount) {
            pageSize = (pageSize + 50).coerceAtMost(totalMessageCount + 10)
        }
    }

    // Auto-mark conversation as read on screen open and whenever incoming messages exist
    LaunchedEffect(normPeer, messages) {
        if (messages.isNotEmpty()) {
            val hasUnread = messages.any { !it.isOutgoing && it.status != MessageStatus.READ.name }
            if (hasUnread) {
                chatRepository.markConversationAsRead(normPeer)
            }
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

    // Gallery Multiple Picker Launcher (WhatsApp multi-photo selection)
    val galleryMultipleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            coroutineScope.launch {
                try {
                    val files = mutableListOf<File>()
                    uris.forEach { uri ->
                        val tempFile = File(context.cacheDir, "gallery_${System.currentTimeMillis()}_${files.size}.jpg")
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                        }
                        if (tempFile.exists() && tempFile.length() > 0) {
                            files.add(tempFile)
                        }
                    }
                    if (files.isNotEmpty()) {
                        photosToPreview = files
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Failed to load images: ${e.message}", Toast.LENGTH_SHORT).show()
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
                        listState.animateScrollToItem(0)
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
                            val statusSubtitle = when {
                                isPeerTyping -> "typing..."
                                isPeerOnline -> "online"
                                peerUser != null && peerUser.lastSeen > 0L -> FirebaseManager.formatLastSeen(peerUser.lastSeen)
                                else -> normPeer
                            }
                            Text(
                                text = statusSubtitle,
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
                    reverseLayout = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Message items: index 0 is newest, rendered at the bottom!
                    items(messages, key = { it.id }) { msg ->
                        val myDisplayName = firebaseManager.currentUser.collectAsState().value?.displayName ?: "Me"
                        SwipeableMessageWrapper(
                            message = msg,
                            peerDisplayName = peerDisplayName,
                            myDisplayName = myDisplayName,
                            onReply = { replyCtx -> replyingTo = replyCtx }
                        ) {
                            val isMsgPlaying = isPlaying && currentPlayingPath != null &&
                                    (currentPlayingPath == msg.mediaPath || (msg.mediaPath?.let { File(it).absolutePath } == currentPlayingPath))
                            val msgProgress = if (isMsgPlaying) playbackProgress else 0f
                            val activeDurationMs = if (isMsgPlaying && playbackDurationMs > 0L) playbackDurationMs else msg.mediaDurationMs
                            MessageBubble(
                                message = msg,
                                isPlaying = isMsgPlaying,
                                playbackProgress = msgProgress,
                                playbackDurationMs = activeDurationMs,
                                playbackSpeed = playbackSpeed,
                                onCycleSpeed = { voiceHelper.cyclePlaybackSpeed() },
                                onSeekAudio = { ratio -> voiceHelper.seekTo(ratio) },
                                onPlayAudio = { path -> voiceHelper.playAudio(path) },
                                onImageClick = { path -> selectedImagePreviewPath = path },
                                onMarkMessageRead = { id -> chatRepository.markMessageRead(id, normPeer) },
                                onMessageLongClick = { selectedMessageForOptions = it },
                                transferProgress = activeTransfers[msg.id],
                                onCancelTransfer = { chatRepository.cancelTransfer(msg.id) }
                            )
                        }
                    }

                    // Top of conversation history (oldest items in reverseLayout)
                    if (messages.isEmpty()) {
                        item(key = "empty_e2ee_banner") {
                            E2eeInfoBanner()
                        }
                    } else if (messages.size < totalMessageCount) {
                        item(key = "pagination_loader") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp,
                                    color = TealPrimary
                                )
                            }
                        }
                    } else {
                        item(key = "e2ee_banner") {
                            E2eeInfoBanner()
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

            // ── Edit Preview Bar ──────────────────────────────────────────────
            AnimatedVisibility(visible = editingMessage != null) {
                if (editingMessage != null) {
                    val editPreviewText = remember(editingMessage!!.text) {
                        try {
                            val obj = JSONObject(editingMessage!!.text)
                            obj.optString("text", editingMessage!!.text)
                        } catch (_: Exception) { editingMessage!!.text }
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = if (isSystemInDarkTheme()) Color(0xFF1B2A32) else Color(0xFFE8F5E9),
                        tonalElevation = 2.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(4.dp)
                                    .height(40.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(GreenCall)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Editing message (10 min window)",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = GreenCall
                                    )
                                )
                                Text(
                                    text = editPreviewText,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = {
                                    editingMessage = null
                                    inputText = ""
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Cancel edit", modifier = Modifier.size(18.dp))
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
                                        listState.animateScrollToItem(0)
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

                        if (editingMessage != null) {
                            IconButton(
                                onClick = {
                                    val textToEdit = inputText.trim()
                                    if (textToEdit.isNotEmpty()) {
                                        val targetId = editingMessage!!.id
                                        editingMessage = null
                                        inputText = ""
                                        chatRepository.setTyping(normPeer, false)
                                        coroutineScope.launch {
                                            val res = chatRepository.editMessage(targetId, textToEdit, normPeer)
                                            if (res.isFailure) {
                                                Toast.makeText(context, res.exceptionOrNull()?.message ?: "Failed to edit message", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(GreenCall)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = "Save edit", tint = Color.White)
                            }
                        } else if (inputText.isNotBlank()) {
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
                                                """{"text":${escapeJson(textToSend)},"replyTo":{"id":${escapeJson(currentReply.messageId)},"text":${escapeJson(currentReply.text)},"senderLabel":${escapeJson(currentReply.senderLabel)}}}"""
                                            } else {
                                                textToSend
                                            }
                                            chatRepository.markConversationAsRead(normPeer)
                                            chatRepository.sendMessage(
                                                recipientNumber = normPeer,
                                                recipientName = peerDisplayName,
                                                text = payload,
                                                mediaType = ChatMediaType.TEXT
                                            )
                                            listState.animateScrollToItem(0)
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
                    // Camera (Native in-app CameraX just like WhatsApp)
                    AttachmentOptionItem(
                        icon = Icons.Default.PhotoCamera,
                        label = "Camera",
                        backgroundColor = Color(0xFFE91E63),
                        onClick = {
                            showAttachmentMenu = false
                            showNativeCamera = true
                        }
                    )

                    // Gallery (Multi-photo picker just like WhatsApp)
                    AttachmentOptionItem(
                        icon = Icons.Default.Image,
                        label = "Gallery",
                        backgroundColor = Color(0xFF9C27B0),
                        onClick = {
                            showAttachmentMenu = false
                            galleryMultipleLauncher.launch("image/*")
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

    // ── Native WhatsApp Camera Screen ─────────────────────────────
    if (showNativeCamera) {
        WhatsAppCameraScreen(
            onPhotoCaptured = { capturedFile ->
                showNativeCamera = false
                photosToPreview = listOf(capturedFile)
            },
            onPhotosSelectedFromGallery = { files ->
                showNativeCamera = false
                photosToPreview = files
            },
            onClose = { showNativeCamera = false }
        )
    }

    // ── WhatsApp Multi-Photo Review, Carousel & Captions ─────────
    if (photosToPreview != null && photosToPreview!!.isNotEmpty()) {
        WhatsAppMediaPreviewScreen(
            initialPhotos = photosToPreview!!,
            onSendPhotos = { results ->
                photosToPreview = null
                coroutineScope.launch {
                    results.forEach { (file, caption) ->
                        chatRepository.sendMessage(
                            recipientNumber = normPeer,
                            recipientName = peerDisplayName,
                            text = caption,
                            mediaType = ChatMediaType.IMAGE,
                            mediaFile = file
                        )
                    }
                    listState.animateScrollToItem(0)
                }
            },
            onClose = { photosToPreview = null }
        )
    }

    // ── Message Options Bottom Sheet (Reply, Copy, Edit, Delete) ──
    if (selectedMessageForOptions != null) {
        val targetMsg = selectedMessageForOptions!!
        val isDeleted = targetMsg.text.startsWith("🚫 ")
        val isEligibleForEdit = targetMsg.isOutgoing &&
                !isDeleted &&
                targetMsg.mediaType == ChatMediaType.TEXT.name &&
                (System.currentTimeMillis() - targetMsg.timestamp <= 10 * 60 * 1000L)

        ModalBottomSheet(
            onDismissRequest = { selectedMessageForOptions = null }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            ) {
                if (!isDeleted) {
                    // Reply
                    ListItem(
                        headlineContent = { Text("Reply") },
                        leadingContent = { Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null) },
                        modifier = Modifier.clickable {
                            val previewText = try {
                                val obj = org.json.JSONObject(targetMsg.text)
                                obj.optString("text", targetMsg.text)
                            } catch (_: Exception) { targetMsg.text }
                            replyingTo = ReplyContext(
                                messageId = targetMsg.id,
                                text = previewText,
                                senderLabel = if (targetMsg.isOutgoing) (firebaseManager.currentUser.value?.displayName ?: "You") else peerDisplayName,
                                isOutgoing = targetMsg.isOutgoing
                            )
                            selectedMessageForOptions = null
                        }
                    )

                    // Copy (if text)
                    if (targetMsg.mediaType == ChatMediaType.TEXT.name) {
                        val rawText = try {
                            val obj = org.json.JSONObject(targetMsg.text)
                            obj.optString("text", targetMsg.text)
                        } catch (_: Exception) { targetMsg.text }
                        ListItem(
                            headlineContent = { Text("Copy") },
                            leadingContent = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                            modifier = Modifier.clickable {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("Copied message", rawText)
                                clipboard?.setPrimaryClip(clip)
                                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                                selectedMessageForOptions = null
                            }
                        )
                    }

                    // Edit (if outgoing, text, and within 10 min)
                    if (isEligibleForEdit) {
                        val rawText = try {
                            val obj = org.json.JSONObject(targetMsg.text)
                            obj.optString("text", targetMsg.text)
                        } catch (_: Exception) { targetMsg.text }
                        ListItem(
                            headlineContent = { Text("Edit (10 min window)") },
                            leadingContent = { Icon(Icons.Default.Edit, contentDescription = null, tint = GreenCall) },
                            modifier = Modifier.clickable {
                                editingMessage = targetMsg
                                inputText = rawText
                                selectedMessageForOptions = null
                            }
                        )
                    }

                    // Delete for everyone (WhatsApp style: outgoing only, not already deleted)
                    if (targetMsg.isOutgoing) {
                        ListItem(
                            headlineContent = { Text("Delete for everyone", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) },
                            leadingContent = { Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            modifier = Modifier.clickable {
                                val idToDelete = targetMsg.id
                                selectedMessageForOptions = null
                                coroutineScope.launch {
                                    chatRepository.deleteMessageForEveryone(idToDelete, normPeer)
                                }
                            }
                        )
                    }
                }

                // Delete for me
                ListItem(
                    headlineContent = { Text("Delete for me", color = MaterialTheme.colorScheme.error) },
                    leadingContent = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.clickable {
                        val idToDelete = targetMsg.id
                        selectedMessageForOptions = null
                        coroutineScope.launch {
                            chatRepository.deleteMessage(idToDelete)
                        }
                    }
                )
            }
        }
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
    val coroutineScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    val thresholdPx = with(density) { 56.dp.toPx() }
    val maxDragPx   = with(density) { 76.dp.toPx() }

    val offsetX = remember { Animatable(0f) }
    var isTriggered by remember { mutableStateOf(false) }

    val progress = (offsetX.value.absoluteValue / thresholdPx).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(message.id) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        isTriggered = false
                    },
                    onDragEnd = {
                        if (isTriggered) {
                            val previewText = when {
                                message.mediaType == ChatMediaType.IMAGE.name -> "📷 Photo"
                                message.mediaType == ChatMediaType.AUDIO.name -> "🎤 Voice message"
                                message.mediaType == ChatMediaType.DOCUMENT.name -> "📄 ${message.text}"
                                else -> run {
                                    try {
                                        val obj = JSONObject(message.text)
                                        obj.optString("text", message.text)
                                    } catch (_: Exception) { message.text }
                                }
                            }
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
                        isTriggered = false
                        coroutineScope.launch {
                            offsetX.animateTo(
                                targetValue = 0f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessLow
                                )
                            )
                        }
                    },
                    onDragCancel = {
                        isTriggered = false
                        coroutineScope.launch {
                            offsetX.animateTo(
                                targetValue = 0f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessLow
                                )
                            )
                        }
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        val currentVal = offsetX.value
                        val newOffset = if (!message.isOutgoing) {
                            // Incoming: swipe right (positive), push back left towards 0 to cancel
                            (currentVal + dragAmount).coerceIn(0f, maxDragPx)
                        } else {
                            // Outgoing: swipe left (negative), push back right towards 0 to cancel
                            (currentVal + dragAmount).coerceIn(-maxDragPx, 0f)
                        }

                        val shouldTrigger = newOffset.absoluteValue >= thresholdPx
                        if (shouldTrigger && !isTriggered) {
                            isTriggered = true
                            try {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            } catch (_: Exception) {}
                        } else if (!shouldTrigger && isTriggered) {
                            // User pushed back! Cancel trigger!
                            isTriggered = false
                        }

                        coroutineScope.launch {
                            offsetX.snapTo(newOffset)
                        }
                    }
                )
            }
    ) {
        // Reply icon on the appropriate side
        if (!message.isOutgoing) {
            // Incoming: icon on the left
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 6.dp)
                    .size(36.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (isTriggered) TealPrimary.copy(alpha = 0.2f) else Color.Transparent,
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.AutoMirrored.Filled.Reply,
                            contentDescription = "Reply",
                            tint = if (isTriggered) TealPrimary else TealPrimary.copy(alpha = progress * 0.7f),
                            modifier = Modifier.size((16 + 6 * progress).dp)
                        )
                    }
                }
            }
        } else {
            // Outgoing: icon on the right
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 6.dp)
                    .size(36.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (isTriggered) GreenCall.copy(alpha = 0.2f) else Color.Transparent,
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.AutoMirrored.Filled.Reply,
                            contentDescription = "Reply",
                            tint = if (isTriggered) GreenCall else GreenCall.copy(alpha = progress * 0.7f),
                            modifier = Modifier.size((16 + 6 * progress).dp)
                        )
                    }
                }
            }
        }

        // Message bubble, translated smoothly by drag
        Box(
            modifier = Modifier.offset {
                androidx.compose.ui.unit.IntOffset(
                    x = offsetX.value.toInt(),
                    y = 0
                )
            }
        ) {
            content()
        }
    }
}

@Composable
private fun E2eeInfoBanner() {
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

// ──────────────────────────────────────────────────────────────────────────────
// Animated Audio Waveform Player with Scrubber & Speed Controls
// ──────────────────────────────────────────────────────────────────────────────
@Composable
private fun AudioWaveformPlayer(
    isPlaying: Boolean,
    progress: Float,
    durationMs: Long,
    playbackSpeed: Float,
    isOutgoing: Boolean,
    onTogglePlay: () -> Unit,
    onSeek: (Float) -> Unit,
    onCycleSpeed: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeColor = if (isOutgoing) TealPrimary else GreenCall
    val inactiveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)

    val infiniteTransition = rememberInfiniteTransition(label = "waveAnim")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.2831855f, // 2 * PI
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase"
    )

    Column(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onTogglePlay,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(activeColor)
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Play/Pause",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Waveform Canvas with interactive drag/seek
            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .height(30.dp)
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { change, _ ->
                                val ratio = (change.position.x / size.width).coerceIn(0f, 1f)
                                onSeek(ratio)
                            },
                            onDragStart = { offset ->
                                val ratio = (offset.x / size.width).coerceIn(0f, 1f)
                                onSeek(ratio)
                            }
                        )
                    }
            ) {
                val barCount = 28
                val barWidth = 3.dp.toPx()
                val totalWidth = size.width
                val spacing = (totalWidth - (barCount * barWidth)) / (barCount - 1).coerceAtLeast(1)

                for (i in 0 until barCount) {
                    val barRatio = i.toFloat() / (barCount - 1).toFloat()
                    val isPassed = barRatio <= progress

                    val harmonic = (kotlin.math.sin(i * 0.45) * 0.35 + 0.65).toFloat()
                    val bounce = if (isPlaying) {
                        (kotlin.math.sin(wavePhase + i * 0.4) * 0.3f + 1f).toFloat()
                    } else 1f

                    val barHeight = (size.height * 0.88f * harmonic * bounce).coerceIn(5.dp.toPx(), size.height)
                    val x = i * (barWidth + spacing)
                    val y = size.height - barHeight

                    drawRoundRect(
                        color = if (isPassed) activeColor else inactiveColor,
                        topLeft = Offset(x, y),
                        size = Size(barWidth, barHeight),
                        cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Speed Toggle Button
            Surface(
                onClick = onCycleSpeed,
                shape = RoundedCornerShape(8.dp),
                color = if (playbackSpeed > 1.0f) activeColor.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.padding(start = 2.dp)
            ) {
                Text(
                    text = "${if (playbackSpeed == 1.5f) "1.5" else playbackSpeed.toInt().toString()}x",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
                    color = if (playbackSpeed > 1.0f) activeColor else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                )
            }
        }

        // Timestamps & Playing Indicator
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp, start = 46.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val currentSec = if (durationMs > 0) ((progress * durationMs) / 1000).toLong() else 0L
            val totalSec = (durationMs / 1000)

            Text(
                text = String.format(Locale.getDefault(), "%02d:%02d", currentSec / 60, currentSec % 60),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (isPlaying) {
                Text(
                    text = "● Playing",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                    color = activeColor
                )
            }

            Text(
                text = if (totalSec > 0) String.format(Locale.getDefault(), "%02d:%02d", totalSec / 60, totalSec % 60) else "--:--",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
    playbackDurationMs: Long = 0L,
    playbackSpeed: Float = 1.0f,
    onCycleSpeed: () -> Unit = {},
    onSeekAudio: (Float) -> Unit = {},
    onPlayAudio: (path: String) -> Unit,
    onImageClick: (path: String) -> Unit,
    onMarkMessageRead: (messageId: String) -> Unit,
    onMessageLongClick: (message: MessageEntity) -> Unit = {},
    transferProgress: com.example.data.p2p.FileTransferProgress? = null,
    onCancelTransfer: () -> Unit = {}
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

    val isDeleted = message.text.startsWith("🚫 ")
    val isImageOnly = !isDeleted && message.mediaType == ChatMediaType.IMAGE.name && displayText.isBlank()
    val bubblePadding = if (isImageOnly) PaddingValues(4.dp) else PaddingValues(horizontal = 8.dp, vertical = 6.dp)

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
            modifier = Modifier
                .widthIn(max = 310.dp, min = if (message.mediaType == ChatMediaType.IMAGE.name && !isDeleted) 200.dp else 0.dp)
                .combinedClickable(
                    onClick = {
                        if (message.mediaType == ChatMediaType.TEXT.name) {
                            // normal tap on text message does nothing
                        }
                    },
                    onLongClick = { onMessageLongClick(message) }
                )
        ) {
            Column(modifier = Modifier.padding(bubblePadding)) {
                if (isDeleted) {
                    Row(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Block,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                } else {

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
                    val finalDuration = if (playbackDurationMs > 0L) playbackDurationMs else message.mediaDurationMs
                    AudioWaveformPlayer(
                        isPlaying = isPlaying,
                        progress = playbackProgress,
                        durationMs = finalDuration,
                        playbackSpeed = playbackSpeed,
                        isOutgoing = isOutgoing,
                        onTogglePlay = {
                            onPlayAudio(message.mediaPath)
                            if (!message.isOutgoing && message.status != MessageStatus.READ.name) {
                                onMarkMessageRead(message.id)
                            }
                        },
                        onSeek = onSeekAudio,
                        onCycleSpeed = onCycleSpeed
                    )
                }

                // ── Document Card ─────────────────────────────────────────────
                if (message.mediaType == ChatMediaType.DOCUMENT.name) {
                    val context = LocalContext.current
                    val docFile = remember(message.mediaPath) { message.mediaPath?.takeIf { it.isNotBlank() }?.let { File(it) } }
                    val hasFile = docFile?.exists() == true
                    val fileName = remember(displayText, docFile) {
                        displayText.ifBlank { docFile?.name ?: "Document" }
                    }
                    val ext = remember(fileName, docFile) {
                        (docFile?.extension?.ifBlank { null }
                            ?: fileName.substringAfterLast('.', "DOC"))
                            .uppercase(Locale.getDefault()).take(5)
                    }
                    val isAudio = ext in listOf("MP3", "M4A", "WAV", "AAC", "OGG", "FLAC", "OPUS")
                    val audioDurationMs = remember(docFile, hasFile, isAudio, message.mediaDurationMs) {
                        if (message.mediaDurationMs > 0L) message.mediaDurationMs
                        else if (isAudio && hasFile) {
                            try {
                                val retriever = android.media.MediaMetadataRetriever()
                                retriever.setDataSource(docFile!!.absolutePath)
                                val dur = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                                retriever.release()
                                dur
                            } catch (_: Exception) { 0L }
                        } else 0L
                    }
                    val finalAudioDuration = if (isPlaying && playbackDurationMs > 0L) playbackDurationMs else audioDurationMs

                    val fileSizeFormatted = remember(docFile, hasFile) {
                        if (docFile != null && hasFile) {
                            val bytes = docFile.length()
                            if (bytes < 1024) "$bytes B"
                            else if (bytes < 1024 * 1024) "${bytes / 1024} KB"
                            else String.format(Locale.getDefault(), "%.1f MB", bytes / (1024f * 1024f))
                        } else if (!isOutgoing && !hasFile) {
                            "Transferring document..."
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
                                if (docFile != null && hasFile) {
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
                                } else {
                                    Toast.makeText(context, "Document is preparing or saved elsewhere", Toast.LENGTH_SHORT).show()
                                }
                            }
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    color = when {
                                        isAudio -> Color(0xFFE91E63)
                                        ext == "PDF" -> Color(0xFFE53935)
                                        ext in listOf("DOC", "DOCX") -> Color(0xFF1E88E5)
                                        ext in listOf("XLS", "XLSX") -> Color(0xFF43A047)
                                        else -> TealPrimary
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = if (isAudio) "🎵" else ext.take(4),
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = if (isAudio) 16.sp else 11.sp
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = fileName,
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
                                    if (hasFile) Icons.Default.FileDownload else Icons.Default.AttachFile,
                                    contentDescription = if (hasFile) "Open Document" else "Document",
                                    tint = if (isOutgoing) TealPrimary else GreenCall,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            if (isAudio && hasFile) {
                                Spacer(modifier = Modifier.height(6.dp))
                                AudioWaveformPlayer(
                                    isPlaying = isPlaying,
                                    progress = playbackProgress,
                                    durationMs = finalAudioDuration,
                                    playbackSpeed = playbackSpeed,
                                    isOutgoing = isOutgoing,
                                    onTogglePlay = {
                                        onPlayAudio(docFile!!.absolutePath)
                                        if (!message.isOutgoing && message.status != MessageStatus.READ.name) {
                                            onMarkMessageRead(message.id)
                                        }
                                    },
                                    onSeek = onSeekAudio,
                                    onCycleSpeed = onCycleSpeed
                                )
                            }

                            // ── P2P / Relay Transfer Progress ───────────────
                            if (transferProgress != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                FileTransferProgressCard(
                                    progress = transferProgress,
                                    onCancel = onCancelTransfer
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // ── Text / Caption ───────────────────────────────────────────
                if (displayText.isNotBlank() && message.mediaType != ChatMediaType.DOCUMENT.name) {
                    Text(
                        text = displayText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
                }

                Spacer(modifier = Modifier.height(2.dp))

                // ── Timestamp & Ticks ────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(end = 4.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (message.isEdited && !isDeleted) {
                        Text(
                            text = "Edited",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.sp,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
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

// ──────────────────────────────────────────────────────────────────────────────
// FileTransferProgressCard — inline progress inside DOCUMENT bubble
// ──────────────────────────────────────────────────────────────────────────────
@Composable
private fun FileTransferProgressCard(
    progress: com.example.data.p2p.FileTransferProgress,
    onCancel: () -> Unit
) {
    val isP2p = progress.mode == com.example.data.p2p.TransferMode.P2P
    val badgeColor = if (isP2p) Color(0xFF00E5FF) else Color(0xFFFFB300)     // cyan for P2P, amber for Relay
    val badgeLabel = if (isP2p) "⚡ P2P Direct" else "☁ Relay"
    val statusText = when (progress.status) {
        com.example.data.p2p.TransferStatus.CONNECTING    -> "Connecting…"
        com.example.data.p2p.TransferStatus.TRANSFERRING  ->
            "${String.format("%.1f", progress.transferredBytes / 1048576f)} / ${String.format("%.1f", progress.totalBytes / 1048576f)} MB"
        com.example.data.p2p.TransferStatus.DONE          -> "✓ Done"
        com.example.data.p2p.TransferStatus.FAILED        -> "✗ Failed"
        com.example.data.p2p.TransferStatus.CANCELLED     -> "Cancelled"
    }

    val animatedProgress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = progress.percent / 100f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 400),
        label = "transfer_progress"
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Badge
            Surface(
                color = badgeColor.copy(alpha = 0.2f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = badgeLabel,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = badgeColor,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            // Show speed when transferring
            if (progress.status == com.example.data.p2p.TransferStatus.TRANSFERRING && progress.speedBytesPerSec > 0) {
                Text(
                    text = "${String.format("%.1f", progress.speedBytesPerSec / 1048576f)} MB/s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            // Cancel button
            if (progress.status == com.example.data.p2p.TransferStatus.CONNECTING ||
                progress.status == com.example.data.p2p.TransferStatus.TRANSFERRING) {
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Cancel transfer",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        val isConnecting = progress.status == com.example.data.p2p.TransferStatus.CONNECTING
        if (isConnecting) {
            // Indeterminate spinner while connecting
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = badgeColor,
                trackColor = badgeColor.copy(alpha = 0.2f)
            )
        } else {
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = badgeColor,
                trackColor = badgeColor.copy(alpha = 0.2f)
            )
        }
    }
}
