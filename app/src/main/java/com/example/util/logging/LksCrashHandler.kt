package com.example.util.logging

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * LksCrashHandler
 * Global uncaught exception handler that intercepts fatal app crashes,
 * creates full diagnostic payloads, persists them locally for offline safety,
 * and uploads them to Firebase Firestore under the `critical_crashes` collection.
 */
class LksCrashHandler private constructor(
    private val context: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    companion object {
        private const val TAG = "LksCrashHandler"
        private const val CRASH_DIR = "pending_crashes"

        @Volatile
        private var isInstalled = false

        /**
         * Installs the global crash handler and flushes any pending offline crash reports.
         */
        fun install(context: Context) {
            if (isInstalled) return
            isInstalled = true

            val currentHandler = Thread.getDefaultUncaughtExceptionHandler()
            val crashHandler = LksCrashHandler(context.applicationContext, currentHandler)
            Thread.setDefaultUncaughtExceptionHandler(crashHandler)

            Log.i(TAG, "🛡️ LksCrashHandler installed as default UncaughtExceptionHandler")

            // Proactively upload any crash reports that were saved during previous runs
            uploadPendingCrashes(context.applicationContext)
        }

        /**
         * Background worker that searches for unsent crash dumps on disk and uploads them.
         */
        fun uploadPendingCrashes(context: Context) {
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                try {
                    val crashDir = File(context.filesDir, CRASH_DIR)
                    if (!crashDir.exists()) return@launch

                    val files = crashDir.listFiles { _, name -> name.endsWith(".json") } ?: return@launch
                    for (file in files) {
                        try {
                            val jsonStr = file.readText()
                            val json = JSONObject(jsonStr)

                            val crashId = json.optString("crashId", "crash_${System.currentTimeMillis()}")
                            val payload = mutableMapOf<String, Any?>()
                            val keys = json.keys()
                            while (keys.hasNext()) {
                                val k = keys.next()
                                if (k == "recentLogs") {
                                    val arr = json.optJSONArray(k) ?: JSONArray()
                                    val list = mutableListOf<String>()
                                    for (i in 0 until arr.length()) list.add(arr.optString(i))
                                    payload[k] = list
                                } else {
                                    payload[k] = json.get(k)
                                }
                            }

                            com.google.firebase.firestore.FirebaseFirestore.getInstance()
                                .collection("critical_crashes")
                                .document(crashId)
                                .set(payload)
                                .addOnSuccessListener {
                                    Log.i(TAG, "✅ Pending crash report uploaded: ${file.name}")
                                    file.delete()
                                }
                                .addOnFailureListener { e ->
                                    Log.w(TAG, "Pending crash upload failed (will retry next launch): ${e.message}")
                                }
                        } catch (e: Exception) {
                            Log.w(TAG, "Error parsing pending crash file ${file.name}: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "uploadPendingCrashes error: ${e.message}")
                }
            }
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            LksLogger.critical(TAG, "FATAL CRASH on thread [${thread.name}]: ${throwable.message}", throwable)

            // 1. Gather recent logs and system state
            val recentLogs = LksLogger.getRecentLogs(400)
            val deviceState = LksLogUploader.captureDeviceState(context)

            // 2. Persist to disk immediately (in case the process terminates abruptly)
            saveCrashToDisk(thread, throwable, deviceState, recentLogs)

            // 3. Attempt synchronous fast-upload (up to 2500ms timeout)
            runBlocking {
                withTimeoutOrNull(2500L) {
                    LksLogUploader.uploadCriticalCrash(context, throwable, thread, recentLogs)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception inside crash handler: ${e.message}", e)
        } finally {
            // Forward to original handler so Android OS can clean up the process properly
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun saveCrashToDisk(
        thread: Thread,
        throwable: Throwable,
        deviceState: Map<String, Any?>,
        recentLogs: List<String>
    ) {
        try {
            val crashDir = File(context.filesDir, CRASH_DIR).apply { mkdirs() }
            val file = File(crashDir, "crash_${System.currentTimeMillis()}.json")

            val json = JSONObject().apply {
                put("crashId", "crash_${System.currentTimeMillis()}")
                put("timestamp", System.currentTimeMillis())
                put("threadName", thread.name)
                put("exceptionClass", throwable.javaClass.name)
                put("exceptionMessage", throwable.message ?: "No message")
                put("stackTrace", Log.getStackTraceString(throwable))
                put("deviceState", JSONObject(deviceState))
                put("recentLogs", JSONArray(recentLogs))
            }

            file.writeText(json.toString())
            Log.i(TAG, "Saved crash report to disk: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save crash report to disk: ${e.message}")
        }
    }
}
