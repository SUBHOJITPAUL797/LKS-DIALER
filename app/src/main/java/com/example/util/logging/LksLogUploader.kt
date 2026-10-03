package com.example.util.logging

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import com.example.util.DeviceUtils
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * LksLogUploader
 * Handles organized, structured upload of logs and crash reports to Firebase Firestore.
 * 
 * Collections:
 * 1. critical_crashes  -> Automatically caught fatal exceptions with system state & stacktrace
 * 2. user_shake_reports -> Reports triggered when user shakes phone or requests help from settings
 * 3. app_logs           -> Device-organized session logs for proactive debugging across all devices
 */
object LksLogUploader {

    private const val TAG = "LksLogUploader"
    private const val COLLECTION_CRASHES = "critical_crashes"
    private const val COLLECTION_SHAKE_REPORTS = "user_shake_reports"
    private const val COLLECTION_APP_LOGS = "app_logs"

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /**
     * Gathers a comprehensive snapshot of device hardware, battery, audio, and network state.
     */
    fun captureDeviceState(context: Context): Map<String, Any?> {
        val state = mutableMapOf<String, Any?>()
        try {
            // 1. Battery State
            val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, batteryFilter)
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val batteryPct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            state["batteryPercent"] = batteryPct
            state["isCharging"] = isCharging

            // 2. Memory State
            val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            actManager?.getMemoryInfo(memInfo)
            state["availableRamMB"] = memInfo.availMem / (1024 * 1024)
            state["totalRamMB"] = memInfo.totalMem / (1024 * 1024)
            state["isLowMemory"] = memInfo.lowMemory

            // 3. Network State
            val connManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNetwork = connManager?.activeNetwork
            val caps = connManager?.getNetworkCapabilities(activeNetwork)
            val netType = when {
                caps == null -> "NONE"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                else -> "OTHER"
            }
            state["networkType"] = netType

            // 4. Audio State (Critical for debugging Xiaomi / Samsung routing issues)
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val modeStr = when (audioManager?.mode) {
                AudioManager.MODE_NORMAL -> "MODE_NORMAL"
                AudioManager.MODE_RINGTONE -> "MODE_RINGTONE"
                AudioManager.MODE_IN_CALL -> "MODE_IN_CALL"
                AudioManager.MODE_IN_COMMUNICATION -> "MODE_IN_COMMUNICATION"
                else -> "UNKNOWN(${audioManager?.mode})"
            }
            state["audioMode"] = modeStr
            @Suppress("DEPRECATION")
            state["isSpeakerphoneOn"] = audioManager?.isSpeakerphoneOn ?: false
            state["ringerMode"] = when (audioManager?.ringerMode) {
                AudioManager.RINGER_MODE_NORMAL -> "NORMAL"
                AudioManager.RINGER_MODE_SILENT -> "SILENT"
                AudioManager.RINGER_MODE_VIBRATE -> "VIBRATE"
                else -> "UNKNOWN"
            }

            // 5. Call state (if WebRTC active)
            val rtcState = com.example.webrtc.WebRtcEngine.getInstanceIfCreated()?.state?.value
            state["callStatus"] = rtcState?.callStatus?.name ?: "IDLE"
            state["callType"] = rtcState?.callType?.name ?: "NONE"
            state["activeCallId"] = rtcState?.activeCall?.callId ?: "NONE"

        } catch (e: Exception) {
            Log.w(TAG, "Error capturing device state: ${e.message}")
        }
        return state
    }

    /**
     * Uploads an uncaught crash to the `critical_crashes` collection in Firestore.
     */
    suspend fun uploadCriticalCrash(
        context: Context,
        throwable: Throwable,
        thread: Thread,
        logs: List<String>
    ): Boolean {
        return try {
            val prefs = context.getSharedPreferences("dialer_prefs", Context.MODE_PRIVATE)
            val userPhone = prefs.getString("user_phone", "Anonymous") ?: "Anonymous"
            val userName = prefs.getString("user_name", "Unknown User") ?: "Unknown User"
            val deviceId = DeviceUtils.getDeviceId(context)
            val crashId = "crash_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"

            val (vName, vCode) = try {
                val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pInfo.longVersionCode else @Suppress("DEPRECATION") pInfo.versionCode.toLong()
                (pInfo.versionName ?: "2.8.9") to code
            } catch (_: Exception) {
                "2.8.9" to 157L
            }

            val payload = hashMapOf<String, Any?>(
                "crashId" to crashId,
                "timestamp" to System.currentTimeMillis(),
                "formattedTime" to synchronized(dateFormat) { dateFormat.format(Date()) },
                "appId" to "com.subhojit.lksdialer.app",
                "appName" to "LKS-DIALER-ANDROID",
                "platform" to "android",
                "userName" to userName,
                "userPhone" to userPhone,
                "deviceId" to deviceId,
                "deviceName" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "deviceManufacturer" to Build.MANUFACTURER,
                "deviceModel" to Build.MODEL,
                "deviceProduct" to Build.PRODUCT,
                "deviceHardware" to Build.HARDWARE,
                "androidVersion" to Build.VERSION.RELEASE,
                "sdkInt" to Build.VERSION.SDK_INT,
                "appVersionName" to vName,
                "appVersionCode" to vCode,
                "exceptionClass" to throwable.javaClass.name,
                "exceptionMessage" to (throwable.message ?: "No message"),
                "stackTrace" to Log.getStackTraceString(throwable),
                "crashingThread" to thread.name,
                "deviceState" to captureDeviceState(context),
                "recentLogs" to logs.takeLast(400),
                "status" to "UNRESOLVED"
            )

            FirebaseFirestore.getInstance()
                .collection(COLLECTION_CRASHES)
                .document(crashId)
                .set(payload)
                .await()

            Log.i(TAG, "✅ Critical crash report uploaded to Firestore: $crashId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload critical crash to Firestore: ${e.message}")
            false
        }
    }

    /**
     * Uploads a user-initiated report (via Shake or Settings screen).
     */
    suspend fun uploadUserShakeReport(
        context: Context,
        triggerType: String,
        userNote: String,
        logs: List<String>
    ): Boolean {
        return try {
            val prefs = context.getSharedPreferences("dialer_prefs", Context.MODE_PRIVATE)
            val userPhone = prefs.getString("user_phone", "Anonymous") ?: "Anonymous"
            val userName = prefs.getString("user_name", "Unknown User") ?: "Unknown User"
            val deviceId = DeviceUtils.getDeviceId(context)
            val reportId = "report_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"

            val (vName, vCode) = try {
                val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pInfo.longVersionCode else @Suppress("DEPRECATION") pInfo.versionCode.toLong()
                (pInfo.versionName ?: "2.8.9") to code
            } catch (_: Exception) {
                "2.8.9" to 157L
            }

            val payload = hashMapOf<String, Any?>(
                "reportId" to reportId,
                "timestamp" to System.currentTimeMillis(),
                "formattedTime" to synchronized(dateFormat) { dateFormat.format(Date()) },
                "triggerType" to triggerType,
                "appId" to "com.subhojit.lksdialer.app",
                "appName" to "LKS-DIALER-ANDROID",
                "platform" to "android",
                "userName" to userName,
                "userPhone" to userPhone,
                "deviceId" to deviceId,
                "deviceName" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "deviceManufacturer" to Build.MANUFACTURER,
                "deviceModel" to Build.MODEL,
                "androidVersion" to "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                "appVersionName" to vName,
                "appVersionCode" to vCode,
                "userNote" to userNote.ifBlank { "No note provided" },
                "deviceState" to captureDeviceState(context),
                "recentLogs" to logs.takeLast(600),
                "logCount" to logs.size,
                "status" to "NEW"
            )

            FirebaseFirestore.getInstance()
                .collection(COLLECTION_SHAKE_REPORTS)
                .document(reportId)
                .set(payload)
                .await()

            // Also update app_logs session for this device
            val cleanPhone = userPhone.replace(Regex("[^0-9+]"), "").ifBlank { "anonymous" }
            val logDocId = "${cleanPhone}_${deviceId}"
            val sessionPayload = hashMapOf<String, Any?>(
                "appId" to "com.subhojit.lksdialer.app",
                "platform" to "android",
                "appVersionName" to vName,
                "appVersionCode" to vCode,
                "deviceId" to deviceId,
                "userPhone" to userPhone,
                "userName" to userName,
                "deviceName" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "lastReportTime" to System.currentTimeMillis(),
                "lastReportId" to reportId,
                "recentLogsSummary" to logs.takeLast(100)
            )
            FirebaseFirestore.getInstance()
                .collection(COLLECTION_APP_LOGS)
                .document(logDocId)
                .set(sessionPayload)
                .await()

            Log.i(TAG, "✅ User shake report uploaded successfully: $reportId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload shake report to Firestore: ${e.message}")
            false
        }
    }
}
