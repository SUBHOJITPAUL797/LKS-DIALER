package com.example.data.repository

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import com.example.MainActivity
import com.example.data.crypto.ChatCryptoManager
import com.example.data.local.*
import com.example.data.model.ChatMessageDto
import com.example.data.model.ChatReceiptDto
import com.example.data.model.UserDto
import com.example.util.ContactsHelper
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import androidx.core.graphics.drawable.IconCompat
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

/**
 * ChatRepository
 *
 * Coordinates End-to-End Encrypted (E2EE) messaging with an Ephemeral Store-and-Forward Relay:
 *
 * 1. Messages are encrypted locally on the sender device via ChatCryptoManager (ECDH + AES-256-GCM).
 * 2. Sent to Firestore under `inboxes/{recipientPhone}/messages/{messageId}`.
 * 3. When recipient receives the message, it is immediately decrypted and persisted to local Room DB.
 * 4. CRITICAL ZERO-RETENTION: The message document is IMMEDIATELY DELETED from Firestore.
 * 5. Recipient posts an ACK to `receipts/{senderPhone}/acks/{ackId}`.
 * 6. Sender receives ACK, updates local Room status to DELIVERED (✓✓) or READ (blue ✓✓),
 *    and IMMEDIATELY DELETES the ACK from Firestore as well.
 */
class ChatRepository private constructor(private val context: Context) {

