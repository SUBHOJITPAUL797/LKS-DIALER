package com.example.ui.screens.chat

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.util.LinkPreviewData
import com.example.util.LinkPreviewHelper
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.draw.blur
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.example.data.local.ChatMediaType
import com.example.data.local.ClearChatMode
import com.example.data.local.MessageEntity
import com.example.data.local.MessageStatus
import com.example.data.model.CallType
import com.example.data.repository.ChatRepository
import com.example.data.repository.FirebaseManager
import com.example.ui.components.ChatMediaGalleryDialog
import com.example.ui.components.ClearChatDialog
import com.example.ui.theme.GreenCall
import com.example.ui.theme.TealPrimary
import com.example.util.ChatWallpaperManager
import com.example.util.ContactsHelper
import com.example.util.WallpaperType
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
    initialSharedPhotos: List<File>? = null,
    initialSharedText: String? = null,
    initialSharedDocuments: List<Pair<File, String>>? = null,
    onSharedContentConsumed: () -> Unit = {},
    onBackClick: () -> Unit,
    onStartCall: (number: String, name: String, callType: CallType) -> Unit
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val coroutineScope = rememberCoroutineScope()
    val chatRepository = remember { ChatRepository.getInstance(context) }
    val voiceHelper = remember { VoiceRecorderHelper(context) }
    var showMediaPicker by remember { mutableStateOf(false) }

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

    var inputText by remember { mutableStateOf(if (initialSharedPhotos.isNullOrEmpty()) (initialSharedText ?: "") else "") }
    var dismissedTypingUrl by remember { mutableStateOf<String?>(null) }
    val currentTypingUrl = remember(inputText) { LinkPreviewHelper.extractFirstUrl(inputText) }
    var typingPreviewData by remember { mutableStateOf<com.example.util.LinkPreviewData?>(null) }

    LaunchedEffect(currentTypingUrl, dismissedTypingUrl) {
        if (currentTypingUrl == null || currentTypingUrl == dismissedTypingUrl) {
            typingPreviewData = null
        } else {
            val cached = LinkPreviewHelper.getCachedPreview(currentTypingUrl)
            if (cached != null) {
                typingPreviewData = cached
            }
            LinkPreviewHelper.getPreviewFlow(currentTypingUrl).collect { preview ->
                if (currentTypingUrl != dismissedTypingUrl) {
                    typingPreviewData = preview
                }
            }
        }
    }
    var selectedImagePreviewPath by remember { mutableStateOf<String?>(null) }
    var selectedVideoPreviewFile by remember { mutableStateOf<File?>(null) }
    var showOptionsMenu by remember { mutableStateOf(false) }
    var showClearChatDialog by remember { mutableStateOf(false) }
    var showMediaGalleryDialog by remember { mutableStateOf(false) }
    var showBlockConfirmDialog by remember { mutableStateOf(false) }
    var showSpamConfirmDialog by remember { mutableStateOf(false) }
    var showStarredMessagesSheet by remember { mutableStateOf(false) }  // Starred messages viewer
    var showDisappearingDialog by remember { mutableStateOf(false) }
    val disappearingDuration by chatRepository.getDisappearingDurationFlow(normPeer).collectAsState(initial = 0L)
    val blockedNumbers by firebaseManager.blockedNumbers.collectAsState()
    val spamNumbers by firebaseManager.spamNumbers.collectAsState()
    val isBlocked = remember(normPeer, blockedNumbers, spamNumbers) {
        firebaseManager.isNumberBlocked(normPeer)
    }
    val wallpaperManager = remember { ChatWallpaperManager.getInstance(context) }
    val wallpaperConfig by wallpaperManager.config.collectAsState()

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
    var photosToPreview by remember { mutableStateOf<List<File>?>(initialSharedPhotos) }
    var selectedMessageForOptions by remember { mutableStateOf<MessageEntity?>(null) }
    var editingMessage by remember { mutableStateOf<MessageEntity?>(null) }

    val listState = rememberLazyListState()

    // ── In-Chat Message Search state ──────────────────────────────────────────
    var isSearching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var currentSearchIdx by remember { mutableIntStateOf(0) }
    val searchFocusRequester = remember { FocusRequester() }

    BackHandler(enabled = isSearching) {
        isSearching = false
        searchQuery = ""
        currentSearchIdx = 0
    }

    val matchingIndices = remember(messages, searchQuery) {
        if (searchQuery.isBlank()) emptyList<Int>()
        else {
            val q = searchQuery.trim().lowercase()
            messages.mapIndexedNotNull { index, msg ->
                val textToSearch = when {
                    msg.mediaType == ChatMediaType.TEXT.name -> {
                        try {
                            val obj = org.json.JSONObject(msg.text)
                            obj.optString("text", msg.text)
                        } catch (_: Exception) { msg.text }
                    }
                    msg.mediaType == ChatMediaType.DOCUMENT.name -> msg.text
                    msg.mediaType == ChatMediaType.IMAGE.name -> {
                        if (msg.text.startsWith("[gif:") || msg.text.startsWith("http")) "" else msg.text
                    }
                    else -> msg.text
                }
                if (textToSearch.lowercase().contains(q)) index else null
            }
        }
    }

    // Auto-scroll to active search match
    LaunchedEffect(currentSearchIdx, matchingIndices) {
        if (matchingIndices.isNotEmpty() && currentSearchIdx in matchingIndices.indices) {
            val targetIdx = matchingIndices[currentSearchIdx]
            listState.animateScrollToItem(targetIdx)
        }
    }

    LaunchedEffect(isSearching) {
        if (isSearching) {
            delay(150)
            try {
                searchFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    // Handle shared text/link consumption (when not attached to photos)
    LaunchedEffect(initialSharedText, initialSharedPhotos) {
        if (!initialSharedText.isNullOrBlank() && initialSharedPhotos.isNullOrEmpty()) {
            inputText = initialSharedText
            onSharedContentConsumed()
        }
    }

    // Handle shared incoming photos
    LaunchedEffect(initialSharedPhotos) {
        if (!initialSharedPhotos.isNullOrEmpty()) {
            photosToPreview = initialSharedPhotos
        }
    }

    // Handle shared documents/videos/files: auto-send
    LaunchedEffect(initialSharedDocuments) {
        if (!initialSharedDocuments.isNullOrEmpty()) {
            coroutineScope.launch {
                initialSharedDocuments.forEach { (file, docName) ->
                    try {
                        chatRepository.sendMessage(
                            recipientNumber = normPeer,
                            recipientName = peerDisplayName,
                            text = docName,
                            mediaType = ChatMediaType.DOCUMENT,
                            mediaFile = file
                        )
                    } catch (_: Exception) {}
                }
                onSharedContentConsumed()
                listState.animateScrollToItem(0)
            }
        }
    }

    // Mark as active chat on open, clear on dispose
    DisposableEffect(normPeer) {
        chatRepository.setActiveChatPeer(normPeer)
        chatRepository.setIsAtBottom(true)
        // Dismiss any existing notification for this chat
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(normPeer.hashCode())
        } catch (_: Exception) {}
        onDispose {
            chatRepository.setTyping(normPeer, false)   // always clear typing on screen exit
            chatRepository.setActiveChatPeer(null)
            chatRepository.setIsAtBottom(false)
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

    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()

    val isNearBottom by remember {
        derivedStateOf { listState.firstVisibleItemIndex <= 1 }
    }

    LaunchedEffect(isNearBottom) {
        chatRepository.setIsAtBottom(isNearBottom)
    }

    // Auto-mark conversation as read on screen open and whenever incoming messages exist,
    // BUT ONLY WHEN the activity is RESUMED, screen is physically ON, UNLOCKED, AND user is near bottom!
    LaunchedEffect(normPeer, messages, lifecycleState, isNearBottom) {
        if (lifecycleState.isAtLeast(Lifecycle.State.RESUMED) && isNearBottom) {
            val isActivelyWatching = chatRepository.isUserInConversation(normPeer)
            if (isActivelyWatching && messages.isNotEmpty()) {
                val hasUnread = messages.any { !it.isOutgoing && it.status != MessageStatus.READ.name }
                if (hasUnread) {
                    chatRepository.markConversationAsRead(normPeer)
                }
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
            if (isSearching) {
                TopAppBar(
                    title = {
                        TextField(
                            value = searchQuery,
                            onValueChange = {
                                searchQuery = it
                                currentSearchIdx = 0
                            },
                            placeholder = {
                                Text(
                                    text = "Search messages...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(searchFocusRequester)
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            isSearching = false
                            searchQuery = ""
                            currentSearchIdx = 0
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search")
                        }
                    },
                    actions = {
                        if (searchQuery.isNotBlank()) {
                            Text(
                                text = if (matchingIndices.isNotEmpty()) "${currentSearchIdx + 1}/${matchingIndices.size}" else "0/0",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                            IconButton(
                                onClick = {
                                    if (matchingIndices.isNotEmpty()) {
                                        currentSearchIdx = (currentSearchIdx + 1) % matchingIndices.size
                                    }
                                },
                                enabled = matchingIndices.isNotEmpty()
                            ) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Previous match (older)")
                            }
                            IconButton(
                                onClick = {
                                    if (matchingIndices.isNotEmpty()) {
                                        currentSearchIdx = if (currentSearchIdx - 1 < 0) matchingIndices.size - 1 else currentSearchIdx - 1
                                    }
                                },
                                enabled = matchingIndices.isNotEmpty()
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Next match (newer)")
                            }
                            IconButton(onClick = {
                                searchQuery = ""
                                currentSearchIdx = 0
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            } else {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showMediaGalleryDialog = true }
                        ) {
                            ChatAvatar(
                                name = peerDisplayName,
                                profilePic = peerProfilePic,
                                size = 40.dp,
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = peerDisplayName,
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (disappearingDuration > 0L) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Icon(
                                            Icons.Default.Schedule,
                                            contentDescription = "Disappearing messages active",
                                            tint = TealPrimary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
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
                        IconButton(onClick = { isSearching = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Search messages")
                        }
                        var lastCallClickTime by remember { mutableStateOf(0L) }
                        IconButton(onClick = {
                            if (isBlocked) {
                                Toast.makeText(context, "Unblock $peerDisplayName to make a call", Toast.LENGTH_SHORT).show()
                                return@IconButton
                            }
                            val now = System.currentTimeMillis()
                            if (now - lastCallClickTime > 1500L) {
                                lastCallClickTime = now
                                onStartCall(normPeer, peerDisplayName, CallType.AUDIO)
                            }
                        }) {
                            Icon(Icons.Default.Call, contentDescription = "Audio Call", tint = if (isBlocked) Color.Gray else GreenCall)
                        }
                        IconButton(onClick = {
                            if (isBlocked) {
                                Toast.makeText(context, "Unblock $peerDisplayName to make a call", Toast.LENGTH_SHORT).show()
                                return@IconButton
                            }
                            val now = System.currentTimeMillis()
                            if (now - lastCallClickTime > 1500L) {
                                lastCallClickTime = now
                                onStartCall(normPeer, peerDisplayName, CallType.VIDEO)
                            }
                        }) {
                            Icon(Icons.Default.Videocam, contentDescription = "Video Call", tint = if (isBlocked) Color.Gray else TealPrimary)
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
                                    text = { Text("Search") },
                                    onClick = {
                                        showOptionsMenu = false
                                        isSearching = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Media, Links & Docs") },
                                    onClick = {
                                        showOptionsMenu = false
                                        showMediaGalleryDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Starred messages") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Star,
                                            contentDescription = null,
                                            tint = Color(0xFFFFC107)
                                        )
                                    },
                                    onClick = {
                                        showOptionsMenu = false
                                        showStarredMessagesSheet = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Disappearing messages") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Schedule,
                                            contentDescription = null,
                                            tint = if (disappearingDuration > 0L) TealPrimary else MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    onClick = {
                                        showOptionsMenu = false
                                        showDisappearingDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Clear chat") },
                                    onClick = {
                                        showOptionsMenu = false
                                        showClearChatDialog = true
                                    }
                                )
                                if (isBlocked) {
                                    DropdownMenuItem(
                                        text = { Text("Unblock contact") },
                                        leadingIcon = { Icon(Icons.Default.CheckCircle, contentDescription = null, tint = TealPrimary) },
                                        onClick = {
                                            showOptionsMenu = false
                                            firebaseManager.unblockNumber(normPeer)
                                            firebaseManager.unmarkSpam(normPeer)
                                            Toast.makeText(context, "Contact unblocked", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                } else {
                                    DropdownMenuItem(
                                        text = { Text("Block contact") },
                                        leadingIcon = { Icon(Icons.Default.Block, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                        onClick = {
                                            showOptionsMenu = false
                                            showBlockConfirmDialog = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Report spam") },
                                        leadingIcon = { Icon(Icons.Default.Report, contentDescription = null, tint = Color(0xFFFF9800)) },
                                        onClick = {
                                            showOptionsMenu = false
                                            showSpamConfirmDialog = true
                                        }
                                    )
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        }
    ) { innerPadding ->
        val customWallpaperBitmap = remember(wallpaperConfig) {
            if (wallpaperConfig.type == WallpaperType.CUSTOM) {
                wallpaperManager.getCustomWallpaperBitmap()
            } else null
        }

        val presetTheme = remember(wallpaperConfig) {
            if (wallpaperConfig.type == WallpaperType.PRESET) {
                ChatWallpaperManager.PRESETS.find { it.id == wallpaperConfig.presetId } ?: ChatWallpaperManager.PRESETS.first()
            } else null
        }

        val isDark = isSystemInDarkTheme()

        val wallpaperModifier = when (wallpaperConfig.type) {
            WallpaperType.CUSTOM -> {
                if (customWallpaperBitmap != null) {
                    Modifier.drawBehind {
                        val bmp = customWallpaperBitmap
                        val canvasWidth = size.width
                        val canvasHeight = size.height
                        val bmpWidth = bmp.width.toFloat()
                        val bmpHeight = bmp.height.toFloat()
                        val scale = maxOf(canvasWidth / bmpWidth, canvasHeight / bmpHeight)
                        val scaledW = bmpWidth * scale
                        val scaledH = bmpHeight * scale
                        val left = (canvasWidth - scaledW) / 2f
                        val top = (canvasHeight - scaledH) / 2f

                        drawImage(
                            image = customWallpaperBitmap.asImageBitmap(),
                            dstOffset = IntOffset(left.toInt(), top.toInt()),
                            dstSize = IntSize(scaledW.toInt(), scaledH.toInt())
                        )
                        drawRect(color = Color.Black.copy(alpha = wallpaperConfig.dimAlpha))
                    }
                } else {
                    Modifier.background(if (isDark) Color(0xFF0B141A) else Color(0xFFEFEAE2))
                }
            }
            WallpaperType.PRESET -> {
                if (presetTheme != null) {
                    Modifier.background(Brush.verticalGradient(presetTheme.gradientColors))
                } else {
                    Modifier.background(if (isDark) Color(0xFF0B141A) else Color(0xFFEFEAE2))
                }
            }
            WallpaperType.DEFAULT -> {
                Modifier.background(if (isDark) Color(0xFF0B141A) else Color(0xFFEFEAE2))
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // ── KEY FIX: push content up when the IME (soft keyboard) appears
                .imePadding()
                .then(wallpaperModifier)
        ) {
            // ── Messages List ────────────────────────────────────────────────
            Box(modifier = Modifier.weight(1f)) {
                val currentMatchedMessageId = if (matchingIndices.isNotEmpty() && currentSearchIdx in matchingIndices.indices) {
                    messages.getOrNull(matchingIndices[currentSearchIdx])?.id
                } else null

                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Message items: index 0 is newest, rendered at the bottom!
                    itemsIndexed(messages, key = { _, msg -> msg.id }) { index, msg ->
                        val myDisplayName = firebaseManager.currentUser.collectAsState().value?.displayName ?: "Me"
                        val isMsgMatched = isSearching && searchQuery.isNotBlank() && matchingIndices.contains(index)
                        val isCurrentMsg = isMsgMatched && msg.id == currentMatchedMessageId

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
                                onVideoClick = { file -> selectedVideoPreviewFile = file },
                                onMarkMessageRead = { id -> chatRepository.markMessageRead(id, normPeer) },
                                onMessageLongClick = { selectedMessageForOptions = it },
                                transferProgress = activeTransfers[msg.id],
                                onCancelTransfer = { chatRepository.cancelTransfer(msg.id) },
                                isSearchMatch = isMsgMatched,
                                isCurrentSearchMatch = isCurrentMsg
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

                // ── Scroll to Bottom Floating Button with Unread Counter ──────
                val isScrolledUp by remember {
                    derivedStateOf { listState.firstVisibleItemIndex > 1 }
                }
                val unreadBelowCount by remember {
                    derivedStateOf {
                        if (listState.firstVisibleItemIndex <= 0) 0
                        else {
                            val belowItems = messages.take(listState.firstVisibleItemIndex)
                            belowItems.count { !it.isOutgoing && it.status != MessageStatus.READ.name }
                        }
                    }
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = isScrolledUp,
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut(),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 12.dp)
                ) {
                    Box(contentAlignment = Alignment.TopEnd) {
                        Surface(
                            onClick = {
                                coroutineScope.launch {
                                    listState.animateScrollToItem(0)
                                    chatRepository.markConversationAsRead(normPeer)
                                }
                            },
                            shape = CircleShape,
                            color = if (isSystemInDarkTheme()) Color(0xFF232D36) else Color.White,
                            shadowElevation = 6.dp,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isSystemInDarkTheme()) Color(0xFF3B4A54) else Color(0xFFCBD5E1)
                            ),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.KeyboardDoubleArrowDown,
                                    contentDescription = "Scroll to bottom",
                                    tint = if (isSystemInDarkTheme()) Color(0xFF8696A0) else Color(0xFF54656F),
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        // WhatsApp-style green unread count badge
                        if (unreadBelowCount > 0) {
                            Surface(
                                color = Color(0xFF25D366),
                                shape = CircleShape,
                                shadowElevation = 3.dp,
                                modifier = Modifier
                                    .offset(x = 6.dp, y = (-6).dp)
                                    .sizeIn(minWidth = 20.dp, minHeight = 20.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = if (unreadBelowCount > 99) "99+" else unreadBelowCount.toString(),
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        textAlign = TextAlign.Center
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

            // ── Live Typing Link Preview Banner (WhatsApp style) ───────────────
            AnimatedVisibility(
                visible = typingPreviewData != null && currentTypingUrl != null && currentTypingUrl != dismissedTypingUrl,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                typingPreviewData?.let { preview ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                        tonalElevation = 2.dp,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Thumbnail or Link Icon
                            if (!preview.imageUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = preview.imageUrl,
                                    contentDescription = "Thumbnail",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(52.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(52.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surface),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Link,
                                        contentDescription = null,
                                        tint = GreenCall,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            // Title and Domain
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = preview.title?.ifBlank { null } ?: preview.domain.ifBlank { preview.url },
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = preview.description?.ifBlank { null } ?: preview.domain.ifBlank { preview.url },
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Link,
                                        contentDescription = null,
                                        tint = GreenCall,
                                        modifier = Modifier.size(11.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = preview.domain.ifBlank { "link" },
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 10.sp,
                                            color = GreenCall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    )
                                }
                            }

                            // Dismiss 'X' Button
                            IconButton(
                                onClick = {
                                    dismissedTypingUrl = currentTypingUrl
                                    typingPreviewData = null
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Dismiss link preview",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
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
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (isBlocked) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Block,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "You blocked this contact.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        firebaseManager.unblockNumber(normPeer)
                                        firebaseManager.unmarkSpam(normPeer)
                                        Toast.makeText(context, "Contact unblocked", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Text("Unblock", color = TealPrimary, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    } else {
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
                            // Emoji / Sticker / GIF Keyboard Toggle
                            IconButton(
                                onClick = {
                                    if (showMediaPicker) {
                                        showMediaPicker = false
                                        keyboardController?.show()
                                    } else {
                                        keyboardController?.hide()
                                        showMediaPicker = true
                                    }
                                }
                            ) {
                                Text(
                                    text = if (showMediaPicker) "⌨️" else "😊",
                                    fontSize = 22.sp
                                )
                            }

                            // Standard Input UI: Attachment button opens options (Camera, Gallery, Document)
                            IconButton(onClick = { showAttachmentMenu = true }) {
                                Icon(Icons.Default.AttachFile, contentDescription = "Attach", tint = TealPrimary)
                            }

                            OutlinedTextField(
                                value = inputText,
                                onValueChange = { text ->
                                    inputText = text
                                    chatRepository.setTyping(normPeer, text.isNotBlank())
                                    if (showMediaPicker) showMediaPicker = false
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
                                            showMediaPicker = false
                                            dismissedTypingUrl = null
                                            chatRepository.setTyping(normPeer, false)
                                            coroutineScope.launch {
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

                // ── Animated Emoji / Sticker / GIF Media Keyboard ─────────
                AnimatedVisibility(
                    visible = showMediaPicker,
                    enter = expandVertically(animationSpec = tween(250)) + fadeIn(),
                    exit = shrinkVertically(animationSpec = tween(200)) + fadeOut()
                ) {
                    ChatMediaKeyboardPicker(
                        onEmojiSelected = { emoji ->
                            inputText += emoji
                            chatRepository.setTyping(normPeer, true)
                        },
                        onBackspace = {
                            if (inputText.isNotEmpty()) {
                                inputText = dropLastCodePoint(inputText)
                                chatRepository.setTyping(normPeer, inputText.isNotBlank())
                            }
                        },
                        onStickerSelected = { stickerCode, stickerName, stickerUrl ->
                            coroutineScope.launch {
                                chatRepository.sendSticker(
                                    recipientNumber = normPeer,
                                    recipientName = peerDisplayName,
                                    stickerCode = stickerCode.ifBlank { "✨" },
                                    stickerName = stickerName,
                                    stickerUrl = stickerUrl.takeIf { it.startsWith("http") }
                                )
                                listState.animateScrollToItem(0)
                            }
                        },
                        onGifSelected = { gifUrl, gifTitle ->
                            coroutineScope.launch {
                                chatRepository.sendGif(
                                    recipientNumber = normPeer,
                                    recipientName = peerDisplayName,
                                    gifUrl = gifUrl,
                                    gifTitle = gifTitle
                                )
                                listState.animateScrollToItem(0)
                            }
                        }
                    )
                }
            }
        }
    }
}

    // Clear Chat Granular Dialog
    if (showClearChatDialog) {
        var storageBytes by remember { mutableStateOf(0L) }
        LaunchedEffect(Unit) {
            try {
                val ranked = chatRepository.getRankedChatStorageList()
                val match = ranked.find { ContactsHelper.numbersMatch(it.phoneNumber, normPeer) }
                storageBytes = match?.totalBytes ?: 0L
            } catch (_: Exception) {}
        }
        ClearChatDialog(
            peerDisplayName = peerDisplayName,
            currentStorageBytes = storageBytes,
            onDismiss = { showClearChatDialog = false },
            onConfirmClear = { mode ->
                coroutineScope.launch {
                    val freed = chatRepository.clearChat(normPeer, mode)
                    val freedStr = if (freed > 0) {
                        val mb = freed / (1024.0 * 1024.0)
                        if (mb >= 1.0) String.format("%.1f MB", mb) else "${freed / 1024} KB"
                    } else "0 KB"
                    when (mode) {
                        ClearChatMode.MEDIA_ONLY -> Toast.makeText(context, "Media cleared! Freed $freedStr", Toast.LENGTH_SHORT).show()
                        ClearChatMode.TEXT_ONLY -> Toast.makeText(context, "Text messages cleared", Toast.LENGTH_SHORT).show()
                        ClearChatMode.BOTH -> Toast.makeText(context, "Chat and media cleared! Freed $freedStr", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    // Block Contact Confirmation Dialog
    if (showBlockConfirmDialog) {
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
            title = { Text("Block $peerDisplayName?") },
            text = {
                Text("Blocked contacts will no longer be able to call you or send you messages.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        firebaseManager.blockNumber(normPeer)
                        Toast.makeText(context, "Contact blocked", Toast.LENGTH_SHORT).show()
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

    // Report Spam Confirmation Dialog
    if (showSpamConfirmDialog) {
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
            title = { Text("Report Spam & Block?") },
            text = {
                Text("Report $peerDisplayName ($normPeer) as spam and block them from calling or messaging you.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        firebaseManager.reportSpam(normPeer)
                        Toast.makeText(context, "Reported as spam and blocked", Toast.LENGTH_SHORT).show()
                        showSpamConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                ) {
                    Text("Report Spam", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSpamConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Media, Links and Docs Gallery Dialog
    if (showMediaGalleryDialog) {
        ChatMediaGalleryDialog(
            messages = messages,
            peerDisplayName = peerDisplayName,
            onDismissRequest = { showMediaGalleryDialog = false },
            onSelectImage = { path ->
                showMediaGalleryDialog = false
                selectedImagePreviewPath = path
            },
            onSelectVideo = { file ->
                showMediaGalleryDialog = false
                selectedVideoPreviewFile = file
            }
        )
    }

    // Starred Messages Dialog
    if (showStarredMessagesSheet) {
        com.example.ui.components.ChatStarredMessagesDialog(
            messages = messages,
            peerDisplayName = peerDisplayName,
            onDismissRequest = { showStarredMessagesSheet = false },
            onUnstarMessage = { msgId ->
                coroutineScope.launch {
                    chatRepository.unstarMessage(msgId)
                    Toast.makeText(context, "Message unstarred", Toast.LENGTH_SHORT).show()
                }
            },
            onSelectMessage = { msg ->
                showStarredMessagesSheet = false
                val idx = messages.indexOfFirst { it.id == msg.id }
                if (idx != -1) {
                    coroutineScope.launch {
                        listState.animateScrollToItem(idx)
                    }
                } else if (msg.mediaType == ChatMediaType.IMAGE.name && msg.mediaPath != null) {
                    selectedImagePreviewPath = msg.mediaPath
                }
            }
        )
    }

    // Disappearing Messages Selection Dialog
    if (showDisappearingDialog) {
        val options = listOf(
            0L to "Off",
            24 * 60 * 60 * 1000L to "24 hours",
            7 * 24 * 60 * 60 * 1000L to "7 days",
            90 * 24 * 60 * 60 * 1000L to "90 days"
        )
        AlertDialog(
            onDismissRequest = { showDisappearingDialog = false },
            icon = {
                Icon(
                    Icons.Default.Schedule,
                    contentDescription = null,
                    tint = TealPrimary,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text("Disappearing Messages", fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    Text(
                        text = "For more privacy and storage, new messages sent in this chat will disappear after the selected duration.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    options.forEach { (duration, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showDisappearingDialog = false
                                    coroutineScope.launch {
                                        chatRepository.setDisappearingDuration(normPeer, duration)
                                        val toastMsg = if (duration == 0L) {
                                            "Disappearing messages turned off"
                                        } else {
                                            "Disappearing messages set to $label"
                                        }
                                        Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
                                    }
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (disappearingDuration == duration),
                                onClick = {
                                    showDisappearingDialog = false
                                    coroutineScope.launch {
                                        chatRepository.setDisappearingDuration(normPeer, duration)
                                        val toastMsg = if (duration == 0L) {
                                            "Disappearing messages turned off"
                                        } else {
                                            "Disappearing messages set to $label"
                                        }
                                        Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = TealPrimary)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = if (disappearingDuration == duration) FontWeight.Bold else FontWeight.Normal
                                )
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDisappearingDialog = false }) {
                    Text("Close")
                }
            }
        )
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

    // Fullscreen Video Player
    if (selectedVideoPreviewFile != null) {
        com.example.ui.components.VideoPlayerDialog(
            videoFile = selectedVideoPreviewFile!!,
            onDismissRequest = { selectedVideoPreviewFile = null }
        )
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
            initialCaption = initialSharedText ?: "",
            onSendPhotos = { results ->
                photosToPreview = null
                onSharedContentConsumed()
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
            onClose = {
                photosToPreview = null
                onSharedContentConsumed()
            }
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
                // ── Star / Unstar ──────────────────────────────────────────
                ListItem(
                    headlineContent = {
                        Text(if (targetMsg.isStarred) "Unstar message" else "Star message")
                    },
                    leadingContent = {
                        Icon(
                            if (targetMsg.isStarred) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = null,
                            tint = if (targetMsg.isStarred) Color(0xFFFFC107) else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    modifier = Modifier.clickable {
                        val msgId = targetMsg.id
                        val willStar = !targetMsg.isStarred
                        selectedMessageForOptions = null
                        coroutineScope.launch {
                            if (willStar) chatRepository.starMessage(msgId)
                            else chatRepository.unstarMessage(msgId)
                            val label = if (willStar) "⭐ Message starred" else "Message unstarred"
                            Toast.makeText(context, label, Toast.LENGTH_SHORT).show()
                        }
                    }
                )

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
    onVideoClick: ((File) -> Unit)? = null,
    onMarkMessageRead: (messageId: String) -> Unit,
    onMessageLongClick: (message: MessageEntity) -> Unit = {},
    transferProgress: com.example.data.p2p.FileTransferProgress? = null,
    onCancelTransfer: () -> Unit = {},
    isSearchMatch: Boolean = false,
    isCurrentSearchMatch: Boolean = false
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val isDark = isSystemInDarkTheme()
    val isOutgoing = message.isOutgoing

    val displayText = remember(message.text, message.mediaType) {
        if (message.mediaType == ChatMediaType.TEXT.name) {
            try {
                val obj = org.json.JSONObject(message.text)
                obj.optString("text", message.text)
            } catch (_: Exception) { message.text }
        } else if (message.mediaType == ChatMediaType.IMAGE.name) {
            val raw = message.text.trim()
            if (raw.startsWith("[gif:") || raw.startsWith("http://") || raw.startsWith("https://")) {
                "" // GIFs do not display URL/code as text caption
            } else {
                raw
            }
        } else message.text
    }

    val firstUrl = remember(displayText, message.mediaType) {
        if (message.mediaType == ChatMediaType.TEXT.name) LinkPreviewHelper.extractFirstUrl(displayText) else null
    }
    val linkPreview by produceState<LinkPreviewData?>(
        initialValue = firstUrl?.let { LinkPreviewHelper.getCachedPreview(it) },
        key1 = firstUrl
    ) {
        if (firstUrl != null) {
            val cached = LinkPreviewHelper.getCachedPreview(firstUrl)
            if (cached != null) {
                value = cached
            } else {
                value = LinkPreviewHelper.fetchPreview(firstUrl)
            }
        } else {
            value = null
        }
    }

    val isDeleted = message.text.startsWith("🚫 ")

    val isStickerMessage = !isDeleted && (message.mediaType == ChatMediaType.STICKER.name ||
                           (message.mediaType == ChatMediaType.TEXT.name && message.text.startsWith("[sticker")))
    val (isPureEmojiMsg, emojiCount) = remember(displayText, message.text, isDeleted, message.mediaType) {
        if (!isDeleted && (message.mediaType == ChatMediaType.TEXT.name || message.mediaType.isBlank()) && !isStickerMessage) {
            isPureEmoji(displayText)
        } else Pair(false, 0)
    }

    val isFrameless = isStickerMessage || isPureEmojiMsg

    val bubbleColor = when {
        isFrameless            -> Color.Transparent
        isOutgoing && isDark  -> Color(0xFF005D4B)
        isOutgoing && !isDark -> Color(0xFFE7FFDB)
        !isOutgoing && isDark -> Color(0xFF1F2C34)
        else                   -> Color.White
    }

    val bubbleShape = when {
        isFrameless -> RoundedCornerShape(0.dp)
        isOutgoing  -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp)
        else        -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp)
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

    val hasLinkPreview = linkPreview != null && !isDeleted
    val hasHeroPreview = hasLinkPreview && !linkPreview!!.imageUrl.isNullOrBlank()
    val isPureUrlMessage = firstUrl != null && displayText.trim().trimEnd(*LinkPreviewHelper.TRAILING_PUNCTUATION).equals(firstUrl.trim(), ignoreCase = true)
    val isHeroPreviewOnly = hasHeroPreview && isPureUrlMessage

    val isImageOnly = !isDeleted && message.mediaType == ChatMediaType.IMAGE.name && displayText.isBlank()
    val bubblePadding = when {
        isFrameless -> PaddingValues(2.dp)
        isImageOnly || isHeroPreviewOnly -> PaddingValues(4.dp)
        else -> PaddingValues(horizontal = 8.dp, vertical = 6.dp)
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
            border = when {
                isCurrentSearchMatch -> BorderStroke(2.5.dp, Color(0xFFFF3366))
                isSearchMatch -> BorderStroke(2.dp, Color(0xFF00B4D8))
                else -> null
            },
            shadowElevation = if (isFrameless) 0.dp else if (isCurrentSearchMatch) 4.dp else 1.dp,
            modifier = Modifier
                .widthIn(
                    min = when {
                        isFrameless -> 0.dp
                        hasHeroPreview -> 270.dp
                        hasLinkPreview -> 240.dp
                        message.mediaType == ChatMediaType.IMAGE.name && !isDeleted -> 200.dp
                        else -> 0.dp
                    },
                    max = if (hasHeroPreview) 320.dp else 310.dp
                )
                .combinedClickable(
                    onClick = {
                        if (message.mediaType == ChatMediaType.TEXT.name) {
                            // normal tap on text message does nothing
                        }
                    },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onMessageLongClick(message)
                    }
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

                // ── Pure Emoji Rendering (Large size without bubble) ────────
                if (isPureEmojiMsg) {
                    val emojiFontSize = when (emojiCount) {
                        1 -> 44.sp
                        2 -> 36.sp
                        else -> 28.sp
                    }
                    Text(
                        text = displayText,
                        fontSize = emojiFontSize,
                        lineHeight = (emojiFontSize.value * 1.25f).sp,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }

                // ── Sticker Rendering (Borderless) ──────────────────────────
                if (isStickerMessage) {
                    val rawStickerText = message.text
                    val isEmojiSticker = rawStickerText.startsWith("[sticker:")
                    val isUrlSticker = rawStickerText.startsWith("[sticker_url:")

                    if (isEmojiSticker) {
                        val parts = rawStickerText.removeSurrounding("[sticker:", "]").split(":")
                        val rawCode = parts.getOrNull(0) ?: "🦄"
                        val rawName = parts.getOrNull(1) ?: ""
                        val resolvedEmoji = StickerCatalog.resolveEmoji(rawCode.ifBlank { rawName })
                        Box(
                            modifier = Modifier
                                .padding(6.dp)
                                .sizeIn(minWidth = 90.dp, minHeight = 90.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = resolvedEmoji,
                                fontSize = if (resolvedEmoji.length <= 4) 72.sp else 24.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        val stickerModel = if (!message.mediaPath.isNullOrBlank()) {
                            message.mediaPath
                        } else if (isUrlSticker) {
                            val parts = rawStickerText.removeSurrounding("[sticker_url:", "]").split(":")
                            parts.getOrNull(0) ?: ""
                        } else ""

                        if (stickerModel.isNotBlank()) {
                            Box(
                                modifier = Modifier
                                    .size(140.dp)
                                    .padding(4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                AsyncImage(
                                    model = stickerModel,
                                    contentDescription = "Sticker",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                            }
                        }
                    }
                }

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

                // ── Image / Animated GIF ──
                if (message.mediaType == ChatMediaType.IMAGE.name) {
                    val rawText = message.text.trim()
                    val isGifMsg = rawText.startsWith("[gif:") ||
                                   rawText.contains(".gif", ignoreCase = true) ||
                                   (message.mediaPath?.endsWith(".gif", ignoreCase = true) == true)

                    val gifUrlFromText = ChatRepository.extractGifUrl(rawText)
                    val effectiveImageModel = message.mediaPath.takeIf { !it.isNullOrBlank() && File(it).exists() } ?: gifUrlFromText

                    if (!effectiveImageModel.isNullOrBlank()) {
                        val imageRatio = remember(effectiveImageModel) {
                            if (isGifMsg) null else try {
                                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeFile(effectiveImageModel, opts)
                                if (opts.outWidth > 0 && opts.outHeight > 0) {
                                    (opts.outWidth.toFloat() / opts.outHeight.toFloat()).coerceIn(0.55f, 1.85f)
                                } else null
                            } catch (_: Exception) { null }
                        }

                        val isTransferring = !isGifMsg && transferProgress != null && transferProgress.percent < 100
                        val unblurPercent = transferProgress?.percent ?: 100
                        val imgBlurRadius = remember(unblurPercent, isTransferring, isOutgoing) {
                            if (!isOutgoing && isTransferring) {
                                ((1f - (unblurPercent / 100f)) * 16f).coerceIn(0f, 16f).dp
                            } else 0.dp
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (imageRatio != null) Modifier.aspectRatio(imageRatio) else Modifier.heightIn(min = 160.dp, max = if (isGifMsg) 280.dp else 360.dp))
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isDark) Color(0xFF182229) else Color(0xFFE9EDEF))
                                .pointerInput(message.id, effectiveImageModel) {
                                    detectTapGestures(
                                        onTap = {
                                            if (!message.isOutgoing && message.status != MessageStatus.READ.name) {
                                                onMarkMessageRead(message.id)
                                            }
                                            onImageClick(effectiveImageModel)
                                        },
                                        onLongPress = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onMessageLongClick(message)
                                        }
                                    )
                                }
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(effectiveImageModel)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = if (isGifMsg) "GIF" else "Photo",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .then(if (imgBlurRadius > 0.dp) Modifier.blur(imgBlurRadius) else Modifier),
                                contentScale = if (isGifMsg) ContentScale.Crop else ContentScale.Crop
                            )

                            // Top-Start: WhatsApp-style "GIF" badge
                            if (isGifMsg) {
                                Surface(
                                    color = Color.Black.copy(alpha = 0.65f),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier
                                        .padding(8.dp)
                                        .align(Alignment.TopStart)
                                ) {
                                    Text(
                                        text = "GIF",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Black,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            // Bottom-End: WhatsApp-style floating timestamp & tick badge for image-only (including GIFs)
                            if (isImageOnly) {
                                Surface(
                                    color = Color.Black.copy(alpha = 0.55f),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .padding(6.dp)
                                        .align(Alignment.BottomEnd)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (message.isEdited && !isDeleted) {
                                            Text(
                                                text = "Edited",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontSize = 9.sp,
                                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                                                ),
                                                color = Color.White.copy(alpha = 0.85f)
                                            )
                                            Spacer(modifier = Modifier.width(3.dp))
                                        }
                                        if (message.isStarred) {
                                            Icon(
                                                Icons.Default.Star,
                                                contentDescription = "Starred",
                                                tint = Color(0xFFFFC107),
                                                modifier = Modifier.size(11.dp)
                                            )
                                            Spacer(modifier = Modifier.width(3.dp))
                                        }
                                        if (message.expiresAt > 0L) {
                                            Icon(
                                                Icons.Default.Schedule,
                                                contentDescription = "Disappearing",
                                                tint = Color.White.copy(alpha = 0.85f),
                                                modifier = Modifier.size(10.dp)
                                            )
                                            Spacer(modifier = Modifier.width(3.dp))
                                        }
                                        val timeString = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(message.timestamp))
                                        Text(
                                            text = timeString,
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                            color = Color.White
                                        )
                                        if (isOutgoing) {
                                            Spacer(modifier = Modifier.width(3.dp))
                                            StatusTickIcon(status = message.status)
                                        }
                                    }
                                }
                            }
                        }

                        if (transferProgress != null && !isGifMsg) {
                            Spacer(modifier = Modifier.height(6.dp))
                            FileTransferProgressCard(
                                progress = transferProgress,
                                onCancel = onCancelTransfer
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    } else {
                        // Receiving incoming image via P2P placeholder
                        Surface(
                            color = if (isOutgoing) TealPrimary.copy(alpha = 0.15f) else GreenCall.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        color = Color(0xFF9C27B0),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                Icons.Default.Image,
                                                contentDescription = "Photo",
                                                tint = Color.White,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (displayText.isNotBlank()) displayText else "Photo",
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = if (transferProgress != null) "Receiving photo..." else "Photo",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
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
                }

                // ── Audio Note ────────────────────────────────────────────────
                if (message.mediaType == ChatMediaType.AUDIO.name) {
                    if (!message.mediaPath.isNullOrBlank()) {
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
                        if (transferProgress != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            FileTransferProgressCard(
                                progress = transferProgress,
                                onCancel = onCancelTransfer
                            )
                        }
                    } else {
                        // Receiving incoming audio via P2P placeholder
                        Surface(
                            color = if (isOutgoing) TealPrimary.copy(alpha = 0.15f) else GreenCall.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        color = Color(0xFFE91E63),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                Icons.Default.Mic,
                                                contentDescription = "Voice message",
                                                tint = Color.White,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Voice message",
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = if (transferProgress != null) "Receiving voice note..." else "Voice note",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                if (transferProgress != null) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    FileTransferProgressCard(
                                        progress = transferProgress,
                                        onCancel = onCancelTransfer
                                    )
                                }
                            }
                        }
                    }
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
                    val isVideo = ext in listOf("MP4", "MKV", "WEBM", "MOV", "3GP", "AVI", "M4V")

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
                            .pointerInput(message.id) {
                                detectTapGestures(
                                    onTap = {
                                        if (!message.isOutgoing && message.status != MessageStatus.READ.name) {
                                            onMarkMessageRead(message.id)
                                        }
                                        if (docFile != null && hasFile) {
                                            if (isVideo && onVideoClick != null) {
                                                onVideoClick(docFile)
                                            } else {
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
                                        } else {
                                            Toast.makeText(context, if (isVideo) "Video is transferring..." else "Document is preparing or saved elsewhere", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onLongPress = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onMessageLongClick(message)
                                    }
                                )
                            }
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    color = when {
                                        isAudio -> Color(0xFFE91E63)
                                        isVideo -> Color(0xFF9C27B0)
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
                                            text = when {
                                                isAudio -> "🎵"
                                                isVideo -> "🎬"
                                                else -> ext.take(4)
                                            },
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = if (isAudio || isVideo) 16.sp else 11.sp
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

                // ── Rich Link Preview Card (WhatsApp style) ───────────────────
                if (linkPreview != null && !isDeleted) {
                    LinkPreviewCard(
                        preview = linkPreview!!,
                        isOutgoing = isOutgoing,
                        onOpenUrl = { url ->
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onMessageLongClick(message)
                        }
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }

                // ── Text / Caption with Clickable Links ───────────────────────
                if (displayText.isNotBlank() && !isHeroPreviewOnly && !isFrameless && message.mediaType != ChatMediaType.DOCUMENT.name) {
                    ClickableMessageText(
                        text = displayText,
                        isOutgoing = isOutgoing,
                        onUrlClick = { url ->
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onMessageLongClick(message)
                        }
                    )
                }
                }

                // ── Timestamp & Ticks (Only if not already rendered inside image/GIF overlay) ──
                if (!isImageOnly) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        modifier = Modifier
                            .align(Alignment.End)
                            .then(
                                if (isFrameless) {
                                    Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                } else {
                                    Modifier.padding(end = 4.dp, bottom = 2.dp)
                                }
                            ),
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
                        if (message.isStarred) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = "Starred",
                                tint = Color(0xFFFFC107),
                                modifier = Modifier.size(11.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                        }
                        if (message.expiresAt > 0L) {
                            Icon(
                                Icons.Default.Schedule,
                                contentDescription = "Disappearing",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.size(10.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
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
}

// ──────────────────────────────────────────────────────────────────────────────
// Clickable Message Text with Highlighted Green URLs
// ──────────────────────────────────────────────────────────────────────────────
@Composable
private fun ClickableMessageText(
    text: String,
    isOutgoing: Boolean,
    onUrlClick: (String) -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val urls = remember(text) { LinkPreviewHelper.URL_REGEX.findAll(text).toList() }

    if (urls.isEmpty()) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
    } else {
        val isDark = isSystemInDarkTheme()
        val linkColor = when {
            isOutgoing && isDark  -> Color(0xFF86EFAC) // Mint green on dark green bubble
            isOutgoing && !isDark -> Color(0xFF0F5132) // Forest green on light green bubble
            !isOutgoing && isDark -> Color(0xFF53BDEB) // Soft blue on dark slate bubble
            else                  -> Color(0xFF0D6EFD) // Vibrant link blue on white bubble
        }
        val annotatedString = remember(text, isOutgoing, isDark) {
            androidx.compose.ui.text.buildAnnotatedString {
                var lastIdx = 0
                for (match in urls) {
                    val start = match.range.first
                    val end = match.range.last + 1
                    if (start > lastIdx) {
                        append(text.substring(lastIdx, start))
                    }
                    val rawUrl = match.value
                    val cleanUrl = rawUrl.trimEnd(*LinkPreviewHelper.TRAILING_PUNCTUATION)
                    val trailingPunct = rawUrl.substring(cleanUrl.length)

                    if (cleanUrl.isNotEmpty()) {
                        pushStringAnnotation(tag = "URL", annotation = cleanUrl)
                        withStyle(
                            style = androidx.compose.ui.text.SpanStyle(
                                color = linkColor,
                                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                                fontWeight = FontWeight.Medium
                            )
                        ) {
                            append(cleanUrl)
                        }
                        pop()
                    }
                    if (trailingPunct.isNotEmpty()) {
                        append(trailingPunct)
                    }
                    lastIdx = end
                }
                if (lastIdx < text.length) {
                    append(text.substring(lastIdx))
                }
            }
        }

        var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
        Text(
            text = annotatedString,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface
            ),
            modifier = Modifier
                .padding(horizontal = 4.dp, vertical = 2.dp)
                .pointerInput(annotatedString) {
                    detectTapGestures(
                        onTap = { offset ->
                            layoutResult?.let { layout ->
                                val position = layout.getOffsetForPosition(offset)
                                annotatedString.getStringAnnotations(tag = "URL", start = position, end = position)
                                    .firstOrNull()?.let { annotation ->
                                        onUrlClick(annotation.item)
                                    }
                            }
                        },
                        onLongPress = {
                            onLongClick?.invoke()
                        }
                    )
                },
            onTextLayout = { layoutResult = it }
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Rich Link Preview Card (WhatsApp style with image, title, description, domain)
// ──────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LinkPreviewCard(
    preview: LinkPreviewData,
    isOutgoing: Boolean,
    onOpenUrl: (String) -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val isDark = isSystemInDarkTheme()
    val isHero = !preview.imageUrl.isNullOrBlank()

    if (isHero) {
        // ── HERO PREVIEW (Screenshot 2: YouTube / Video / Rich Web) ────────────
        val cardBg = if (isOutgoing) {
            if (isDark) Color(0xFF025144) else Color(0xFFD6F8C8)
        } else {
            if (isDark) Color(0xFF1E2B32) else Color(0xFFF0F2F5)
        }

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = cardBg,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .combinedClickable(
                    onClick = { onOpenUrl(preview.url) },
                    onLongClick = onLongClick
                )
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Large Hero Image with Video Play Button overlay
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = preview.imageUrl,
                        contentDescription = preview.title ?: "Link Preview",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                    )

                    // Video Play Button Overlay
                    val isYouTube = preview.domain.contains("youtube", ignoreCase = true) ||
                                    preview.domain.contains("youtu.be", ignoreCase = true) ||
                                    preview.siteName?.contains("youtube", ignoreCase = true) == true

                    if (preview.isVideo || isYouTube) {
                        Box(
                            modifier = Modifier
                                .size(54.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.45f))
                                .border(1.5.dp, Color.White.copy(alpha = 0.85f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = "Play",
                                tint = Color.White,
                                modifier = Modifier
                                    .size(34.dp)
                                    .offset(x = 1.dp)
                            )
                        }
                    }
                }

                // Info Section
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    // Bold Title
                    Text(
                        text = preview.title?.ifBlank { null } ?: preview.domain.ifBlank { preview.url },
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            lineHeight = 18.sp
                        ),
                        color = if (isOutgoing) {
                            if (isDark) Color.White else Color(0xFF0F2E28)
                        } else {
                            if (isDark) Color.White else Color(0xFF111B21)
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    // Description
                    if (!preview.description.isNullOrBlank() && preview.description != preview.title) {
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = preview.description,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            ),
                            color = if (isOutgoing) {
                                if (isDark) Color.White.copy(alpha = 0.72f) else Color(0xFF24473F)
                            } else {
                                if (isDark) Color.White.copy(alpha = 0.7f) else Color(0xFF667781)
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Domain & Brand Badge Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Left: 🔗 domain.com
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Link,
                                contentDescription = null,
                                tint = if (isOutgoing) {
                                    if (isDark) Color.White.copy(alpha = 0.6f) else Color(0xFF3B665A)
                                } else {
                                    if (isDark) Color.White.copy(alpha = 0.55f) else Color(0xFF667781)
                                },
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = preview.domain.ifBlank { "youtube.com" },
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Normal
                                ),
                                color = if (isOutgoing) {
                                    if (isDark) Color.White.copy(alpha = 0.65f) else Color(0xFF3B665A)
                                } else {
                                    if (isDark) Color.White.copy(alpha = 0.6f) else Color(0xFF667781)
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Right: YouTube red play badge
                        val isYouTube = preview.domain.contains("youtube", ignoreCase = true) ||
                                        preview.domain.contains("youtu.be", ignoreCase = true) ||
                                        preview.siteName?.contains("youtube", ignoreCase = true) == true

                        if (isYouTube) {
                            Surface(
                                color = Color(0xFFFF0000),
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.size(width = 24.dp, height = 16.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Filled.PlayArrow,
                                        contentDescription = "YouTube",
                                        tint = Color.White,
                                        modifier = Modifier.size(11.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    } else {
        // ── COMPACT PREVIEW (Screenshot 1: Generic Sites / No Hero Image) ───────
        val cardBg = if (isOutgoing) {
            if (isDark) Color(0xFF025144) else Color(0xFFD6F8C8)
        } else {
            if (isDark) Color(0xFF2A3942) else Color(0xFFE2E8F0).copy(alpha = 0.7f)
        }

        Surface(
            shape = RoundedCornerShape(10.dp),
            color = cardBg,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp)
                .combinedClickable(
                    onClick = { onOpenUrl(preview.url) },
                    onLongClick = onLongClick
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left square gray box with 🔗 Link icon (matching Screenshot 1)
                Box(
                    modifier = Modifier
                        .size(width = 64.dp, height = 64.dp)
                        .clip(RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp))
                        .background(if (isDark) Color(0xFF384954) else Color(0xFFCBD5E1)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Link,
                        contentDescription = null,
                        tint = if (isDark) Color(0xFF8696A0) else Color(0xFF64748B),
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Text Info on Right
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = preview.title?.ifBlank { null } ?: preview.domain.ifBlank { preview.url },
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        ),
                        color = if (isOutgoing) {
                            if (isDark) Color.White else Color(0xFF0F2E28)
                        } else {
                            if (isDark) Color.White else Color.Black
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (!preview.description.isNullOrBlank() && preview.description != preview.title) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = preview.description,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = (if (isDark) Color.White else Color.Black).copy(alpha = 0.65f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Link,
                            contentDescription = null,
                            tint = (if (isDark) Color.White else Color.Black).copy(alpha = 0.5f),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = preview.domain.ifBlank { "link" },
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = (if (isDark) Color.White else Color.Black).copy(alpha = 0.5f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
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
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Cancel transfer",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
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

// ──────────────────────────────────────────────────────────────────────────────
// Helpers for Pure Emojis & Backspace Code Points
// ──────────────────────────────────────────────────────────────────────────────
private fun isPureEmoji(text: String): Pair<Boolean, Int> {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return Pair(false, 0)
    var count = 0
    var i = 0
    while (i < trimmed.length) {
        val codePoint = trimmed.codePointAt(i)
        val charCount = Character.charCount(codePoint)
        if (Character.isWhitespace(codePoint)) {
            i += charCount
            continue
        }
        val isEmoji = Character.isSurrogate(trimmed[i]) ||
                codePoint in 0x1F600..0x1F64F || // Emoticons
                codePoint in 0x1F300..0x1F5FF || // Misc Symbols and Pictographs
                codePoint in 0x1F680..0x1F6FF || // Transport and Map
                codePoint in 0x1F1E0..0x1F1FF || // Regional indicator flags
                codePoint in 0x2600..0x26FF ||   // Misc symbols
                codePoint in 0x2700..0x27BF ||   // Dingbats
                codePoint in 0xFE00..0xFE0F ||   // Variation Selectors
                codePoint in 0x1F900..0x1F9FF || // Supplemental Symbols
                codePoint in 0x1FA70..0x1FAFF || // Symbols and Pictographs Extended-A
                codePoint == 0x200D              // Zero Width Joiner

        if (!isEmoji) return Pair(false, 0)
        if (codePoint != 0x200D && codePoint !in 0xFE00..0xFE0F) {
            count++
        }
        i += charCount
    }
    return Pair(count in 1..3, count)
}

private fun dropLastCodePoint(text: String): String {
    if (text.isEmpty()) return ""
    val lastCodePoint = text.codePointBefore(text.length)
    val charCount = Character.charCount(lastCodePoint)
    return text.dropLast(charCount)
}
