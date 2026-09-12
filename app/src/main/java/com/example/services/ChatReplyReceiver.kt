package com.example.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.example.data.local.ChatMediaType
import com.example.data.repository.ChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver for handling inline (direct) replies from chat notifications.
 *
 * Using a BroadcastReceiver instead of PendingIntent.getActivity() ensures the
 * reply is sent silently in the background WITHOUT opening the app — so the user
 * can reply while doing other things, completely uninterrupted.
 */
class ChatReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val peerNumber = intent.getStringExtra("chat_peer_number") ?: return
        val peerName   = intent.getStringExtra("chat_peer_name") ?: peerNumber
        val notifId    = intent.getIntExtra("notification_id", -1)

        // Extract the inline-reply text the user typed in the notification shade
        val remoteInputResults = RemoteInput.getResultsFromIntent(intent)
        val replyText = remoteInputResults
            ?.getCharSequence(ChatRepository.KEY_TEXT_REPLY)
            ?.toString()
            ?.trim()

        if (replyText.isNullOrBlank()) return

        val peerNorm = com.example.util.ContactsHelper.normalizePhoneNumber(peerNumber)
        val chatRepo = ChatRepository.getInstance(context)

        // goAsync() ensures Android does NOT kill or freeze this process before
        // the background network/database operations complete!
        val pendingResult = goAsync()

        // Send the reply message in the background — no Activity launch needed
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Replying to a message confirms user has read all prior incoming messages
                chatRepo.markConversationAsRead(peerNorm)

                chatRepo.sendMessage(
                    recipientNumber = peerNorm,
                    recipientName   = peerName,
                    text            = replyText,
                    mediaType       = ChatMediaType.TEXT
                )

                // Update the notification to show the sent reply (prevents it from disappearing
                // mid-conversation). Simply cancel it — the outgoing message flow will update UI.
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
