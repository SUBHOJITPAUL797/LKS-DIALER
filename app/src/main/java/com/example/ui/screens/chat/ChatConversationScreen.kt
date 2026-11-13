package com.example.ui.screens.chat

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import com.example.util.VideoThumbnailHelper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import com.example.data.model.GroupDto
import com.example.data.repository.ChatRepository
import com.example.data.repository.FirebaseManager
import com.example.ui.components.ChatMediaGalleryDialog
import com.example.ui.components.ClearChatDialog
import com.example.ui.theme.GreenCall
import com.example.ui.theme.TealPrimary
import com.example.ui.theme.Avatar3dHelper
import com.example.ui.theme.LocalThemeColor
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

data class ParsedReply(
    val replyMessageId: String,
    val senderLabel: String,
    val text: String
)

/** Formats a clean, readable preview text for a message when replying to it */
fun formatReplyPreview(message: MessageEntity): String {
    val rawText = message.text.trim()
    val isAudio = message.mediaType == ChatMediaType.AUDIO.name ||
        rawText.startsWith("voice_", ignoreCase = true) ||
        rawText.endsWith(".m4a", ignoreCase = true) ||
        rawText.contains("voice_", ignoreCase = true) ||
        rawText.equals("Voice message", ignoreCase = true) ||
        rawText.startsWith("🎤") ||
        (message.mediaPath?.let { path ->
            val ext = path.substringAfterLast('.', "").lowercase()
            ext in listOf("m4a", "mp3", "aac", "wav", "ogg", "opus") || path.contains("voice_")
        } == true)

    if (isAudio) return "🎤 Voice message"

    val isImage = message.mediaType == ChatMediaType.IMAGE.name ||
        rawText.startsWith("[gif:") ||
        rawText.endsWith(".gif", ignoreCase = true) ||
        rawText.endsWith(".jpg", ignoreCase = true) ||
        rawText.endsWith(".png", ignoreCase = true) ||
        rawText.endsWith(".webp", ignoreCase = true)

    if (isImage) {
        return if (rawText.startsWith("[gif:") || rawText.endsWith(".gif", ignoreCase = true)) "🎬 GIF"
        else if (rawText.isNotBlank() && !rawText.startsWith("img_")) "📷 $rawText"
        else "📷 Photo"
    }

    if (message.mediaType == ChatMediaType.STICKER.name || rawText.startsWith("sticker_")) {
        return "🦄 Sticker"
    }

    if (message.mediaType == ChatMediaType.DOCUMENT.name) {
        val docName = when {
            rawText.isNotBlank() && !rawText.startsWith("doc_") -> rawText
            !message.mediaPath.isNullOrBlank() -> File(message.mediaPath).name
            else -> "Document"
        }
        return "📄 $docName"
    }

    // Check for WhatsApp rich payloads (Location, Contact, Poll)
    WhatsAppPayloadParser.parseLocation(rawText)?.let { loc ->
        return if (loc.isLiveLocation) "📍 Live Location: ${loc.title}" else "📍 Location: ${loc.title}"
    }
    WhatsAppPayloadParser.parseContact(rawText)?.let { contact ->
        return "👤 Contact: ${contact.name}"
    }
    WhatsAppPayloadParser.parsePoll(rawText)?.let { poll ->
        return "📊 Poll: ${poll.question}"
    }

    // Text message — extract if it's a JSON reply wrapper
    return try {
        val obj = JSONObject(rawText)
        obj.optString("text", rawText)
    } catch (_: Exception) {
        rawText
    }
}

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

    val isGroup = remember(peerPhoneNumber) { peerPhoneNumber.startsWith("group_") }
    val normPeer = remember(peerPhoneNumber, isGroup) {
        if (isGroup) peerPhoneNumber else ContactsHelper.normalizePhoneNumber(peerPhoneNumber)
    }
    var groupDto by remember { mutableStateOf<GroupDto?>(null) }
    val liveGroupDto by remember(normPeer, isGroup) {
        if (isGroup) chatRepository.getGroupFlow(normPeer)
        else kotlinx.coroutines.flow.flowOf(null)
    }.collectAsState(initial = null)
    val effectiveGroupDto = liveGroupDto ?: groupDto

    var showGroupInfoDialog by remember { mutableStateOf(false) }
    var showExitGroupDialog by remember { mutableStateOf(false) }
    var showDeleteGroupDialog by remember { mutableStateOf(false) }
    var showAddMembersDialog by remember { mutableStateOf(false) }
    var memberToRemove by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(normPeer, isGroup) {
        if (isGroup) {
            groupDto = chatRepository.getGroup(normPeer)
        }
    }
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
    // ── Avatar resolution: use peerInitialAvatar immediately, then upgrade from registeredUsers/contacts/groupDto ──
    val peerProfilePic = remember(peerUser, peerContact, peerInitialAvatar, effectiveGroupDto) {
        if (isGroup) {
            effectiveGroupDto?.iconUrl?.takeIf { it.isNotBlank() } ?: peerInitialAvatar
        } else {
            val userPic     = peerUser?.profilePictureUrl?.takeIf { it.isNotBlank() }
            val contactPic  = peerContact?.profilePictureUrl?.takeIf { it.isNotBlank() }
            // Priority: live Firestore user > synced contact > avatar passed from ChatListScreen
            userPic ?: contactPic ?: peerInitialAvatar
        }
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
    var selectedImagePreviewSubtitle by remember { mutableStateOf<String?>(null) }
    var activeViewOnceMessageId by remember { mutableStateOf<String?>(null) }
    var selectedVideoPreviewFile by remember { mutableStateOf<File?>(null) }
    var showOptionsMenu by remember { mutableStateOf(false) }
    var showClearChatDialog by remember { mutableStateOf(false) }
    var showMediaGalleryDialog by remember { mutableStateOf(false) }
    var showBlockConfirmDialog by remember { mutableStateOf(false) }
    var showSpamConfirmDialog by remember { mutableStateOf(false) }
    var showStarredMessagesSheet by remember { mutableStateOf(false) }  // Starred messages viewer
    var showDisappearingDialog by remember { mutableStateOf(false) }
    var showWallpaperDialog by remember { mutableStateOf(false) }
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
    var highlightedMessageId by remember { mutableStateOf<String?>(null) }
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
    var showLocationSharingDialog by remember { mutableStateOf(false) }
    var showShareContactDialog by remember { mutableStateOf(false) }
    var showCreatePollDialog by remember { mutableStateOf(false) }
    var showNativeCamera by remember { mutableStateOf(false) }
    var photosToPreview by remember { mutableStateOf<List<File>?>(initialSharedPhotos) }
    var selectedMessageForOptions by remember { mutableStateOf<MessageEntity?>(null) }
    var editingMessage by remember { mutableStateOf<MessageEntity?>(null) }

    val listState = rememberLazyListState()

    val onReplyQuoteClick: (String) -> Unit = { targetId ->
        if (targetId.isNotBlank()) {
            val index = messages.indexOfFirst { it.id == targetId }
            if (index != -1) {
                coroutineScope.launch {
                    listState.animateScrollToItem(index)
                    highlightedMessageId = targetId
                    delay(1800)
                    if (highlightedMessageId == targetId) {
                        highlightedMessageId = null
                    }
                }
            } else {
                if (pageSize < totalMessageCount) {
                    pageSize = (pageSize + 50).coerceAtMost(totalMessageCount)
                    coroutineScope.launch {
                        delay(300)
                        val newIndex = messages.indexOfFirst { it.id == targetId }
                        if (newIndex != -1) {
                            listState.animateScrollToItem(newIndex)
                            highlightedMessageId = targetId
                            delay(1800)
                            if (highlightedMessageId == targetId) {
                                highlightedMessageId = null
                            }
                        }
                    }
                }
            }
        }
    }

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

    // Unconditionally mark conversation as read on open & fetch any pending receipts/messages
    LaunchedEffect(normPeer) {
        chatRepository.markConversationAsRead(normPeer)
        chatRepository.fetchPendingMessagesAndReceipts()
    }

    // Auto-mark conversation as read whenever user is viewing this conversation and unread incoming messages exist
    LaunchedEffect(normPeer, messages, lifecycleState) {
        if (lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) {
            val isActivelyWatching = chatRepository.isUserInConversation(normPeer)
            if (isActivelyWatching && messages.isNotEmpty()) {
                val hasUnread = messages.any { !it.isOutgoing && it.status != MessageStatus.READ.name }
                if (hasUnread) {
                    chatRepository.markConversationAsRead(normPeer)
                }
            }
        }
    }

    // Periodic check every 3.5s while active in chat to auto-heal any missed ACKs / delivered ticks
    LaunchedEffect(normPeer, lifecycleState) {
        while (lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) {
            kotlinx.coroutines.delay(3500)
            if (lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) {
                chatRepository.fetchPendingMessagesAndReceipts()
                val hasUnread = messages.any { !it.isOutgoing && it.status != MessageStatus.READ.name }
                if (hasUnread && chatRepository.isUserInConversation(normPeer)) {
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

    // Prioritized in-chat BackHandler so Back closes open panels/bars before exiting the chat
    androidx.activity.compose.BackHandler(
        enabled = !photosToPreview.isNullOrEmpty() ||
            showNativeCamera ||
            showAttachmentMenu ||
            showMediaPicker ||
            isSearching ||
            editingMessage != null ||
            replyingTo != null
    ) {
        when {
            !photosToPreview.isNullOrEmpty() -> photosToPreview = null
            showNativeCamera -> showNativeCamera = false
            showAttachmentMenu -> showAttachmentMenu = false
            showMediaPicker -> showMediaPicker = false
            isSearching -> {
                isSearching = false
                searchQuery = ""
                currentSearchIdx = 0
            }
            editingMessage != null -> {
                editingMessage = null
                inputText = ""
            }
            replyingTo != null -> replyingTo = null
        }
    }

    // Automatic first-time swipe hint disabled per user preference

    // Media Processing Helper for Gallery Selection (Supports Both Photos & Videos)
    val handlePickedMediaUris: (List<Uri>) -> Unit = { uris ->
        if (uris.isNotEmpty()) {
            coroutineScope.launch {
                try {
                    val photoFiles = mutableListOf<File>()
                    val videoFiles = mutableListOf<Pair<File, String>>()

                    uris.forEach { uri ->
                        var displayName = "media_${System.currentTimeMillis()}"
                        var mimeType = context.contentResolver.getType(uri) ?: ""
                        try {
                            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                                if (cursor.moveToFirst()) {
                                    val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                    if (nameIdx != -1) {
                                        val name = cursor.getString(nameIdx)
                                        if (!name.isNullOrBlank()) displayName = name
                                    }
                                }
                            }
                        } catch (_: Exception) {}

                        val extFromName = displayName.substringAfterLast('.', "")
                        val ext = if (extFromName.isNotBlank() && extFromName.length <= 5) {
                            extFromName.lowercase(Locale.getDefault())
                        } else {
                            android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "jpg"
                        }

                        val isVideo = mimeType.startsWith("video/") || ext in listOf("mp4", "mkv", "webm", "mov", "3gp", "avi", "m4v")
                        val prefix = if (isVideo) "video" else "gallery"
                        val tempFile = File(context.cacheDir, "${prefix}_${System.currentTimeMillis()}_${photoFiles.size + videoFiles.size}.$ext")

                        context.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                        }

                        if (tempFile.exists() && tempFile.length() > 0) {
                            if (isVideo) {
                                val finalName = if (displayName.endsWith(".$ext", ignoreCase = true)) displayName else "$displayName.$ext"
                                videoFiles.add(Pair(tempFile, finalName))
                            } else {
                                photoFiles.add(tempFile)
                            }
                        }
                    }

                    // Send videos immediately as media documents (with inline video player)
                    videoFiles.forEach { (videoFile, videoName) ->
                        chatRepository.sendMessage(
                            recipientNumber = normPeer,
                            recipientName = peerDisplayName,
                            text = videoName,
                            mediaType = ChatMediaType.DOCUMENT,
                            mediaFile = videoFile
                        )
                    }
                    if (videoFiles.isNotEmpty()) {
                        listState.animateScrollToItem(0)
                    }

                    // Open photo review carousel for any selected photos
                    if (photoFiles.isNotEmpty()) {
                        photosToPreview = photoFiles
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Failed to load media: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Modern Android Photo & Video Picker (with Fallback for all devices)
    val galleryMultipleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        handlePickedMediaUris(uris)
    }

    val galleryFallbackLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        handlePickedMediaUris(uris)
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

    // Audio File Picker Launcher (MP3, WAV, M4A, AAC, OGG)
    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                try {
                    var displayName = "audio_${System.currentTimeMillis()}.mp3"
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (nameIdx != -1) displayName = cursor.getString(nameIdx) ?: displayName
                        }
                    }
                    val ext = displayName.substringAfterLast('.', "mp3")
                    val tempFile = File(context.cacheDir, "aud_${System.currentTimeMillis()}.$ext")
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
                    Toast.makeText(context, "Failed to send audio file: ${e.message}", Toast.LENGTH_SHORT).show()
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
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable {
                                        activeViewOnceMessageId = null
                                        selectedImagePreviewSubtitle = if (isGroup) "Group Photo" else normPeer
                                        selectedImagePreviewPath = if (peerProfilePic.isNotBlank()) {
                                            peerProfilePic
                                        } else {
                                            val avatarRes = com.example.ui.theme.Avatar3dHelper.getAvatarResId(peerDisplayName, normPeer)
                                            "android.resource://${context.packageName}/$avatarRes"
                                        }
                                    }
                            ) {
                                ChatAvatar(
                                    name = peerDisplayName,
                                    profilePic = peerProfilePic,
                                    phoneNumber = normPeer,
                                    size = 42.dp,
                                    fontSize = 16.sp,
                                    isGroup = isGroup
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        if (isGroup) {
                                            showGroupInfoDialog = true
                                        } else {
                                            showMediaGalleryDialog = true
                                        }
                                    }
                            ) {
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
                                    isGroup -> {
                                        val count = groupDto?.members?.size ?: 0
                                        if (count > 0) "Group • $count members" else "Group"
                                    }
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
                        if (!isGroup) {
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
                        }
                        Box {
                            Surface(
                                onClick = { showOptionsMenu = true },
                                shape = CircleShape,
                                color = if (showOptionsMenu) TealPrimary.copy(alpha = 0.16f) else Color.Transparent,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.MoreVert,
                                        contentDescription = "Options",
                                        tint = if (showOptionsMenu) TealPrimary else MaterialTheme.colorScheme.onSurface
                                    )
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
                                // Top mini header inside 3-dots menu
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = peerDisplayName,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isDarkMenu) Color.White else Color(0xFF0F172A),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Default.Lock,
                                                contentDescription = null,
                                                tint = if (isDarkMenu) Color(0xFF4ADE80) else Color(0xFF059669),
                                                modifier = Modifier.size(11.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "End-to-End Encrypted",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (isDarkMenu) Color(0xFF4ADE80) else Color(0xFF059669)
                                            )
                                        }
                                    }
                                }

                                HorizontalDivider(
                                    color = if (isDarkMenu) Color.White.copy(alpha = 0.08f) else Color(0xFFE2E8F0),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                )

                                // 1. Contact / Group Info
                                if (isGroup) {
                                    WhatsAppStyledMenuItem(
                                        title = "Group info",
                                        subtitle = "Members & group settings",
                                        icon = Icons.Default.People,
                                        iconTint = Color(0xFF0284C7),
                                        onClick = {
                                            showOptionsMenu = false
                                            showGroupInfoDialog = true
                                        }
                                    )
                                } else {
                                    WhatsAppStyledMenuItem(
                                        title = "View contact",
                                        subtitle = "Profile & shared media",
                                        icon = Icons.Default.Person,
                                        iconTint = Color(0xFF0284C7),
                                        onClick = {
                                            showOptionsMenu = false
                                            showMediaGalleryDialog = true
                                        }
                                    )
                                }

                                // 2. Media, Links & Docs
                                WhatsAppStyledMenuItem(
                                    title = "Media, links, and docs",
                                    subtitle = "Photos, videos & files",
                                    icon = Icons.Default.PhotoLibrary,
                                    iconTint = Color(0xFF9333EA),
                                    onClick = {
                                        showOptionsMenu = false
                                        showMediaGalleryDialog = true
                                    }
                                )

                                // 3. Search in chat
                                WhatsAppStyledMenuItem(
                                    title = "Search",
                                    subtitle = "Find messages in chat",
                                    icon = Icons.Default.Search,
                                    iconTint = Color(0xFF0EA5E9),
                                    onClick = {
                                        showOptionsMenu = false
                                        isSearching = true
                                    }
                                )

                                // 4. Starred messages
                                WhatsAppStyledMenuItem(
                                    title = "Starred messages",
                                    subtitle = "Important saved messages",
                                    icon = Icons.Default.Star,
                                    iconTint = Color(0xFFF59E0B),
                                    onClick = {
                                        showOptionsMenu = false
                                        showStarredMessagesSheet = true
                                    }
                                )

                                // 5. Disappearing messages
                                val disappearingBadge = when {
                                    disappearingDuration <= 0L -> "Off"
                                    disappearingDuration <= 24 * 3600 * 1000L -> "24h"
                                    disappearingDuration <= 7 * 24 * 3600 * 1000L -> "7d"
                                    else -> "On"
                                }
                                WhatsAppStyledMenuItem(
                                    title = "Disappearing messages",
                                    subtitle = "Auto-delete timer",
                                    icon = Icons.Default.Schedule,
                                    iconTint = Color(0xFF059669),
                                    badgeText = disappearingBadge,
                                    onClick = {
                                        showOptionsMenu = false
                                        showDisappearingDialog = true
                                    }
                                )

                                // 6. Wallpaper
                                WhatsAppStyledMenuItem(
                                    title = "Wallpaper",
                                    subtitle = "Customize chat background",
                                    icon = Icons.Default.Wallpaper,
                                    iconTint = Color(0xFF6366F1),
                                    onClick = {
                                        showOptionsMenu = false
                                        showWallpaperDialog = true
                                    }
                                )

                                HorizontalDivider(
                                    color = if (isDarkMenu) Color.White.copy(alpha = 0.08f) else Color(0xFFE2E8F0),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                )

                                // 7. Clear chat
                                WhatsAppStyledMenuItem(
                                    title = "Clear chat",
                                    icon = Icons.Default.DeleteSweep,
                                    iconTint = Color(0xFFFB7185),
                                    isDestructive = true,
                                    onClick = {
                                        showOptionsMenu = false
                                        showClearChatDialog = true
                                    }
                                )

                                // 8. Group or Contact Moderation Actions
                                if (isGroup) {
                                    WhatsAppStyledMenuItem(
                                        title = "Exit group",
                                        icon = Icons.Default.ExitToApp,
                                        iconTint = Color(0xFFEF4444),
                                        isDestructive = true,
                                        onClick = {
                                            showOptionsMenu = false
                                            showExitGroupDialog = true
                                        }
                                    )
                                    val myPhone = firebaseManager.currentUser.value?.phoneNumber ?: ""
                                    val isGroupAdmin = ContactsHelper.numbersMatch(groupDto?.adminPhone ?: "", myPhone)
                                    if (isGroupAdmin) {
                                        WhatsAppStyledMenuItem(
                                            title = "Delete group",
                                            icon = Icons.Default.Delete,
                                            iconTint = Color(0xFFEF4444),
                                            isDestructive = true,
                                            onClick = {
                                                showOptionsMenu = false
                                                showDeleteGroupDialog = true
                                            }
                                        )
                                    }
                                } else {
                                    if (isBlocked) {
                                        WhatsAppStyledMenuItem(
                                            title = "Unblock contact",
                                            icon = Icons.Default.CheckCircle,
                                            iconTint = Color(0xFF4ADE80),
                                            onClick = {
                                                showOptionsMenu = false
                                                firebaseManager.unblockNumber(normPeer)
                                                firebaseManager.unmarkSpam(normPeer)
                                                Toast.makeText(context, "Contact unblocked", Toast.LENGTH_SHORT).show()
                                            }
                                        )
                                    } else {
                                        WhatsAppStyledMenuItem(
                                            title = "Block contact",
                                            icon = Icons.Default.Block,
                                            iconTint = Color(0xFFEF4444),
                                            isDestructive = true,
                                            onClick = {
                                                showOptionsMenu = false
                                                showBlockConfirmDialog = true
                                            }
                                        )
                                        WhatsAppStyledMenuItem(
                                            title = "Report spam",
                                            icon = Icons.Default.Report,
                                            iconTint = Color(0xFFF97316),
                                            isDestructive = true,
                                            onClick = {
                                                showOptionsMenu = false
                                                showSpamConfirmDialog = true
                                            }
                                        )
                                    }
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

        val isDark = com.example.ui.theme.LocalIsDarkTheme.current

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
                                onImageClick = { path, viewOnceMsgId ->
                                    selectedImagePreviewPath = path
                                    activeViewOnceMessageId = viewOnceMsgId
                                    val timeStr = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(msg.timestamp))
                                    val rawCap = msg.text.trim()
                                    selectedImagePreviewSubtitle = if (rawCap.isNotBlank() && !rawCap.startsWith("[gif:") && !rawCap.startsWith("http")) {
                                        "$rawCap • $timeStr"
                                    } else {
                                        timeStr
                                    }
                                },
                                onVideoClick = { file -> selectedVideoPreviewFile = file },
                                onMarkMessageRead = { id -> chatRepository.markMessageRead(id, normPeer) },
                                onMessageLongClick = { selectedMessageForOptions = it },
                                transferProgress = activeTransfers[msg.id],
                                onCancelTransfer = { chatRepository.cancelTransfer(msg.id) },
                                isSearchMatch = isMsgMatched,
                                isCurrentSearchMatch = isCurrentMsg,
                                isHighlighted = (msg.id == highlightedMessageId),
                                onReplyQuoteClick = onReplyQuoteClick
                            )
                        }
                    }

                    if (messages.size < totalMessageCount) {
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
                            color = if (isDark) Color(0xFF232D36) else Color.White,
                            shadowElevation = 6.dp,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isDark) Color(0xFF3B4A54) else Color(0xFFCBD5E1)
                            ),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.KeyboardDoubleArrowDown,
                                    contentDescription = "Scroll to bottom",
                                    tint = if (isDark) Color(0xFF8696A0) else Color(0xFF54656F),
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
                        color = if (isDark) Color(0xFF1F2C34) else Color(0xFFE0F7FA),
                        tonalElevation = 2.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    replyingTo?.messageId?.let { onReplyQuoteClick(it) }
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val reply = replyingTo
                            // Accent bar
                            Box(
                                modifier = Modifier
                                    .width(4.dp)
                                    .height(40.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(if (reply?.isOutgoing == true) TealPrimary else GreenCall)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Replying to ${reply?.senderLabel ?: ""}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = if (reply?.isOutgoing == true) TealPrimary else GreenCall
                                    )
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val isVoice = reply?.text?.contains("Voice message", ignoreCase = true) == true || reply?.text?.startsWith("🎤") == true
                                    if (isVoice) {
                                        Icon(
                                            Icons.Default.Mic,
                                            contentDescription = null,
                                            tint = if (reply?.isOutgoing == true) TealPrimary else GreenCall,
                                            modifier = Modifier.size(14.dp).padding(end = 2.dp)
                                        )
                                    }
                                    Text(
                                        text = (reply?.text ?: "").removePrefix("🎤 ").trim(),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
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
            val currentEditingMsg = editingMessage
            AnimatedVisibility(visible = currentEditingMsg != null) {
                if (currentEditingMsg != null) {
                    val editPreviewText = remember(currentEditingMsg.text) {
                        try {
                            val obj = JSONObject(currentEditingMsg.text)
                            obj.optString("text", currentEditingMsg.text)
                        } catch (_: Exception) { currentEditingMsg.text }
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = if (isDark) Color(0xFF1B2A32) else Color(0xFFE8F5E9),
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

            // ── WhatsApp Floating Attachment Sheet (Pops up above input bar) ──
            WhatsAppFloatingAttachmentSheet(
                visible = showAttachmentMenu,
                onDismiss = { showAttachmentMenu = false },
                onDocumentClick = {
                    showAttachmentMenu = false
                    documentPickerLauncher.launch(
                        arrayOf(
                            "application/pdf",
                            "application/msword",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            "application/vnd.ms-excel",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            "text/plain",
                            "*/*"
                        )
                    )
                },
                onCameraClick = {
                    showAttachmentMenu = false
                    showNativeCamera = true
                },
                onGalleryClick = {
                    showAttachmentMenu = false
                    try {
                        galleryMultipleLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                        )
                    } catch (_: Exception) {
                        galleryFallbackLauncher.launch(arrayOf("image/*", "video/*"))
                    }
                },
                onAudioClick = {
                    showAttachmentMenu = false
                    audioPickerLauncher.launch(arrayOf("audio/*"))
                },
                onLocationClick = {
                    showAttachmentMenu = false
                    showLocationSharingDialog = true
                },
                onContactClick = {
                    showAttachmentMenu = false
                    showShareContactDialog = true
                },
                onPollClick = {
                    showAttachmentMenu = false
                    showCreatePollDialog = true
                }
            )

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
                                .padding(horizontal = 8.dp, vertical = 6.dp),
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
                                        .size(46.dp)
                                        .clip(CircleShape)
                                        .background(GreenCall)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send voice note", tint = Color.White)
                                }
                            } else {
                                // ── WhatsApp Pill Input Container (Emoji + Text + Attach + Camera) ──
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 6.dp),
                                    shape = RoundedCornerShape(26.dp),
                                    color = if (isDark) Color(0xFF1F2C34) else Color(0xFFF1F5F9),
                                    border = BorderStroke(
                                        1.dp,
                                        if (showAttachmentMenu || showMediaPicker) TealPrimary.copy(alpha = 0.6f)
                                        else if (isDark) Color.White.copy(alpha = 0.08f)
                                        else Color(0xFFCBD5E1)
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 4.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Emoji / Sticker / GIF Keyboard Toggle
                                        IconButton(
                                            onClick = {
                                                showAttachmentMenu = false
                                                if (showMediaPicker) {
                                                    showMediaPicker = false
                                                    keyboardController?.show()
                                                } else {
                                                    keyboardController?.hide()
                                                    showMediaPicker = true
                                                }
                                            },
                                            modifier = Modifier.size(40.dp)
                                        ) {
                                            Text(
                                                text = if (showMediaPicker) "⌨️" else "😊",
                                                fontSize = 21.sp
                                            )
                                        }

                                        // Borderless WhatsApp TextField inside pill
                                        TextField(
                                            value = inputText,
                                            onValueChange = { text ->
                                                inputText = text
                                                chatRepository.setTyping(normPeer, text.isNotBlank())
                                                if (showMediaPicker) showMediaPicker = false
                                                if (showAttachmentMenu) showAttachmentMenu = false
                                            },
                                            placeholder = {
                                                val currentReply = replyingTo
                                                Text(
                                                    text = if (currentReply != null) "Reply to ${currentReply.senderLabel}…"
                                                    else "Message",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                                )
                                            },
                                            modifier = Modifier.weight(1f),
                                            maxLines = 4,
                                            colors = TextFieldDefaults.colors(
                                                focusedContainerColor = Color.Transparent,
                                                unfocusedContainerColor = Color.Transparent,
                                                disabledContainerColor = Color.Transparent,
                                                focusedIndicatorColor = Color.Transparent,
                                                unfocusedIndicatorColor = Color.Transparent,
                                                disabledIndicatorColor = Color.Transparent
                                            ),
                                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        )

                                        // Paperclip Attachment Button (toggles floating WhatsApp attachment grid)
                                        IconButton(
                                            onClick = {
                                                keyboardController?.hide()
                                                showMediaPicker = false
                                                showAttachmentMenu = !showAttachmentMenu
                                            },
                                            modifier = Modifier.size(40.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.AttachFile,
                                                contentDescription = "Attach",
                                                tint = if (showAttachmentMenu) GreenCall else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        // Quick Camera Button (shown when input is empty, just like WhatsApp)
                                        if (inputText.isBlank()) {
                                            IconButton(
                                                onClick = {
                                                    showAttachmentMenu = false
                                                    showMediaPicker = false
                                                    showNativeCamera = true
                                                },
                                                modifier = Modifier.size(40.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.PhotoCamera,
                                                    contentDescription = "Camera",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }

                                // Right circular action button (Save Edit / Send / Mic)
                                if (editingMessage != null) {
                                    IconButton(
                                        onClick = {
                                            val textToEdit = inputText.trim()
                                            val editMsg = editingMessage
                                            if (textToEdit.isNotEmpty() && editMsg != null) {
                                                val targetId = editMsg.id
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
                                            .size(46.dp)
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
                                                showAttachmentMenu = false
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
                                            .size(46.dp)
                                            .clip(CircleShape)
                                            .background(Brush.horizontalGradient(listOf(LocalThemeColor.current.primary, LocalThemeColor.current.primaryDark)))
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.White)
                                    }
                                } else {
                                    IconButton(
                                        onClick = {
                                            showAttachmentMenu = false
                                            showMediaPicker = false
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
                                            .size(46.dp)
                                            .clip(CircleShape)
                                            .background(TealPrimary)
                                    ) {
                                        Icon(Icons.Default.Mic, contentDescription = "Record Voice Note", tint = Color.White)
                                    }
                                }
                            }
                        }
                    }

                    // ── Instant Zero-Jank Emoji / Sticker / GIF Media Keyboard ─────────
                    if (showMediaPicker) {
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

    // Group Info Dialog
    if (showGroupInfoDialog) {
        if (effectiveGroupDto != null) {
            GroupInfoDialog(
                group = effectiveGroupDto,
                firebaseManager = firebaseManager,
                onDismiss = { showGroupInfoDialog = false },
                onExitGroup = {
                    showGroupInfoDialog = false
                    showExitGroupDialog = true
                },
                onDeleteGroup = {
                    showGroupInfoDialog = false
                    showDeleteGroupDialog = true
                },
                onRemoveMember = { memberPhone ->
                    memberToRemove = memberPhone
                },
                onAddMembersClick = {
                    showAddMembersDialog = true
                },
                onUpdateGroupIcon = { base64 ->
                    coroutineScope.launch {
                        val res = chatRepository.updateGroupIcon(normPeer, base64)
                        if (res.isSuccess) {
                            groupDto = effectiveGroupDto?.copy(iconUrl = base64)
                            Toast.makeText(context, "Group photo updated", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Failed to update group photo", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
        } else {
            LaunchedEffect(normPeer) {
                groupDto = chatRepository.getGroup(normPeer)
            }
        }
    }

    // Exit Group Confirmation Dialog
    if (showExitGroupDialog) {
        AlertDialog(
            onDismissRequest = { showExitGroupDialog = false },
            icon = { Icon(Icons.Default.ExitToApp, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Exit group?", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to exit \"${groupDto?.name ?: "this group"}\"? You will no longer receive messages from this group.") },
            confirmButton = {
                Button(
                    onClick = {
                        showExitGroupDialog = false
                        coroutineScope.launch {
                            chatRepository.leaveGroup(normPeer)
                            Toast.makeText(context, "You left the group", Toast.LENGTH_SHORT).show()
                            onBackClick()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Exit", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitGroupDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete Group Confirmation Dialog
    if (showDeleteGroupDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteGroupDialog = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete group?", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to permanently delete \"${groupDto?.name ?: "this group"}\"? The group will be deleted for all members.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteGroupDialog = false
                        coroutineScope.launch {
                            chatRepository.deleteGroup(normPeer)
                            Toast.makeText(context, "Group deleted", Toast.LENGTH_SHORT).show()
                            onBackClick()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteGroupDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Remove Member Confirmation Dialog
    memberToRemove?.let { mPhone ->
        AlertDialog(
            onDismissRequest = { memberToRemove = null },
            icon = { Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Remove member?", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to remove $mPhone from the group?") },
            confirmButton = {
                Button(
                    onClick = {
                        val target = mPhone
                        memberToRemove = null
                        coroutineScope.launch {
                            chatRepository.removeMemberFromGroup(normPeer, target)
                            groupDto = chatRepository.getGroup(normPeer)
                            Toast.makeText(context, "Member removed", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Remove", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { memberToRemove = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Add Participants Dialog
    if (showAddMembersDialog) {
        var addMemberSearch by remember { mutableStateOf("") }
        var selectedNewMembers by remember { mutableStateOf(setOf<String>()) }
        val currentGroupMembers = remember(groupDto) { groupDto?.members?.map { ContactsHelper.normalizePhoneNumber(it) }?.toSet() ?: emptySet() }
        val candidates = remember(syncedContacts, registeredUsers, currentGroupMembers, addMemberSearch) {
            syncedContacts.mapNotNull { contact ->
                val p = ContactsHelper.normalizePhoneNumber(contact.phoneNumber)
                if (p.isBlank() || currentGroupMembers.contains(p)) {
                    null
                } else {
                    val regUser = registeredUsers.find { ContactsHelper.numbersMatch(it.phoneNumber, p) }
                    val resolvedName = contact.name.ifBlank { regUser?.displayName ?: contact.phoneNumber }
                    val resolvedPic = contact.profilePictureUrl.ifBlank { regUser?.profilePictureUrl ?: "" }
                    if (addMemberSearch.isBlank() ||
                        resolvedName.contains(addMemberSearch, ignoreCase = true) ||
                        contact.phoneNumber.contains(addMemberSearch)
                    ) {
                        com.example.data.model.UserDto(
                            phoneNumber = p,
                            displayName = resolvedName,
                            profilePictureUrl = resolvedPic
                        )
                    } else null
                }
            }.distinctBy { it.phoneNumber }
        }

        AlertDialog(
            onDismissRequest = { showAddMembersDialog = false },
            title = { Text("Add Participants", fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = addMemberSearch,
                        onValueChange = { addMemberSearch = it },
                        placeholder = { Text("Search users...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (candidates.isEmpty()) {
                        Text(
                            text = "No additional users available",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                            items(candidates) { user ->
                                val p = ContactsHelper.normalizePhoneNumber(user.phoneNumber)
                                val isSelected = selectedNewMembers.contains(p)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedNewMembers = if (isSelected) selectedNewMembers - p else selectedNewMembers + p
                                        }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ChatAvatar(name = user.displayName, profilePic = user.profilePictureUrl, size = 32.dp, fontSize = 14.sp)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(user.displayName, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                        Text(user.phoneNumber, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = {
                                            selectedNewMembers = if (isSelected) selectedNewMembers - p else selectedNewMembers + p
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val toAdd = selectedNewMembers.toList()
                        showAddMembersDialog = false
                        if (toAdd.isNotEmpty()) {
                            coroutineScope.launch {
                                chatRepository.addMembersToGroup(normPeer, toAdd)
                                groupDto = chatRepository.getGroup(normPeer)
                                Toast.makeText(context, "Added ${toAdd.size} member(s)", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = selectedNewMembers.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = TealPrimary)
                ) {
                    Text("Add (${selectedNewMembers.size})", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddMembersDialog = false }) {
                    Text("Cancel")
                }
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

    // Hardened View Once Security: If user backgrounds app, locks screen, or leaves screen while viewing, auto-destroy immediately!
    DisposableEffect(activeViewOnceMessageId, lifecycleOwner) {
        val viewOnceId = activeViewOnceMessageId
        val pathToDelete = selectedImagePreviewPath
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                val currentId = activeViewOnceMessageId
                val currentPath = selectedImagePreviewPath
                selectedImagePreviewPath = null
                activeViewOnceMessageId = null
                if (currentId != null) {
                    chatRepository.markViewOnceOpened(currentId, normPeer)
                    if (currentPath != null) {
                        try {
                            val f = File(currentPath)
                            if (f.exists()) f.delete()
                        } catch (_: Exception) {}
                    }
                }
            }
        }
        if (viewOnceId != null) {
            lifecycleOwner.lifecycle.addObserver(observer)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // If composable is disposed while actively viewing a View Once media
            if (viewOnceId != null && activeViewOnceMessageId == viewOnceId) {
                chatRepository.markViewOnceOpened(viewOnceId, normPeer)
                if (pathToDelete != null) {
                    try {
                        val f = File(pathToDelete)
                        if (f.exists()) f.delete()
                    } catch (_: Exception) {}
                }
            }
        }
    }

    // Fullscreen Image Preview (WhatsApp-Style Interactive Pinch-To-Zoom, Double-Tap Zoom & Pan)
    if (selectedImagePreviewPath != null) {
        com.example.ui.components.ZoomableImageViewerDialog(
            imageModel = selectedImagePreviewPath,
            title = peerDisplayName,
            subtitle = if (activeViewOnceMessageId != null) "View Once Photo" else selectedImagePreviewSubtitle,
            isViewOnce = activeViewOnceMessageId != null,
            onDismissRequest = {
                val viewOnceId = activeViewOnceMessageId
                val pathToDelete = selectedImagePreviewPath
                selectedImagePreviewPath = null
                selectedImagePreviewSubtitle = null
                activeViewOnceMessageId = null
                if (viewOnceId != null) {
                    chatRepository.markViewOnceOpened(viewOnceId, normPeer)
                    if (pathToDelete != null) {
                        try {
                            val f = File(pathToDelete)
                            if (f.exists()) f.delete()
                        } catch (_: Exception) {}
                    }
                    Toast.makeText(context, "View once photo viewed & destroyed", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // Fullscreen Video Player
    val videoPreview = selectedVideoPreviewFile
    if (videoPreview != null) {
        com.example.ui.components.VideoPlayerDialog(
            videoFile = videoPreview,
            onDismissRequest = { selectedVideoPreviewFile = null }
        )
    }

    // ── WhatsApp Google Maps Location Sharing Dialog ─────────────────────
    if (showLocationSharingDialog) {
        WhatsAppLocationSharingDialog(
            onDismiss = { showLocationSharingDialog = false },
            onSendLocation = { locationData ->
                showLocationSharingDialog = false
                coroutineScope.launch {
                    val payload = locationData.toMessageJson()
                    replyingTo = null
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
        )
    }

    // ── WhatsApp Contact Sharing Dialog ──────────────────────────────────
    if (showShareContactDialog) {
        WhatsAppShareContactDialog(
            contacts = syncedContacts,
            onDismiss = { showShareContactDialog = false },
            onSendContact = { contactData ->
                showShareContactDialog = false
                coroutineScope.launch {
                    val payload = contactData.toMessageJson()
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
        )
    }

    // ── WhatsApp Poll Creator Dialog ─────────────────────────────────────
    if (showCreatePollDialog) {
        WhatsAppCreatePollDialog(
            onDismiss = { showCreatePollDialog = false },
            onSendPoll = { pollData ->
                showCreatePollDialog = false
                coroutineScope.launch {
                    val payload = pollData.toMessageJson()
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
        )
    }

    // ── WhatsApp Quick Wallpaper Picker Dialog ───────────────────────────
    if (showWallpaperDialog) {
        WhatsAppQuickWallpaperDialog(
            wallpaperManager = wallpaperManager,
            onDismiss = { showWallpaperDialog = false }
        )
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
                val (videos, photos) = files.partition { file ->
                    val ext = file.extension.lowercase(Locale.getDefault())
                    ext in listOf("mp4", "mkv", "webm", "mov", "3gp", "avi", "m4v")
                }
                videos.forEach { videoFile ->
                    coroutineScope.launch {
                        chatRepository.sendMessage(
                            recipientNumber = normPeer,
                            recipientName = peerDisplayName,
                            text = videoFile.name,
                            mediaType = ChatMediaType.DOCUMENT,
                            mediaFile = videoFile
                        )
                    }
                }
                if (videos.isNotEmpty()) {
                    coroutineScope.launch { listState.animateScrollToItem(0) }
                }
                if (photos.isNotEmpty()) {
                    photosToPreview = photos
                }
            },
            onClose = { showNativeCamera = false }
        )
    }

    // ── WhatsApp Multi-Photo Review, Carousel & Captions ─────────
    val activePhotos = photosToPreview
    if (!activePhotos.isNullOrEmpty()) {
        WhatsAppMediaPreviewScreen(
            initialPhotos = activePhotos,
            initialCaption = initialSharedText ?: "",
            onSendPhotos = { results, isViewOnce ->
                photosToPreview = null
                onSharedContentConsumed()
                coroutineScope.launch {
                    results.forEach { (file, caption) ->
                        chatRepository.sendMessage(
                            recipientNumber = normPeer,
                            recipientName = peerDisplayName,
                            text = caption,
                            mediaType = ChatMediaType.IMAGE,
                            mediaFile = file,
                            isViewOnce = isViewOnce
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
    val targetMsg = selectedMessageForOptions
    if (targetMsg != null) {
        val isDeleted = targetMsg.text.startsWith("🚫 ")
        val isEligibleForEdit = targetMsg.isOutgoing &&
                !isDeleted &&
                targetMsg.mediaType == ChatMediaType.TEXT.name &&
                (System.currentTimeMillis() - targetMsg.timestamp <= 10 * 60 * 1000L)
        val isDarkSheet = com.example.ui.theme.LocalIsDarkTheme.current

        ModalBottomSheet(
            onDismissRequest = { selectedMessageForOptions = null },
            containerColor = if (isDarkSheet) Color(0xFF182229) else Color.White,
            contentColor = if (isDarkSheet) Color.White else Color(0xFF0F172A),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Text(
                    text = "Message options",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isDarkSheet) Color(0xFF94A3B8) else Color(0xFF64748B),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )

                // ── Star / Unstar ──────────────────────────────────────────
                WhatsAppStyledMenuItem(
                    title = if (targetMsg.isStarred) "Unstar message" else "Star message",
                    subtitle = if (targetMsg.isStarred) "Remove from starred messages" else "Save for quick access later",
                    icon = if (targetMsg.isStarred) Icons.Default.Star else Icons.Default.StarBorder,
                    iconTint = Color(0xFFF59E0B),
                    onClick = {
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
                    WhatsAppStyledMenuItem(
                        title = "Reply",
                        subtitle = "Quote this message in your reply",
                        icon = Icons.AutoMirrored.Filled.Reply,
                        iconTint = Color(0xFF0284C7),
                        onClick = {
                            val previewText = formatReplyPreview(targetMsg)
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
                        WhatsAppStyledMenuItem(
                            title = "Copy",
                            subtitle = "Copy message text to clipboard",
                            icon = Icons.Default.ContentCopy,
                            iconTint = Color(0xFF6366F1),
                            onClick = {
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
                        WhatsAppStyledMenuItem(
                            title = "Edit message",
                            subtitle = "Available within 10 minutes of sending",
                            icon = Icons.Default.Edit,
                            iconTint = Color(0xFF059669),
                            onClick = {
                                editingMessage = targetMsg
                                inputText = rawText
                                selectedMessageForOptions = null
                            }
                        )
                    }

                    HorizontalDivider(
                        color = if (isDarkSheet) Color.White.copy(alpha = 0.08f) else Color(0xFFE2E8F0),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )

                    // Delete for everyone (WhatsApp style: outgoing only, not already deleted)
                    if (targetMsg.isOutgoing) {
                        WhatsAppStyledMenuItem(
                            title = "Delete for everyone",
                            subtitle = "Remove for all participants in this chat",
                            icon = Icons.Default.DeleteSweep,
                            iconTint = Color(0xFFEF4444),
                            isDestructive = true,
                            onClick = {
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
                WhatsAppStyledMenuItem(
                    title = "Delete for me",
                    subtitle = "Remove from your device only",
                    icon = Icons.Default.Delete,
                    iconTint = Color(0xFFEF4444),
                    isDestructive = true,
                    onClick = {
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
                            val previewText = formatReplyPreview(message)
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
    val isDark = com.example.ui.theme.LocalIsDarkTheme.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = if (isDark) Color(0xFF182229) else Color(0xFFFFF8DB),
            shape = RoundedCornerShape(12.dp),
            shadowElevation = 1.dp,
            border = BorderStroke(1.dp, if (isDark) Color.White.copy(alpha = 0.06f) else Color(0xFFFDE68A))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = if (isDark) Color(0xFFFFD279) else Color(0xFF92400E)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Messages are End-to-End Encrypted and deleted from server once delivered.",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isDark) Color(0xFFFFD279) else Color(0xFF92400E),
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
    onImageClick: (path: String, viewOnceMessageId: String?) -> Unit,
    onVideoClick: ((File) -> Unit)? = null,
    onMarkMessageRead: (messageId: String) -> Unit,
    onMessageLongClick: (message: MessageEntity) -> Unit = {},
    transferProgress: com.example.data.p2p.FileTransferProgress? = null,
    onCancelTransfer: () -> Unit = {},
    isSearchMatch: Boolean = false,
    isCurrentSearchMatch: Boolean = false,
    isHighlighted: Boolean = false,
    onReplyQuoteClick: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val isDark = com.example.ui.theme.LocalIsDarkTheme.current
    val isOutgoing = message.isOutgoing

    val sharedLocation = remember(message.text, message.mediaType) {
        if (message.mediaType == ChatMediaType.TEXT.name) WhatsAppPayloadParser.parseLocation(message.text) else null
    }
    val sharedContact = remember(message.text, message.mediaType) {
        if (message.mediaType == ChatMediaType.TEXT.name) WhatsAppPayloadParser.parseContact(message.text) else null
    }
    val sharedPoll = remember(message.text, message.mediaType) {
        if (message.mediaType == ChatMediaType.TEXT.name) WhatsAppPayloadParser.parsePoll(message.text) else null
    }
    val isRichWhatsAppPayload = sharedLocation != null || sharedContact != null || sharedPoll != null

    val displayText = remember(message.text, message.mediaType, isRichWhatsAppPayload) {
        if (isRichWhatsAppPayload) {
            ""
        } else if (message.mediaType == ChatMediaType.TEXT.name) {
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

    val firstUrl = remember(displayText, message.mediaType, isRichWhatsAppPayload) {
        if (!isRichWhatsAppPayload && message.mediaType == ChatMediaType.TEXT.name) LinkPreviewHelper.extractFirstUrl(displayText) else null
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

    val themeColor = LocalThemeColor.current
    val bubbleColor = when {
        isFrameless            -> Color.Transparent
        isOutgoing && isDark  -> themeColor.primary
        isOutgoing && !isDark -> themeColor.primary
        !isOutgoing && isDark -> Color(0xFF1B1F27)
        else                   -> Color.White
    }

    val bubbleShape = when {
        isFrameless -> RoundedCornerShape(0.dp)
        isOutgoing  -> RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp)
        else        -> RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 6.dp, bottomEnd = 20.dp)
    }

    // Try to parse reply context from message text
    val parsedReply: ParsedReply? = remember(message.text) {
        if (message.mediaType == ChatMediaType.TEXT.name) {
            try {
                val obj = org.json.JSONObject(message.text)
                if (obj.has("replyTo")) {
                    val replyTo = obj.getJSONObject("replyTo")
                    ParsedReply(
                        replyMessageId = replyTo.optString("id", ""),
                        senderLabel = replyTo.optString("senderLabel", ""),
                        text = replyTo.optString("text", "")
                    )
                } else null
            } catch (_: Exception) { null }
        } else null
    }

    val hasLinkPreview = linkPreview != null && !isDeleted
    val hasHeroPreview = hasLinkPreview && !linkPreview?.imageUrl.isNullOrBlank()
    val isPureUrlMessage = firstUrl != null && displayText.trim().trimEnd(*LinkPreviewHelper.TRAILING_PUNCTUATION).equals(firstUrl.trim(), ignoreCase = true)
    val isHeroPreviewOnly = hasHeroPreview && isPureUrlMessage

    val isImageOnly = !isDeleted && !message.isViewOnce && message.mediaType == ChatMediaType.IMAGE.name && displayText.isBlank()
    val isVideoMessage = remember(message.mediaType, message.mediaPath, displayText) {
        if (message.mediaType != ChatMediaType.DOCUMENT.name) false
        else {
            val path = message.mediaPath?.takeIf { it.isNotBlank() }
            val ext = (path?.let { File(it).extension.ifBlank { null } }
                ?: displayText.substringAfterLast('.', "DOC"))
                .uppercase(Locale.getDefault()).take(5)
            ext in listOf("MP4", "MKV", "WEBM", "MOV", "3GP", "AVI", "M4V")
        }
    }
    val bubblePadding = when {
        isFrameless -> PaddingValues(2.dp)
        isImageOnly || isHeroPreviewOnly -> PaddingValues(4.dp)
        isVideoMessage -> PaddingValues(4.dp)
        else -> PaddingValues(horizontal = 8.dp, vertical = 6.dp)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        contentAlignment = if (isOutgoing) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Surface(
            color = if (isHighlighted) {
                if (isOutgoing) TealPrimary.copy(alpha = 0.22f) else GreenCall.copy(alpha = 0.22f)
            } else bubbleColor,
            shape = bubbleShape,
            border = when {
                isHighlighted -> BorderStroke(2.5.dp, if (isOutgoing) TealPrimary else GreenCall)
                isCurrentSearchMatch -> BorderStroke(2.5.dp, Color(0xFFFF3366))
                isSearchMatch -> BorderStroke(2.dp, Color(0xFF00B4D8))
                !isOutgoing && !isFrameless && isDark -> BorderStroke(0.5.dp, Color(0xFF282E3A))
                !isOutgoing && !isFrameless && !isDark -> BorderStroke(0.5.dp, Color(0xFFE2E8F0))
                else -> null
            },
            shadowElevation = if (isFrameless) 0.dp else if (isHighlighted || isCurrentSearchMatch) 4.dp else 1.5.dp,
            modifier = Modifier
                .widthIn(
                    min = when {
                        isFrameless -> 0.dp
                        hasHeroPreview -> 270.dp
                        hasLinkPreview -> 240.dp
                        message.mediaType == ChatMediaType.IMAGE.name && !isDeleted -> 200.dp
                        isVideoMessage && !isDeleted -> 220.dp
                        else -> 0.dp
                    },
                    max = if (hasHeroPreview) 320.dp else 310.dp
                )
                .combinedClickable(
                    onClick = {
                        if (message.mediaType == ChatMediaType.IMAGE.name && !message.isViewOnce && !isDeleted) {
                            val rawText = message.text.trim()
                            val gifUrlFromText = ChatRepository.extractGifUrl(rawText)
                            val mediaPath = message.mediaPath?.trim()
                            val model = when {
                                !mediaPath.isNullOrBlank() && (File(mediaPath).exists() || mediaPath.startsWith("content://") || mediaPath.startsWith("file://") || mediaPath.startsWith("http") || mediaPath.length > 64) -> mediaPath
                                !gifUrlFromText.isNullOrBlank() -> gifUrlFromText
                                else -> null
                            }
                            if (!model.isNullOrBlank()) {
                                if (!message.isOutgoing && message.status != MessageStatus.READ.name) {
                                    onMarkMessageRead(message.id)
                                }
                                onImageClick(model, null)
                            }
                        }
                    },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onMessageLongClick(message)
                    }
                )
        ) {
            Column(modifier = Modifier.padding(bubblePadding)) {
                if (!isOutgoing && !message.senderName.isNullOrBlank()) {
                    Text(
                        text = message.senderName,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = TealPrimary
                        ),
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
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
                    val (replyMsgId, senderLbl, rawReplyText) = parsedReply
                    val isVoiceReply = rawReplyText.contains("voice_", ignoreCase = true) ||
                            rawReplyText.endsWith(".m4a", ignoreCase = true) ||
                            rawReplyText.equals("Voice message", ignoreCase = true) ||
                            rawReplyText.contains("Voice message", ignoreCase = true) ||
                            rawReplyText.startsWith("🎤")
                    val isImageReply = !isVoiceReply && (rawReplyText.contains("📷") ||
                            rawReplyText.contains("img_", ignoreCase = true) ||
                            rawReplyText.endsWith(".jpg", ignoreCase = true) ||
                            rawReplyText.endsWith(".png", ignoreCase = true) ||
                            rawReplyText.startsWith("🎬"))

                    val displayReplyText = when {
                        isVoiceReply -> "Voice message"
                        rawReplyText.startsWith("📄 voice_", ignoreCase = true) -> "Voice message"
                        rawReplyText.startsWith("🎤 ") -> rawReplyText.removePrefix("🎤 ").trim()
                        rawReplyText.startsWith("📷 ") -> rawReplyText.removePrefix("📷 ").trim()
                        rawReplyText.startsWith("🎬 ") -> rawReplyText.removePrefix("🎬 ").trim()
                        else -> rawReplyText
                    }

                    Surface(
                        color = if (isOutgoing)
                            TealPrimary.copy(alpha = 0.12f)
                        else
                            GreenCall.copy(alpha = 0.10f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = replyMsgId.isNotBlank()) {
                                onReplyQuoteClick(replyMsgId)
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .fillMaxHeight()
                                    .background(if (isOutgoing) TealPrimary else GreenCall)
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 2.dp)
                            ) {
                                if (senderLbl.isNotBlank()) {
                                    Text(
                                        senderLbl,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.ExtraBold,
                                            color = if (isOutgoing) TealPrimary else GreenCall
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isVoiceReply) {
                                        Icon(
                                            Icons.Default.Mic,
                                            contentDescription = null,
                                            tint = if (isOutgoing) TealPrimary else GreenCall,
                                            modifier = Modifier
                                                .size(14.dp)
                                                .padding(end = 2.dp)
                                        )
                                    } else if (isImageReply) {
                                        Icon(
                                            Icons.Default.Image,
                                            contentDescription = null,
                                            tint = if (isOutgoing) TealPrimary else GreenCall,
                                            modifier = Modifier
                                                .size(14.dp)
                                                .padding(end = 2.dp)
                                        )
                                    }
                                    Text(
                                        displayReplyText,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // ── View Once Media Pill Bubble (WhatsApp Style) ──
                if (message.isViewOnce) {
                    val isOpened = message.isViewOnceOpened
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = when {
                            isOpened -> if (isDark) Color(0xFF202C33).copy(alpha = 0.65f) else Color(0xFFE9EDEF).copy(alpha = 0.65f)
                            isOutgoing -> if (isDark) Color(0xFF005C4B) else Color(0xFFD9FDD3)
                            else -> if (isDark) Color(0xFF202C33) else Color.White
                        },
                        border = androidx.compose.foundation.BorderStroke(
                            width = 1.dp,
                            color = when {
                                isOpened -> Color.Gray.copy(alpha = 0.35f)
                                isOutgoing -> GreenCall.copy(alpha = 0.5f)
                                else -> TealPrimary.copy(alpha = 0.4f)
                            }
                        ),
                        modifier = Modifier
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .combinedClickable(
                                onClick = {
                                    if (isOpened) {
                                        android.widget.Toast.makeText(
                                            context,
                                            "This view once media has already been viewed and destroyed",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    } else if (isOutgoing) {
                                        android.widget.Toast.makeText(
                                            context,
                                            "View once photos cannot be opened by sender after sending",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        val path = message.mediaPath
                                        if (!path.isNullOrBlank() && File(path).exists()) {
                                            onImageClick(path, message.id)
                                        } else {
                                            android.widget.Toast.makeText(
                                                context,
                                                "Media expired or unavailable",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onMessageLongClick(message)
                                }
                            )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            isOpened -> Color.Gray.copy(alpha = 0.25f)
                                            isOutgoing -> GreenCall.copy(alpha = 0.2f)
                                            else -> TealPrimary.copy(alpha = 0.18f)
                                        }
                                    )
                                    .border(
                                        width = 1.5.dp,
                                        color = if (isOpened) Color.Gray.copy(alpha = 0.5f) else GreenCall,
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "①",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (isOpened) Color.Gray else GreenCall
                                )
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isOpened) "Opened" else "Photo",
                                    fontSize = 15.sp,
                                    fontWeight = if (isOpened) FontWeight.Normal else FontWeight.SemiBold,
                                    color = when {
                                        isOpened -> Color.Gray
                                        isDark -> Color.White
                                        else -> Color.Black
                                    }
                                )
                                Text(
                                    text = if (isOpened) "Viewed & destroyed" else "View once",
                                    fontSize = 11.sp,
                                    color = if (isOpened) Color.Gray.copy(alpha = 0.7f) else if (isDark) Color.LightGray else Color.DarkGray
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            // Timestamp and Status Ticks (Single tick, double tick, double blue tick)
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val timeString = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(message.timestamp))
                                Text(
                                    text = timeString,
                                    fontSize = 10.sp,
                                    color = if (isOpened) Color.Gray else if (isDark) Color.LightGray else Color(0xFF667781)
                                )
                                if (isOutgoing) {
                                    Spacer(modifier = Modifier.width(3.dp))
                                    StatusTickIcon(status = message.status, isOutgoingBubble = true)
                                }
                            }
                        }
                    }
                } else if (message.mediaType == ChatMediaType.IMAGE.name) {
                    val rawText = message.text.trim()
                    val isGifMsg = rawText.startsWith("[gif:") ||
                                   rawText.contains(".gif", ignoreCase = true) ||
                                   (message.mediaPath?.endsWith(".gif", ignoreCase = true) == true)

                    val gifUrlFromText = ChatRepository.extractGifUrl(rawText)
                    val trimmedMediaPath = message.mediaPath?.trim()
                    val effectiveImageModel = when {
                        !trimmedMediaPath.isNullOrBlank() && (
                            File(trimmedMediaPath).exists() ||
                            trimmedMediaPath.startsWith("content://") ||
                            trimmedMediaPath.startsWith("file://") ||
                            trimmedMediaPath.startsWith("http") ||
                            trimmedMediaPath.length > 64
                        ) -> trimmedMediaPath
                        !gifUrlFromText.isNullOrBlank() -> gifUrlFromText
                        else -> null
                    }

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
                                .combinedClickable(
                                    onClick = {
                                        if (!message.isOutgoing && message.status != MessageStatus.READ.name) {
                                            onMarkMessageRead(message.id)
                                        }
                                        onImageClick(effectiveImageModel, null)
                                    },
                                    onLongClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onMessageLongClick(message)
                                    }
                                )
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
                                            StatusTickIcon(status = message.status, isOutgoingBubble = true)
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

                    if (isVideo) {
                        WhatsAppVideoBubbleCard(
                            message = message,
                            videoFile = docFile,
                            hasFile = hasFile,
                            fileName = fileName,
                            isOutgoing = isOutgoing,
                            transferProgress = transferProgress,
                            onVideoClick = onVideoClick,
                            onCancelTransfer = onCancelTransfer,
                            onMessageLongClick = onMessageLongClick,
                            onMarkMessageRead = onMarkMessageRead
                        )
                    } else {
                        val audioDurationMs = remember(docFile, hasFile, isAudio, message.mediaDurationMs) {
                            if (message.mediaDurationMs > 0L) message.mediaDurationMs
                            else if (isAudio && hasFile) {
                                try {
                                    val doc = docFile
                                    if (doc != null) {
                                        val retriever = android.media.MediaMetadataRetriever()
                                        retriever.setDataSource(doc.absolutePath)
                                        val dur = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                                        retriever.release()
                                        dur
                                    } else 0L
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
                                if (ext in listOf("JPG", "JPEG", "PNG", "WEBP", "GIF")) "Transfer incomplete" else "Transferring document..."
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
                                                val msg = when {
                                                    ext in listOf("JPG", "JPEG", "PNG", "WEBP", "GIF") -> "Photo transfer could not complete. Please resend."
                                                    else -> "Document is preparing or saved elsewhere"
                                                }
                                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
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
                                                    else -> ext.take(4)
                                                },
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
                                            docFile?.let { onPlayAudio(it.absolutePath) }
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
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // ── WhatsApp Location Bubble Card (Google Maps) ───────────────
                if (sharedLocation != null && !isDeleted) {
                    WhatsAppLocationBubbleCard(
                        locationData = sharedLocation,
                        isOutgoing = isOutgoing,
                        onOpenMap = {
                            openInGoogleMaps(
                                context = context,
                                latitude = sharedLocation.latitude,
                                longitude = sharedLocation.longitude,
                                label = sharedLocation.title,
                                navigationMode = false
                            )
                        },
                        onDirections = {
                            openInGoogleMaps(
                                context = context,
                                latitude = sharedLocation.latitude,
                                longitude = sharedLocation.longitude,
                                label = sharedLocation.title,
                                navigationMode = true
                            )
                        }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // ── WhatsApp Contact Card ─────────────────────────────────────
                if (sharedContact != null && !isDeleted) {
                    WhatsAppContactBubbleCard(
                        contactData = sharedContact,
                        isOutgoing = isOutgoing,
                        onCallClick = { phone, _ ->
                            try {
                                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(dialIntent)
                            } catch (_: Exception) {}
                        }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // ── WhatsApp Interactive Poll Card ────────────────────────────
                if (sharedPoll != null && !isDeleted) {
                    WhatsAppPollBubbleCard(
                        pollData = sharedPoll,
                        isOutgoing = isOutgoing
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // ── Rich Link Preview Card (WhatsApp style) ───────────────────
                val activePreview = linkPreview
                if (activePreview != null && !isDeleted && !isRichWhatsAppPayload) {
                    LinkPreviewCard(
                        preview = activePreview,
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
                if (displayText.isNotBlank() && !isHeroPreviewOnly && !isFrameless && !isRichWhatsAppPayload && message.mediaType != ChatMediaType.DOCUMENT.name) {
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

                // ── Timestamp & Ticks (Only if not already rendered inside image/GIF/video overlay) ──
                if (!isImageOnly && !isVideoMessage) {
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
                            color = if (isOutgoing) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                        if (isOutgoing) {
                            Spacer(modifier = Modifier.width(4.dp))
                            StatusTickIcon(status = message.status, isOutgoingBubble = true)
                        }
                    }
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// WhatsApp-style Video Message Bubble Card
// ──────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WhatsAppVideoBubbleCard(
    message: MessageEntity,
    videoFile: File?,
    hasFile: Boolean,
    fileName: String,
    isOutgoing: Boolean,
    transferProgress: com.example.data.p2p.FileTransferProgress?,
    onVideoClick: ((File) -> Unit)?,
    onCancelTransfer: () -> Unit,
    onMessageLongClick: (MessageEntity) -> Unit,
    onMarkMessageRead: (String) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val isDark = com.example.ui.theme.LocalIsDarkTheme.current

    // Video metadata (duration, width, height, formatted strings)
    val metadataState = produceState<com.example.util.VideoMetadata?>(
        initialValue = null,
        key1 = videoFile?.absolutePath,
        key2 = videoFile?.lastModified()
    ) {
        value = if (videoFile != null && videoFile.exists()) {
            com.example.util.VideoThumbnailHelper.getVideoMetadata(context, videoFile)
        } else null
    }

    // Video thumbnail extraction with memory LRU & disk cache
    val thumbnailState = produceState<Bitmap?>(
        initialValue = null,
        key1 = videoFile?.absolutePath,
        key2 = videoFile?.lastModified()
    ) {
        value = if (videoFile != null && videoFile.exists()) {
            com.example.util.VideoThumbnailHelper.getVideoThumbnail(context, videoFile)
        } else null
    }
    val thumbnail = thumbnailState.value

    val isTransferringIncoming = !isOutgoing && transferProgress != null && transferProgress.percent < 100

    val formattedDuration = remember(metadataState.value, message.mediaDurationMs) {
        val ms = if (message.mediaDurationMs > 0L) message.mediaDurationMs else (metadataState.value?.durationMs ?: 0L)
        if (ms > 0L) {
            com.example.util.VideoThumbnailHelper.formatDuration(ms)
        } else {
            metadataState.value?.formattedDuration?.takeIf { it != "0:00" } ?: ""
        }
    }

    val formattedSize = remember(videoFile, metadataState.value, transferProgress) {
        if (videoFile != null && videoFile.exists() && videoFile.length() > 0L) {
            com.example.util.VideoThumbnailHelper.formatFileSize(videoFile.length())
        } else if (transferProgress != null && transferProgress.totalBytes > 0L) {
            com.example.util.VideoThumbnailHelper.formatFileSize(transferProgress.totalBytes)
        } else {
            metadataState.value?.formattedSize ?: ""
        }
    }

    val hasCaption = remember(message.text, fileName, videoFile) {
        val raw = message.text.trim()
        val baseDocName = fileName.trim()
        val fileObjName = videoFile?.name?.trim() ?: ""
        raw.isNotBlank() &&
            !raw.equals(baseDocName, ignoreCase = true) &&
            (fileObjName.isBlank() || !raw.equals(fileObjName, ignoreCase = true))
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Video Preview Card Box
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 180.dp, max = 320.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (isDark) Color(0xFF182229) else Color(0xFFE9EDEF))
                .combinedClickable(
                    onClick = {
                        if (!message.isOutgoing && message.status != MessageStatus.READ.name) {
                            onMarkMessageRead(message.id)
                        }
                        if (hasFile && videoFile != null) {
                            if (onVideoClick != null) {
                                onVideoClick(videoFile)
                            } else {
                                try {
                                    val fileUri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        videoFile
                                    )
                                    val mime = android.webkit.MimeTypeMap.getSingleton()
                                        .getMimeTypeFromExtension(videoFile.extension.lowercase(Locale.getDefault())) ?: "video/*"
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(fileUri, mime)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    Toast.makeText(context, "No video player found", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } else {
                            val msg = if (transferProgress != null) "Video is transferring..." else "Video file not found"
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onMessageLongClick(message)
                    }
                )
        ) {
            // ── Video Preview Image or Dark Gradient Fallback ─────────────────
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = "Video preview",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (isTransferringIncoming) Modifier.blur(8.dp) else Modifier)
                )
                if (isTransferringIncoming) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.38f))
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFF263238),
                                    Color(0xFF182229),
                                    Color(0xFF10171D)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Videocam,
                        contentDescription = "Video preview placeholder",
                        tint = Color.White.copy(alpha = 0.35f),
                        modifier = Modifier.size(56.dp)
                    )
                }
            }

            // ── Center Play Button / Transfer Indicator ──────────────────────
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .border(1.5.dp, Color.White.copy(alpha = 0.85f), CircleShape)
                    .clickable {
                        if (!message.isOutgoing && message.status != MessageStatus.READ.name) {
                            onMarkMessageRead(message.id)
                        }
                        if (hasFile && videoFile != null) {
                            onVideoClick?.invoke(videoFile)
                        } else {
                            val msg = if (transferProgress != null) "Video is transferring..." else "Video file not found"
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isTransferringIncoming && !hasFile) {
                    CircularProgressIndicator(
                        progress = { (transferProgress?.percent ?: 0) / 100f },
                        color = Color.White,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(30.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Play video",
                        tint = Color.White,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }

            // ── Bottom-Left Pill Badge: Duration • File Size ─────────────────
            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Videocam,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    if (formattedDuration.isNotBlank()) {
                        Text(
                            text = formattedDuration,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        if (formattedSize.isNotBlank()) {
                            Text(
                                text = " • ",
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Normal
                            )
                        }
                    }
                    if (formattedSize.isNotBlank()) {
                        Text(
                            text = formattedSize,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    } else if (formattedDuration.isBlank()) {
                        Text(
                            text = "Video",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // ── Bottom-Right Pill Badge: Time + Status Tick ──────────────────
            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (message.isStarred) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = "Starred",
                            tint = Color(0xFFFFC107),
                            modifier = Modifier.size(11.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                    }
                    val timeString = remember(message.timestamp) {
                        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(message.timestamp))
                    }
                    Text(
                        text = timeString,
                        color = Color.White,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Normal
                    )
                    if (isOutgoing) {
                        Spacer(modifier = Modifier.width(3.dp))
                        StatusTickIcon(status = message.status, isOutgoingBubble = true)
                    }
                }
            }
        }

        // ── P2P / Relay Transfer Progress ────────────────────────────────────
        if (transferProgress != null) {
            Spacer(modifier = Modifier.height(6.dp))
            FileTransferProgressCard(
                progress = transferProgress,
                onCancel = onCancelTransfer
            )
        }

        // ── Caption Support ──────────────────────────────────────────────────
        if (hasCaption) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.5.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Normal
                ),
                color = if (isOutgoing) Color.White else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
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

    val themeColor = LocalThemeColor.current
    if (urls.isEmpty()) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isOutgoing) Color.White else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
    } else {
        val isDark = com.example.ui.theme.LocalIsDarkTheme.current
        val linkColor = when {
            isOutgoing            -> Color(0xFFFFF3CD) // Warm light cream underline on orange bubble
            !isOutgoing && isDark -> themeColor.accent // Soft amber on dark slate bubble
            else                  -> themeColor.primaryDark // Vibrant link orange on white bubble
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
                color = if (isOutgoing) Color.White else MaterialTheme.colorScheme.onSurface
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
    val isDark = com.example.ui.theme.LocalIsDarkTheme.current
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
    phoneNumber: String = "",
    size: androidx.compose.ui.unit.Dp = 40.dp,
    fontSize: androidx.compose.ui.unit.TextUnit = 16.sp,
    isGroup: Boolean = false
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
    } else if (isGroup) {
        Surface(
            modifier = Modifier.size(size),
            shape = CircleShape,
            color = TealPrimary.copy(alpha = 0.15f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.People,
                    contentDescription = name,
                    tint = TealPrimary,
                    modifier = Modifier.size(size * 0.55f)
                )
            }
        }
    } else {
        // Vibrant 3D Stylized Character Avatar
        val charRes = Avatar3dHelper.getAvatarResId(name, phoneNumber)
        Image(
            painter = androidx.compose.ui.res.painterResource(id = charRes),
            contentDescription = name,
            modifier = Modifier
                .size(size)
                .clip(CircleShape),
            contentScale = ContentScale.Crop
        )
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

@Composable
private fun GroupInfoDialog(
    group: GroupDto?,
    firebaseManager: FirebaseManager,
    onDismiss: () -> Unit,
    onExitGroup: () -> Unit,
    onDeleteGroup: () -> Unit,
    onRemoveMember: (memberPhone: String) -> Unit,
    onAddMembersClick: () -> Unit,
    onUpdateGroupIcon: (base64Icon: String) -> Unit
) {
    if (group == null) return
    val context = LocalContext.current
    val registeredUsers by firebaseManager.registeredUsers.collectAsState()
    val syncedContacts by firebaseManager.contacts.collectAsState()
    val myPhone = firebaseManager.currentUser.value?.phoneNumber ?: ""
    val isCurrentUserAdmin = ContactsHelper.numbersMatch(group.adminPhone, myPhone)

    val iconPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val base64 = com.example.util.ImageUtils.compressUriToBase64(context, uri)
            if (base64 != null) {
                onUpdateGroupIcon(base64)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.People, contentDescription = null, tint = TealPrimary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(group.name, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Group Avatar with Camera Edit Badge
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .clickable { iconPickerLauncher.launch("image/*") }
                    ) {
                        ChatAvatar(
                            name = group.name,
                            profilePic = group.iconUrl,
                            size = 72.dp,
                            fontSize = 28.sp,
                            isGroup = true
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .align(Alignment.BottomEnd)
                            .clip(CircleShape)
                            .background(TealPrimary)
                            .clickable { iconPickerLauncher.launch("image/*") }
                            .padding(4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.CameraAlt,
                            contentDescription = "Change group photo",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                Text(
                    text = "${group.members.size} members • E2EE Encrypted",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Members",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = TealPrimary
                    )
                    TextButton(
                        onClick = onAddMembersClick,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp), tint = TealPrimary)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = TealPrimary)
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                    items(group.members) { memberPhone ->
                        val isMe = ContactsHelper.numbersMatch(memberPhone, myPhone)
                        val matchedUser = registeredUsers.find { ContactsHelper.numbersMatch(it.phoneNumber, memberPhone) }
                        val matchedContact = syncedContacts.find { ContactsHelper.numbersMatch(it.phoneNumber, memberPhone) }
                        val rawName = matchedUser?.displayName?.takeIf { it.isNotBlank() }
                            ?: matchedContact?.name?.takeIf { it.isNotBlank() }
                            ?: memberPhone
                        val name = if (isMe) "$rawName (You)" else rawName
                        val isAdmin = ContactsHelper.numbersMatch(memberPhone, group.adminPhone)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ChatAvatar(name = name, profilePic = matchedUser?.profilePictureUrl ?: matchedContact?.profilePictureUrl ?: "", size = 32.dp, fontSize = 14.sp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                if (rawName != memberPhone) {
                                    Text(memberPhone, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (isAdmin) {
                                Surface(
                                    color = TealPrimary.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = "Admin",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = TealPrimary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            if (isCurrentUserAdmin && !isMe) {
                                Spacer(modifier = Modifier.width(4.dp))
                                IconButton(
                                    onClick = { onRemoveMember(memberPhone) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(12.dp))

                // Bottom Group Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onExitGroup,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                    ) {
                        Icon(Icons.Default.ExitToApp, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Exit", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    if (isCurrentUserAdmin) {
                        Button(
                            onClick = onDeleteGroup,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Delete", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = TealPrimary, fontWeight = FontWeight.Bold)
            }
        }
    )
}


@Composable
fun EmptyChatWelcomeCard(
    peerName: String,
    onQuickAction: (String) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = androidx.compose.ui.res.painterResource(id = com.example.R.drawable.character_3d_wave),
                contentDescription = "Say Hello",
                modifier = Modifier
                    .size(120.dp)
                    .clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Fit
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Say Hello to $peerName! 👋",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Messages are end-to-end encrypted. No one outside of this chat can read them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf("👋 Hi!", "🍕 Free to chat?", "📞 Quick call?").forEach { starter ->
                    Surface(
                        onClick = { onQuickAction(starter) },
                        shape = RoundedCornerShape(16.dp),
                        color = com.example.ui.theme.SwiggyOrange.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, com.example.ui.theme.SwiggyOrange.copy(alpha = 0.35f))
                    ) {
                        Text(
                            text = starter,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = com.example.ui.theme.SwiggyOrange,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}
