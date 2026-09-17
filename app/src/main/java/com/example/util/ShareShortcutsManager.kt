package com.example.util

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.example.MainActivity
import com.example.R
import com.example.data.local.ConversationEntity

private const val TAG = "ShareShortcutsManager"
private const val MAX_SHARE_SHORTCUTS = 5

object ShareShortcutsManager {

    /**
     * Publishes dynamic shortcuts for the most recent chats so Android's system
     * Share Sheet displays these contacts directly in the Direct Share row at the top.
     */
    fun publishRecentChatShortcuts(context: Context, conversations: List<ConversationEntity>) {
        try {
            if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context) &&
                conversations.isEmpty()) {
                return
            }

            val topChats = conversations.take(MAX_SHARE_SHORTCUTS)
            val shortcuts = topChats.mapIndexed { index, chat ->
                val peerNumber = chat.phoneNumber
                val peerName = chat.contactName.ifBlank { peerNumber }

                val targetIntent = Intent(context, MainActivity::class.java).apply {
                    action = Intent.ACTION_SEND
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("chat_peer_number", peerNumber)
                    putExtra("chat_peer_name", peerName)
                }

                ShortcutInfoCompat.Builder(context, "direct_share_${peerNumber.hashCode()}")
                    .setShortLabel(peerName)
                    .setLongLabel(peerName)
                    .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                    .setIntent(targetIntent)
                    .setCategories(setOf("com.example.category.SHARE_TARGET"))
                    .setRank(index)
                    .build()
            }

            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
            Log.d(TAG, "Published ${shortcuts.size} Direct Share shortcuts")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to publish Direct Share shortcuts: ${e.message}")
        }
    }
}
