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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
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
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            
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
        try {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val uri = Uri.parse(url)
            val request = DownloadManager.Request(uri)
                .setTitle("LKS Dialer Update")
                .setDescription("Downloading version $version")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "update_$version.apk")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
            
            downloadId = downloadManager.enqueue(request)
            
            _downloadState.value = DownloadState.Downloading(0f)
            startProgressTracking(downloadManager)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start download", e)
            _downloadState.value = DownloadState.Error(e.message ?: "Unknown error")
        }
    }
    
    private fun startProgressTracking(downloadManager: DownloadManager) {
        Thread {
            var downloading = true
            while (downloading) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = downloadManager.query(query)
                if (cursor.moveToFirst()) {
                    val statusColumn = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val status = if (statusColumn != -1) cursor.getInt(statusColumn) else DownloadManager.STATUS_FAILED
                    
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        downloading = false
                        val uriStringColumn = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                        if (uriStringColumn != -1) {
                            val localUri = Uri.parse(cursor.getString(uriStringColumn))
                            val file = File(localUri.path!!)
                            // Use FileProvider to get a safe content URI
                            val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                            _downloadState.value = DownloadState.Downloaded(contentUri)
                            installApk(contentUri)
                        }
                    } else if (status == DownloadManager.STATUS_FAILED) {
                        downloading = false
                        _downloadState.value = DownloadState.Error("Download failed")
                    } else {
                        val bytesDownloadedColumn = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        val bytesTotalColumn = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                        
                        if (bytesDownloadedColumn != -1 && bytesTotalColumn != -1) {
                            val bytesDownloaded = cursor.getInt(bytesDownloadedColumn)
                            val bytesTotal = cursor.getInt(bytesTotalColumn)
                            if (bytesTotal > 0) {
                                val progress = bytesDownloaded.toFloat() / bytesTotal.toFloat()
                                _downloadState.value = DownloadState.Downloading(progress)
                            }
                        }
                    }
                }
                cursor.close()
                Thread.sleep(500)
            }
        }.start()
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
