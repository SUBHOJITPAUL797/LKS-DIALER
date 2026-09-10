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
import java.io.File
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
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
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

        try {
            // STEP 1: Decrypt message payload
            val decryptedRaw = cryptoManager.decrypt(dto.ciphertext, dto.iv, dto.senderPublicKey)
            val senderNorm = ContactsHelper.normalizePhoneNumber(dto.senderNumber)
            val isCurrentPeer = (_activeChatPeerNumber.value == senderNorm)

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
            }

            val finalStatus = if (isCurrentPeer) MessageStatus.READ.name else MessageStatus.DELIVERED.name

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
                status = finalStatus,
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
                    else -> notificationDisplayText
                },
                lastMessageType = dto.mediaType,
                lastMessageTimestamp = messageEntity.timestamp,
                lastMessageStatus = finalStatus,
                lastMessageIsOutgoing = false,
                unreadCount = unreadCount,
                isPinned = existingConv?.isPinned ?: false
            )
            conversationDao.upsertConversation(convEntity)

            // STEP 3: CRITICAL ZERO-RETENTION — Immediately delete from Firestore!
            try {
                docRef.delete().addOnSuccessListener {
                    Log.d(TAG, "Ephemeral message ${dto.messageId} permanently deleted from Firestore")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete message doc from Firestore: ${e.message}")
            }

            // STEP 4: Send ACK back to sender
            sendReceipt(
                recipientNumber = dto.senderNumber,
                messageId = dto.messageId,
                status = finalStatus
            )

            // STEP 5: Show Notification if conversation is not currently open
            if (!isCurrentPeer) {
                showIncomingMessageNotification(
                    senderNumber = senderNorm,
                    senderName = resolvedName,
                    messageText = notificationDisplayText,
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
            messageDao.updateMessageStatus(receipt.messageId, receipt.status)

            // If READ receipt, also update all earlier outgoing messages with this peer to READ
            if (receipt.status == MessageStatus.READ.name && receipt.recipientNumber.isNotBlank()) {
                val peerNorm = ContactsHelper.normalizePhoneNumber(receipt.recipientNumber)
                messageDao.updateOutgoingMessagesStatus(peerNorm, MessageStatus.READ.name)
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
        val myPhone = currentListeningPhone ?: return
        val receiptId = UUID.randomUUID().toString()

        val receiptDto = ChatReceiptDto(
            receiptId = receiptId,
            messageId = messageId,
            senderNumber = myPhone,
            recipientNumber = normalized,
            status = status,
            timestamp = System.currentTimeMillis()
        )

        firestore.collection("receipts")
            .document(normalized)
            .collection("acks")
            .document(receiptId)
            .set(receiptDto)
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to send receipt to $normalized: ${e.message}")
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
        val myPhone = currentListeningPhone
            ?: FirebaseManager.getInstance(context).currentUser.value?.phoneNumber
            ?: return@withContext Result.failure(IllegalStateException("Current user not logged in"))

        val normRecipient = ContactsHelper.normalizePhoneNumber(recipientNumber)
        val messageId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        // 1. Resolve Recipient's Public Key
        val recipientPublicKey = resolvePeerPublicKey(normRecipient)
            ?: return@withContext Result.failure(IllegalStateException("Recipient does not have E2EE key registered"))

        // 2. Prepare Payload and Media
        var payloadToEncrypt = text
        var localSavedPath: String? = null

        if (mediaType == ChatMediaType.IMAGE && mediaFile != null && mediaFile.exists()) {
            // Copy file to persistent app media folder
            val savedFile = File(ensureMediaDirectory(), "img_$messageId.jpg")
            mediaFile.copyTo(savedFile, overwrite = true)
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
        }

        // 3. Encrypt via ChatCryptoManager
        val (ciphertext, iv) = cryptoManager.encrypt(payloadToEncrypt, recipientPublicKey)
        val myPublicKey = cryptoManager.getMyPublicKeyBase64()

        // 4. Save to local Room DB immediately as SENT
        val messageEntity = MessageEntity(
            id = messageId,
            conversationId = normRecipient,
            senderNumber = myPhone,
            recipientNumber = normRecipient,
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
            contactName = recipientName.ifBlank { existingConv?.contactName ?: normRecipient },
            profilePicUrl = existingConv?.profilePicUrl ?: "",
            lastMessageText = if (mediaType == ChatMediaType.IMAGE) "📷 Photo"
                              else if (mediaType == ChatMediaType.AUDIO) "🎤 Voice message"
                              else text,
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
            recipientNumber = normRecipient,
            senderPublicKey = myPublicKey,
            ciphertext = ciphertext,
            iv = iv,
            mediaType = mediaType.name,
            mediaDurationMs = mediaDurationMs,
            timestamp = now
        )

        firestore.collection("inboxes")
            .document(normRecipient)
            .collection("messages")
            .document(messageId)
            .set(chatDto)
            .addOnSuccessListener {
                Log.d(TAG, "Message $messageId delivered to ephemeral inbox for $normRecipient")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to upload message to inbox: ${e.message}")
                repositoryScope.launch {
                    messageDao.updateMessageStatus(messageId, MessageStatus.FAILED.name)
                }
            }

        // Send high-priority FCM notification wakeup if recipient has token
        sendFcmWakeup(normRecipient, myPhone, text)

        Result.success(messageEntity)
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

    private fun sendFcmWakeup(recipientPhone: String, senderPhone: String, previewText: String) {
        val workerUrl = com.example.BuildConfig.CALL_WORKER_URL
        val workerSecret = com.example.BuildConfig.CALL_WORKER_SECRET
        if (workerUrl.isBlank()) return

        val firebaseManager = FirebaseManager.getInstance(context)
        val recipientUser = firebaseManager.lookupUserByNumber(recipientPhone)
        val myName = firebaseManager.currentUser.value?.displayName ?: senderPhone

        fun postPush(token: String) {
            if (token.isBlank()) return
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
                        put("token", token)
                        put("callerName", myName)
                        put("callerNumber", senderPhone)
                        put("type", "chat_message")
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
        if (fcmToken.isNotBlank()) {
            postPush(fcmToken)
        } else {
            firestore.collection("users").document(recipientPhone).get().addOnSuccessListener { doc ->
                val fetchedToken = doc.getString("fcmToken") ?: ""
                if (fetchedToken.isNotBlank()) {
                    postPush(fetchedToken)
                }
            }
        }
    }

    /**
     * Marks all unread messages from this peer as READ and notifies sender.
     */
    suspend fun markConversationAsRead(peerPhoneNumber: String) {
        val norm = ContactsHelper.normalizePhoneNumber(peerPhoneNumber)
        conversationDao.resetUnreadCount(norm)
        sendReceipt(recipientNumber = norm, messageId = "all", status = MessageStatus.READ.name)
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

    /**
     * Displays a rich Android MessagingStyle notification with direct reply.
     */
    private fun showIncomingMessageNotification(
        senderNumber: String,
        senderName: String,
        messageText: String,
        messageType: String
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

        val person = Person.Builder()
            .setName(senderName)
            .setKey(senderNumber)
            .build()

        val displayContent = when (messageType) {
            ChatMediaType.IMAGE.name -> "📷 Photo"
            ChatMediaType.AUDIO.name -> "🎤 Voice message"
            else -> messageText
        }

        val messagingStyle = NotificationCompat.MessagingStyle(person)
            .addMessage(displayContent, System.currentTimeMillis(), person)

        val builder = NotificationCompat.Builder(context, CHAT_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setStyle(messagingStyle)
            .setContentTitle(senderName)
            .setContentText(displayContent)
            .setContentIntent(tapPendingIntent)
            .addAction(replyAction)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(notifId, builder.build())
    }

    // Exposed Flows for UI
    fun getConversationsFlow(): Flow<List<ConversationEntity>> = conversationDao.getConversationsFlow()
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
}
