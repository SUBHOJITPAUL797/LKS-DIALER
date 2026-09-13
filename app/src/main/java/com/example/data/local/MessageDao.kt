package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)

    @Query("UPDATE messages SET status = :status WHERE id = :messageId AND (status != 'READ' OR :status = 'READ')")
    suspend fun updateMessageStatus(messageId: String, status: String)

    @Query("UPDATE messages SET text = :newText, isEdited = 1 WHERE id = :messageId")
    suspend fun updateMessageText(messageId: String, newText: String)

    @Query("UPDATE messages SET text = :tombstoneText, mediaPath = NULL, isEdited = 0 WHERE id = :messageId")
    suspend fun markMessageDeletedForEveryone(messageId: String, tombstoneText: String)

    /** Updates mediaPath for a message after P2P file assembly completes. */
    @Query("UPDATE messages SET mediaPath = :mediaPath WHERE id = :messageId")
    suspend fun updateMessageMedia(messageId: String, mediaPath: String)

    @Query("UPDATE messages SET status = :newStatus WHERE (conversationId = :conversationId OR (length(:last10Digits) >= 7 AND conversationId LIKE '%' || :last10Digits)) AND isOutgoing = 1 AND status != :newStatus AND (status != 'READ' OR :newStatus = 'READ')")
    suspend fun updateOutgoingMessagesStatus(conversationId: String, last10Digits: String, newStatus: String)

    @Query("UPDATE messages SET status = :newStatus WHERE conversationId = :conversationId AND isOutgoing = 1 AND status != :newStatus AND (status != 'READ' OR :newStatus = 'READ')")
    suspend fun updateOutgoingMessagesStatus(conversationId: String, newStatus: String)

    /** Upgrades outgoing messages up to a specific timestamp to READ (prevents in-flight / SENT messages from getting stuck on single tick) */
    @Query("UPDATE messages SET status = :newStatus WHERE (conversationId = :conversationId OR (length(:last10Digits) >= 7 AND conversationId LIKE '%' || :last10Digits)) AND isOutgoing = 1 AND status != 'READ' AND timestamp <= :upToTimestamp")
    suspend fun markDeliveredMessagesAsReadUpTo(conversationId: String, last10Digits: String, upToTimestamp: Long, newStatus: String = "READ")

    @Query("UPDATE messages SET status = :newStatus WHERE conversationId = :conversationId AND isOutgoing = 1 AND status != 'READ' AND timestamp <= :upToTimestamp")
    suspend fun markDeliveredMessagesAsReadUpTo(conversationId: String, upToTimestamp: Long, newStatus: String = "READ")

    @Query("UPDATE messages SET status = :newStatus WHERE (conversationId = :conversationId OR (length(:last10Digits) >= 7 AND conversationId LIKE '%' || :last10Digits)) AND isOutgoing = 0 AND status != :newStatus")
    suspend fun updateIncomingMessagesStatus(conversationId: String, last10Digits: String, newStatus: String)

    @Query("UPDATE messages SET status = :newStatus WHERE conversationId = :conversationId AND isOutgoing = 0 AND status != :newStatus")
    suspend fun updateIncomingMessagesStatus(conversationId: String, newStatus: String)

    @Query("SELECT * FROM messages WHERE (conversationId = :conversationId OR (length(:last10Digits) >= 7 AND conversationId LIKE '%' || :last10Digits)) ORDER BY timestamp ASC")
    fun getMessagesFlow(conversationId: String, last10Digits: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getMessagesFlow(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE (conversationId = :conversationId OR (length(:last10Digits) >= 7 AND conversationId LIKE '%' || :last10Digits)) ORDER BY timestamp DESC LIMIT :limit")
    fun getMessagesPagedFlow(conversationId: String, last10Digits: String, limit: Int): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp DESC LIMIT :limit")
    fun getMessagesPagedFlow(conversationId: String, limit: Int): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM messages WHERE (conversationId = :conversationId OR (length(:last10Digits) >= 7 AND conversationId LIKE '%' || :last10Digits))")
    fun getMessageCountFlow(conversationId: String, last10Digits: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    fun getMessageCountFlow(conversationId: String): Flow<Int>

    @Query("SELECT * FROM messages WHERE id = :messageId LIMIT 1")
    suspend fun getMessageById(messageId: String): MessageEntity?

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId OR (length(:last10Digits) >= 7 AND conversationId LIKE '%' || :last10Digits)")
    suspend fun clearMessagesForConversation(conversationId: String, last10Digits: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun clearMessagesForConversation(conversationId: String)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId OR (length(:last10Digits) >= 7 AND conversationId LIKE '%' || :last10Digits) ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastMessageForConversation(conversationId: String, last10Digits: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastMessageForConversation(conversationId: String): MessageEntity?

    @Query("SELECT * FROM messages")
    suspend fun getAllMessages(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId OR (length(:last10Digits) >= 7 AND conversationId LIKE '%' || :last10Digits)")
    suspend fun getMessagesForConversationList(conversationId: String, last10Digits: String): List<MessageEntity>

    @Query("UPDATE messages SET mediaPath = NULL WHERE (conversationId = :conversationId OR (length(:last10Digits) >= 7 AND conversationId LIKE '%' || :last10Digits)) AND mediaPath IS NOT NULL")
    suspend fun clearMediaForConversation(conversationId: String, last10Digits: String)

    @Query("UPDATE messages SET mediaPath = NULL WHERE (conversationId = :conversationId OR (length(:last10Digits) >= 7 AND conversationId LIKE '%' || :last10Digits)) AND mediaType IN (:types)")
    suspend fun clearMediaByTypes(conversationId: String, last10Digits: String, types: List<String>)

    @Query("DELETE FROM messages WHERE (conversationId = :conversationId OR (length(:last10Digits) >= 7 AND conversationId LIKE '%' || :last10Digits)) AND (mediaPath IS NULL OR mediaPath = '')")
    suspend fun clearTextOnlyForConversation(conversationId: String, last10Digits: String)

    @Query("UPDATE messages SET mediaPath = NULL WHERE mediaPath IS NOT NULL")
    suspend fun clearAllMedia()

    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages()
}
