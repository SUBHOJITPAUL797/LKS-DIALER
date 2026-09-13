package com.example.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.p2p.FileTransferProgress
import com.example.data.p2p.TransferMode
import com.example.data.p2p.TransferStatus
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * FileTransferNotificationManager
 * Manages system notification drawer progress bar for P2P and Relay file transfers.
 * Displays live percentage, transfer speed, transfer mode badge (P2P / Relay),
 * and provides an immediate Cancel action button.
 */
object FileTransferNotificationManager {

    private const val TAG = "FileTransferNotif"
    const val CHANNEL_ID = "file_transfers_channel"
    const val EXTRA_MESSAGE_ID = "transfer_message_id"
    const val ACTION_CANCEL_TRANSFER = "com.example.action.CANCEL_TRANSFER"

    private val activeNotifIds = ConcurrentHashMap<String, Int>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun getNotificationId(messageId: String): Int {
        return activeNotifIds.getOrPut(messageId) {
            (messageId.hashCode() and 0x7FFFFFFF) % 10000 + 4000
        }
    }

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "File Transfers",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress of incoming and outgoing file transfers"
                enableVibration(false)
                setSound(null, null)
                setShowBadge(false)
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    fun updateProgress(context: Context, progress: FileTransferProgress) {
        createNotificationChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val notifId = getNotificationId(progress.messageId)

        val modeLabel = if (progress.mode == TransferMode.P2P) "⚡ P2P Direct" else "☁ Cloud Relay"
        val speedStr = if (progress.speedBytesPerSec > 0) {
            String.format(Locale.getDefault(), "%.1f MB/s • %s", progress.speedMbps, modeLabel)
        } else {
            modeLabel
        }

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(context, FileTransferCancelReceiver::class.java).apply {
            action = ACTION_CANCEL_TRANSFER
            putExtra(EXTRA_MESSAGE_ID, progress.messageId)
        }
        val cancelPendingIntent = PendingIntent.getBroadcast(
            context,
            notifId,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        when (progress.status) {
            TransferStatus.CONNECTING -> {
                val title = if (progress.isIncoming) "Receiving file…" else "Sending file…"
                val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText("${progress.fileName} • Connecting…")
                    .setSubText(modeLabel)
                    .setSmallIcon(if (progress.isIncoming) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_upload)
                    .setProgress(100, 0, true)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(tapPendingIntent)
                    .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPendingIntent)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build()
                nm.notify(notifId, notif)
            }

            TransferStatus.TRANSFERRING -> {
                val title = if (progress.isIncoming) "Receiving file (${progress.percent}%)" else "Sending file (${progress.percent}%)"
                val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText("${progress.fileName} • ${progress.percent}%")
                    .setSubText(speedStr)
                    .setSmallIcon(if (progress.isIncoming) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_upload)
                    .setProgress(100, progress.percent, false)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(tapPendingIntent)
                    .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPendingIntent)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build()
                nm.notify(notifId, notif)
            }

            TransferStatus.DONE -> {
                val title = if (progress.isIncoming) "File received ✓" else "File sent ✓"
                val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(progress.fileName)
                    .setSubText(modeLabel)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setProgress(0, 0, false)
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .setContentIntent(tapPendingIntent)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build()
                nm.notify(notifId, notif)

                mainHandler.postDelayed({
                    nm.cancel(notifId)
                    activeNotifIds.remove(progress.messageId)
                }, 3500L)
            }

            TransferStatus.CANCELLED -> {
                val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle("Transfer cancelled")
                    .setContentText(progress.fileName)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setProgress(0, 0, false)
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .setContentIntent(tapPendingIntent)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build()
                nm.notify(notifId, notif)

                mainHandler.postDelayed({
                    nm.cancel(notifId)
                    activeNotifIds.remove(progress.messageId)
                }, 2500L)
            }

            TransferStatus.FAILED -> {
                val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle("Transfer failed")
                    .setContentText(progress.fileName)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setProgress(0, 0, false)
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .setContentIntent(tapPendingIntent)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build()
                nm.notify(notifId, notif)

                mainHandler.postDelayed({
                    nm.cancel(notifId)
                    activeNotifIds.remove(progress.messageId)
                }, 3000L)
            }
        }
    }

    fun dismiss(context: Context, messageId: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val notifId = activeNotifIds.remove(messageId) ?: ((messageId.hashCode() and 0x7FFFFFFF) % 10000 + 4000)
        nm.cancel(notifId)
    }
}
