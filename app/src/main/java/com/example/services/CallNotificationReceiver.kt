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
                val callerName = intent.getStringExtra("caller_name") ?: "LKS User"
                val callerNumber = intent.getStringExtra("caller_number") ?: ""
                val callTypeStr = intent.getStringExtra("call_type") ?: "AUDIO"
                val callType = try { com.example.data.model.CallType.valueOf(callTypeStr) } catch (_: Exception) { com.example.data.model.CallType.AUDIO }

                com.example.util.LksIncomingRingtonePlayer.stop()
                FloatingCallBubbleService.silenceRingtone(context)

                // ⚡ ULTRA-FAST BACKGROUND ANSWER (<100ms):
                // Do not wait for MainActivity to launch; establish WebRTC audio & peer connection immediately!
                val engine = com.example.webrtc.WebRtcEngine.getInstanceIfCreated() 
                    ?: com.example.webrtc.WebRtcEngine.getInstance(context)
                engine.answerIncomingCall(callId, callerName, callerNumber, callTypeStr)

                val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
                val isLocked = keyguardManager?.isKeyguardLocked == true
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                val isInteractive = powerManager?.isInteractive == true

                if (callType == com.example.data.model.CallType.VIDEO || isLocked || !isInteractive) {
                    val launchIntent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra("incoming_call", true)
                        putExtra("call_id", callId)
                        putExtra("auto_answer", false)
                    }
                    context.startActivity(launchIntent)
                } else {
                    // Screen is unlocked & audio call: show floating active call pill directly!
                    FloatingCallBubbleService.showActive(
                        context = context,
                        callId = callId,
                        peerName = callerName,
                        peerNumber = callerNumber,
                        callType = callType
                    )
                }
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
