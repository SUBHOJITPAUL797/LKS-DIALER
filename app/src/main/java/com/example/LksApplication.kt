package com.example

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.example.util.logging.LksCrashHandler
import com.example.util.logging.LksLogger

/**
 * LksApplication
 * Application entry point for LKS Dialer.
 * Initializes logging, crash reporting, and activity lifecycle tracking
 * before any activity or background service runs.
 */
class LksApplication : Application() {

    companion object {
        @Volatile
        var currentActivity: Activity? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()

        // 1. Initialize App-Wide Real-Time Logger & Logcat Streamer
        LksLogger.init(this)

        // 2. Install Bulletproof Uncaught Crash Handler
        LksCrashHandler.install(this)

        // 3. Track Activity Lifecycles for Breadcrumbs & UI Dialog Anchor
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                LksLogger.breadcrumb("LIFECYCLE", "${activity.localClassName} CREATED")
            }

            override fun onActivityStarted(activity: Activity) {
                currentActivity = activity
                LksLogger.breadcrumb("LIFECYCLE", "${activity.localClassName} STARTED")
            }

            override fun onActivityResumed(activity: Activity) {
                currentActivity = activity
                LksLogger.breadcrumb("LIFECYCLE", "${activity.localClassName} RESUMED")
            }

            override fun onActivityPaused(activity: Activity) {
                if (currentActivity == activity) {
                    currentActivity = null
                }
                LksLogger.breadcrumb("LIFECYCLE", "${activity.localClassName} PAUSED")
            }

            override fun onActivityStopped(activity: Activity) {
                LksLogger.breadcrumb("LIFECYCLE", "${activity.localClassName} STOPPED")
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

            override fun onActivityDestroyed(activity: Activity) {
                LksLogger.breadcrumb("LIFECYCLE", "${activity.localClassName} DESTROYED")
            }
        })
    }
}