    companion object {
        private const val TAG = "ChatRepository"
        const val CHAT_NOTIFICATION_CHANNEL_ID = "chat_messages_channel"
        const val KEY_TEXT_REPLY = "key_text_reply"

        @Volatile
        private var INSTANCE: ChatRepository? = null

        fun getInstance(context: Context): ChatRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ChatRepository(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun extractCleanText(rawText: String): String {
            if (rawText.isBlank()) return ""
            return try {
                val obj = JSONObject(rawText)
                obj.optString("text", rawText)
            } catch (_: Exception) {
                rawText
            }
        }

        fun getCircularBitmap(bitmap: Bitmap): Bitmap {
            val size = Math.min(bitmap.width, bitmap.height)
            val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint().apply {
                isAntiAlias = true
                shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
            val radius = size / 2f
            canvas.drawCircle(radius, radius, radius, paint)
            return output
        }
    }

    private val db = ChatDatabase.getInstance(context)
    private val messageDao = db.messageDao()
    private val conversationDao = db.conversationDao()
    private val cryptoManager = ChatCryptoManager.getInstance(context)
    private val firestore = FirebaseFirestore.getInstance()

    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Tracks currently open chat screen to suppress notifications & auto-mark READ
    private val _activeChatPeerNumber = MutableStateFlow<String?>(null)
    val activeChatPeerNumber: StateFlow<String?> = _activeChatPeerNumber.asStateFlow()

    @Volatile
    private var isAppInForeground: Boolean = false

    fun setAppForeground(foreground: Boolean) {
        isAppInForeground = foreground
        if (foreground) {
            _activeChatPeerNumber.value?.let { activePeer ->
                repositoryScope.launch {
                    markConversationAsRead(activePeer)
                }
            }
        } else {
            // When app leaves foreground, clear active chat peer so background incoming messages
            // NEVER get auto-marked as READ or send fake blue ticks!
            _activeChatPeerNumber.value = null
        }
    }

    // Real-time typing indicators: peerPhoneNumber -> isTyping
    private val _typingStatus = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val typingStatus: StateFlow<Map<String, Boolean>> = _typingStatus.asStateFlow()

    private var inboxListener: ListenerRegistration? = null
    private var receiptsListener: ListenerRegistration? = null
    private var typingListener: ListenerRegistration? = null
    private var currentListeningPhone: String? = null

    init {
        createNotificationChannel()
        ensureMediaDirectory()
        repositoryScope.launch {
            syncOutdatedConversationStatuses()
        }
    }

    fun setActiveChatPeer(phoneNumber: String?) {
        val normalized = phoneNumber?.let { ContactsHelper.normalizePhoneNumber(it) }
        _activeChatPeerNumber.value = normalized
        if (normalized != null) {
            repositoryScope.launch {
                markConversationAsRead(normalized)
            }
        }
    }

    private fun ensureMediaDirectory(): File {
        val mediaDir = File(context.filesDir, "chat_media")
        if (!mediaDir.exists()) {
            mediaDir.mkdirs()
        }
        return mediaDir
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHAT_NOTIFICATION_CHANNEL_ID,
                "Chat Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "End-to-End Encrypted chat notifications"
                enableVibration(true)
                enableLights(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    /**
     * Connects real-time listeners for the logged-in user.
     */
    @Synchronized
    fun attachChatListeners(myPhoneNumber: String) {
        val normalizedMyPhone = ContactsHelper.normalizePhoneNumber(myPhoneNumber)
        if (currentListeningPhone == normalizedMyPhone && inboxListener != null) return

        currentListeningPhone = normalizedMyPhone
        detachChatListeners()

        Log.d(TAG, "Attaching ephemeral chat listeners for: $normalizedMyPhone")

        // 1. Inbox Listener: listens for incoming encrypted messages
        inboxListener = firestore.collection("inboxes")
            .document(normalizedMyPhone)
            .collection("messages")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener

                for (change in snapshot.documentChanges) {
                    if (change.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                        val doc = change.document
                        val messageDto = doc.toObject(ChatMessageDto::class.java)
                        repositoryScope.launch {
                            processIncomingMessage(messageDto, doc.reference)
                        }
                    }
                }
            }

        // 2. Receipts Listener: listens for DELIVERED and READ receipts
        receiptsListener = firestore.collection("receipts")
            .document(normalizedMyPhone)
            .collection("acks")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener

                for (change in snapshot.documentChanges) {
                    if (change.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                        val doc = change.document
                        val receiptDto = doc.toObject(ChatReceiptDto::class.java)
                        repositoryScope.launch {
                            processIncomingReceipt(receiptDto, doc.reference)
                        }
                    }
                }
            }

        // 3. Typing Status Listener
        typingListener = firestore.collection("typingStatus")
            .document(normalizedMyPhone)
            .collection("peers")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                val current = _typingStatus.value.toMutableMap()
                val now = System.currentTimeMillis()

                snapshot.documents.forEach { doc ->
                    val peer = doc.id
                    val isTyping = doc.getBoolean("isTyping") ?: false
                    val timestamp = doc.getLong("timestamp") ?: 0L
                    // If typing ping is older than 5 seconds, treat as false
                    current[peer] = isTyping && (now - timestamp < 5000)
                }
                _typingStatus.value = current
            }

        // 4. Reconcile any existing conversation statuses
        repositoryScope.launch {
            syncOutdatedConversationStatuses()
        }
    }

    @Synchronized
    fun detachChatListeners() {
        inboxListener?.remove()
        inboxListener = null
        receiptsListener?.remove()
        receiptsListener = null
        typingListener?.remove()
        typingListener = null
    }

    /**
     * Processes an incoming encrypted message:
     * 1. Decrypts payload
     * 2. Saves to local Room database
     * 3. IMMEDIATELY DELETES document from Firestore (Zero Server Retention)
     * 4. Sends ACK receipt back to sender
     * 5. Displays notification if not in foreground
     */
    private suspend fun processIncomingMessage(
        dto: ChatMessageDto,
        docRef: com.google.firebase.firestore.DocumentReference
    ) {
        if (dto.messageId.isBlank() || dto.ciphertext.isBlank()) {
            try { docRef.delete() } catch (_: Exception) {}
            return
        }

        // Deduplication guard: if already processed and saved in Room DB, delete ephemeral relay document and skip
        if (messageDao.getMessageById(dto.messageId) != null) {
            try { docRef.delete().await() } catch (_: Exception) {}
            return
        }

        try {
            // STEP 1: Decrypt message payload
            val decryptedRaw = cryptoManager.decrypt(dto.ciphertext, dto.iv, dto.senderPublicKey)
            val senderNorm = ContactsHelper.normalizePhoneNumber(dto.senderNumber)
            val senderLast10 = senderNorm.filter { it.isDigit() }.takeLast(10)
            val isCurrentPeer = isAppInForeground && _activeChatPeerNumber.value?.let {
                ContactsHelper.numbersMatch(it, senderNorm)
            } == true

            // When peer sends a message to us, all our prior outgoing messages to them MUST have been read by them!
            messageDao.updateOutgoingMessagesStatus(senderNorm, senderLast10, MessageStatus.READ.name)
            val lastOutgoing = messageDao.getLastMessageForConversation(senderNorm, senderLast10)
            if (lastOutgoing != null && lastOutgoing.isOutgoing) {
                conversationDao.updateLastMessageStatus(senderNorm, senderLast10, MessageStatus.READ.name)
            }

            // Handle incoming Message Edit packets
            if (dto.mediaType == ChatMediaType.EDIT.name) {
                try {
                    val json = JSONObject(decryptedRaw)
                    val originalMessageId = json.optString("originalMessageId", "")
                    val newText = json.optString("newText", "")
                    if (originalMessageId.isNotBlank()) {
                        val existing = messageDao.getMessageById(originalMessageId)
                        val textToUpdate = if (existing != null) {
                            try {
                                val existingJson = JSONObject(existing.text)
                                if (existingJson.has("replyTo")) {
                                    existingJson.put("text", newText)
                                    existingJson.toString()
                                } else newText
                            } catch (_: Exception) { newText }
                        } else newText

                        messageDao.updateMessageText(originalMessageId, textToUpdate)
                        val lastMsg = messageDao.getLastMessageForConversation(senderNorm)
                        if (lastMsg != null && lastMsg.id == originalMessageId) {
                            conversationDao.updateLastMessageText(senderNorm, newText)
                        }
                        Log.d(TAG, "Message $originalMessageId updated via edit packet from $senderNorm")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse message edit packet: ${e.message}")
                }
                // Zero retention: delete ephemeral edit doc immediately
                try { docRef.delete().await() } catch (_: Exception) {}
                return
            }

            // Handle incoming Message Delete packets
            if (dto.mediaType == ChatMediaType.DELETE.name) {
                try {
                    val json = JSONObject(decryptedRaw)
                    val targetMessageId = json.optString("targetMessageId", "")
                    if (targetMessageId.isNotBlank()) {
                        val existing = messageDao.getMessageById(targetMessageId)
                        if (existing != null) {
                            existing.mediaPath?.let { p -> try { File(p).delete() } catch (_: Exception) {} }
                            val tombstone = "🚫 This message was deleted"
                            messageDao.markMessageDeletedForEveryone(targetMessageId, tombstone)
                            val lastMsg = messageDao.getLastMessageForConversation(senderNorm)
                            if (lastMsg != null && lastMsg.id == targetMessageId) {
                                conversationDao.updateLastMessageText(senderNorm, tombstone)
                            }
                            Log.d(TAG, "Message $targetMessageId marked deleted for everyone from $senderNorm")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse message delete packet: ${e.message}")
                }
                try { docRef.delete().await() } catch (_: Exception) {}
                return
            }

            // Handle incoming File Chunks (Large documents / files)
            if (dto.mediaType == ChatMediaType.CHUNK.name) {
                try {
                    val json = JSONObject(decryptedRaw)
                    val parentId = json.getString("parentMessageId")
                    val fileName = json.getString("fileName")
                    val chunkIndex = json.getInt("chunkIndex")
                    val totalChunks = json.getInt("totalChunks")
                    val base64Chunk = json.getString("bytes")

                    val chunksDir = File(context.cacheDir, "chunks_$parentId")
                    if (!chunksDir.exists()) chunksDir.mkdirs()
                    val partFile = File(chunksDir, "part_$chunkIndex")
                    val bytes = Base64.decode(base64Chunk, Base64.NO_WRAP)
                    partFile.writeBytes(bytes)

                    // Check if all chunks have arrived
                    var allPresent = true
                    for (i in 0 until totalChunks) {
                        if (!File(chunksDir, "part_$i").exists()) {
                            allPresent = false
                            break
                        }
                    }

                    if (allPresent) {
                        val ext = fileName.substringAfterLast('.', "bin")
                        val finalFile = File(ensureMediaDirectory(), "doc_${parentId}.$ext")
                        FileOutputStream(finalFile).use { fos ->
                            for (i in 0 until totalChunks) {
                                val part = File(chunksDir, "part_$i")
                                if (part.exists()) {
                                    fos.write(part.readBytes())
                                    part.delete()
                                }
                            }
                        }
                        try { chunksDir.delete() } catch (_: Exception) {}

                        // Insert reassembled message into Room DB
                        val messageEntity = MessageEntity(
                            id = parentId,
                            conversationId = senderNorm,
                            senderNumber = dto.senderNumber,
                            recipientNumber = dto.recipientNumber,
                            text = fileName,
                            mediaType = ChatMediaType.DOCUMENT.name,
                            mediaPath = finalFile.absolutePath,
                            mediaDurationMs = 0L,
                            timestamp = dto.timestamp.takeIf { it > 0 } ?: System.currentTimeMillis(),
                            status = MessageStatus.DELIVERED.name,
                            isOutgoing = false
                        )
                        messageDao.insertMessage(messageEntity)

                        // Update conversation summary
                        val firebaseManager = FirebaseManager.getInstance(context)
                        val registeredUser = firebaseManager.lookupUserByNumber(senderNorm)
                        val contactInfo = firebaseManager.contacts.value.find { ContactsHelper.numbersMatch(it.phoneNumber, senderNorm) }
                        val resolvedName = registeredUser?.displayName?.ifBlank { null }
                            ?: contactInfo?.name?.ifBlank { null }
                            ?: senderNorm
                        val profilePic = registeredUser?.profilePictureUrl ?: contactInfo?.profilePictureUrl ?: ""

                        val existingConv = conversationDao.getConversation(senderNorm)
                        val unreadCount = if (isCurrentPeer) 0 else ((existingConv?.unreadCount ?: 0) + 1)
                        val convEntity = ConversationEntity(
                            phoneNumber = senderNorm,
                            contactName = resolvedName,
                            profilePicUrl = profilePic,
                            lastMessageText = "📄 $fileName",
                            lastMessageType = ChatMediaType.DOCUMENT.name,
                            lastMessageTimestamp = messageEntity.timestamp,
                            lastMessageStatus = messageEntity.status,
                            lastMessageIsOutgoing = false,
                            unreadCount = unreadCount,
                            isPinned = existingConv?.isPinned ?: false
                        )
                        conversationDao.upsertConversation(convEntity)

                        // Send DELIVERED receipt for the parent message
                        sendReceipt(dto.senderNumber, parentId, MessageStatus.DELIVERED.name)

                        // Show notification if in background
                        if (!isCurrentPeer) {
                            showIncomingMessageNotification(
                                senderNumber = senderNorm,
                                senderName = resolvedName,
                                messageText = "📄 $fileName",
                                messageType = ChatMediaType.DOCUMENT.name
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to process file chunk: ${e.message}")
                }
                // Zero retention: delete chunk doc from Firestore immediately
                try { docRef.delete().await() } catch (_: Exception) {}
                return
            }

            var displayText = decryptedRaw
            var localMediaPath: String? = null
            var durationMs = dto.mediaDurationMs

            // Handle structured JSON payloads (Images and Audio Notes)
            if (dto.mediaType == ChatMediaType.IMAGE.name) {
                try {
                    val json = JSONObject(decryptedRaw)
                    displayText = json.optString("caption", "Photo")
                    val base64Data = json.optString("bytes", "")
                    if (base64Data.isNotBlank()) {
                        val imgFile = File(ensureMediaDirectory(), "img_${dto.messageId}.jpg")
                        val bytes = Base64.decode(base64Data, Base64.NO_WRAP)
                        FileOutputStream(imgFile).use { it.write(bytes) }
                        localMediaPath = imgFile.absolutePath
                    }
                } catch (_: Exception) {
                    displayText = "Photo"
                }
            } else if (dto.mediaType == ChatMediaType.AUDIO.name) {
                try {
                    val json = JSONObject(decryptedRaw)
                    displayText = "Voice message"
                    durationMs = json.optLong("duration", dto.mediaDurationMs)
                    val base64Data = json.optString("bytes", "")
                    if (base64Data.isNotBlank()) {
                        val audioFile = File(ensureMediaDirectory(), "voice_${dto.messageId}.m4a")
                        val bytes = Base64.decode(base64Data, Base64.NO_WRAP)
                        FileOutputStream(audioFile).use { it.write(bytes) }
                        localMediaPath = audioFile.absolutePath
                    }
                } catch (_: Exception) {
                    displayText = "Voice message"
                }
            } else if (dto.mediaType == ChatMediaType.DOCUMENT.name) {
                try {
                    val json = JSONObject(decryptedRaw)
                    val fileName = json.optString("fileName", "Document")
                    displayText = fileName
                    val base64Data = json.optString("bytes", "")
                    if (base64Data.isNotBlank()) {
                        val ext = fileName.substringAfterLast('.', "bin")
                        val docFile = File(ensureMediaDirectory(), "doc_${dto.messageId}.$ext")
                        val bytes = Base64.decode(base64Data, Base64.NO_WRAP)
                        FileOutputStream(docFile).use { it.write(bytes) }
                        localMediaPath = docFile.absolutePath
                    }
                } catch (_: Exception) {
                    displayText = "Document"
                }
            }

            // Messages upon arrival are ALWAYS DELIVERED first so the sender sees double grey ticks.
            // Only if user is actively watching this conversation right now in the foreground,
            // we upgrade TEXT messages to READ.
            val initialStatus = MessageStatus.DELIVERED.name

            // STEP 2: Save to local Room DB
            val messageEntity = MessageEntity(
                id = dto.messageId,
                conversationId = senderNorm,
                senderNumber = dto.senderNumber,
                recipientNumber = dto.recipientNumber,
                text = displayText,
                mediaType = dto.mediaType,
                mediaPath = localMediaPath,
                mediaDurationMs = durationMs,
                timestamp = dto.timestamp.takeIf { it > 0 } ?: System.currentTimeMillis(),
                status = if (isCurrentPeer) MessageStatus.READ.name else initialStatus,
                isOutgoing = false
            )
            messageDao.insertMessage(messageEntity)

            // Resolve contact info for conversation header
            val firebaseManager = FirebaseManager.getInstance(context)
            val registeredUser = firebaseManager.lookupUserByNumber(senderNorm)
            val contactInfo = firebaseManager.contacts.value.find { ContactsHelper.numbersMatch(it.phoneNumber, senderNorm) }
            val resolvedName = registeredUser?.displayName?.ifBlank { null }
                ?: contactInfo?.name?.ifBlank { null }
                ?: senderNorm
            val profilePic = registeredUser?.profilePictureUrl ?: contactInfo?.profilePictureUrl ?: ""

            val existingConv = conversationDao.getConversation(senderNorm)
            val unreadCount = if (isCurrentPeer) 0 else ((existingConv?.unreadCount ?: 0) + 1)

            // For TEXT messages that contain reply metadata (JSON), extract just the visible text
            // so notifications and conversation previews show plain text, not raw JSON
            val notificationDisplayText = if (dto.mediaType == ChatMediaType.TEXT.name) {
                try {
                    val obj = JSONObject(displayText)
                    obj.optString("text", displayText)
                } catch (_: Exception) { displayText }
            } else displayText

            val convEntity = ConversationEntity(
                phoneNumber = senderNorm,
                contactName = resolvedName,
                profilePicUrl = profilePic,
                lastMessageText = when (dto.mediaType) {
                    ChatMediaType.IMAGE.name -> "📷 Photo"
                    ChatMediaType.AUDIO.name -> "🎤 Voice message"
                    ChatMediaType.DOCUMENT.name -> "📄 $displayText"
                    else -> notificationDisplayText
                },
                lastMessageType = dto.mediaType,
                lastMessageTimestamp = messageEntity.timestamp,
                lastMessageStatus = messageEntity.status,
                lastMessageIsOutgoing = false,
                unreadCount = unreadCount,
                isPinned = existingConv?.isPinned ?: false
            )
            conversationDao.upsertConversation(convEntity)

            // STEP 3: Delete ephemeral message from Firestore
            docRef.delete().await()

            // STEP 4: Send ACK back to sender (always confirms DELIVERED first)
            sendReceipt(
                recipientNumber = dto.senderNumber,
                messageId = dto.messageId,
                status = MessageStatus.DELIVERED.name
            )

            if (isCurrentPeer) {
                // If user is actively watching this conversation right now in the foreground,
                // auto-mark message as READ and send the READ receipt to the peer for all media types!
                sendReceipt(
                    recipientNumber = dto.senderNumber,
                    messageId = dto.messageId,
                    status = MessageStatus.READ.name
                )
            } else {
                // STEP 5: Always show notification if conversation is NOT open in foreground
                showIncomingMessageNotification(
                    senderNumber = senderNorm,
                    senderName = resolvedName,
                    messageText = when (dto.mediaType) {
                        ChatMediaType.IMAGE.name -> if (displayText.isNotBlank()) "📷 $displayText" else "📷 Photo"
                        ChatMediaType.AUDIO.name -> "🎤 Voice message"
                        ChatMediaType.DOCUMENT.name -> "📄 $displayText"
                        else -> notificationDisplayText
                    },
                    messageType = dto.mediaType
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt/process incoming message: ${e.message}", e)
            // Even if decryption fails, delete corrupted message from relay so it doesn't loop
            try { docRef.delete() } catch (_: Exception) {}
        }
    }

    /**
     * Processes an incoming delivery / read receipt:
     * 1. Updates message status in Room DB
     * 2. Updates conversation summary
     * 3. IMMEDIATELY DELETES receipt from Firestore
     */
    private suspend fun processIncomingReceipt(
        receipt: ChatReceiptDto,
        docRef: com.google.firebase.firestore.DocumentReference
    ) {
        if (receipt.messageId.isBlank()) {
            try { docRef.delete() } catch (_: Exception) {}
            return
        }

        try {
            val prefs = context.getSharedPreferences("dialer_prefs", Context.MODE_PRIVATE)
            val myPhone = currentListeningPhone
                ?: FirebaseManager.getInstance(context).currentUser.value?.phoneNumber
                ?: prefs.getString("user_phone", null)
                ?: ""

            val isSenderMe = ContactsHelper.numbersMatch(receipt.senderNumber, myPhone)
            val peerRaw = if (!isSenderMe && receipt.senderNumber.isNotBlank()) {
                receipt.senderNumber
            } else {
                receipt.recipientNumber
            }
            val peerNorm = ContactsHelper.normalizePhoneNumber(peerRaw)
            val last10 = peerNorm.filter { it.isDigit() }.takeLast(10)

            if (receipt.messageId != "all") {
                messageDao.updateMessageStatus(receipt.messageId, receipt.status)
                val specificMsg = messageDao.getMessageById(receipt.messageId)
                if (specificMsg != null && receipt.status == MessageStatus.READ.name) {
                    val cId = specificMsg.conversationId
                    val cLast10 = cId.filter { it.isDigit() }.takeLast(10)
                    messageDao.updateOutgoingMessagesStatus(cId, cLast10, MessageStatus.READ.name)
                    conversationDao.updateLastMessageStatus(cId, cLast10, MessageStatus.READ.name)
                }
            }

            // If READ receipt, also update all earlier outgoing messages with this peer to READ
            if (receipt.status == MessageStatus.READ.name) {
                messageDao.updateOutgoingMessagesStatus(peerNorm, last10, MessageStatus.READ.name)
                conversationDao.updateLastMessageStatus(peerNorm, last10, MessageStatus.READ.name)
            }

            // Sync the conversation entity's lastMessageStatus so the Chat tab list
            // reflects the updated ticks (Delivered or Read double blue ticks)
            val lastMsg = messageDao.getLastMessageForConversation(peerNorm, last10)
            if (lastMsg != null && lastMsg.isOutgoing) {
                conversationDao.updateLastMessageStatus(peerNorm, last10, lastMsg.status)
            }

            // Delete receipt from Firestore immediately
            docRef.delete()
            Log.d(TAG, "Receipt for ${receipt.messageId} (${receipt.status}) processed and deleted from Firestore")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to process receipt: ${e.message}")
        }
    }

    /**
     * Sends an ephemeral receipt (DELIVERED or READ) to peer's receipts inbox.
     */
    private fun sendReceipt(recipientNumber: String, messageId: String, status: String) {
        val normalized = ContactsHelper.normalizePhoneNumber(recipientNumber)
        val targetUser = FirebaseManager.getInstance(context).lookupUserByNumber(recipientNumber)
        val canonicalRecipient = targetUser?.phoneNumber?.takeIf { it.isNotBlank() } ?: normalized

        val prefs = context.getSharedPreferences("dialer_prefs", Context.MODE_PRIVATE)
        val myPhone = currentListeningPhone
            ?: FirebaseManager.getInstance(context).currentUser.value?.phoneNumber
            ?: prefs.getString("user_phone", null)
            ?: return

        if (currentListeningPhone == null) {
            currentListeningPhone = ContactsHelper.normalizePhoneNumber(myPhone)
        }

        val receiptId = UUID.randomUUID().toString()

        val receiptDto = ChatReceiptDto(
            receiptId = receiptId,
            messageId = messageId,
            senderNumber = myPhone,
            recipientNumber = canonicalRecipient,
            status = status,
            timestamp = System.currentTimeMillis()
        )

        firestore.collection("receipts")
            .document(canonicalRecipient)
            .collection("acks")
            .document(receiptId)
            .set(receiptDto)
            .addOnSuccessListener {
                Log.d(TAG, "Receipt sent successfully to $canonicalRecipient: msgId=$messageId, status=$status")
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to send receipt to $canonicalRecipient: ${e.message}")
            }
    }

    /**
     * Public API: Sends an End-to-End Encrypted message.
     */
    suspend fun sendMessage(
        recipientNumber: String,
        recipientName: String,
        text: String,
        mediaType: ChatMediaType = ChatMediaType.TEXT,
        mediaFile: File? = null,
        mediaDurationMs: Long = 0L
    ): Result<MessageEntity> = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("dialer_prefs", Context.MODE_PRIVATE)
        val myPhone = currentListeningPhone
            ?: FirebaseManager.getInstance(context).currentUser.value?.phoneNumber
            ?: prefs.getString("user_phone", null)
            ?: return@withContext Result.failure(IllegalStateException("Current user not logged in"))

        val normRecipient = ContactsHelper.normalizePhoneNumber(recipientNumber)
        val firebaseManager = FirebaseManager.getInstance(context)
        val targetUser = firebaseManager.lookupUserByNumber(recipientNumber)
        val canonicalRecipient = targetUser?.phoneNumber?.takeIf { it.isNotBlank() } ?: normRecipient

        val messageId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        // Replying or sending to a recipient confirms user has read all prior incoming messages from them
        try {
            markConversationAsRead(canonicalRecipient)
        } catch (_: Exception) {}

        // 1. Resolve Recipient's Public Key
        val recipientPublicKey = targetUser?.publicKey?.takeIf { it.isNotBlank() }
            ?: resolvePeerPublicKey(canonicalRecipient)
            ?: return@withContext Result.failure(IllegalStateException("Recipient does not have E2EE key registered"))

        // 2. Prepare Payload and Media
        var payloadToEncrypt = text
        var localSavedPath: String? = null

        if (mediaType == ChatMediaType.IMAGE && mediaFile != null && mediaFile.exists()) {
            // Compress and copy file to persistent app media folder (guarantees < 450KB and max 1600px HD)
            val savedFile = File(ensureMediaDirectory(), "img_$messageId.jpg")
            com.example.util.ImageUtils.compressAndSaveChatImage(
                inputFile = mediaFile,
                outputFile = savedFile,
                maxDimension = 1600,
                targetMaxBytes = 450 * 1024
            )
            localSavedPath = savedFile.absolutePath

            val fileBytes = savedFile.readBytes()
            val base64Data = Base64.encodeToString(fileBytes, Base64.NO_WRAP)
            val json = JSONObject().apply {
                put("caption", text)
                put("bytes", base64Data)
            }
            payloadToEncrypt = json.toString()
        } else if (mediaType == ChatMediaType.AUDIO && mediaFile != null && mediaFile.exists()) {
            val savedFile = File(ensureMediaDirectory(), "voice_$messageId.m4a")
            mediaFile.copyTo(savedFile, overwrite = true)
            localSavedPath = savedFile.absolutePath

            val fileBytes = savedFile.readBytes()
            val base64Data = Base64.encodeToString(fileBytes, Base64.NO_WRAP)
            val json = JSONObject().apply {
                put("duration", mediaDurationMs)
                put("bytes", base64Data)
            }
            payloadToEncrypt = json.toString()
        } else if (mediaType == ChatMediaType.DOCUMENT && mediaFile != null && mediaFile.exists()) {
            val ext = mediaFile.extension.ifBlank { "bin" }
            val savedFile = File(ensureMediaDirectory(), "doc_${messageId}.$ext")
            mediaFile.copyTo(savedFile, overwrite = true)
            localSavedPath = savedFile.absolutePath

            // If document is large (> 500 KB, e.g. 3.8MB MP3), chunk it into 384KB parts to stay within Firestore 1MB limit
            if (mediaFile.length() > 500 * 1024L) {
                val messageEntity = MessageEntity(
                    id = messageId,
                    conversationId = normRecipient,
                    senderNumber = myPhone,
                    recipientNumber = normRecipient,
                    text = text.ifBlank { mediaFile.name },
                    mediaType = ChatMediaType.DOCUMENT.name,
                    mediaPath = localSavedPath,
                    mediaDurationMs = mediaDurationMs,
                    timestamp = now,
                    status = MessageStatus.SENT.name,
                    isOutgoing = true
                )
                messageDao.insertMessage(messageEntity)

                val existingConv = conversationDao.getConversation(normRecipient)
                val convEntity = ConversationEntity(
                    phoneNumber = normRecipient,
                    contactName = recipientName.ifBlank { existingConv?.contactName ?: normRecipient },
                    profilePicUrl = existingConv?.profilePicUrl ?: "",
                    lastMessageText = "📄 ${text.ifBlank { mediaFile.name }}",
                    lastMessageType = ChatMediaType.DOCUMENT.name,
                    lastMessageTimestamp = now,
                    lastMessageStatus = MessageStatus.SENT.name,
                    lastMessageIsOutgoing = true,
                    unreadCount = existingConv?.unreadCount ?: 0,
                    isPinned = existingConv?.isPinned ?: false
                )
                conversationDao.upsertConversation(convEntity)

                val chunkSize = 384 * 1024
                val fileBytes = mediaFile.readBytes()
                val totalChunks = (fileBytes.size + chunkSize - 1) / chunkSize
                val myPublicKey = cryptoManager.getMyPublicKeyBase64()

                try {
                    for (i in 0 until totalChunks) {
                        val start = i * chunkSize
                        val end = minOf(start + chunkSize, fileBytes.size)
                        val slice = fileBytes.copyOfRange(start, end)
                        val base64Chunk = Base64.encodeToString(slice, Base64.NO_WRAP)

                        val chunkPayload = JSONObject().apply {
                            put("type", "FILE_CHUNK")
                            put("parentMessageId", messageId)
                            put("fileName", text.ifBlank { mediaFile.name })
                            put("fileSize", mediaFile.length())
                            put("chunkIndex", i)
                            put("totalChunks", totalChunks)
                            put("bytes", base64Chunk)
                        }.toString()

                        val (chunkCiphertext, chunkIv) = cryptoManager.encrypt(chunkPayload, recipientPublicKey)
                        val chunkDocId = "${messageId}_chunk_$i"
                        val chunkDto = ChatMessageDto(
                            messageId = chunkDocId,
                            senderNumber = myPhone,
                            recipientNumber = canonicalRecipient,
                            senderPublicKey = myPublicKey,
                            ciphertext = chunkCiphertext,
                            iv = chunkIv,
                            mediaType = ChatMediaType.CHUNK.name,
                            timestamp = now + i
                        )

                        firestore.collection("inboxes")
                            .document(canonicalRecipient)
                            .collection("messages")
                            .document(chunkDocId)
                            .set(chunkDto)
                            .await()
                    }
                    Log.d(TAG, "Uploaded $totalChunks chunks for message $messageId to $canonicalRecipient")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to upload file chunks: ${e.message}", e)
                    messageDao.updateMessageStatus(messageId, MessageStatus.FAILED.name)
                    return@withContext Result.failure(e)
                }

                sendFcmWakeup(
                    recipientPhone = canonicalRecipient,
                    senderPhone = myPhone,
                    previewText = "📄 ${text.ifBlank { mediaFile.name }}",
                    mediaType = ChatMediaType.DOCUMENT.name,
                    messageId = messageId
                )

                return@withContext Result.success(messageEntity)
            }

            val fileBytes = savedFile.readBytes()
            val base64Data = Base64.encodeToString(fileBytes, Base64.NO_WRAP)
            val json = JSONObject().apply {
                put("fileName", text.ifBlank { mediaFile.name })
                put("fileSize", savedFile.length())
                put("bytes", base64Data)
            }
            payloadToEncrypt = json.toString()
        }

        // 3. Encrypt via ChatCryptoManager
        val (ciphertext, iv) = cryptoManager.encrypt(payloadToEncrypt, recipientPublicKey)
        val myPublicKey = cryptoManager.getMyPublicKeyBase64()

        // 4. Save to local Room DB immediately as SENT
        val messageEntity = MessageEntity(
            id = messageId,
            conversationId = normRecipient,
            senderNumber = myPhone,
            recipientNumber = canonicalRecipient,
            text = text,
            mediaType = mediaType.name,
            mediaPath = localSavedPath,
            mediaDurationMs = mediaDurationMs,
            timestamp = now,
            status = MessageStatus.SENT.name,
            isOutgoing = true
        )
        messageDao.insertMessage(messageEntity)

        // Upsert Conversation summary
        val existingConv = conversationDao.getConversation(normRecipient)
        val convEntity = ConversationEntity(
            phoneNumber = normRecipient,
            contactName = recipientName.ifBlank { targetUser?.displayName ?: existingConv?.contactName ?: normRecipient },
            profilePicUrl = targetUser?.profilePictureUrl ?: existingConv?.profilePicUrl ?: "",
            lastMessageText = when (mediaType) {
                ChatMediaType.IMAGE -> "📷 Photo"
                ChatMediaType.AUDIO -> "🎤 Voice message"
                ChatMediaType.DOCUMENT -> "📄 ${text.ifBlank { "Document" }}"
                else -> text
            },
            lastMessageType = mediaType.name,
            lastMessageTimestamp = now,
            lastMessageStatus = MessageStatus.SENT.name,
            lastMessageIsOutgoing = true,
            unreadCount = existingConv?.unreadCount ?: 0,
            isPinned = existingConv?.isPinned ?: false
        )
        conversationDao.upsertConversation(convEntity)

        // 5. Post to ephemeral Firestore inbox: `inboxes/{recipient}/messages/{messageId}`
        val chatDto = ChatMessageDto(
            messageId = messageId,
            senderNumber = myPhone,
            recipientNumber = canonicalRecipient,
            senderPublicKey = myPublicKey,
            ciphertext = ciphertext,
            iv = iv,
            mediaType = mediaType.name,
            mediaDurationMs = mediaDurationMs,
            timestamp = now
        )

        firestore.collection("inboxes")
            .document(canonicalRecipient)
            .collection("messages")
            .document(messageId)
            .set(chatDto)
            .addOnSuccessListener {
                Log.d(TAG, "Message $messageId delivered to ephemeral inbox for $canonicalRecipient")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to upload message to inbox: ${e.message}")
                repositoryScope.launch {
                    messageDao.updateMessageStatus(messageId, MessageStatus.FAILED.name)
                }
            }

        // Send high-priority FCM notification wakeup if recipient has token
        val preview = when (mediaType) {
            ChatMediaType.IMAGE -> if (text.isNotBlank()) text else "📷 Photo"
            ChatMediaType.AUDIO -> "🎤 Voice message"
            ChatMediaType.DOCUMENT -> "📄 ${text.ifBlank { "Document" }}"
            else -> text
        }
        sendFcmWakeup(
            recipientPhone = canonicalRecipient,
            senderPhone = myPhone,
            previewText = preview,
            mediaType = mediaType.name,
            messageId = messageId
        )

        Result.success(messageEntity)
    }

    /**
     * Public API: Edits an outgoing message within 10 minutes of sending.
     * Updates local Room DB immediately, then sends an ephemeral edit packet to peer.
     */
    suspend fun editMessage(
        originalMessageId: String,
        newText: String,
        recipientNumber: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val originalMsg = messageDao.getMessageById(originalMessageId)
            ?: return@withContext Result.failure(IllegalArgumentException("Message not found"))

        if (!originalMsg.isOutgoing) {
            return@withContext Result.failure(IllegalStateException("Cannot edit incoming messages"))
        }

        val elapsed = System.currentTimeMillis() - originalMsg.timestamp
        val tenMinutesMs = 10 * 60 * 1000L
        if (originalMsg.timestamp > 0L && elapsed > tenMinutesMs) {
            return@withContext Result.failure(IllegalStateException("Message can only be edited within 10 minutes of sending"))
        }

        val prefs = context.getSharedPreferences("dialer_prefs", Context.MODE_PRIVATE)
        val myPhone = currentListeningPhone
            ?: FirebaseManager.getInstance(context).currentUser.value?.phoneNumber
            ?: prefs.getString("user_phone", null)
            ?: return@withContext Result.failure(IllegalStateException("Current user not logged in"))

        val normRecipient = ContactsHelper.normalizePhoneNumber(recipientNumber)

        // 1. Update Room DB locally (preserve replyTo if present)
        val textToSave = try {
            val json = JSONObject(originalMsg.text)
            if (json.has("replyTo")) {
                json.put("text", newText)
                json.toString()
            } else {
                newText
            }
        } catch (_: Exception) {
            newText
        }
        messageDao.updateMessageText(originalMessageId, textToSave)

        // 2. If it was the last message, update conversation summary
        val lastMsg = messageDao.getLastMessageForConversation(normRecipient)
        if (lastMsg != null && lastMsg.id == originalMessageId) {
            conversationDao.updateLastMessageText(normRecipient, newText)
        }

        // 3. Resolve recipient public key and encrypt edit packet
        val targetUser = FirebaseManager.getInstance(context).lookupUserByNumber(recipientNumber)
        val canonicalRecipient = targetUser?.phoneNumber?.takeIf { it.isNotBlank() } ?: normRecipient
        val recipientPublicKey = targetUser?.publicKey?.takeIf { it.isNotBlank() } ?: resolvePeerPublicKey(canonicalRecipient)
        if (recipientPublicKey != null) {
            val payload = JSONObject().apply {
                put("type", "MESSAGE_EDIT")
                put("originalMessageId", originalMessageId)
                put("newText", newText)
                put("editedAt", System.currentTimeMillis())
            }.toString()

            val (ciphertext, iv) = cryptoManager.encrypt(payload, recipientPublicKey)
            val myPublicKey = cryptoManager.getMyPublicKeyBase64()
            val editPacketId = UUID.randomUUID().toString()

            val chatDto = ChatMessageDto(
                messageId = editPacketId,
                senderNumber = myPhone,
                recipientNumber = canonicalRecipient,
                senderPublicKey = myPublicKey,
                ciphertext = ciphertext,
                iv = iv,
                mediaType = ChatMediaType.EDIT.name,
                timestamp = System.currentTimeMillis()
            )

            try {
                firestore.collection("inboxes")
                    .document(canonicalRecipient)
                    .collection("messages")
                    .document(editPacketId)
                    .set(chatDto)
                    .await()
                Log.d(TAG, "Edit packet $editPacketId delivered to ephemeral inbox for $canonicalRecipient")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to upload edit packet: ${e.message}")
            }

            // Send FCM wakeup push
            sendFcmWakeup(
                recipientPhone = canonicalRecipient,
                senderPhone = myPhone,
                previewText = newText,
                mediaType = ChatMediaType.EDIT.name,
                messageId = editPacketId
            )
        }

        Result.success(Unit)
    }

    /**
     * Public API: Deletes an outgoing message for everyone (WhatsApp style).
     * Replaces local message text with tombstone, clears mediaPath and deletes local media file,
     * updates conversation summary, and sends an encrypted ephemeral DELETE packet to the peer.
     */
    suspend fun deleteMessageForEveryone(
        messageId: String,
        recipientNumber: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val originalMsg = messageDao.getMessageById(messageId)
            ?: return@withContext Result.failure(IllegalArgumentException("Message not found"))

        if (!originalMsg.isOutgoing) {
            return@withContext Result.failure(IllegalStateException("Cannot delete incoming messages for everyone"))
        }

        // 1. Delete local media file if present
        originalMsg.mediaPath?.let { path ->
            try { File(path).delete() } catch (_: Exception) {}
        }

        // 2. Mark local message with tombstone text and clear mediaPath in Room
        val tombstone = "🚫 You deleted this message"
        messageDao.markMessageDeletedForEveryone(messageId, tombstone)

        val normRecipient = ContactsHelper.normalizePhoneNumber(recipientNumber)
        val targetUser = FirebaseManager.getInstance(context).lookupUserByNumber(recipientNumber)
        val canonicalRecipient = targetUser?.phoneNumber?.takeIf { it.isNotBlank() } ?: normRecipient

        // 3. Update conversation summary if this was the last message
        val lastMsg = messageDao.getLastMessageForConversation(normRecipient)
        if (lastMsg != null && lastMsg.id == messageId) {
            conversationDao.updateLastMessageText(normRecipient, tombstone)
        }

        // 4. Send ephemeral DELETE packet to peer
        val prefs = context.getSharedPreferences("dialer_prefs", Context.MODE_PRIVATE)
        val myPhone = currentListeningPhone
            ?: FirebaseManager.getInstance(context).currentUser.value?.phoneNumber
            ?: prefs.getString("user_phone", null)

        if (myPhone != null) {
            val recipientPublicKey = targetUser?.publicKey?.takeIf { it.isNotBlank() } ?: resolvePeerPublicKey(canonicalRecipient)
            if (recipientPublicKey != null) {
                val payload = JSONObject().apply {
                    put("type", "MESSAGE_DELETE")
                    put("targetMessageId", messageId)
                    put("deletedAt", System.currentTimeMillis())
                }.toString()

                val (ciphertext, iv) = cryptoManager.encrypt(payload, recipientPublicKey)
                val myPublicKey = cryptoManager.getMyPublicKeyBase64()
                val deletePacketId = UUID.randomUUID().toString()

                val chatDto = ChatMessageDto(
                    messageId = deletePacketId,
                    senderNumber = myPhone,
                    recipientNumber = canonicalRecipient,
                    senderPublicKey = myPublicKey,
                    ciphertext = ciphertext,
                    iv = iv,
                    mediaType = ChatMediaType.DELETE.name,
                    timestamp = System.currentTimeMillis()
                )

                try {
                    firestore.collection("inboxes")
                        .document(canonicalRecipient)
                        .collection("messages")
                        .document(deletePacketId)
                        .set(chatDto)
                        .await()
                    Log.d(TAG, "Delete packet $deletePacketId sent to $canonicalRecipient")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to upload delete packet: ${e.message}")
                }

                sendFcmWakeup(
                    recipientPhone = canonicalRecipient,
                    senderPhone = myPhone,
                    previewText = "",
                    mediaType = ChatMediaType.DELETE.name,
                    messageId = deletePacketId
                )
            }
        }

        Result.success(Unit)
    }

    private suspend fun resolvePeerPublicKey(phoneNumber: String): String? {
        val firebaseManager = FirebaseManager.getInstance(context)
        val cached = firebaseManager.lookupUserByNumber(phoneNumber)
        if (!cached?.publicKey.isNullOrBlank()) {
            return cached!!.publicKey
        }

        // Fetch from Firestore
        return try {
            val doc = firestore.collection("users").document(phoneNumber).get().await()
            val user = doc.toObject(UserDto::class.java)
            user?.publicKey?.ifBlank { null }
        } catch (e: Exception) {
            Log.w(TAG, "Error looking up public key for $phoneNumber: ${e.message}")
            null
        }
    }

    private fun sendFcmWakeup(
        recipientPhone: String,
        senderPhone: String,
        previewText: String,
        mediaType: String = "TEXT",
        messageId: String = ""
    ) {
        val workerUrl = com.example.BuildConfig.CALL_WORKER_URL
        val workerSecret = com.example.BuildConfig.CALL_WORKER_SECRET
        if (workerUrl.isBlank()) return

        val firebaseManager = FirebaseManager.getInstance(context)
        val recipientUser = firebaseManager.lookupUserByNumber(recipientPhone)
        val myName = firebaseManager.currentUser.value?.displayName ?: senderPhone
        val myPic = firebaseManager.currentUser.value?.profilePictureUrl ?: ""

        fun postPush(token: String, webToken: String = "") {
            if (token.isBlank() && webToken.isBlank()) return
            Thread {
                var conn: java.net.HttpURLConnection? = null
                try {
                    val url = java.net.URL(workerUrl)
                    conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("X-Worker-Secret", workerSecret)
                        connectTimeout = 5000
                        readTimeout = 5000
                        doOutput = true
                    }
                    val json = JSONObject().apply {
                        if (token.isNotBlank()) put("token", token)
                        if (webToken.isNotBlank()) put("webToken", webToken)
                        put("callerName", myName)
                        put("callerNumber", senderPhone)
                        put("type", "chat_message")
                        put("messageText", extractCleanText(previewText))
                        put("mediaType", mediaType)
                        put("messageId", messageId)
                        if (myPic.isNotBlank()) put("callerProfilePic", myPic)
                    }.toString()

                    conn.outputStream.use { it.write(json.toByteArray()) }
                    Log.d(TAG, "Chat push notification triggered, response code: ${conn.responseCode}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send chat push trigger: ${e.message}")
                } finally {
                    conn?.disconnect()
                }
            }.start()
        }

        val fcmToken = recipientUser?.fcmToken ?: ""
        val webToken = recipientUser?.webToken ?: ""
        if (fcmToken.isNotBlank() || webToken.isNotBlank()) {
            postPush(fcmToken, webToken)
        } else {
            val variations = ContactsHelper.generateNumberVariations(recipientPhone)
            firestore.collection("users").whereIn("phoneNumber", variations).get().addOnSuccessListener { querySnapshot ->
                val userDoc = querySnapshot.documents.firstOrNull {
                    !it.getString("fcmToken").isNullOrEmpty() || !it.getString("webToken").isNullOrEmpty()
                }
                val fToken = userDoc?.getString("fcmToken") ?: ""
                val wToken = userDoc?.getString("webToken") ?: ""
                if (fToken.isNotBlank() || wToken.isNotBlank()) {
                    postPush(fToken, wToken)
                }
            }.addOnFailureListener {
                firestore.collection("users").document(recipientPhone).get().addOnSuccessListener { doc ->
                    val fetchedToken = doc.getString("fcmToken") ?: ""
                    val fetchedWebToken = doc.getString("webToken") ?: ""
                    if (fetchedToken.isNotBlank() || fetchedWebToken.isNotBlank()) {
                        postPush(fetchedToken, fetchedWebToken)
                    }
                }
            }
        }
    }

    /**
     * Marks all unread messages from this peer as READ and notifies sender.
     */
    suspend fun markConversationAsRead(peerPhoneNumber: String) {
        val norm = ContactsHelper.normalizePhoneNumber(peerPhoneNumber)
        val last10 = norm.filter { it.isDigit() }.takeLast(10)
        messageDao.updateIncomingMessagesStatus(norm, last10, MessageStatus.READ.name)
        conversationDao.resetUnreadCount(norm, last10)
        val targetUser = FirebaseManager.getInstance(context).lookupUserByNumber(peerPhoneNumber)
        val canonicalRecipient = targetUser?.phoneNumber?.takeIf { it.isNotBlank() } ?: norm
        sendReceipt(recipientNumber = canonicalRecipient, messageId = "all", status = MessageStatus.READ.name)
    }

    /**
     * Sets real-time typing status for this user visible to the peer.
     */
    fun setTyping(peerPhoneNumber: String, isTyping: Boolean) {
        val myPhone = currentListeningPhone ?: return
        val norm = ContactsHelper.normalizePhoneNumber(peerPhoneNumber)

        firestore.collection("typingStatus")
            .document(norm)
            .collection("peers")
            .document(myPhone)
            .set(mapOf("isTyping" to isTyping, "timestamp" to System.currentTimeMillis()))
    }

    /** Called by UI when user actually plays a voice note or taps an image — upgrades status to READ */
    fun markMessageRead(messageId: String, peerNumber: String) {
        repositoryScope.launch {
            try {
                messageDao.updateMessageStatus(messageId, MessageStatus.READ.name)
                sendReceipt(peerNumber, messageId, MessageStatus.READ.name)
            } catch (e: Exception) {
                Log.w(TAG, "markMessageRead failed: ${e.message}")
            }
        }
    }

    /**
     * Displays a rich Android MessagingStyle notification with direct reply.
     */
    private suspend fun showIncomingMessageNotification(
        senderNumber: String,
        senderName: String,
        messageText: String,
        messageType: String,
        profilePicUrl: String? = null
    ) {
        val notifId = senderNumber.hashCode()

        // Tap opens MainActivity directly into this chat conversation
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            action = "com.example.ACTION_OPEN_CHAT_$notifId"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_tab", "CHATS")
            putExtra("chat_peer_number", senderNumber)
            putExtra("chat_peer_name", senderName)
            putExtra("notification_id", notifId)
        }
        val tapPendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Direct Reply RemoteInput
        val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
            .setLabel("Reply to $senderName...")
            .build()

        val replyIntent = Intent(context, com.example.services.ChatReplyReceiver::class.java).apply {
            action = "com.example.ACTION_REPLY_CHAT"
            putExtra("chat_peer_number", senderNumber)
            putExtra("chat_peer_name", senderName)
            putExtra("notification_id", notifId)
        }
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            notifId + 1000,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        )

        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Reply",
            replyPendingIntent
        )
            .addRemoteInput(remoteInput)
            .build()

        val firebaseManager = FirebaseManager.getInstance(context)
        val registeredUser = firebaseManager.lookupUserByNumber(senderNumber)
        val contactInfo = firebaseManager.contacts.value.find { ContactsHelper.numbersMatch(it.phoneNumber, senderNumber) }
        var rawAvatar = profilePicUrl?.takeIf { it.isNotBlank() }
            ?: registeredUser?.profilePictureUrl?.takeIf { it.isNotBlank() }
            ?: contactInfo?.profilePictureUrl?.takeIf { it.isNotBlank() }
            ?: ""

        if (rawAvatar.isBlank()) {
            val conv = conversationDao.getConversation(senderNumber)
            rawAvatar = conv?.profilePicUrl?.takeIf { it.isNotBlank() } ?: ""
        }

        if (rawAvatar.isBlank()) {
            try {
                val doc = firestore.collection("users").document(senderNumber).get().await()
                rawAvatar = doc.getString("profilePictureUrl") ?: ""
            } catch (_: Exception) {}
        }

        var avatarBitmap: Bitmap? = null
        if (rawAvatar.isNotBlank()) {
            avatarBitmap = try {
                if (rawAvatar.startsWith("http://") || rawAvatar.startsWith("https://")) {
                    val url = java.net.URL(rawAvatar)
                    val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                        connectTimeout = 3000
                        readTimeout = 3000
                        doInput = true
                    }
                    val stream = conn.inputStream
                    val bmp = BitmapFactory.decodeStream(stream)
                    stream.close()
                    conn.disconnect()
                    if (bmp != null) getCircularBitmap(bmp) else null
                } else {
                    val clean = if (rawAvatar.contains(",")) rawAvatar.substringAfter(",") else rawAvatar
                    val bytes = Base64.decode(clean, Base64.DEFAULT)
                    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (decoded != null) getCircularBitmap(decoded) else null
                }
            } catch (_: Exception) {
                try {
                    val decoded = BitmapFactory.decodeFile(rawAvatar)
                    if (decoded != null) getCircularBitmap(decoded) else null
                } catch (_: Exception) { null }
            }
        }

        val myUser = Person.Builder()
            .setName("You")
            .setKey("me")
            .build()

        val senderPersonBuilder = Person.Builder()
            .setName(senderName)
            .setKey(senderNumber)
        if (avatarBitmap != null) {
            senderPersonBuilder.setIcon(androidx.core.graphics.drawable.IconCompat.createWithBitmap(avatarBitmap))
        }
        val senderPerson = senderPersonBuilder.build()

        val cleanContent = extractCleanText(messageText)
        val displayContent = when {
            cleanContent.isNotBlank() -> cleanContent
            messageType == ChatMediaType.IMAGE.name -> "📷 Photo"
            messageType == ChatMediaType.AUDIO.name -> "🎤 Voice message"
            messageType == ChatMediaType.DOCUMENT.name -> "📄 Document"
            else -> "New message"
        }

        val messagingStyle = NotificationCompat.MessagingStyle(myUser)
            .setConversationTitle(null)
            .setGroupConversation(false)
            .addMessage(displayContent, System.currentTimeMillis(), senderPerson)

        val builder = NotificationCompat.Builder(context, CHAT_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .apply {
                if (avatarBitmap != null) {
                    setLargeIcon(avatarBitmap)
                }
            }
            .setContentTitle(senderName)
            .setContentText(displayContent)
            .setTicker("$senderName: $displayContent")
            .setStyle(messagingStyle)
            .setContentIntent(tapPendingIntent)
            .addAction(replyAction)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_ALL)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(notifId, builder.build())
    }

    /**
     * Reconciles conversation lastMessageStatus with the latest outgoing message in Room DB.
     * Ensures that any conversations with outdated ticks (e.g. single tick when read)
     * are healed immediately.
     */
    suspend fun syncOutdatedConversationStatuses() = withContext(Dispatchers.IO) {
        try {
            val convs = conversationDao.getConversationsList()
            for (conv in convs) {
                if (conv.lastMessageIsOutgoing) {
                    val lastMsg = messageDao.getLastMessageForConversation(conv.phoneNumber)
                    if (lastMsg != null && lastMsg.status != conv.lastMessageStatus) {
                        conversationDao.updateLastMessageStatus(conv.phoneNumber, lastMsg.status)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error syncing conversation statuses: ${e.message}")
        }
    }

    // Exposed Flows for UI
    fun getConversationsFlow(): Flow<List<ConversationEntity>> {
        repositoryScope.launch {
            syncOutdatedConversationStatuses()
        }
        return conversationDao.getConversationsFlow()
    }
    fun getMessagesFlow(phoneNumber: String): Flow<List<MessageEntity>> =
        messageDao.getMessagesFlow(ContactsHelper.normalizePhoneNumber(phoneNumber))
    fun getTotalUnreadCountFlow(): Flow<Int> = conversationDao.getTotalUnreadCountFlow()

    suspend fun clearChat(phoneNumber: String) {
        val norm = ContactsHelper.normalizePhoneNumber(phoneNumber)
        messageDao.clearMessagesForConversation(norm)
        conversationDao.deleteConversation(norm)
    }

    suspend fun deleteMessage(messageId: String) {
        messageDao.deleteMessage(messageId)
    }

    /**
     * Called directly by CallMessagingService when an FCM 'chat_message' push arrives.
     * 1. If not currently viewing the conversation, INSTANTLY posts the rich notification
     *    with sound, vibration, and Direct Reply so aggressive OEM task-killers cannot drop it.
     * 2. Direct-fetches any pending messages in inboxes/{myPhone}/messages, decrypts them,
     *    stores them in local Room DB, deletes them from Firestore (zero retention),
     *    and sends DELIVERED receipts back to the sender.
     */
    suspend fun handlePushMessageReceived(data: Map<String, String>) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("dialer_prefs", Context.MODE_PRIVATE)
        val myPhone = currentListeningPhone
            ?: FirebaseManager.getInstance(context).currentUser.value?.phoneNumber
            ?: prefs.getString("user_phone", null)
            ?: return@withContext

        val senderNumber = data["callerNumber"] ?: data["senderNumber"] ?: ""
        val senderName = data["callerName"] ?: data["senderName"] ?: senderNumber
        val rawMessageText = data["messageText"] ?: data["messagePreview"] ?: ""
        val messageText = extractCleanText(rawMessageText)
        val mediaType = data["mediaType"] ?: "TEXT"
        val callerProfilePic = data["callerProfilePic"] ?: data["profilePictureUrl"] ?: ""
        val senderNorm = ContactsHelper.normalizePhoneNumber(senderNumber)

        Log.d(TAG, "⚡ handlePushMessageReceived: sender=$senderNorm, text=$messageText, media=$mediaType")

        // Step 1: Immediately show notification if user is not in this conversation right now (skip for silent edits, deletes, chunks)
        val isWatchingConversation = isAppInForeground && (_activeChatPeerNumber.value == senderNorm)
        if (!isWatchingConversation && senderNorm.isNotBlank() &&
            mediaType != ChatMediaType.EDIT.name &&
            mediaType != ChatMediaType.DELETE.name &&
            mediaType != ChatMediaType.CHUNK.name) {
            val firebaseManager = FirebaseManager.getInstance(context)
            val registeredUser = firebaseManager.lookupUserByNumber(senderNorm)
            val contactInfo = firebaseManager.contacts.value.find { ContactsHelper.numbersMatch(it.phoneNumber, senderNorm) }
            val resolvedName = registeredUser?.displayName?.ifBlank { null }
                ?: contactInfo?.name?.ifBlank { null }
                ?: senderName.ifBlank { senderNorm }

            val displayPreview = when (mediaType) {
                ChatMediaType.IMAGE.name -> if (messageText.isNotBlank()) "📷 $messageText" else "📷 Photo"
                ChatMediaType.AUDIO.name -> "🎤 Voice message"
                ChatMediaType.DOCUMENT.name -> if (messageText.isNotBlank()) "📄 $messageText" else "📄 Document"
                else -> messageText.ifBlank { "New message" }
            }
            showIncomingMessageNotification(
                senderNumber = senderNorm,
                senderName = resolvedName,
                messageText = displayPreview,
                messageType = mediaType,
                profilePicUrl = callerProfilePic
            )
        }

        // Also ensure snapshot listener is attached for continuous updates
        attachChatListeners(myPhone)

        // Step 2: Direct-fetch ephemeral messages from Firestore while FCM holds the process alive
        try {
            val snapshot = firestore.collection("inboxes")
                .document(myPhone)
                .collection("messages")
                .get()
                .await()

            if (!snapshot.isEmpty) {
                Log.d(TAG, "📥 Direct fetch found ${snapshot.size()} pending messages for $myPhone")
                for (doc in snapshot.documents) {
                    val dto = doc.toObject(ChatMessageDto::class.java)
                    if (dto != null) {
                        processIncomingMessage(dto, doc.reference)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Direct fetch messages failed on push message: ${e.message}")
        }

        // Step 3: Direct-fetch ephemeral receipts (DELIVERED / READ) while FCM holds the process alive
        try {
            val receiptsSnapshot = firestore.collection("receipts")
                .document(myPhone)
                .collection("acks")
                .get()
                .await()

            if (!receiptsSnapshot.isEmpty) {
                Log.d(TAG, "📥 Direct fetch found ${receiptsSnapshot.size()} pending receipts for $myPhone")
                for (doc in receiptsSnapshot.documents) {
                    val rDto = doc.toObject(ChatReceiptDto::class.java)
                    if (rDto != null) {
                        processIncomingReceipt(rDto, doc.reference)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Direct fetch receipts failed on push message: ${e.message}")
        }
    }
}
