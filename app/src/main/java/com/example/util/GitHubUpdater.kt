package com.example.util

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val isUpdateAvailable: Boolean,
    val latestVersion: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val isMandatory: Boolean
)

sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(val progress: Float) : DownloadState()
    data class Downloaded(val fileUri: Uri) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

class GitHubUpdater(private val context: Context) {
    private val TAG = "GitHubUpdater"
    private val REPO_OWNER = "SUBHOJITPAUL797"
    private val REPO_NAME = "LKS-DIALER"
    
    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()
    
    private var downloadId: Long = -1L

    suspend fun checkForUpdates(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "LKS-Dialer-Android-App")
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonObject = JSONObject(response)
                
                val tagName = jsonObject.optString("tag_name", "")
                val body = jsonObject.optString("body", "No release notes provided.")
                val assets = jsonObject.optJSONArray("assets")
                
                var downloadUrl = ""
                if (assets != null && assets.length() > 0) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        if (asset.optString("name", "").endsWith(".apk")) {
                            downloadUrl = asset.optString("browser_download_url", "")
                            break
                        }
                    }
                }
                
                // Compare versions. Very basic string comparison for simple semantic versioning (e.g. v1.0.0)
                val currentVersion = "v${BuildConfig.VERSION_NAME}"
                val isUpdateAvailable = isVersionGreater(tagName, currentVersion)
                
                // Check if mandatory
                val isMandatory = body.contains("[MANDATORY]", ignoreCase = true) || 
                                  body.contains("[CRITICAL]", ignoreCase = true)
                                  
                if (isUpdateAvailable && downloadUrl.isNotBlank()) {
                    return@withContext UpdateInfo(
                        isUpdateAvailable = true,
                        latestVersion = tagName,
                        releaseNotes = body.replace("[MANDATORY]", "").replace("[CRITICAL]", "").trim(),
                        downloadUrl = downloadUrl,
                        isMandatory = isMandatory
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for updates", e)
        }
        return@withContext null
    }
    
    fun downloadUpdate(url: String, version: String) {
        val fileName = "update_$version.apk"
        val destinationFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                _downloadState.value = DownloadState.Downloading(0f)
                
                var downloadUrl = url
                var connection = URL(downloadUrl).openConnection() as HttpURLConnection
                connection.setRequestProperty("User-Agent", "LKS-Dialer-Android-App")
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                
                // Manually handle redirects if HttpURLConnection fails to do so for HTTPS to HTTPS cross-domain
                var redirectCount = 0
                var responseCode = connection.responseCode
                while (responseCode / 100 == 3 && redirectCount < 5) {
                    downloadUrl = connection.getHeaderField("Location")
                    connection = URL(downloadUrl).openConnection() as HttpURLConnection
                    connection.setRequestProperty("User-Agent", "LKS-Dialer-Android-App")
                    connection.connectTimeout = 15000
                    connection.readTimeout = 15000
                    responseCode = connection.responseCode
                    redirectCount++
                }

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    _downloadState.value = DownloadState.Error("Server returned HTTP $responseCode")
                    return@launch
                }

                val fileLength = connection.contentLength
                val input = connection.inputStream
                val output = java.io.FileOutputStream(destinationFile)
                
                val data = ByteArray(8192)
                var total: Long = 0
                var count: Int
                
                var lastUpdate = System.currentTimeMillis()
                
                while (input.read(data).also { count = it } != -1) {
                    total += count
                    output.write(data, 0, count)
                    
                    if (fileLength > 0) {
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 100) { // Throttle UI updates to every 100ms
                            val progress = total.toFloat() / fileLength.toFloat()
                            _downloadState.value = DownloadState.Downloading(progress)
                            lastUpdate = now
                        }
                    }
                }
                
                output.flush()
                output.close()
                input.close()
                
                val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destinationFile)
                _downloadState.value = DownloadState.Downloaded(contentUri)
                
                withContext(Dispatchers.Main) {
                    installApk(contentUri)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                _downloadState.value = DownloadState.Error(e.message ?: "Download failed")
            }
        }
    }
    
    fun installApk(contentUri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install APK", e)
        }
    }
    
    private fun isVersionGreater(remote: String, local: String): Boolean {
        return try {
            val rParts = remote.replace("v", "").split(".").map { it.toIntOrNull() ?: 0 }
            val lParts = local.replace("v", "").split(".").map { it.toIntOrNull() ?: 0 }
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
