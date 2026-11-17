package com.example.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.example.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val isUpdateAvailable: Boolean,
    val latestVersion: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val isMandatory: Boolean
)

sealed interface UpdateDiscoveryState {
    data object Idle : UpdateDiscoveryState
    data object Checking : UpdateDiscoveryState
    data class Available(val info: UpdateInfo) : UpdateDiscoveryState
    data class SuppressedWithinCooldown(val info: UpdateInfo) : UpdateDiscoveryState
    data object UpToDate : UpdateDiscoveryState
    data class Failed(val reason: String) : UpdateDiscoveryState
}

sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(
        val progress: Float,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = 0L,
        val speedBytesPerSec: Long = 0L
    ) : DownloadState()
    data class Downloaded(val fileUri: Uri) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

/**
 * Pure, testable policy determining when an automatic app update prompt or notification
 * is allowed to surface without interrupting active calls or focused user workflows.
 */
object UpdatePromptPolicy {
    fun shouldShowAutoUpdateDialog(
        hasPendingUpdate: Boolean,
        isVersionSnoozed: Boolean,
        isOneToOneCallActive: Boolean,
        isGroupCallActive: Boolean,
        hasIncomingGroupInvite: Boolean,
        isOnMainHubRoute: Boolean,
        hasActiveModalOrSheet: Boolean,
        millisSinceLastUserInteraction: Long,
        minIdleWindowMs: Long = 2500L
    ): Boolean {
        if (!hasPendingUpdate || isVersionSnoozed) return false
        if (isOneToOneCallActive || isGroupCallActive || hasIncomingGroupInvite) return false
        if (!isOnMainHubRoute || hasActiveModalOrSheet) return false
        if (millisSinceLastUserInteraction in 0 until minIdleWindowMs) return false
        return true
    }

    fun shouldPostUpdateNotification(
        force: Boolean,
        isVersionSnoozed: Boolean,
        isOneToOneCallActive: Boolean,
        isGroupCallActive: Boolean,
        hasIncomingGroupInvite: Boolean
    ): Boolean {
        if (isOneToOneCallActive || isGroupCallActive || hasIncomingGroupInvite) return false
        if (!force && isVersionSnoozed) return false
        return true
    }
}

class GitHubUpdater(context: Context) {
    companion object {
        const val UPDATE_CHANNEL_ID = "lks_app_updates"
        const val UPDATE_NOTIFICATION_ID = 2001
        const val UPDATE_DOWNLOAD_NOTIFICATION_ID = 2002
        const val SNOOZE_COOLDOWN_MS = 24 * 60 * 60 * 1000L // 24 hours snooze when user taps Later
    }

    private val context = context.applicationContext
    private val TAG = "GitHubUpdater"
    private val REPO_OWNER = "SUBHOJITPAUL797"
    private val REPO_NAME = "LKS-DIALER"

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val _discoveryState = MutableStateFlow<UpdateDiscoveryState>(UpdateDiscoveryState.Idle)
    val discoveryState: StateFlow<UpdateDiscoveryState> = _discoveryState.asStateFlow()

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    fun isUpdateSnoozed(version: String): Boolean {
        if (version.isBlank()) return false
        val prefs = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
        val dismissedVersion = prefs.getString("dismissed_version", "") ?: ""
        val dismissedTime = prefs.getLong("dismissed_time", 0L)
        return dismissedVersion == version && (System.currentTimeMillis() - dismissedTime) < SNOOZE_COOLDOWN_MS
    }

    private fun isAnyCallCurrentlyActive(): Triple<Boolean, Boolean, Boolean> {
        val rtcState = com.example.webrtc.WebRtcEngine.getInstanceIfCreated()?.state?.value
        val oneToOneActive = rtcState != null && (
            rtcState.callStatus == com.example.data.model.CallStatus.CALLING ||
            rtcState.callStatus == com.example.data.model.CallStatus.RINGING ||
            rtcState.callStatus == com.example.data.model.CallStatus.ANSWERED
        )
        val grpState = com.example.webrtc.GroupCallEngine.getInstanceIfCreated()?.state?.value
        val groupActive = grpState?.isActive == true
        val groupInvite = grpState?.incomingInviteRoom != null
        return Triple(oneToOneActive, groupActive, groupInvite)
    }

