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

        val isBoot = action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.QUICKBOOT_POWERON"
        val isUpdate = action == Intent.ACTION_MY_PACKAGE_REPLACED
        val isUserPresent = action == Intent.ACTION_USER_PRESENT
        val isResurrect = action == LksKeepAliveService.ACTION_RESURRECT_KEEP_ALIVE

        if (isBoot || isUpdate || isUserPresent || isResurrect) {
            Log.d("BootReceiver", "Waking up LKS Dialer. Initializing Firebase and ensuring KeepAliveService is running.")

            // Initialize FirebaseManager to ensure FCM token is registered
            try {
                FirebaseManager.getInstance(context)
            } catch (e: Exception) {
                Log.w("BootReceiver", "Failed to initialize FirebaseManager: ${e.message}")
            }

            // Start or resurrect the keep-alive foreground service for 24/7 call readiness
            try {
                val keepAliveIntent = Intent(context, LksKeepAliveService::class.java)
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
