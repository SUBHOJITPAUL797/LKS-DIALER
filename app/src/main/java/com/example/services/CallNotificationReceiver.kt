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

                // Instantly mark status as ANSWERED in Firestore so caller screen switches immediately (<100ms)
                try {
                    FirebaseFirestore.getInstance()
                        .collection("calls")
                        .document(callId)
                        .update(
                            "status", CallStatus.ANSWERED.name,
                            "answeredAt", System.currentTimeMillis()
                        )
                } catch (_: Exception) {}

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
                val callerNumber = intent.getStringExtra("caller_number") ?: ""

                val engine = com.example.webrtc.WebRtcEngine.getInstanceIfCreated() 
                    ?: com.example.webrtc.WebRtcEngine.getInstance(context)
                engine.declineCall(callId, callerNumber)

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    try { LksConnectionService.disconnectCall() } catch (_: Exception) {}
                }
                FloatingCallBubbleService.hide(context)
                com.example.util.LksIncomingRingtonePlayer.stop()
                com.example.util.CallSoundEffectsManager.stopRingbackTone()
            }
        }
    }

    companion object {
        const val ACTION_ACCEPT = "com.example.ACTION_ACCEPT_CALL"
        const val ACTION_DECLINE = "com.example.ACTION_DECLINE_CALL"
        const val NOTIFICATION_ID = 1001
    }
}
