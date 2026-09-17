package com.example.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.NotificationManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.example.data.local.ChatMediaType
import com.example.data.repository.ChatRepository
import com.example.util.ContactsHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver for handling notification actions:
 * - Direct inline reply (WhatsApp style)
 * - Mark conversation as read
 * - Mute conversation (8h)
 *
 * Using a BroadcastReceiver with goAsync() ensures operations finish in background
 * without launching MainActivity or interrupting the user.
 */
class ChatReplyReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_REPLY_CHAT = "com.example.ACTION_REPLY_CHAT"
        const val ACTION_MARK_AS_READ = "com.example.ACTION_MARK_AS_READ"
        const val ACTION_MUTE_CHAT = "com.example.ACTION_MUTE_CHAT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val peerNumber = intent.getStringExtra("chat_peer_number") ?: return
        val peerName = intent.getStringExtra("chat_peer_name") ?: peerNumber
        val notifId = intent.getIntExtra("notification_id", -1)
        val peerNorm = ContactsHelper.normalizePhoneNumber(peerNumber)
        val chatRepo = ChatRepository.getInstance(context)
        val action = intent.action

        when (action) {
            ACTION_MARK_AS_READ -> {
                if (notifId != -1) {
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    nm?.cancel(notifId)
                }
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        chatRepo.markConversationAsRead(peerNorm)
                    } catch (e: Exception) {
                        android.util.Log.e("ChatReplyReceiver", "Failed to mark as read: ${e.message}", e)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }

            ACTION_MUTE_CHAT -> {
                if (notifId != -1) {
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    nm?.cancel(notifId)
                }
                chatRepo.muteChat(peerNorm, 8 * 60 * 60 * 1000L)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "Muted $peerName for 8 hours", Toast.LENGTH_SHORT).show()
                }
            }

            ACTION_REPLY_CHAT, null -> {
                val remoteInputResults = RemoteInput.getResultsFromIntent(intent)
                val replyText = remoteInputResults
                    ?.getCharSequence(ChatRepository.KEY_TEXT_REPLY)
                    ?.toString()
                    ?.trim()

                if (replyText.isNullOrBlank()) return

                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        chatRepo.markConversationAsRead(peerNorm)
                        chatRepo.sendMessage(
                            recipientNumber = peerNorm,
                            recipientName = peerName,
                            text = replyText,
                            mediaType = ChatMediaType.TEXT
                        )
                        if (notifId != -1) {
                            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                            nm?.cancel(notifId)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("ChatReplyReceiver", "Failed to send inline reply: ${e.message}", e)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }

            else -> {
                val remoteInputResults = RemoteInput.getResultsFromIntent(intent)
                val replyText = remoteInputResults
                    ?.getCharSequence(ChatRepository.KEY_TEXT_REPLY)
                    ?.toString()
                    ?.trim()

                if (!replyText.isNullOrBlank()) {
                    val pendingResult = goAsync()
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            chatRepo.markConversationAsRead(peerNorm)
                            chatRepo.sendMessage(
                                recipientNumber = peerNorm,
                                recipientName = peerName,
                                text = replyText,
                                mediaType = ChatMediaType.TEXT
                            )
                            if (notifId != -1) {
                                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                                nm?.cancel(notifId)
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("ChatReplyReceiver", "Failed to send inline reply: ${e.message}", e)
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }
            }
        }
    }
}
