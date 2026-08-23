package com.example.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.data.repository.FirebaseManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            Log.d("BootReceiver", "Device rebooted. Starting keep-alive service and initializing Firebase.")
            
            // Initialize FirebaseManager to ensure FCM token is ready
            FirebaseManager.getInstance(context)
            
            // Start the keep-alive foreground service for 24/7 call readiness
            try {
                val keepAliveIntent = Intent(context, LksKeepAliveService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(keepAliveIntent)
                } else {
                    context.startService(keepAliveIntent)
                }
            } catch (e: Exception) {
                Log.e("BootReceiver", "Failed to start keep-alive service on boot: ${e.message}")
            }
        }
    }
}
