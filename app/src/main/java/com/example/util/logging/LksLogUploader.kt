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
import com.example.ui.screens.chat.ScheduledMessageManager
import com.example.util.DeviceUtils
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * LksLogUploader
 * Handles organized, AI-ready structured upload of logs, telemetry, and crash reports to Firebase Firestore.
 *
 * Collections (all covered by firestore.rules):
 * 1. critical_crashes       → Fatal exceptions with AI diagnostic snapshot, system state & stacktrace
 * 2. user_shake_reports     → Reports triggered via Shake gesture, Settings, or Live Log Viewer
 * 3. app_logs               → Device-organized session logs & `ai_telemetry` subcollection for proactive AI debugging
 *
 * NOTE: Firestore documents have a hard 1 MB size limit.
 * Full logs are stored in a sub-collection "log_chunks" under the parent report document,
 * while structured `aiDiagnosticSummary`, `errorDigest`, and `recentBreadcrumbs` are stored inline
 * so an AI or developer inspecting the database can immediately identify and fix root causes.
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

    @Volatile
    private var lastAutoTelemetryUploadTimeMs: Long = 0L

    // ─────────────────────────────────────────────────────────────────────────
    // Device & AI Diagnostic State Capture
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
            val isValidated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            state["networkType"] = netType
            state["networkValidated"] = isValidated

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
            state["isMicrophoneMute"] = audioManager?.isMicrophoneMute ?: false
            state["ringerMode"] = when (audioManager?.ringerMode) {
                AudioManager.RINGER_MODE_NORMAL -> "NORMAL"
                AudioManager.RINGER_MODE_SILENT -> "SILENT"
                AudioManager.RINGER_MODE_VIBRATE -> "VIBRATE"
                else -> "UNKNOWN"
            }

            // 5. Call state (if WebRTC engine is active)
            val rtcEngine = com.example.webrtc.WebRtcEngine.getInstanceIfCreated()
            val rtcState = rtcEngine?.state?.value
            state["callStatus"] = rtcState?.callStatus?.name ?: "IDLE"
            state["callType"] = rtcState?.callType?.name ?: "NONE"
            state["activeCallId"] = rtcState?.activeCall?.callId ?: "NONE"
            state["connectionStatusText"] = rtcState?.connectionStatusText ?: "IDLE"
            state["iceServersConfigured"] = rtcEngine?.getIceServers()?.size ?: 0

        } catch (e: Exception) {
            Log.w(TAG, "Error capturing device state: ${e.message}")
        }
        return state
    }

    /**
     * Builds a structured AI-Ready Diagnostic Summary so an AI agent or engineer reading Firestore
     * can immediately see the health of Call Engine, ICE/TURN Relays, Scheduled Messages, and Error Digests.
     */
    fun buildAiDiagnosticSummary(context: Context): Map<String, Any?> {
        val summary = mutableMapOf<String, Any?>()
        try {
            val rtcEngine = com.example.webrtc.WebRtcEngine.getInstanceIfCreated()
            val rtcState = rtcEngine?.state?.value
            val iceServers = rtcEngine?.getIceServers() ?: emptyList()
            val turnRelayCount = iceServers.count { server ->
                server.urls.any { it.startsWith("turn:", ignoreCase = true) || it.startsWith("turns:", ignoreCase = true) }
            }

            summary["callEngineHealth"] = mapOf(
                "engineInitialized" to (rtcEngine != null),
                "callStatus" to (rtcState?.callStatus?.name ?: "IDLE"),
                "callType" to (rtcState?.callType?.name ?: "NONE"),
                "isCaller" to (rtcState?.isCaller ?: false),
                "isMuted" to (rtcState?.isMuted ?: false),
                "isSpeakerOn" to (rtcState?.isSpeakerOn ?: false),
                "isOnHold" to (rtcState?.isOnHold ?: false),
                "connectionStatusText" to (rtcState?.connectionStatusText ?: ""),
                "activeCallId" to (rtcState?.activeCall?.callId ?: "NONE"),
                "totalIceServers" to iceServers.size,
                "turnRelayServersCount" to turnRelayCount
            )

            val scheduledQueue = ScheduledMessageManager.scheduledMessages.value
            val retryingItems = scheduledQueue.filter { it.retryCount > 0 || !it.lastError.isNullOrBlank() }
            summary["scheduledMessagesHealth"] = mapOf(
                "pendingCount" to scheduledQueue.size,
                "retryingCount" to retryingItems.size,
                "nextScheduledAtMillis" to (scheduledQueue.minOfOrNull { it.scheduledAtMillis } ?: 0L),
                "recentScheduleErrors" to retryingItems.take(5).map {
                    mapOf(
                        "id" to it.id,
                        "recipient" to it.recipientNumber,
                        "retryCount" to it.retryCount,
                        "status" to it.status,
                        "lastError" to (it.lastError ?: "")
                    )
                }
            )

            val errorDigest = LksLogger.getErrorDigest(45)
            val breadcrumbs = LksLogger.getBreadcrumbs(45)
            summary["errorDigestCount"] = errorDigest.size
            summary["errorDigest"] = errorDigest
            summary["recentBreadcrumbs"] = breadcrumbs
        } catch (e: Exception) {
            Log.w(TAG, "Error building AI diagnostic summary: ${e.message}")
        }
        return summary
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
                "stackTrace" to Log.getStackTraceString(throwable).take(4000),
                "crashingThread" to thread.name,
                "deviceState" to captureDeviceState(context),
                "aiDiagnosticSummary" to buildAiDiagnosticSummary(context),
                "recentLogsSummary" to logs.takeLast(MAX_INLINE_LOGS),
                "totalLogLines" to logs.size,
                "status" to "UNRESOLVED"
            )

            val db = FirebaseFirestore.getInstance()
            val docRef = db.collection(COLLECTION_CRASHES).document(crashId)

            docRef.set(payload).await()
            Log.i(TAG, "✅ Main crash doc written: $crashId")

            uploadLogChunks(docRef, logs)

            Log.i(TAG, "✅ Critical crash report fully uploaded: $crashId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to upload critical crash: ${e.javaClass.simpleName}: ${e.message}", e)
            false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // User Shake / Manual Diagnostic Report Upload
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Uploads a user-initiated diagnostic report and returns the generated `reportId` on success.
     */
    suspend fun uploadDiagnosticReportWithId(
        context: Context,
        triggerType: String,
        userNote: String,
        logs: List<String>
    ): Result<String> {
        return try {
            val prefs = context.getSharedPreferences("dialer_prefs", Context.MODE_PRIVATE)
            val userPhone = prefs.getString("user_phone", "Anonymous") ?: "Anonymous"
            val userName = prefs.getString("user_name", "Unknown User") ?: "Unknown User"
            val deviceId = DeviceUtils.getDeviceId(context)
            val reportId = "report_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"

            Log.i(TAG, "Uploading diagnostic report... reportId=$reportId user=$userName device=$deviceId logs=${logs.size} trigger=$triggerType")

            val deviceState = captureDeviceState(context)
            val aiSummary = buildAiDiagnosticSummary(context)
            val errorDigest = LksLogger.getErrorDigest(60)
            val breadcrumbs = LksLogger.getBreadcrumbs(60)

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
                "deviceState" to deviceState,
                "aiDiagnosticSummary" to aiSummary,
                "errorDigest" to errorDigest,
                "recentBreadcrumbs" to breadcrumbs,
                "recentLogsSummary" to logs.takeLast(MAX_INLINE_LOGS),
                "totalLogLines" to logs.size,
                "status" to "NEW"
            )

            val db = FirebaseFirestore.getInstance()
            val docRef = db.collection(COLLECTION_SHAKE_REPORTS).document(reportId)

            // 1. Write main document first
            docRef.set(payload).await()
            Log.i(TAG, "✅ Main diagnostic report doc written: $reportId")

            // 2. Write full logs as chunked sub-documents (avoids 1MB limit)
            uploadLogChunks(docRef, logs)

            // 3. Update app_logs session index for this device with AI-ready summary
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
                "lastUserNote" to userNote.ifBlank { "No note provided" },
                "deviceState" to deviceState,
                "aiDiagnosticSummary" to aiSummary,
                "errorDigest" to errorDigest,
                "recentLogsSummary" to logs.takeLast(80)
            )
            db.collection(COLLECTION_APP_LOGS)
                .document(logDocId)
                .set(sessionPayload)
                .await()

            Log.i(TAG, "✅ Diagnostic report fully uploaded: $reportId (${logs.size} total log lines)")
            Result.success(reportId)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to upload diagnostic report: ${e.javaClass.simpleName}: ${e.message}", e)
            LksLogger.exportLogsToFile(context)
            Result.failure(e)
        }
    }

    /**
     * Backwards-compatible boolean wrapper for `uploadDiagnosticReportWithId`.
     */
    suspend fun uploadUserShakeReport(
        context: Context,
        triggerType: String,
        userNote: String,
        logs: List<String>
    ): Boolean {
        return uploadDiagnosticReportWithId(context, triggerType, userNote, logs).isSuccess
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Proactive Auto-Telemetry Upload for Critical Non-Fatal Engine Anomalies
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Automatically uploads a lightweight AI telemetry event to `app_logs/{logDocId}/ai_telemetry/{eventId}`
     * when a critical non-fatal issue occurs (e.g., WebRTC ICE failure or scheduled message retry).
     * Rate-limited to at most once every 20 seconds so it never floods Firestore.
     */
    suspend fun uploadAutoTelemetryEvent(
        context: Context,
        subsystem: String,
        eventType: String,
        summary: String,
        details: Map<String, Any?> = emptyMap()
    ) {
        val now = System.currentTimeMillis()
        if (now - lastAutoTelemetryUploadTimeMs < 20_000L) return
        lastAutoTelemetryUploadTimeMs = now

        try {
            val prefs = context.getSharedPreferences("dialer_prefs", Context.MODE_PRIVATE)
            val userPhone = prefs.getString("user_phone", "Anonymous") ?: "Anonymous"
            val userName = prefs.getString("user_name", "Unknown User") ?: "Unknown User"
            val deviceId = DeviceUtils.getDeviceId(context)
            val cleanPhone = userPhone.replace(Regex("[^0-9+]"), "").ifBlank { "anonymous" }
            val logDocId = "${cleanPhone}_${deviceId}"
            val eventId = "evt_${now}_${UUID.randomUUID().toString().take(5)}"

            val eventDoc = hashMapOf<String, Any?>(
                "eventId" to eventId,
                "timestamp" to now,
                "formattedTime" to synchronized(dateFormat) { dateFormat.format(Date(now)) },
                "subsystem" to subsystem,
                "eventType" to eventType,
                "summary" to summary,
                "details" to details,
                "userPhone" to userPhone,
                "userName" to userName,
                "deviceId" to deviceId,
                "deviceName" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "appVersionName" to BuildConfig.VERSION_NAME,
                "appVersionCode" to BuildConfig.VERSION_CODE.toLong(),
                "deviceState" to captureDeviceState(context),
                "errorDigest" to LksLogger.getErrorDigest(30),
                "recentBreadcrumbs" to LksLogger.getBreadcrumbs(30),
                "recentLogsTail" to LksLogger.getRecentLogs(60)
            )

            val db = FirebaseFirestore.getInstance()
            val parentDoc = db.collection(COLLECTION_APP_LOGS).document(logDocId)
            parentDoc.collection("ai_telemetry").document(eventId).set(eventDoc).await()
            parentDoc.set(
                mapOf(
                    "appId" to "com.subhojit.lksdialer.app",
                    "platform" to "android",
                    "appVersionName" to BuildConfig.VERSION_NAME,
                    "appVersionCode" to BuildConfig.VERSION_CODE.toLong(),
                    "deviceId" to deviceId,
                    "userPhone" to userPhone,
                    "userName" to userName,
                    "deviceName" to "${Build.MANUFACTURER} ${Build.MODEL}",
                    "lastTelemetryEventTime" to now,
                    "lastTelemetrySubsystem" to subsystem,
                    "lastTelemetrySummary" to summary,
                    "aiDiagnosticSummary" to buildAiDiagnosticSummary(context)
                ),
                com.google.firebase.firestore.SetOptions.merge()
            ).await()
        } catch (e: Exception) {
            Log.d(TAG, "Auto-telemetry upload skipped: ${e.message}")
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
