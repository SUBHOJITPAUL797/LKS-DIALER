package com.example.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class ActiveCallService : Service() {

    private var headsetButtonManager: HeadsetButtonManager? = null
    private val serviceJob = kotlinx.coroutines.SupervisorJob()
    private val serviceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + serviceJob)

    companion object {
        const val CHANNEL_ID = "active_call_channel"
        const val NOTIFICATION_ID = 2001
        
        fun start(context: Context, callId: String, callType: String) {
            try {
                val intent = Intent(context, ActiveCallService::class.java).apply {
                    putExtra("callId", callId)
                    putExtra("callType", callType)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        fun stop(context: Context) {
            val intent = Intent(context, ActiveCallService::class.java)
            context.stopService(intent)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val callId = intent?.getStringExtra("callId") ?: ""
        val callType = intent?.getStringExtra("callType") ?: "Audio"

        if (headsetButtonManager == null) {
            headsetButtonManager = HeadsetButtonManager(this).also { it.startListening() }
        } else {
            headsetButtonManager?.startListening()
        }

        // Auto-stop self if call ends in WebRtcEngine
        serviceScope.launch {
            val engine = com.example.webrtc.WebRtcEngine.getInstanceIfCreated() ?: return@launch
            engine.state.collectLatest { s ->
                when (s.callStatus) {
                    com.example.data.model.CallStatus.ENDED,
                    com.example.data.model.CallStatus.DECLINED,
                    com.example.data.model.CallStatus.MISSED,
                    com.example.data.model.CallStatus.IDLE -> {
                        stopSelf()
                    }
                    else -> {}
                }
            }
        }

        createNotificationChannel()

        val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
            this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("incoming_call", true)
            putExtra("call_id", callId)
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ongoing $callType Call")
            .setContentText("Tap to return to call screen")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (callType.equals("VIDEO", ignoreCase = true)) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                }
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceJob.cancel()
        try {
            headsetButtonManager?.stopListening()
        } catch (_: Exception) {}
        headsetButtonManager = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Ongoing Calls",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows an ongoing call indicator"
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
