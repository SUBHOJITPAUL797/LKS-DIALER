package com.example.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.example.MainActivity
import com.example.data.repository.FirebaseManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class CallMessagingService : FirebaseMessagingService() {

    companion object {
        const val CHANNEL_ID = "incoming_call_channel"
        const val MISSED_CALL_CHANNEL_ID = "missed_call_channel"
        const val NOTIFICATION_ID = 1001
    }

    override fun onNewToken(token: String) {
        Log.d("FCM", "New FCM token received - syncing to Firestore")
        FirebaseManager.getInstance(this).updateFcmToken(token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        Log.d("FCM", "Message received from: ${remoteMessage.from}")

        if (remoteMessage.data.isNotEmpty()) {
            Log.d("FCM", "Data payload: ${remoteMessage.data}")
            val type = remoteMessage.data["type"]
            val callId = remoteMessage.data["callId"] ?: return
            
            if (type == "cancel_call" || type == "missed_call") {
                Log.d("FCM", "Received $type for callId=$callId. Dismissing incoming notification.")
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(NOTIFICATION_ID)
                
                // Force end the call in WebRtcEngine to drop the ringing UI if it's open
                val engine = com.example.webrtc.WebRtcEngine.getInstanceIfCreated()
                engine?.forceEndCallFromPush(callId)
                
                if (type == "missed_call") {
                    // Create silent channel for missed calls if not exists
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val missedChannel = NotificationChannel(
                            MISSED_CALL_CHANNEL_ID,
                            "Missed Calls",
                            NotificationManager.IMPORTANCE_DEFAULT
                        ).apply {
                            description = "Notifications for missed VoIP calls"
                        }
                        notificationManager.createNotificationChannel(missedChannel)
                    }

                    val callerName = remoteMessage.data["callerName"] ?: "Unknown Caller"
                    val callType = remoteMessage.data["callType"] ?: "AUDIO"
                    val callTypeLabel = if (callType.equals("VIDEO", ignoreCase = true)) "Video" else "Audio"
                    
                    val builder = NotificationCompat.Builder(this, MISSED_CALL_CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.sym_action_call)
                        .setContentTitle("Missed $callTypeLabel Call")
                        .setContentText("You missed a call from $callerName")
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setAutoCancel(true)
                        
                    notificationManager.notify(callId.hashCode(), builder.build())
                }
                return
            }
            
            if (type == "incoming_call") {
                val callerName   = remoteMessage.data["callerName"]   ?: "Unknown Caller"
                val callerNumber = remoteMessage.data["callerNumber"] ?: ""
                val callType     = remoteMessage.data["callType"]     ?: "AUDIO"
                val callerProfilePic = remoteMessage.data["callerProfilePic"] ?: ""
                
                // Let the caller know we've received the push and the phone is ringing
                try {
                    com.google.firebase.firestore.FirebaseFirestore.getInstance()
                        .collection("calls").document(callId)
                        .update("status", "RINGING")
                } catch (e: Exception) {
                    Log.e("FCM", "Failed to update call status to RINGING: ${e.message}")
                }
                
                showIncomingCallNotification(callerName, callerNumber, callType, callId, callerProfilePic)
            }
        }
    }

    private fun showIncomingCallNotification(
        callerName: String,
        callerNumber: String,
        callType: String,
        callId: String,
        callerProfilePic: String
    ) {
        // Prevent zombie notifications if Firestore already answered/declined the call via the active UI
        val engine = com.example.webrtc.WebRtcEngine.getInstanceIfCreated()
        if (engine != null) {
            val rtcState = engine.state.value
            if (rtcState.activeCall?.callId == callId &&
                rtcState.callStatus != com.example.data.model.CallStatus.CALLING &&
                rtcState.callStatus != com.example.data.model.CallStatus.RINGING
            ) {
                Log.d("FCM", "Call already answered or ended locally. Skipping zombie notification.")
                return
            }
        }

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create the notification channel with maximum importance + ringtone
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Incoming Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming VoIP call alerts with Accept & Decline"
                setSound(
                    ringtoneUri,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }

        // 🔲 Full-screen intent - opens MainActivity (call screen) when tapped 🔲
        val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("incoming_call", true)
            putExtra("call_id", callId)
            putExtra("caller_name", callerName)
            putExtra("caller_number", callerNumber)
            putExtra("call_type", callType)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, 0, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 🟢 ACCEPT action - opens the app and auto-answers 🟢
        val acceptIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("incoming_call", true)
            putExtra("call_id", callId)
            putExtra("auto_answer", true)
            putExtra("caller_name", callerName)
            putExtra("caller_number", callerNumber)
            putExtra("call_type", callType)
        }
        val acceptPendingIntent = PendingIntent.getActivity(
            this, 1, acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // ── DECLINE action — declines in Firestore without opening the app ──
        val declineIntent = Intent(this, CallNotificationReceiver::class.java).apply {
            action = CallNotificationReceiver.ACTION_DECLINE
            putExtra("call_id", callId)
        }
        val declinePendingIntent = PendingIntent.getBroadcast(
            this, 2, declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // ── Build the WhatsApp-style caller person for CallStyle notification ──
        val callerBuilder = Person.Builder()
            .setName(callerName)
            .setImportant(true)
            
        // Download Profile Picture if available (runs on FCM background thread)
        if (callerProfilePic.isNotEmpty()) {
            try {
                val url = java.net.URL(callerProfilePic)
                val connection = url.openConnection()
                connection.connectTimeout = 2000 // 2 seconds
                connection.readTimeout = 2000
                val bitmap = android.graphics.BitmapFactory.decodeStream(connection.getInputStream())
                if (bitmap != null) {
                    callerBuilder.setIcon(androidx.core.graphics.drawable.IconCompat.createWithBitmap(bitmap))
                }
            } catch (e: Exception) {
                Log.e("FCM", "Failed to download profile picture for notification", e)
            }
        }
        val caller = callerBuilder.build()

        val callTypeLabel = if (callType.equals("VIDEO", ignoreCase = true)) "Video" else "Audio"

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("Incoming $callTypeLabel Call")
            .setContentText("$callerName${if (callerNumber.isNotBlank()) " • $callerNumber" else ""}")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)           // can't be swiped away — must tap Accept or Decline
            .setAutoCancel(false)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            .setVibrate(longArrayOf(0, 500, 200, 500, 200, 500))
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_call,
                    "Answer",
                    acceptPendingIntent
                ).build()
            )
            .addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Decline",
                    declinePendingIntent
                ).build()
            )

        notificationManager.notify(NOTIFICATION_ID, builder.build())
        Log.d("FCM", "Incoming call notification shown for callId=$callId caller=$callerName")
    }
}
