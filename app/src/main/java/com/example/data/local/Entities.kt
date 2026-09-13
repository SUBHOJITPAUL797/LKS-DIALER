package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class MessageStatus {
    PENDING,
    SENT,
    DELIVERED,
    READ,
    FAILED
}

enum class ChatMediaType {
    TEXT,
    IMAGE,
    AUDIO,
    DOCUMENT,
    EDIT,
    DELETE,
    CHUNK,
    P2P_OFFER    // WebRTC DataChannel P2P file transfer signaling
}

@Entity(
    tableName = "messages",
    indices = [
        Index("conversationId"),
        Index("timestamp")
    ]
)
data class MessageEntity(
    @PrimaryKey
    val id: String, // UUID
    val conversationId: String, // Peer's normalized phone number
    val senderNumber: String,
    val recipientNumber: String,
    val text: String,
    val mediaType: String = ChatMediaType.TEXT.name,
    val mediaPath: String? = null, // Local absolute file path on disk (for voice note or photo)
    val mediaDurationMs: Long = 0L, // Duration in milliseconds for audio notes
    val timestamp: Long = System.currentTimeMillis(),
    val status: String = MessageStatus.PENDING.name,
    val isOutgoing: Boolean = false,
    val isEdited: Boolean = false
)

@Entity(
    tableName = "conversations"
)
data class ConversationEntity(
    @PrimaryKey
    val phoneNumber: String, // Peer's normalized phone number
    val contactName: String,
    val profilePicUrl: String = "",
    val lastMessageText: String = "",
    val lastMessageType: String = ChatMediaType.TEXT.name,
    val lastMessageTimestamp: Long = System.currentTimeMillis(),
    val lastMessageStatus: String = MessageStatus.SENT.name,
    val lastMessageIsOutgoing: Boolean = false,
    val unreadCount: Int = 0,
    val isPinned: Boolean = false
)

enum class ClearChatMode {
    TEXT_ONLY,
    MEDIA_ONLY,
    BOTH
}

data class StorageUsageSummary(
    val totalDeviceBytes: Long = 0L,
    val freeDeviceBytes: Long = 0L,
    val usedDeviceBytes: Long = 0L,
    val totalChatBytes: Long = 0L,
    val totalChatVideoBytes: Long = 0L,
    val totalChatPhotoBytes: Long = 0L,
    val totalChatAudioBytes: Long = 0L,
    val totalChatDocumentBytes: Long = 0L,
    val totalChatTextBytes: Long = 0L
)

data class ConversationStorageItem(
    val phoneNumber: String,
    val displayName: String,
    val profilePicUrl: String = "",
    val totalBytes: Long = 0L,
    val videoBytes: Long = 0L,
    val videoCount: Int = 0,
    val photoBytes: Long = 0L,
    val photoCount: Int = 0,
    val audioBytes: Long = 0L,
    val audioCount: Int = 0,
    val documentBytes: Long = 0L,
    val documentCount: Int = 0,
    val messageCount: Int = 0
)
