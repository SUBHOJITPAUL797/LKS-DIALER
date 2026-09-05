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
        Log.d("FCM", "Message received from: ${remoteMessage.from}")

        if (remoteMessage.data.isNotEmpty()) {
            Log.d("FCM", "Data payload: ${remoteMessage.data}")
            val type = remoteMessage.data["type"]
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

                // Create high-importance channel for missed calls
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val missedChannel = NotificationChannel(
                        MISSED_CALL_CHANNEL_ID,
                        "Missed Calls",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "Notifications for missed VoIP calls"
                        enableVibration(true)
                        enableLights(true)
                        lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    }
                    notificationManager.createNotificationChannel(missedChannel)
                }

                // Tap notification opens Recents tab
                val openIntent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("open_tab", "RECENTS")
                }
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    callId.hashCode(),
                    openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                // Call Back action
                val callBackIntent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("call_back_number", callerNumber)
                    putExtra("call_back_name", callerName)
                    putExtra("call_back_type", callType)
                }
                val callBackPendingIntent = PendingIntent.getActivity(
                    this,
                    (callId + "_cb").hashCode(),
                    callBackIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val builder = NotificationCompat.Builder(this, MISSED_CALL_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.sym_call_missed)
                    .setContentTitle("Missed $callTypeLabel Call")
                    .setContentText("Missed call from $callerName${if (callerNumber.isNotBlank()) " • $callerNumber" else ""}")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .addAction(
                        android.R.drawable.sym_action_call,
                        "Call Back",
                        callBackPendingIntent
                    )
                    
                notificationManager.notify(callId.hashCode(), builder.build())
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
            Log.d("FCM", "MainActivity is already visible in foreground. Ringing directly without heads-up card.")
            com.example.util.LksIncomingRingtonePlayer.start(this, callerNumber)
            return
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
            
        // Load Profile Picture if available (Base64 decoded locally)
        if (callerProfilePic.isNotEmpty() && !callerProfilePic.startsWith("http")) {
            try {
                val decodedBytes = android.util.Base64.decode(callerProfilePic, android.util.Base64.DEFAULT)
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                if (bitmap != null) {
                    callerBuilder.setIcon(androidx.core.graphics.drawable.IconCompat.createWithBitmap(bitmap))
                }
            } catch (e: Exception) {
                Log.e("FCM", "Failed to decode profile picture for notification", e)
            }
        }
        val caller = callerBuilder.build()

        val callTypeLabel = if (callType.equals("VIDEO", ignoreCase = true)) "Video" else "Audio"

        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        val isLocked = keyguardManager?.isKeyguardLocked == true
        val canDrawOverlays = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) android.provider.Settings.canDrawOverlays(this) else true
        val callTypeEnum = try { com.example.data.model.CallType.valueOf(callType) } catch (_: Exception) { com.example.data.model.CallType.AUDIO }

        // Fresh channel ID forces Android to apply null sound so LksIncomingRingtonePlayer handles audio exclusively
        val targetChannelId = if (isLocked) "lks_incoming_call_fgs_v4" else "incoming_call_silent_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (isLocked) {
                val lockedChannel = NotificationChannel(
                    "lks_incoming_call_fgs_v4",
                    "Incoming Calls (Full Screen Alert)",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Incoming VoIP call full-screen alert on locked screen"
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
                notificationManager.createNotificationChannel(lockedChannel)
            } else {
                val silentChannel = NotificationChannel(
                    "incoming_call_silent_channel",
                    "Incoming Calls (Floating Overlay)",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Silent background notification for floating call pill"
                    setShowBadge(false)
                }
                notificationManager.createNotificationChannel(silentChannel)
            }
        }

        val builder = NotificationCompat.Builder(this, targetChannelId)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("Incoming $callTypeLabel Call")
            .setContentText("$callerName${if (callerNumber.isNotBlank()) " • $callerNumber" else ""}")
            .setPriority(if (isLocked) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSound(null)
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

        // BUG-24: Always attach fullScreenIntent. When locked → high priority forces full-screen.
        // When unlocked → false priority still re-shows activity when screen turns back on after Power button press.
        builder.setFullScreenIntent(fullScreenPendingIntent, isLocked)

        val notification = builder.build()
        notificationManager.notify(NOTIFICATION_ID, notification)
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

        // ─── Start ringtone via LksIncomingRingtonePlayer (in-process, 100% reliable) ───
        // Plays directly in process memory on STREAM_RING with CPU WakeLock.
        // Works on locked screen, unlocked screen, idle, Doze mode — always.
        com.example.util.LksIncomingRingtonePlayer.start(this, callerNumber)

        // ─── Start FloatingCallBubbleService (Foreground Service with Microphone type) ───
        // Only show floating incoming pill if unlocked and NOT already showing in foreground
        if (!isLocked && !com.example.MainActivity.isForeground) {
            try {
                FloatingCallBubbleService.showIncoming(this, callId, callerName, callerNumber, callTypeEnum)
            } catch (e: Exception) {
                Log.e("FCM", "Failed to start FloatingCallBubbleService: ${e.message}")
            }
        }

        // Wake screen if locked
        if (isLocked) {
            try {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                val wakeLock = powerManager?.newWakeLock(
                    android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    android.os.PowerManager.ON_AFTER_RELEASE,
                    "lksdialer:incoming_call_wake"
                )
                wakeLock?.acquire(20000)
            } catch (e: Exception) {
                Log.w("FCM", "WakeLock acquisition failed: ${e.message}")
            }
        }


        // Safety fallback: If Telecom doesn't fire onShowIncomingCallUi within 1.5s,
        // show UI ourselves
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
                val currentlyLocked = km?.isKeyguardLocked == true
                if (currentlyLocked) {
                    // Full-screen activity should already be launched by Telecom or fullScreenIntent.
                    // If not visible yet, try launching it.
                    try { appCtx.startActivity(fullScreenIntent) } catch (_: Exception) {}
                } else if (!com.example.MainActivity.isForeground && !com.example.services.FloatingCallBubbleService.isShowingPill) {
                    // Pill not shown yet — try again
                    val canOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) android.provider.Settings.canDrawOverlays(appCtx) else true
                    if (canOverlay) {
                        FloatingCallBubbleService.showIncoming(appCtx, callId, callerName, callerNumber, callTypeEnum)
                    }
                }
            } catch (_: Exception) {}
        }, 1500)

        try {
            LksTelecomManager.reportIncomingCall(this, callId, callerName, callerNumber, callTypeEnum)
        } catch (_: Exception) {}
    }
}
