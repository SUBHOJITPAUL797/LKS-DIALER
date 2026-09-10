package com.example.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
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
        // 1. Immediately hold CPU awake for up to 35 seconds to prevent Battery Saver / Doze from freezing execution
        val powerManager = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val wakeLock = try {
            powerManager?.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "lksdialer:fcm_incoming_wakelock"
            )?.apply {
                setReferenceCounted(false)
                acquire(35_000L)
            }
        } catch (e: Exception) {
            Log.w("FCM", "Failed to acquire CPU wake lock: ${e.message}")
            null
        }

        Log.d("FCM", "Message received from: ${remoteMessage.from}")

        if (remoteMessage.data.isNotEmpty()) {
            Log.d("FCM", "Data payload: ${remoteMessage.data}")
            val type = remoteMessage.data["type"]

            if (type == "chat_message") {
                Log.d("FCM", "Received chat_message push notification, waking up ChatRepository")
                val prefs = getSharedPreferences("dialer_prefs", Context.MODE_PRIVATE)
                val myPhone = prefs.getString("user_phone", null)
                if (!myPhone.isNullOrBlank()) {
                    com.example.data.repository.ChatRepository.getInstance(this).attachChatListeners(myPhone)
                }
                return
            }

            val callId = remoteMessage.data["callId"] ?: return
            
            if (type == "cancel_call" || type == "missed_call") {
                Log.d("FCM", "Received $type for callId: $callId, dismissing incoming ringing notification")
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(NOTIFICATION_ID)
                
                // Force end the call in WebRtcEngine to drop the ringing UI if it's open
                val engine = com.example.webrtc.WebRtcEngine.getInstanceIfCreated()
                engine?.forceEndCallFromPush(callId)
                FloatingCallBubbleService.silenceRingtone(this)
                FloatingCallBubbleService.hide(this)
                com.example.util.LksIncomingRingtonePlayer.stop()
                LksKeepAliveService.stopRingtone(this)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    try { LksConnectionService.disconnectCall() } catch (_: Exception) {}
                }
                
                val callerName = remoteMessage.data["callerName"] ?: "Unknown Caller"
                val callerNumber = remoteMessage.data["callerNumber"] ?: ""
                val callType = remoteMessage.data["callType"] ?: "AUDIO"
                val callTypeLabel = if (callType.equals("VIDEO", ignoreCase = true)) "Video" else "Audio"
                val callTypeEnum = try { com.example.data.model.CallType.valueOf(callType) } catch (_: Exception) { com.example.data.model.CallType.AUDIO }

                // Record the missed call in FirebaseManager immediately
                try {
                    com.example.data.repository.FirebaseManager.getInstance(this).logCall(
                        direction = com.example.data.model.CallDirection.MISSED,
                        otherPartyNumber = callerNumber,
                        otherPartyName = callerName,
                        callType = callTypeEnum,
                        status = com.example.data.model.CallStatus.MISSED,
                        durationSeconds = 0
                    )
                } catch (e: Exception) {
                    Log.w("FCM", "Failed to log missed call locally: ${e.message}")
                }

                // Show unified missed call notification with Call Back action
                com.example.data.repository.FirebaseManager.getInstance(this).showMissedCallNotification(
                    callerNumber = callerNumber,
                    callerName = callerName,
                    callType = callTypeEnum,
                    callId = callId
                )
                return
            }
            
            if (type == "incoming_call") {
                // Drop stale/delayed push notifications (>45 seconds old) from reconnecting devices
                if (remoteMessage.sentTime > 0 && (System.currentTimeMillis() - remoteMessage.sentTime > 45_000L)) {
                    Log.w("FCM", "Dropping stale incoming call push: $callId (sent ${System.currentTimeMillis() - remoteMessage.sentTime}ms ago)")
                    return
                }

                val callerName   = remoteMessage.data["callerName"]   ?: "Unknown Caller"
                val callerNumber = remoteMessage.data["callerNumber"] ?: ""
                val callType     = remoteMessage.data["callType"]     ?: "AUDIO"
                val callerProfilePic = remoteMessage.data["callerProfilePic"] ?: ""

                // Check Do Not Disturb (DND) and Blocklist
                val firebaseMgr = com.example.data.repository.FirebaseManager.getInstance(this)
                if (firebaseMgr.isDndEnabled() || firebaseMgr.isNumberBlocked(callerNumber)) {
                    Log.i("FCM", "Incoming call auto-declined by DND or Blocklist: $callId from $callerNumber")
                    try {
                        val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                        db.collection("calls").document(callId).update("status", com.example.data.model.CallStatus.DECLINED.name)
                    } catch (e: Exception) {
                        Log.w("FCM", "Failed to decline blocked/DND call: ${e.message}")
                    }
                    return
                }
                
                // Immediately update Firestore status to RINGING so caller knows recipient device received it
                try {
                    val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                    val docRef = db.collection("calls").document(callId)
                    docRef.update("status", "RINGING").addOnFailureListener {
                        docRef.set(mapOf("status" to "RINGING"), com.google.firebase.firestore.SetOptions.merge())
                    }
                } catch (e: Exception) {
                    Log.w("FCM", "Failed to update call status to RINGING: ${e.message}")
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

        if (com.example.MainActivity.isForeground) {
            Log.d("FCM", "MainActivity is already in foreground. Forwarding intent to show call screen.")
            try {
                val directIntent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("incoming_call", true)
                    putExtra("call_id", callId)
                    putExtra("caller_name", callerName)
                    putExtra("caller_number", callerNumber)
                    putExtra("call_type", callType)
                }
                startActivity(directIntent)
            } catch (e: Exception) {
                Log.w("FCM", "Failed to forward incoming call intent: ${e.message}")
            }
        }

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

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
            
        // Load Profile Picture if available (Base64 decoded locally & downscaled to safe icon size)
        if (callerProfilePic.isNotEmpty() && !callerProfilePic.startsWith("http")) {
            try {
                val decodedBytes = android.util.Base64.decode(callerProfilePic, android.util.Base64.DEFAULT)
                val originalBitmap = android.graphics.BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                if (originalBitmap != null) {
                    val scaledBitmap = android.graphics.Bitmap.createScaledBitmap(originalBitmap, 128, 128, true)
                    callerBuilder.setIcon(androidx.core.graphics.drawable.IconCompat.createWithBitmap(scaledBitmap))
                }
            } catch (e: Exception) {
                Log.e("FCM", "Failed to decode profile picture for notification", e)
            }
        }
        val caller = callerBuilder.build()

        val callTypeLabel = if (callType.equals("VIDEO", ignoreCase = true)) "Video" else "Audio"

        val powerManager = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val isInteractive = powerManager?.isInteractive == true
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        val isLocked = keyguardManager?.isKeyguardLocked == true
        val canDrawOverlays = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) android.provider.Settings.canDrawOverlays(this) else true
        val callTypeEnum = try { com.example.data.model.CallType.valueOf(callType) } catch (_: Exception) { com.example.data.model.CallType.AUDIO }

        val needsFullScreen = !isInteractive || isLocked

        // Resolve system ringtone URI for bulletproof OS-level playback
        val ringtoneUri = try {
            com.example.util.LksRingtoneManager.getRingtoneForIncomingCall(this, callerNumber)
        } catch (_: Exception) {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        }
        val vibrationPattern = longArrayOf(0, 1000, 500, 1000, 500, 1000)

        // Fresh high-importance channel WITH real system ringtone & vibration
        // Fresh high-importance silent channel: Audio is managed exclusively by LksIncomingRingtonePlayer
        // Crucial: Must be IMPORTANCE_HIGH with vibration so Android displays native heads-up banner when unlocked!
        val targetChannelId = "lks_incoming_call_v11"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Delete all legacy channels so stale settings/importance don't interfere
            val oldChannels = listOf(
                "incoming_call_channel",
                "incoming_call_silent_channel",
                "lks_incoming_call_v1",
                "lks_incoming_call_v2",
                "lks_incoming_call_v3",
                "lks_incoming_call_v4",
                "lks_incoming_call_v5",
                "lks_incoming_call_v6",
                "lks_incoming_call_v7",
                "lks_incoming_call_v8",
                "lks_incoming_call_v9",
                "lks_incoming_call_v10"
            )
            for (oldChannel in oldChannels) {
                try { notificationManager.deleteNotificationChannel(oldChannel) } catch (_: Exception) {}
            }

            val highChannel = NotificationChannel(
                targetChannelId,
                "Incoming Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming VoIP call alerts"
                setSound(null, null) // Silent channel: audio is managed exclusively by LksIncomingRingtonePlayer
                enableVibration(true)
                this.vibrationPattern = vibrationPattern
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            }
            notificationManager.createNotificationChannel(highChannel)
        }

        // WhatsApp / Telegram style CallStyle notification for guaranteed lockscreen and heads-up visibility
        // CallStyle automatically creates Answer and Decline actions internally.
        val callStyle = NotificationCompat.CallStyle.forIncomingCall(
            caller,
            declinePendingIntent,
            acceptPendingIntent
        )

        val canUseFullScreen = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            notificationManager.canUseFullScreenIntent()
        } else {
            true
        }

        val builder = NotificationCompat.Builder(this, targetChannelId)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("Incoming $callTypeLabel Call")
            .setContentText("$callerName${if (callerNumber.isNotBlank()) " • $callerNumber" else ""}")
            .setStyle(callStyle)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(fullScreenPendingIntent)

        if (needsFullScreen || !canDrawOverlays) {
            // Locked screen OR overlay not permitted: attach fullScreenIntent + MAX priority to wake/alert device
            builder.setPriority(NotificationCompat.PRIORITY_MAX)
            builder.setFullScreenIntent(fullScreenPendingIntent, true)
        } else {
            // Unlocked screen WITH overlay permission:
            // The Floating Pill is the single, clean visual banner on screen!
            // Post with LOW priority and WITHOUT fullScreenIntent so Android SystemUI
            // does NOT pop up a competing heads-up notification card over the pill.
            builder.setPriority(NotificationCompat.PRIORITY_LOW)
        }

        try {
            val notification = builder.build()
            notificationManager.notify(NOTIFICATION_ID, notification)
            Log.i("FCM", "✅ Incoming call notification successfully posted (targetChannel=$targetChannelId, canUseFullScreen=$canUseFullScreen)")
        } catch (e: Exception) {
            Log.e("FCM", "Failed to post incoming call notification: ${e.message}", e)
        }

        // Ensure keep-alive service is active to prevent process kill during incoming ring
        try {
            val keepAliveIntent = Intent(this, LksKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(keepAliveIntent)
            } else {
                startService(keepAliveIntent)
            }
        } catch (e: Exception) {
            Log.w("FCM", "Failed to start keep alive from FCM: ${e.message}")
        }

        // ─── Update Firestore status to RINGING so the CALLER sees "Ringing..." instead of "Calling..." ───
        try {
            com.google.firebase.firestore.FirebaseFirestore.getInstance()
                .collection("calls")
                .document(callId)
                .update("status", com.example.data.model.CallStatus.RINGING.name)
                .addOnSuccessListener {
                    Log.i("FCM", "✅ Call status updated to RINGING in Firestore for callId=$callId")
                }
        } catch (e: Exception) {
            Log.w("FCM", "Failed to update call status to RINGING: ${e.message}")
        }

        // Also trigger fallback in-app audio player in case system sound stream is ducked
        com.example.util.LksIncomingRingtonePlayer.start(this, callerNumber)

        if (needsFullScreen) {
            // Screen is off or phone is locked: Aggressively wake display and launch full-screen UI
            try {
                val screenWake = powerManager?.newWakeLock(
                    android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    android.os.PowerManager.ON_AFTER_RELEASE,
                    "lksdialer:screen_wake_call"
                )
                screenWake?.acquire(25000L)
            } catch (e: Exception) {
                Log.w("FCM", "Screen WakeLock acquisition failed: ${e.message}")
            }

            try {
                fullScreenIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                applicationContext.startActivity(fullScreenIntent)
                Log.i("FCM", "Device is locked: Direct full-screen activity launched")
            } catch (e: Exception) {
                Log.w("FCM", "Direct activity start failed: ${e.message}")
            }
        } else {
            // Device is UNLOCKED:
            if (canDrawOverlays) {
                Log.i("FCM", "Device is unlocked & overlay permission granted -> showing single draggable floating pill")
                FloatingCallBubbleService.showIncoming(
                    this,
                    callId,
                    callerName,
                    callerNumber,
                    callTypeEnum
                )
            } else {
                Log.i("FCM", "Overlay permission not granted -> launching call activity as fallback")
                val directIntent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("incoming_call", true)
                    putExtra("call_id", callId)
                    putExtra("caller_name", callerName)
                    putExtra("caller_number", callerNumber)
                    putExtra("call_type", callType)
                }
                try { applicationContext.startActivity(directIntent) } catch (_: Exception) {}
            }
        }

        // Safety fallback: Check status after 800ms
        val appCtx = applicationContext
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                val engine = com.example.webrtc.WebRtcEngine.getInstanceIfCreated()
                if (engine != null && (engine.state.value.callStatus == com.example.data.model.CallStatus.ENDED || 
                                       engine.state.value.callStatus == com.example.data.model.CallStatus.DECLINED || 
                                       engine.state.value.callStatus == com.example.data.model.CallStatus.MISSED)) {
                    return@postDelayed
                }
                if (com.example.MainActivity.isForeground) {
                    return@postDelayed
                }
                val km = appCtx.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
                val pm = appCtx.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                val currentlyLocked = km?.isKeyguardLocked == true || pm?.isInteractive == false
                if (currentlyLocked) {
                    try { appCtx.startActivity(fullScreenIntent) } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }, 800)
    }
}
