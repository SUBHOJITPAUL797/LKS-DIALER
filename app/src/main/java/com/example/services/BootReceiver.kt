package com.example.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.data.repository.FirebaseManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Device rebooted. Initializing Firebase to ensure FCM is ready.")
            // Initialize FirebaseManager to ensure it binds/refreshes the FCM token if needed
            FirebaseManager.getInstance(context)
        }
    }
}
