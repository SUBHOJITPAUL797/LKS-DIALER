package com.example.util.logging

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LksLogger
 * Centralized, industry-grade diagnostic & telemetry logging engine for LKS Dialer.
 *
 * Features:
 * 1. Automatic real-time Logcat ingestion for the app process (captures WebRTC, Android Audio HAL, Telecom, FCM, UI)
 * 2. In-memory thread-safe Ring Buffer of recent 2500 log lines
 * 3. Dedicated Error/Warning Digest Ring Buffer (captures E/, W/, CRITICAL, ICE failures, Scheduler retries)
 * 4. Structured Subsystem Telemetry Events (`CALL_ENGINE`, `WEBRTC_ICE`, `AUDIO_HAL`, `SCHEDULED_MSG`, `CHAT_SYNC`)
 * 5. Persistent rolling disk log file (`filesDir/logs/lks_persistent_session.log`) so logs survive process restarts
 */
object LksLogger {

    private const val TAG = "LksLogger"
    private const val MAX_BUFFER_LINES = 2500
    private const val MAX_ERROR_DIGEST_LINES = 300
    private const val MAX_BREADCRUMB_LINES = 150
    private const val MAX_PERSISTENT_FILE_BYTES = 512 * 1024L // 512 KB rolling cap

    private val ringBuffer = ArrayDeque<String>(MAX_BUFFER_LINES)
    private val errorDigestBuffer = ArrayDeque<String>(MAX_ERROR_DIGEST_LINES)
    private val breadcrumbBuffer = ArrayDeque<String>(MAX_BREADCRUMB_LINES)
    private val bufferLock = Any()

    private val isLogcatCollectorRunning = AtomicBoolean(false)
    private var logcatJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var appContext: Context? = null
    private var persistentLogFile: File? = null
    private var linesSinceDiskFlush = 0

