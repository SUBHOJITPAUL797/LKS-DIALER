package com.example.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.repository.FirebaseManager
import com.example.util.LksRingtoneManager

/**
 * LksKeepAliveService
 * Industry-standard persistent foreground service (like WhatsApp/Telegram) that:
 * 1. Keeps the app process alive 24/7 so FCM messages are received instantly
 * 2. Plays incoming call ringtone from ALREADY-RUNNING foreground context
 *    (no background service start restrictions — the service is ALREADY in foreground)
 * 3. Periodically refreshes FCM token (every 6 hours) — write-only to Firestore
 *
 * Why ringtone is played here (not FloatingCallBubbleService):
 * Android 12+ restricts starting NEW foreground services from background.
 * On locked/idle screens, startForegroundService() fails silently.
 * Since this service is ALREADY foreground, it can play audio immediately.
 * This is exactly how WhatsApp and Telegram handle it.
 */
class LksKeepAliveService : Service() {

    companion object {
        private const val TAG = "LksKeepAlive"
        private const val CHANNEL_ID = "keep_alive_channel"
        private const val NOTIFICATION_ID = 3001
        private const val TOKEN_REFRESH_INTERVAL = 6 * 60 * 60 * 1000L // 6 hours

        const val ACTION_START_RINGTONE = "com.example.action.START_RINGTONE"
        const val ACTION_STOP_RINGTONE = "com.example.action.STOP_RINGTONE"
        const val ACTION_SILENCE_RINGTONE = "com.example.action.SILENCE_RINGTONE"

        @Volatile
        var instance: LksKeepAliveService? = null
            private set

        @Volatile
        var isRinging: Boolean = false
            private set

        /**
         * Start ringtone from the already-running keep-alive service.
         * This is 100% reliable because the service is ALREADY in foreground.
         */
        fun startRingtone(context: Context, callerNumber: String) {
            val intent = Intent(context, LksKeepAliveService::class.java).apply {
                action = ACTION_START_RINGTONE
                putExtra("caller_number", callerNumber)
            }
            try {
                context.startService(intent) // NOT startForegroundService — already foreground
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send ringtone intent: ${e.message}")
            }
        }

        /**
         * Stop ringtone playback.
         */
        fun stopRingtone(context: Context) {
            val intent = Intent(context, LksKeepAliveService::class.java).apply {
                action = ACTION_STOP_RINGTONE
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send stop ringtone intent: ${e.message}")
                // Direct call as fallback
                instance?.stopRingingInternal()
            }
        }

        /**
         * Silence ringtone (volume button press). Stops audio but keeps vibration pattern info.
         */
        fun silenceRingtone(context: Context) {
            val intent = Intent(context, LksKeepAliveService::class.java).apply {
                action = ACTION_SILENCE_RINGTONE
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send silence intent: ${e.message}")
                instance?.stopRingingInternal()
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var tokenRefreshRunnable: Runnable? = null
    private var ringtonePlayer: MediaPlayer? = null
    private var ringtoneObject: Ringtone? = null
    private var vibrator: Vibrator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startServiceForeground()
        scheduleTokenRefresh()
        Log.d(TAG, "LKS Keep-Alive Service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RINGTONE -> {
                val callerNumber = intent.getStringExtra("caller_number") ?: ""
                startRingingInternal(callerNumber)
            }
            ACTION_STOP_RINGTONE -> {
                stopRingingInternal()
            }
            ACTION_SILENCE_RINGTONE -> {
                stopRingingInternal()
            }
        }
        return START_STICKY
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // RINGTONE PLAYBACK (runs in already-foreground service context)
    // ─────────────────────────────────────────────────────────────────────────────

    private fun startRingingInternal(callerNumber: String) {
        // Stop any existing ringtone first
        stopRingingInternal()

        Log.d(TAG, "Starting ringtone for caller: $callerNumber")
        isRinging = true

        // Get the user's custom ringtone (per-contact → app-level → system default)
        val ringtoneUri: Uri = try {
            LksRingtoneManager.getRingtoneForIncomingCall(this, callerNumber)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get custom ringtone, using system default: ${e.message}")
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        }

        // Try MediaPlayer first (better control: looping, volume)
        try {
            ringtonePlayer = MediaPlayer().apply {
                setDataSource(this@LksKeepAliveService, ringtoneUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
            Log.d(TAG, "Ringtone playing via MediaPlayer: $ringtoneUri")
        } catch (e: Exception) {
            Log.w(TAG, "MediaPlayer failed, falling back to Ringtone API: ${e.message}")
            // Fallback: Use Ringtone API
            try {
                ringtoneObject = RingtoneManager.getRingtone(this, ringtoneUri)?.apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        isLooping = true
                    }
                    play()
                }
                Log.d(TAG, "Ringtone playing via Ringtone API")
            } catch (e2: Exception) {
                Log.e(TAG, "Both ringtone methods failed: ${e2.message}")
            }
        }

        // Start vibration pattern
        startVibration()
    }

    fun stopRingingInternal() {
        isRinging = false

        try {
            ringtonePlayer?.stop()
            ringtonePlayer?.release()
            ringtonePlayer = null
        } catch (_: Exception) {}

        try {
            ringtoneObject?.stop()
            ringtoneObject = null
        } catch (_: Exception) {}

        try {
            vibrator?.cancel()
            vibrator = null
        } catch (_: Exception) {}

        Log.d(TAG, "Ringtone stopped")
    }

    private fun startVibration() {
        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            val pattern = longArrayOf(0, 500, 200, 500, 200, 500, 1000) // ring-ring-ring-pause pattern
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0)) // 0 = repeat from index 0
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibration failed: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // NOTIFICATION CHANNEL + FOREGROUND
    // ─────────────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LKS Dialer Background",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps LKS Dialer ready to receive calls"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    private fun startServiceForeground() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LKS DIALER")
            .setContentText("Checking for incoming calls")
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground: ${e.message}")
            try { startForeground(NOTIFICATION_ID, notification) } catch (_: Exception) {}
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // FCM TOKEN REFRESH
    // ─────────────────────────────────────────────────────────────────────────────

    private fun scheduleTokenRefresh() {
        tokenRefreshRunnable = object : Runnable {
            override fun run() {
                try {
                    com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                        .addOnSuccessListener { token ->
                            FirebaseManager.getInstance(this@LksKeepAliveService).updateFcmToken(token)
                            Log.d(TAG, "Periodic FCM token refresh successful")
                        }
                } catch (e: Exception) {
                    Log.w(TAG, "Token refresh failed: ${e.message}")
                }
                handler.postDelayed(this, TOKEN_REFRESH_INTERVAL)
            }
        }
        handler.postDelayed(tokenRefreshRunnable!!, TOKEN_REFRESH_INTERVAL)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        stopRingingInternal()
        tokenRefreshRunnable?.let { handler.removeCallbacks(it) }
        Log.d(TAG, "LKS Keep-Alive Service destroyed")
    }
}
