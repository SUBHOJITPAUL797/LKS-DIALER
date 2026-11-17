package com.example.services

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.util.GitHubUpdater

/**
 * Handles "Update Now" and "Later" (24h snooze) actions from the system update notification.
 */
class UpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        when (action) {
            "ACTION_START_UPDATE_DOWNLOAD" -> {
                val url = intent.getStringExtra("update_url") ?: return
                val version = intent.getStringExtra("update_version") ?: return
                Log.i("UpdateDownloadReceiver", "Starting update download for $version from notification action")
                val updater = GitHubUpdater(context)
                updater.downloadUpdate(url, version)
            }
            "ACTION_SNOOZE_UPDATE" -> {
                val version = intent.getStringExtra("update_version") ?: ""
                Log.i("UpdateDownloadReceiver", "User tapped Later on update notification for $version")
                if (version.isNotBlank()) {
                    GitHubUpdater(context).dismissUpdate(version)
                }
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.cancel(GitHubUpdater.UPDATE_NOTIFICATION_ID)
            }
        }
    }
}