    fun dismissUpdate(version: String) {
        val prefs = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
        if (version.isNotBlank()) {
            prefs.edit()
                .putString("dismissed_version", version)
                .putLong("dismissed_time", System.currentTimeMillis())
                .apply()
        }
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(UPDATE_NOTIFICATION_ID)
        } catch (_: Exception) {}
        val currentDiscovered = (_discoveryState.value as? UpdateDiscoveryState.Available)?.info
        if (currentDiscovered != null) {
            _discoveryState.value = UpdateDiscoveryState.SuppressedWithinCooldown(currentDiscovered)
        }
    }

    suspend fun checkForUpdates(currentVersion: String? = null, force: Boolean = false): UpdateInfo? = withContext(Dispatchers.IO) {
        _discoveryState.value = UpdateDiscoveryState.Checking
        try {
            val pInfo = try {
                context.packageManager.getPackageInfo(context.packageName, 0)
            } catch (_: Exception) { null }
            val resolvedLocalVersion = currentVersion ?: "v${pInfo?.versionName ?: BuildConfig.VERSION_NAME}"

            // Tier 1: Try GitHub REST API (detailed release notes + asset metadata)
            // Tier 2: Direct GitHub Web Redirect (Bypasses the 60 req/hr rate limit on mobile carrier CGNAT IPs)
            val discovered = checkViaGitHubApi(resolvedLocalVersion) ?: run {
                Log.i(TAG, "GitHub API unavailable or rate-limited; attempting direct web redirect fallback...")
                checkViaWebRedirect(resolvedLocalVersion)
            }

            if (discovered != null) {
                val withinCooldown = isUpdateSnoozed(discovered.latestVersion)
                val (oneToOneActive, groupActive, groupInvite) = isAnyCallCurrentlyActive()
                if (UpdatePromptPolicy.shouldPostUpdateNotification(
                        force = force,
                        isVersionSnoozed = withinCooldown,
                        isOneToOneCallActive = oneToOneActive,
                        isGroupCallActive = groupActive,
                        hasIncomingGroupInvite = groupInvite
                    )
                ) {
                    showUpdateAvailableNotification(discovered)
                } else {
                    Log.d(TAG, "Suppressing update notification (callActive=${oneToOneActive || groupActive || groupInvite}, snoozed=$withinCooldown)")
                }

                if (!force && withinCooldown) {
                    Log.d(TAG, "Update ${discovered.latestVersion} was snoozed by user ('Later') within 24 hours — suppressing auto dialog")
                    _discoveryState.value = UpdateDiscoveryState.SuppressedWithinCooldown(discovered)
                    return@withContext null
                }
                _discoveryState.value = UpdateDiscoveryState.Available(discovered)
                return@withContext discovered
            }

            _discoveryState.value = UpdateDiscoveryState.UpToDate
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for updates", e)
            _discoveryState.value = UpdateDiscoveryState.Failed(e.message ?: "Update check failed")
        }
        return@withContext null
    }

    private fun checkViaGitHubApi(resolvedLocalVersion: String): UpdateInfo? {
        try {
            val urlString = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
            val request = Request.Builder()
                .url(urlString)
                .header("User-Agent", "LKS-Dialer-Android-App")
                .header("Accept", "application/vnd.github.v3+json")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "GitHub API returned HTTP ${response.code} (e.g. rate limit on shared mobile carrier IP)")
                    return null
                }
                val bodyStr = response.body?.string() ?: return null
                val json = JSONObject(bodyStr)
                val tagName = json.getString("tag_name")
                val releaseNotes = json.optString("body", "Bug fixes and performance improvements.")

                val assets = json.getJSONArray("assets")
                var downloadUrl: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.getString("name")
                    if (name.endsWith(".apk")) {
                        downloadUrl = asset.getString("browser_download_url")
                        break
                    }
                }

                if (downloadUrl != null && isVersionGreater(tagName, resolvedLocalVersion)) {
                    return UpdateInfo(
                        isUpdateAvailable = true,
                        latestVersion = tagName,
                        releaseNotes = releaseNotes,
                        downloadUrl = downloadUrl,
                        isMandatory = false
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "checkViaGitHubApi failed: ${e.message}")
        }
        return null
    }

    private fun checkViaWebRedirect(resolvedLocalVersion: String): UpdateInfo? {
        try {
            // Do NOT follow redirects — we only want the 302 Found 'Location' header!
            val noRedirectClient = okHttpClient.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            val urlString = "https://github.com/$REPO_OWNER/$REPO_NAME/releases/latest"
            val request = Request.Builder()
                .url(urlString)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                .build()

            noRedirectClient.newCall(request).execute().use { response ->
                val location = response.header("Location") ?: ""
                Log.d(TAG, "Web redirect Location: $location (HTTP ${response.code})")
                if (location.contains("/releases/tag/")) {
                    val tagName = location.substringAfterLast("/releases/tag/").trim()
                    if (tagName.isNotBlank() && isVersionGreater(tagName, resolvedLocalVersion)) {
                        val downloadUrl = "https://github.com/$REPO_OWNER/$REPO_NAME/releases/download/$tagName/app-release.apk"
                        return UpdateInfo(
                            isUpdateAvailable = true,
                            latestVersion = tagName,
                            releaseNotes = "A new update ($tagName) is available with performance and security improvements.",
                            downloadUrl = downloadUrl,
                            isMandatory = false
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "checkViaWebRedirect failed: ${e.message}")
        }
        return null
    }

    fun downloadUpdate(url: String, version: String) {
        val fileName = "update_$version.apk"
        val destinationFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        val tempFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "$fileName.tmp")

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureNotificationChannel(notificationManager)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                _downloadState.value = DownloadState.Downloading(0f, 0L, 0L, 0L)
                updateDownloadNotification(notificationManager, version, 0, 0L)

                // High-performance OkHttp client tuned for fast large downloads
                val downloadClient = okHttpClient.newBuilder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(90, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "LKS-Dialer-Android-App")
                    .header("Accept-Encoding", "identity")
                    .header("Connection", "Keep-Alive")
                    .build()

                var response: okhttp3.Response? = null
                var attempt = 0
                val maxAttempts = 3
                while (attempt < maxAttempts) {
                    attempt++
                    try {
                        val res = downloadClient.newCall(request).execute()
                        if (res.isSuccessful && res.body != null) {
                            response = res
                            break
                        } else {
                            val code = res.code
                            res.close()
                            if (code in listOf(502, 503, 504) && attempt < maxAttempts) {
                                Log.w(TAG, "Transient HTTP $code from GitHub CDN edge, retrying in ${attempt * 2}s (attempt $attempt/$maxAttempts)...")
                                delay(attempt * 2000L)
                                continue
                            }
                            notificationManager.cancel(UPDATE_DOWNLOAD_NOTIFICATION_ID)
                            _downloadState.value = DownloadState.Error("Server returned HTTP $code (CDN Gateway Timeout). Tap Retry or open browser link.")
                            return@launch
                        }
                    } catch (e: Exception) {
                        if (attempt < maxAttempts) {
                            Log.w(TAG, "Download attempt $attempt failed (${e.message}), retrying in ${attempt * 2}s...")
                            delay(attempt * 2000L)
                            continue
                        }
                        notificationManager.cancel(UPDATE_DOWNLOAD_NOTIFICATION_ID)
                        _downloadState.value = DownloadState.Error(e.message ?: "Download connection failed")
                        return@launch
                    }
                }

                val safeResponse = response ?: run {
                    notificationManager.cancel(UPDATE_DOWNLOAD_NOTIFICATION_ID)
                    _downloadState.value = DownloadState.Error("Download failed after retries")
                    return@launch
                }

                safeResponse.use { resp ->
                    val body = resp.body ?: run {
                        notificationManager.cancel(UPDATE_DOWNLOAD_NOTIFICATION_ID)
                        _downloadState.value = DownloadState.Error("Empty response body from server")
                        return@launch
                    }

                    val totalBytes = body.contentLength()
                    var downloadedBytes = 0L

                    // 128KB buffered streams & 64KB read chunk for full 10MB/s+ bandwidth utilization
                    val inStream = BufferedInputStream(body.byteStream(), 128 * 1024)
                    val outStream = BufferedOutputStream(FileOutputStream(tempFile), 128 * 1024)

                    val buffer = ByteArray(64 * 1024)
                    var count: Int
                    var lastUiUpdate = System.currentTimeMillis()
                    var lastSpeedCalc = System.currentTimeMillis()
                    var lastSpeedBytes = 0L
                    var currentSpeed = 0L

                    inStream.use { input ->
                        outStream.use { output ->
                            while (input.read(buffer).also { count = it } != -1) {
                                downloadedBytes += count
                                output.write(buffer, 0, count)

                                val now = System.currentTimeMillis()
                                if (now - lastSpeedCalc >= 250) {
                                    val timeDelta = now - lastSpeedCalc
                                    if (timeDelta > 0) {
                                        currentSpeed = ((downloadedBytes - lastSpeedBytes) * 1000L) / timeDelta
                                    }
                                    lastSpeedCalc = now
                                    lastSpeedBytes = downloadedBytes
                                }

                                if (now - lastUiUpdate > 300) {
                                    val progress = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes.toFloat() else 0f
                                    _downloadState.value = DownloadState.Downloading(
                                        progress = progress,
                                        downloadedBytes = downloadedBytes,
                                        totalBytes = totalBytes,
                                        speedBytesPerSec = currentSpeed
                                    )
                                    updateDownloadNotification(notificationManager, version, (progress * 100).toInt(), currentSpeed)
                                    lastUiUpdate = now
                                }
                            }
                            output.flush()
                        }
                    }

                    // Rename temp file to final destination file
                    if (tempFile.exists()) {
                        if (destinationFile.exists()) destinationFile.delete()
                        tempFile.renameTo(destinationFile)
                    }

                    val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destinationFile)
                    _downloadState.value = DownloadState.Downloaded(contentUri)

                    notificationManager.cancel(UPDATE_DOWNLOAD_NOTIFICATION_ID)
                    showDownloadCompleteNotification(notificationManager, version, contentUri)

                    withContext(Dispatchers.Main) {
                        installApk(contentUri)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                try { if (tempFile.exists()) tempFile.delete() } catch (_: Exception) {}
                notificationManager.cancel(UPDATE_DOWNLOAD_NOTIFICATION_ID)
                _downloadState.value = DownloadState.Error(e.message ?: "Download failed")
            }
        }
    }

    fun showUpdateAvailableNotification(info: UpdateInfo) {
        if (!info.isUpdateAvailable) return
        val (oneToOneActive, groupActive, groupInvite) = isAnyCallCurrentlyActive()
        if (oneToOneActive || groupActive || groupInvite) {
            Log.d(TAG, "Skipping update notification because a call is currently active")
            return
        }

        val prefs = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
        val lastNotifiedVersion = prefs.getString("last_notified_version", "") ?: ""
        val lastNotifiedTime = prefs.getLong("last_notified_time", 0L)
        // Throttle notifications to at most once per 6 hours for the same version
        if (lastNotifiedVersion == info.latestVersion && (System.currentTimeMillis() - lastNotifiedTime) < 6 * 60 * 60 * 1000L) {
            Log.d(TAG, "Update notification for ${info.latestVersion} already shown within 6 hours — skipping duplicate")
            return
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureNotificationChannel(notificationManager)

        // Tapping the notification body opens MainActivity and shows UpdateDialog
        val openAppIntent = Intent(context, com.example.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("show_update_dialog", true)
            putExtra("update_version", info.latestVersion)
            putExtra("update_notes", info.releaseNotes)
            putExtra("update_url", info.downloadUrl)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            UPDATE_NOTIFICATION_ID,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        // "⚡ Update Now" action button: starts download directly in background
        val downloadIntent = Intent(context, com.example.services.UpdateDownloadReceiver::class.java).apply {
            action = "ACTION_START_UPDATE_DOWNLOAD"
            putExtra("update_url", info.downloadUrl)
            putExtra("update_version", info.latestVersion)
        }
        val downloadPendingIntent = PendingIntent.getBroadcast(
            context,
            UPDATE_NOTIFICATION_ID + 1,
            downloadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        // "Later" action button: snoozes the update and dismisses the notification
        val snoozeIntent = Intent(context, com.example.services.UpdateDownloadReceiver::class.java).apply {
            action = "ACTION_SNOOZE_UPDATE"
            putExtra("update_version", info.latestVersion)
        }
        val snoozePendingIntent = PendingIntent.getBroadcast(
            context,
            UPDATE_NOTIFICATION_ID + 2,
            snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val cleanNotes = if (info.releaseNotes.isNotBlank() && info.releaseNotes != "null") {
            info.releaseNotes.take(200).replace(Regex("[#*`-]"), " ").trim()
        } else {
            "Includes performance optimizations, instant call connectivity, and bug fixes."
        }

        val notification = androidx.core.app.NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("🚀 Update Available: ${info.latestVersion}")
            .setContentText("Tap to update now, or choose Later to snooze.")
            .setStyle(
                androidx.core.app.NotificationCompat.BigTextStyle()
                    .setBigContentTitle("🚀 LKS-DIALER ${info.latestVersion} Available")
                    .bigText("A new version is available with the latest features and bug fixes:\n\n$cleanNotes\n\nTap to download & install now, or tap Later.")
                    .setSummaryText("Update Available")
            )
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .addAction(
                android.R.drawable.stat_sys_download,
                "⚡ Update Now",
                downloadPendingIntent
            )
            .addAction(
                android.R.drawable.ic_menu_recent_history,
                "Later",
                snoozePendingIntent
            )
            .setColor(0xFF00ADB5.toInt())
            .build()

        notificationManager.notify(UPDATE_NOTIFICATION_ID, notification)

        prefs.edit()
            .putString("last_notified_version", info.latestVersion)
            .putLong("last_notified_time", System.currentTimeMillis())
            .apply()
    }

    private fun ensureNotificationChannel(notificationManager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                UPDATE_CHANNEL_ID,
                "App Updates",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for new LKS-DIALER updates and releases"
                enableLights(true)
                lightColor = android.graphics.Color.CYAN
                enableVibration(true)
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun updateDownloadNotification(notificationManager: NotificationManager, version: String, progressPercent: Int, speedBytes: Long) {
        val speedStr = formatSpeed(speedBytes)
        val notif = androidx.core.app.NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading LKS-DIALER $version")
            .setContentText("$progressPercent% • $speedStr")
            .setProgress(100, progressPercent, progressPercent <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
            .setColor(0xFF00ADB5.toInt())
            .build()
        notificationManager.notify(UPDATE_DOWNLOAD_NOTIFICATION_ID, notif)
    }

    private fun showDownloadCompleteNotification(notificationManager: NotificationManager, version: String, contentUri: Uri) {
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        val installPendingIntent = PendingIntent.getActivity(
            context,
            UPDATE_DOWNLOAD_NOTIFICATION_ID + 5,
            installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val readyNotif = androidx.core.app.NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("✅ Update Ready to Install")
            .setContentText("LKS-DIALER $version has downloaded. Tap to install now.")
            .setStyle(
                androidx.core.app.NotificationCompat.BigTextStyle()
                    .setBigContentTitle("✅ LKS-DIALER $version Downloaded")
                    .bigText("The update package has been downloaded successfully. Tap to install now and restart the app.")
            )
            .setContentIntent(installPendingIntent)
            .setAutoCancel(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setDefaults(androidx.core.app.NotificationCompat.DEFAULT_ALL)
            .addAction(
                android.R.drawable.stat_sys_download_done,
                "Install Now",
                installPendingIntent
            )
            .setColor(0xFF00ADB5.toInt())
            .build()
        notificationManager.notify(UPDATE_NOTIFICATION_ID, readyNotif)
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB/s", bytesPerSec / (1024f * 1024f))
            bytesPerSec >= 1024 -> String.format(java.util.Locale.US, "%.0f KB/s", bytesPerSec / 1024f)
            else -> "$bytesPerSec B/s"
        }
    }

    fun installApk(uri: Uri) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    android.widget.Toast.makeText(context, "Please allow 'Install unknown apps' to complete update", android.widget.Toast.LENGTH_LONG).show()
                    val settingsIntent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(settingsIntent)
                    return
                }
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            val resolveInfoList = context.packageManager.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            for (resolveInfo in resolveInfoList) {
                val pkgName = resolveInfo.activityInfo.packageName
                context.grantUriPermission(pkgName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install APK", e)
            android.widget.Toast.makeText(context, "Install failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun isVersionGreater(remote: String, local: String): Boolean {
        return try {
            val rParts = remote.removePrefix("v").trim().split(".").map { it.toIntOrNull() ?: 0 }
            val lParts = local.removePrefix("v").trim().split(".").map { it.toIntOrNull() ?: 0 }
            val maxLen = maxOf(rParts.size, lParts.size)
            for (i in 0 until maxLen) {
                val r = rParts.getOrElse(i) { 0 }
                val l = lParts.getOrElse(i) { 0 }
                if (r > l) return true
                if (r < l) return false
            }
            false
        } catch (e: Exception) {
            false
        }
    }
}
