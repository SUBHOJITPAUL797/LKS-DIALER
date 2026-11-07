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
    private var proximityWakeLock: android.os.PowerManager.WakeLock? = null
    private var cpuWakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
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

        val powerManager = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        // Battery & Deep Sleep Armor: Prevent CPU suspension during call (MIUI/Samsung OneUI)
        if (cpuWakeLock == null) {
            try {
                cpuWakeLock = powerManager?.newWakeLock(
                    android.os.PowerManager.PARTIAL_WAKE_LOCK,
                    "LksDialer:ActiveCallCpuLock"
                )?.apply {
                    setReferenceCounted(false)
                    acquire(60 * 60 * 1000L) // 1-hour safety max timeout
                }
            } catch (_: Exception) {}
        }

        // Full Performance / Low Latency Wi-Fi Armor: Prevent Wi-Fi radio sleep/packet batching
        if (wifiLock == null) {
            try {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                @Suppress("DEPRECATION")
                val wifiMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wifiManager?.createWifiLock(wifiMode, "LksDialer:ActiveCallWifiLock")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (_: Exception) {}
        }

        // PROXIMITY_SCREEN_OFF_WAKE_LOCK is 32 (added in API 21)
        if (powerManager?.isWakeLockLevelSupported(32) == true && proximityWakeLock == null) {
            try {
                proximityWakeLock = powerManager.newWakeLock(32, "LksDialer:ActiveCallProximity").apply {
                    setReferenceCounted(false)
                }
            } catch (_: Exception) {}
        }

        // Auto-stop self if call ends in WebRtcEngine
        serviceScope.launch {
            val engine = com.example.webrtc.WebRtcEngine.getInstanceIfCreated()
                ?: com.example.webrtc.WebRtcEngine.getInstance(applicationContext)
            engine.state.collectLatest { s ->
                when (s.callStatus) {
                    com.example.data.model.CallStatus.ENDED,
                    com.example.data.model.CallStatus.DECLINED,
                    com.example.data.model.CallStatus.MISSED,
                    com.example.data.model.CallStatus.IDLE -> {
                        releaseCallLocks()
                        stopSelf()
                    }
                    else -> {
                        val shouldLock = s.callStatus == com.example.data.model.CallStatus.ANSWERED &&
                            s.callType == com.example.data.model.CallType.AUDIO &&
                            s.selectedAudioDevice == com.example.webrtc.AudioDeviceType.EARPIECE
                        if (shouldLock) {
                            try { proximityWakeLock?.acquire() } catch (_: Exception) {}
                        } else {
                            try { if (proximityWakeLock?.isHeld == true) proximityWakeLock?.release() } catch (_: Exception) {}
                        }
                    }
                }
            }
        }

        // Watchdog: heartbeat check every 10 seconds to eliminate zombie notifications
        serviceScope.launch {
            while (isActive) {
                delay(10_000L)
                val eng = com.example.webrtc.WebRtcEngine.getInstanceIfCreated()
                val status = eng?.state?.value?.callStatus
                if (status == null || status == com.example.data.model.CallStatus.IDLE ||
                    status == com.example.data.model.CallStatus.ENDED ||
                    status == com.example.data.model.CallStatus.DECLINED ||
                    status == com.example.data.model.CallStatus.MISSED) {
                    android.util.Log.i("ActiveCallService", "Watchdog: call no longer active (status=$status) -> stopping service")
                    stopSelf()
                    break
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                }
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

    private fun releaseCallLocks() {
        releaseProximityLock()
        try {
            if (cpuWakeLock?.isHeld == true) {
                cpuWakeLock?.release()
            }
        } catch (_: Exception) {}
        cpuWakeLock = null

        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (_: Exception) {}
        wifiLock = null
    }

    private fun releaseProximityLock() {
        try {
            if (proximityWakeLock?.isHeld == true) {
                proximityWakeLock?.release()
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        serviceJob.cancel()
        releaseCallLocks()
        proximityWakeLock = null
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
