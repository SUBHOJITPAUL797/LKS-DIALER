package com.example.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
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

class GitHubUpdater(context: Context) {
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

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    fun dismissUpdate(version: String) {
        val prefs = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("dismissed_version", version)
            .putLong("dismissed_time", System.currentTimeMillis())
            .apply()
    }

    suspend fun checkForUpdates(currentVersion: String? = null, force: Boolean = false): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val pInfo = try {
                context.packageManager.getPackageInfo(context.packageName, 0)
            } catch (_: Exception) { null }
            val resolvedLocalVersion = currentVersion ?: "v${pInfo?.versionName ?: BuildConfig.VERSION_NAME}"

            // Check if user previously dismissed this release (suppress for 6 hours unless force == true)
            val prefs = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
            val dismissedVersion = prefs.getString("dismissed_version", "") ?: ""
            val dismissedTime = prefs.getLong("dismissed_time", 0L)

            // Tier 1: Try GitHub REST API (detailed release notes + asset metadata)
            val apiResult = checkViaGitHubApi(resolvedLocalVersion)
            if (apiResult != null) {
                if (!force && dismissedVersion == apiResult.latestVersion && (System.currentTimeMillis() - dismissedTime) < 6 * 60 * 60 * 1000L) {
                    Log.d(TAG, "Update ${apiResult.latestVersion} was dismissed by user within 6 hours — suppressing dialog")
                    return@withContext null
                }
                return@withContext apiResult
            }

            // Tier 2: Direct GitHub Web Redirect (Bypasses the 60 req/hr rate limit on mobile carrier CGNAT IPs!)
            Log.i(TAG, "GitHub API unavailable or rate-limited; attempting direct web redirect fallback...")
            val fallbackResult = checkViaWebRedirect(resolvedLocalVersion)
            if (fallbackResult != null) {
                if (!force && dismissedVersion == fallbackResult.latestVersion && (System.currentTimeMillis() - dismissedTime) < 6 * 60 * 60 * 1000L) {
                    Log.d(TAG, "Update ${fallbackResult.latestVersion} was dismissed by user within 6 hours — suppressing dialog")
                    return@withContext null
                }
                return@withContext fallbackResult
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for updates", e)
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

        CoroutineScope(Dispatchers.IO).launch {
            try {
                _downloadState.value = DownloadState.Downloading(0f, 0L, 0L, 0L)

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

                downloadClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        _downloadState.value = DownloadState.Error("Server returned HTTP ${response.code}")
                        return@launch
                    }

                    val body = response.body ?: run {
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

                                if (now - lastUiUpdate > 120) {
                                    val progress = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes.toFloat() else 0f
                                    _downloadState.value = DownloadState.Downloading(
                                        progress = progress,
                                        downloadedBytes = downloadedBytes,
                                        totalBytes = totalBytes,
                                        speedBytesPerSec = currentSpeed
                                    )
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

                    withContext(Dispatchers.Main) {
                        installApk(contentUri)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                try { if (tempFile.exists()) tempFile.delete() } catch (_: Exception) {}
                _downloadState.value = DownloadState.Error(e.message ?: "Download failed")
            }
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
