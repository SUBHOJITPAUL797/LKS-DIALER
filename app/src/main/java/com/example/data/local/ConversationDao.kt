package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversation(conversation: ConversationEntity)

    @Query("SELECT * FROM conversations ORDER BY isPinned DESC, lastMessageTimestamp DESC")
    fun getConversationsFlow(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE phoneNumber = :phoneNumber LIMIT 1")
    suspend fun getConversation(phoneNumber: String): ConversationEntity?

    @Query("UPDATE conversations SET unreadCount = 0 WHERE phoneNumber = :phoneNumber")
    suspend fun resetUnreadCount(phoneNumber: String)

    @Query("UPDATE conversations SET unreadCount = unreadCount + 1 WHERE phoneNumber = :phoneNumber")
    suspend fun incrementUnreadCount(phoneNumber: String)

    @Query("SELECT COALESCE(SUM(unreadCount), 0) FROM conversations")
    fun getTotalUnreadCountFlow(): Flow<Int>

    @Query("DELETE FROM conversations WHERE phoneNumber = :phoneNumber")
    suspend fun deleteConversation(phoneNumber: String)

    @Query("UPDATE conversations SET lastMessageStatus = :status WHERE phoneNumber = :phoneNumber")
    suspend fun updateLastMessageStatus(phoneNumber: String, status: String)

    @Query("UPDATE conversations SET lastMessageText = :text WHERE phoneNumber = :phoneNumber")
    suspend fun updateLastMessageText(phoneNumber: String, text: String)

    @Query("SELECT * FROM conversations")
    suspend fun getConversationsList(): List<ConversationEntity>
}
