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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.repository.FirebaseManager

/**
 * LksKeepAliveService
 * Lightweight foreground service that keeps the app process alive 24/7
 * so FCM messages for incoming calls are received instantly.
 * Periodically refreshes FCM token (every 6 hours) — write-only to Firestore.
 */
class LksKeepAliveService : Service() {

    companion object {
        private const val TAG = "LksKeepAlive"
        private const val CHANNEL_ID = "keep_alive_channel"
        private const val NOTIFICATION_ID = 3001
        private const val TOKEN_REFRESH_INTERVAL = 6 * 60 * 60 * 1000L // 6 hours
    }

    private val handler = Handler(Looper.getMainLooper())
    private var tokenRefreshRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startServiceForeground()
        scheduleTokenRefresh()
        Log.d(TAG, "LKS Keep-Alive Service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

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
            .setContentTitle("LKS Dialer Active")
            .setContentText("Ready to receive calls")
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
        tokenRefreshRunnable?.let { handler.removeCallbacks(it) }
        Log.d(TAG, "LKS Keep-Alive Service destroyed")
    }
}
