package com.example.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.data.repository.ChatRepository

/**
 * FileTransferCancelReceiver
 * Receives the "Cancel" action tap from the File Transfer ongoing notification
 * and terminates the transfer in ChatRepository immediately.
 */
class FileTransferCancelReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val messageId = intent.getStringExtra(FileTransferNotificationManager.EXTRA_MESSAGE_ID) ?: return
        Log.i("FileTransferCancel", "Received notification cancel action for messageId=$messageId")

        FileTransferNotificationManager.dismiss(context, messageId)
        ChatRepository.getInstance(context).cancelTransfer(messageId)
    }
}
