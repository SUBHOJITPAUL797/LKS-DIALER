package com.example.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.util.GitHubUpdater

class UpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == "ACTION_START_UPDATE_DOWNLOAD") {
            val url = intent.getStringExtra("update_url") ?: return
            val version = intent.getStringExtra("update_version") ?: return
            Log.i("UpdateDownloadReceiver", "Starting update download for $version from notification action")
            val updater = GitHubUpdater(context)
            updater.downloadUpdate(url, version)
        }
    }
}