    /**
     * Initializes the logger at app launch (called from Application.onCreate).
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        val pid = Process.myPid()

        try {
            val logsDir = File(context.applicationContext.filesDir, "logs").apply { mkdirs() }
            val sessionFile = File(logsDir, "lks_persistent_session.log")
            persistentLogFile = sessionFile
            // Pre-load tail of previous persistent session log if ringBuffer is empty
            if (sessionFile.exists() && sessionFile.length() > 0L) {
                val prevLines = sessionFile.readLines().takeLast(250)
                synchronized(bufferLock) {
                    if (ringBuffer.isEmpty() && prevLines.isNotEmpty()) {
                        ringBuffer.addLast("─── PREVIOUS SESSION LOG TAIL (${prevLines.size} lines) ───")
                        prevLines.forEach { line ->
                            if (ringBuffer.size >= MAX_BUFFER_LINES) ringBuffer.pollFirst()
                            ringBuffer.addLast(line)
                        }
                        ringBuffer.addLast("─── END OF PREVIOUS SESSION LOG TAIL ───")
                    }
                }
                // Rotate file if it exceeded 512 KB
                if (sessionFile.length() > MAX_PERSISTENT_FILE_BYTES) {
                    val backup = File(logsDir, "lks_persistent_prev.log")
                    try {
                        sessionFile.copyTo(backup, overwrite = true)
                        sessionFile.writeText(prevLines.joinToString("\n") + "\n")
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed initializing persistent log file: ${e.message}")
        }

        i(TAG, "==================================================")
        i(TAG, "🚀 LKS DIALER LOGGING ENGINE STARTED (PID: $pid)")
        i(TAG, "Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.PRODUCT})")
        i(TAG, "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        i(TAG, "App Version: ${com.example.BuildConfig.VERSION_NAME} (Code ${com.example.BuildConfig.VERSION_CODE})")
        i(TAG, "==================================================")

        startLogcatCollector(pid)
    }

    /**
     * Spawns a background worker that streams logcat output of this process into the buffer.
     */
    private fun startLogcatCollector(pid: Int) {
        if (!isLogcatCollectorRunning.compareAndSet(false, true)) return

        logcatJob = scope.launch {
            var process: java.lang.Process? = null
            var reader: BufferedReader? = null
            try {
                try {
                    Runtime.getRuntime().exec(arrayOf("logcat", "-c")).waitFor()
                } catch (_: Exception) {}

                val cmd = arrayOf("logcat", "-v", "threadtime", "--pid=$pid", "*:V")
                process = Runtime.getRuntime().exec(cmd)
                reader = BufferedReader(InputStreamReader(process.inputStream), 8192)

                var line: String? = null
                while (isLogcatCollectorRunning.get() && reader.readLine().also { line = it } != null) {
                    val currentLine = line ?: continue
                    if (currentLine.contains("logcat:") || currentLine.contains("LksLogger: [BUFFER]")) continue
                    appendRawLine(currentLine, fromLogcat = true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Logcat collector stream ended or not supported on this OEM: ${e.message}")
            } finally {
                try { reader?.close() } catch (_: Exception) {}
                try { process?.destroy() } catch (_: Exception) {}
                isLogcatCollectorRunning.set(false)
            }
        }
    }

    private fun appendRawLine(line: String, isErrorOrWarn: Boolean = false, isBreadcrumb: Boolean = false, fromLogcat: Boolean = false) {
        var shouldFlushChunk: List<String>? = null
        synchronized(bufferLock) {
            if (ringBuffer.size >= MAX_BUFFER_LINES) {
                ringBuffer.pollFirst()
            }
            ringBuffer.addLast(line)

            val detectedErr = isErrorOrWarn ||
                line.contains(" E/") ||
                line.contains(" W/") ||
                line.contains("CRITICAL") ||
                line.contains("Exception") ||
                line.contains("ICE FAILED")
            if (detectedErr) {
                if (errorDigestBuffer.size >= MAX_ERROR_DIGEST_LINES) {
                    errorDigestBuffer.pollFirst()
                }
                errorDigestBuffer.addLast(line)
            }

            if (isBreadcrumb || line.contains("BREADCRUMB/") || line.contains("EVENT/")) {
                if (breadcrumbBuffer.size >= MAX_BREADCRUMB_LINES) {
                    breadcrumbBuffer.pollFirst()
                }
                breadcrumbBuffer.addLast(line)
            }

            if (!fromLogcat || detectedErr || isBreadcrumb) {
                linesSinceDiskFlush++
                if (linesSinceDiskFlush >= 25 || isErrorOrWarn) {
                    linesSinceDiskFlush = 0
                    shouldFlushChunk = ringBuffer.toList().takeLast(25)
                }
            }
        }

        if (shouldFlushChunk != null) {
            val toWrite = shouldFlushChunk
            scope.launch {
                appendLinesToPersistentDisk(toWrite)
            }
        }
    }

    private fun appendLinesToPersistentDisk(lines: List<String>?) {
        if (lines.isNullOrEmpty()) return
        val file = persistentLogFile ?: return
        try {
            FileOutputStream(file, true).bufferedWriter().use { writer ->
                for (l in lines) {
                    writer.write(l)
                    writer.newLine()
                }
            }
        } catch (_: Exception) {}
    }

    private fun formatLog(level: String, tag: String, message: String): String {
        val time = synchronized(dateFormat) { dateFormat.format(Date()) }
        val thread = Thread.currentThread().name
        return "$time [$thread] $level/$tag: $message"
    }

    fun v(tag: String, message: String) {
        Log.v(tag, message)
        appendRawLine(formatLog("V", tag, message))
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        appendRawLine(formatLog("D", tag, message))
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        appendRawLine(formatLog("I", tag, message))
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.w(tag, message, throwable)
            appendRawLine(formatLog("W", tag, "$message\n${Log.getStackTraceString(throwable)}"), isErrorOrWarn = true)
        } else {
            Log.w(tag, message)
            appendRawLine(formatLog("W", tag, message), isErrorOrWarn = true)
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
            appendRawLine(formatLog("E", tag, "$message\n${Log.getStackTraceString(throwable)}"), isErrorOrWarn = true)
        } else {
            Log.e(tag, message)
            appendRawLine(formatLog("E", tag, message), isErrorOrWarn = true)
        }
    }

    fun critical(tag: String, message: String, throwable: Throwable? = null) {
        val formatted = if (throwable != null) {
            "$message\n${Log.getStackTraceString(throwable)}"
        } else message
        Log.e(tag, "🚨 CRITICAL: $formatted")
        appendRawLine(formatLog("CRITICAL", tag, formatted), isErrorOrWarn = true, isBreadcrumb = true)
    }

    /**
     * Records a high-level breadcrumb (e.g. user navigation, call action, audio mode change).
     */
    fun breadcrumb(category: String, message: String) {
        val line = formatLog("BREADCRUMB", category, message)
        Log.i("LksBreadcrumb", "[$category] $message")
        appendRawLine(line, isBreadcrumb = true)
    }

    /**
     * Records a structured subsystem telemetry event for AI cloud diagnostics.
     * Subsystems: CALL_ENGINE, WEBRTC_ICE, AUDIO_HAL, SCHEDULED_MSG, CHAT_SYNC, FCM_PUSH
     */
    fun event(subsystem: String, eventName: String, details: Map<String, Any?> = emptyMap()) {
        val detailStr = if (details.isEmpty()) "" else " | " + details.entries.joinToString(", ") { "${it.key}=${it.value}" }
        val line = formatLog("EVENT", subsystem, "$eventName$detailStr")
        Log.i("LksTelemetry", "[$subsystem] $eventName$detailStr")
        appendRawLine(line, isBreadcrumb = true)
    }

    /**
     * Records a structured subsystem error event for AI cloud diagnostics and optionally triggers auto-telemetry.
     */
    fun errorEvent(
        subsystem: String,
        errorName: String,
        details: Map<String, Any?> = emptyMap(),
        throwable: Throwable? = null,
        autoUploadToCloud: Boolean = false
    ) {
        val detailStr = if (details.isEmpty()) "" else " | " + details.entries.joinToString(", ") { "${it.key}=${it.value}" }
        val stackStr = if (throwable != null) "\n${Log.getStackTraceString(throwable).take(1200)}" else ""
        val line = formatLog("E", subsystem, "[$errorName]$detailStr$stackStr")
        Log.e("LksTelemetry", "[$subsystem] ERROR $errorName$detailStr", throwable)
        appendRawLine(line, isErrorOrWarn = true, isBreadcrumb = true)

        if (autoUploadToCloud) {
            val ctx = appContext
            if (ctx != null) {
                scope.launch {
                    LksLogUploader.uploadAutoTelemetryEvent(
                        context = ctx,
                        subsystem = subsystem,
                        eventType = errorName,
                        summary = "$errorName$detailStr",
                        details = details
                    )
                }
            }
        }
    }

    /**
     * Retrieves the most recent logs from the buffer (up to [limit] lines).
     */
    fun getRecentLogs(limit: Int = 500): List<String> {
        synchronized(bufferLock) {
            val total = ringBuffer.size
            val skipCount = (total - limit).coerceAtLeast(0)
            return ringBuffer.drop(skipCount)
        }
    }

    /**
     * Retrieves the most recent Error and Warning lines for rapid AI root-cause diagnosis.
     */
    fun getErrorDigest(limit: Int = 100): List<String> {
        synchronized(bufferLock) {
            val total = errorDigestBuffer.size
            val skipCount = (total - limit).coerceAtLeast(0)
            return errorDigestBuffer.drop(skipCount)
        }
    }

    /**
     * Retrieves the most recent breadcrumbs and structured subsystem events.
     */
    fun getBreadcrumbs(limit: Int = 80): List<String> {
        synchronized(bufferLock) {
            val total = breadcrumbBuffer.size
            val skipCount = (total - limit).coerceAtLeast(0)
            return breadcrumbBuffer.drop(skipCount)
        }
    }

    /**
     * Returns the recent logs as a single formatted String.
     */
    fun getRecentLogsAsString(limit: Int = 500): String {
        return getRecentLogs(limit).joinToString("\n")
    }

    /**
     * Returns total count of log lines currently retained in memory.
     */
    fun getLogCount(): Int {
        synchronized(bufferLock) {
            return ringBuffer.size
        }
    }

    /**
     * Flushes current buffer to a local file for offline access or debugging.
     */
    fun exportLogsToFile(context: Context): File? {
        return try {
            val logsDir = File(context.filesDir, "logs").apply { mkdirs() }
            val file = File(logsDir, "lks_diag_${System.currentTimeMillis()}.log")
            val content = getRecentLogsAsString(MAX_BUFFER_LINES)
            file.writeText(content)
            file
        } catch (e: Exception) {
            Log.w(TAG, "Failed to export logs to file: ${e.message}")
            null
        }
    }

    fun clear() {
        synchronized(bufferLock) {
            ringBuffer.clear()
            errorDigestBuffer.clear()
            breadcrumbBuffer.clear()
        }
        try {
            persistentLogFile?.writeText("")
        } catch (_: Exception) {}
    }
}
