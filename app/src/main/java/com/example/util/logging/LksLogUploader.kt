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
import com.example.BuildConfig
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
 * 1. critical_crashes       → Fatal exceptions with system state & stacktrace
 * 2. user_shake_reports     → Reports triggered when user shakes phone or uses Settings report button
 * 3. app_logs               → Device-organized session logs for proactive debugging
 *
 * NOTE: Firestore documents have a hard 1 MB size limit.
 * To avoid hitting this limit (which silently fails), logs are stored in a
 * sub-collection "log_chunks" under the parent report document, NOT inline.
 * Only the last MAX_INLINE_LOGS lines are kept inline for a quick preview.
 */
object LksLogUploader {

    private const val TAG = "LksLogUploader"
    private const val COLLECTION_CRASHES = "critical_crashes"
    private const val COLLECTION_SHAKE_REPORTS = "user_shake_reports"
    private const val COLLECTION_APP_LOGS = "app_logs"

    /** Max log lines stored inline in the main document (safe < 1 MB margin) */
    private const val MAX_INLINE_LOGS = 80

    /** Max log lines per sub-document chunk */
    private const val LOGS_PER_CHUNK = 200

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    // ─────────────────────────────────────────────────────────────────────────
    // Device State Capture
    // ─────────────────────────────────────────────────────────────────────────

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
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
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

            // 4. Audio State (Critical for debugging Xiaomi/Samsung audio routing bugs)
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

            // 5. Call state (if WebRTC engine is active)
            val rtcState = com.example.webrtc.WebRtcEngine.getInstanceIfCreated()?.state?.value
            state["callStatus"] = rtcState?.callStatus?.name ?: "IDLE"
            state["callType"] = rtcState?.callType?.name ?: "NONE"
            state["activeCallId"] = rtcState?.activeCall?.callId ?: "NONE"

        } catch (e: Exception) {
            Log.w(TAG, "Error capturing device state: ${e.message}")
        }
        return state
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Critical Crash Upload
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Uploads an uncaught crash to the `critical_crashes` collection in Firestore.
     * Logs are chunked into sub-documents to avoid the 1 MB Firestore document limit.
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

            Log.i(TAG, "Uploading critical crash... crashId=$crashId user=$userName device=$deviceId logs=${logs.size}")

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
                "appVersionName" to BuildConfig.VERSION_NAME,
                "appVersionCode" to BuildConfig.VERSION_CODE.toLong(),
                "exceptionClass" to throwable.javaClass.name,
                "exceptionMessage" to (throwable.message ?: "No message"),
                // Truncate stacktrace to 4000 chars (stays well within limits)
                "stackTrace" to Log.getStackTraceString(throwable).take(4000),
                "crashingThread" to thread.name,
                "deviceState" to captureDeviceState(context),
                // Only inline the last MAX_INLINE_LOGS lines; full logs go to sub-collection
                "recentLogsSummary" to logs.takeLast(MAX_INLINE_LOGS),
                "totalLogLines" to logs.size,
                "status" to "UNRESOLVED"
            )

            val db = FirebaseFirestore.getInstance()
            val docRef = db.collection(COLLECTION_CRASHES).document(crashId)

            // 1. Write main document
            docRef.set(payload).await()
            Log.i(TAG, "✅ Main crash doc written: $crashId")

            // 2. Write full logs as chunked sub-documents (avoids 1MB limit)
            uploadLogChunks(docRef, logs)

            Log.i(TAG, "✅ Critical crash report fully uploaded: $crashId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to upload critical crash: ${e.javaClass.simpleName}: ${e.message}", e)
            false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // User Shake / Diagnostic Report Upload
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Uploads a user-initiated diagnostic report (via Shake gesture or Settings screen).
     * Logs are chunked into sub-documents to avoid the 1 MB Firestore document limit.
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

            Log.i(TAG, "Uploading shake report... reportId=$reportId user=$userName device=$deviceId logs=${logs.size} trigger=$triggerType")

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
                "appVersionName" to BuildConfig.VERSION_NAME,
                "appVersionCode" to BuildConfig.VERSION_CODE.toLong(),
                "userNote" to userNote.ifBlank { "No note provided" },
                "deviceState" to captureDeviceState(context),
                // Only inline the last MAX_INLINE_LOGS lines; full logs go to sub-collection
                "recentLogsSummary" to logs.takeLast(MAX_INLINE_LOGS),
                "totalLogLines" to logs.size,
                "status" to "NEW"
            )

            val db = FirebaseFirestore.getInstance()
            val docRef = db.collection(COLLECTION_SHAKE_REPORTS).document(reportId)

            // 1. Write main document first
            docRef.set(payload).await()
            Log.i(TAG, "✅ Main shake report doc written: $reportId")

            // 2. Write full logs as chunked sub-documents (avoids 1MB limit)
            uploadLogChunks(docRef, logs)

            // 3. Update app_logs session index for this device (small doc, just summary)
            val cleanPhone = userPhone.replace(Regex("[^0-9+]"), "").ifBlank { "anonymous" }
            val logDocId = "${cleanPhone}_${deviceId}"
            val sessionPayload = hashMapOf<String, Any?>(
                "appId" to "com.subhojit.lksdialer.app",
                "platform" to "android",
                "appVersionName" to BuildConfig.VERSION_NAME,
                "appVersionCode" to BuildConfig.VERSION_CODE.toLong(),
                "deviceId" to deviceId,
                "userPhone" to userPhone,
                "userName" to userName,
                "deviceName" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "lastReportTime" to System.currentTimeMillis(),
                "lastReportId" to reportId,
                "recentLogsSummary" to logs.takeLast(80)
            )
            db.collection(COLLECTION_APP_LOGS)
                .document(logDocId)
                .set(sessionPayload)
                .await()

            Log.i(TAG, "✅ User shake report fully uploaded: $reportId (${logs.size} total log lines)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to upload shake report: ${e.javaClass.simpleName}: ${e.message}", e)
            false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Log Chunk Sub-Collection Writer
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Stores full log lines as chunked sub-documents under a parent document reference.
     * Each chunk holds up to LOGS_PER_CHUNK lines to stay well within the 1 MB Firestore limit.
     * Sub-collection path: {parentCollection}/{docId}/log_chunks/chunk_0, chunk_1, ...
     */
    private suspend fun uploadLogChunks(
        parentRef: com.google.firebase.firestore.DocumentReference,
        logs: List<String>
    ) {
        if (logs.isEmpty()) {
            Log.w(TAG, "uploadLogChunks: no logs to upload")
            return
        }
        try {
            val chunks = logs.chunked(LOGS_PER_CHUNK)
            chunks.forEachIndexed { index, chunk ->
                val chunkDoc = hashMapOf<String, Any>(
                    "chunkIndex" to index,
                    "totalChunks" to chunks.size,
                    "lineCount" to chunk.size,
                    "lines" to chunk
                )
                parentRef.collection("log_chunks")
                    .document("chunk_$index")
                    .set(chunkDoc)
                    .await()
            }
            Log.i(TAG, "✅ Uploaded ${logs.size} log lines in ${chunks.size} chunk(s) to log_chunks sub-collection")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Log chunk upload failed (main report doc still saved): ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
