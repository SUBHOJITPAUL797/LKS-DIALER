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
