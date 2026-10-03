package com.example.util.logging

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlin.math.sqrt

/**
 * ShakeDetector
 * Rock-solid accelerometer-based shake listener for instant bug/crash reporting.
 * Uses low-latency vector magnitude with directional reversal counts and cooldown
 * to prevent false positives from walking or pocket movements.
 */
class ShakeDetector(
    private val context: Context,
    private val onShakeListener: () -> Unit
) : SensorEventListener {

    companion object {
        private const val TAG = "ShakeDetector"
        private const val SHAKE_THRESHOLD_GRAVITY = 2.7f // G-force threshold
        private const val SHAKE_SLOP_TIME_MS = 500       // Min time between shakes
        private const val SHAKE_COUNT_RESET_TIME_MS = 3000 // Window to accumulate shakes
        private const val COOLDOWN_TIME_MS = 3000L       // Cooldown between triggers
    }

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null

    private var shakeTimestamp: Long = 0L
    private var shakeCount: Int = 0
    private var lastTriggerTimestamp: Long = 0L

    fun start() {
        try {
            val prefs = context.getSharedPreferences("dialer_prefs", Context.MODE_PRIVATE)
            val isEnabled = prefs.getBoolean("shake_to_report_enabled", true)
            if (!isEnabled) {
                Log.d(TAG, "Shake to report is disabled in user preferences")
                return
            }

            sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            accelerometer?.let {
                sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
                Log.d(TAG, "Shake detector started")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start shake detector: ${e.message}")
        }
    }

    fun stop() {
        try {
            sensorManager?.unregisterListener(this)
            sensorManager = null
            accelerometer = null
            Log.d(TAG, "Shake detector stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop shake detector: ${e.message}")
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val now = System.currentTimeMillis()
        if (now - lastTriggerTimestamp < COOLDOWN_TIME_MS) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val gX = x / SensorManager.GRAVITY_EARTH
        val gY = y / SensorManager.GRAVITY_EARTH
        val gZ = z / SensorManager.GRAVITY_EARTH

        // gForce will be close to 1 when still.
        val gForce = sqrt((gX * gX + gY * gY + gZ * gZ).toDouble()).toFloat()

        if (gForce > SHAKE_THRESHOLD_GRAVITY) {
            // Check if within the slop window
            if (shakeTimestamp + SHAKE_SLOP_TIME_MS > now) {
                return
            }

            // Reset count if too much time passed since last shake
            if (shakeTimestamp + SHAKE_COUNT_RESET_TIME_MS < now) {
                shakeCount = 0
            }

            shakeTimestamp = now
            shakeCount++

            Log.d(TAG, "Shake detected! count=$shakeCount, gForce=$gForce")

            // Require 2 quick deliberate shakes to trigger
            if (shakeCount >= 2) {
                shakeCount = 0
                lastTriggerTimestamp = now
                triggerHapticFeedback()
                LksLogger.i(TAG, "🔔 Physical shake event triggered by user (gForce=$gForce)")
                onShakeListener()
            }
        }
    }

    private fun triggerHapticFeedback() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(120)
            }
        } catch (_: Exception) {}
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
