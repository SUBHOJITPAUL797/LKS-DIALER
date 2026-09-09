package com.example.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.data.repository.FirebaseManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d("BootReceiver", "Received broadcast action: $action")

        val isBoot = action == Intent.ACTION_BOOT_COMPLETED || 
                     action == "android.intent.action.QUICKBOOT_POWERON" ||
                     action == Intent.ACTION_LOCKED_BOOT_COMPLETED
        val isUpdate = action == Intent.ACTION_MY_PACKAGE_REPLACED
        val isUserPresent = action == Intent.ACTION_USER_PRESENT
        val isResurrect = action == LksKeepAliveService.ACTION_RESURRECT_KEEP_ALIVE
        val isWatchdog = action == LksKeepAliveService.ACTION_WATCHDOG_HEARTBEAT

        if (isBoot || isUpdate || isUserPresent || isResurrect || isWatchdog) {
            Log.d("BootReceiver", "Waking up LKS Dialer ($action). Initializing Firebase and ensuring KeepAliveService is running.")

            // Acquire brief 10s wakelock to ensure CPU remains awake while foreground service starts
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            val wakeLock = try {
                powerManager?.newWakeLock(
                    android.os.PowerManager.PARTIAL_WAKE_LOCK,
                    "lksdialer:boot_receiver_wake"
                )?.apply {
                    setReferenceCounted(false)
                    acquire(10_000L)
                }
            } catch (_: Exception) { null }

            // Initialize FirebaseManager to ensure FCM token is registered & synced
            try {
                val fbMgr = FirebaseManager.getInstance(context)
                fbMgr.fetchAndUpdateFcmToken()
            } catch (e: Exception) {
                Log.w("BootReceiver", "Failed to initialize FirebaseManager: ${e.message}")
            }

            // Start or resurrect the keep-alive foreground service for 24/7 call readiness
            try {
                val keepAliveIntent = Intent(context, LksKeepAliveService::class.java).apply {
                    if (isWatchdog) setAction(LksKeepAliveService.ACTION_WATCHDOG_HEARTBEAT)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(keepAliveIntent)
                } else {
                    context.startService(keepAliveIntent)
                }
                Log.i("BootReceiver", "KeepAliveService successfully started/resurrected via $action")
            } catch (e: Exception) {
                Log.e("BootReceiver", "Failed to start keep-alive service on $action: ${e.message}")
            }
        }
    }
}
