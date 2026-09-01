package com.example.services

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.data.model.CallStatus
import com.example.MainActivity
import com.google.firebase.firestore.FirebaseFirestore

class CallNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val callId = intent.getStringExtra("call_id") ?: return
        if (action != ACTION_ACCEPT && action != ACTION_DECLINE) return
        Log.d("CallReceiver", "Action: $action, CallId: $callId")

        // Dismiss the incoming call notification
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)

        when (action) {
            ACTION_ACCEPT -> {
                com.example.util.LksIncomingRingtonePlayer.stop()
                FloatingCallBubbleService.silenceRingtone(context)

                // Open MainActivity and pass the call info to answer
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("incoming_call", true)
                    putExtra("call_id", callId)
                    putExtra("auto_answer", true)
                }
                context.startActivity(launchIntent)
            }
            ACTION_DECLINE -> {
                // Dismiss any floating bubble notification
                try { notificationManager.cancel(2002) } catch (_: Exception) {}

                // Decline in Firestore immediately without opening the app
                FirebaseFirestore.getInstance()
                    .collection("calls")
                    .document(callId)
                    .update(
                        "status", CallStatus.DECLINED.name,
                        "endedAt", System.currentTimeMillis()
                    )
                    .addOnSuccessListener {
                        Log.d("CallReceiver", "Call $callId declined via notification")
                    }
                    .addOnFailureListener { e ->
                        Log.e("CallReceiver", "Failed to decline call $callId", e)
                    }

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    try { LksConnectionService.disconnectCall() } catch (_: Exception) {}
                }
                val engine = com.example.webrtc.WebRtcEngine.getInstanceIfCreated()
                engine?.forceEndCallFromPush(callId)
                FloatingCallBubbleService.hide(context)
                com.example.util.LksIncomingRingtonePlayer.stop()
            }
        }
    }

    companion object {
        const val ACTION_ACCEPT = "com.example.ACTION_ACCEPT_CALL"
        const val ACTION_DECLINE = "com.example.ACTION_DECLINE_CALL"
        const val NOTIFICATION_ID = 1001
    }
}
