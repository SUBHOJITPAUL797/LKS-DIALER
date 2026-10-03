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
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LksLogger
 * Centralized, app-wide logging engine for LKS Dialer.
 * 
 * Features:
 * 1. Automatic real-time Logcat ingestion for the app process (captures WebRTC, Android Audio HAL, Telecom, FCM, UI)
 * 2. In-memory thread-safe Ring Buffer of recent 2000 log lines
 * 3. Breadcrumb trail recorder for user and lifecycle actions
 * 4. Export to text / file for diagnostic uploading and crash reporting
 */
object LksLogger {

    private const val TAG = "LksLogger"
    private const val MAX_BUFFER_LINES = 2000

    private val ringBuffer = ArrayDeque<String>(MAX_BUFFER_LINES)
    private val bufferLock = Any()

    private val isLogcatCollectorRunning = AtomicBoolean(false)
    private var logcatJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var appContext: Context? = null

    /**
     * Initializes the logger at app launch (called from Application.onCreate).
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        val pid = Process.myPid()
        
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
                // Clear old system logcat buffer first, then stream this PID's output with threadtime
                try {
                    Runtime.getRuntime().exec(arrayOf("logcat", "-c")).waitFor()
                } catch (_: Exception) {}

                val cmd = arrayOf("logcat", "-v", "threadtime", "--pid=$pid", "*:V")
                process = Runtime.getRuntime().exec(cmd)
                reader = BufferedReader(InputStreamReader(process.inputStream), 8192)

                var line: String? = null
                while (isLogcatCollectorRunning.get() && reader.readLine().also { line = it } != null) {
                    val currentLine = line ?: continue
                    // Skip noisy internal logcat warnings or self logs to prevent loops
                    if (currentLine.contains("logcat:") || currentLine.contains("LksLogger: [BUFFER]")) continue
                    appendRawLine(currentLine)
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

    private fun appendRawLine(line: String) {
        synchronized(bufferLock) {
            if (ringBuffer.size >= MAX_BUFFER_LINES) {
                ringBuffer.pollFirst()
            }
            ringBuffer.addLast(line)
        }
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
            appendRawLine(formatLog("W", tag, "$message\n${Log.getStackTraceString(throwable)}"))
        } else {
            Log.w(tag, message)
            appendRawLine(formatLog("W", tag, message))
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
            appendRawLine(formatLog("E", tag, "$message\n${Log.getStackTraceString(throwable)}"))
        } else {
            Log.e(tag, message)
            appendRawLine(formatLog("E", tag, message))
        }
    }

    fun critical(tag: String, message: String, throwable: Throwable? = null) {
        val formatted = if (throwable != null) {
            "$message\n${Log.getStackTraceString(throwable)}"
        } else message
        Log.e(tag, "🚨 CRITICAL: $formatted")
        appendRawLine(formatLog("CRITICAL", tag, formatted))
    }

    /**
     * Records a high-level breadcrumb (e.g. user navigation, call action, audio mode change).
     */
    fun breadcrumb(category: String, message: String) {
        val line = formatLog("BREADCRUMB", category, message)
        Log.i("LksBreadcrumb", "[$category] $message")
        appendRawLine(line)
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
        }
    }
}
