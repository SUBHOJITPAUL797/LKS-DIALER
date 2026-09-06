package com.example.util

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * CallSoundEffectsManager
 * Provides professional telecom audio progress feedback:
 * 1. Outgoing Ringback Tone: classic supervisory "tuuut... tuuut..." played to caller while recipient rings.
 * 2. Call Ended / Disconnect Tone: 3 prompt beeps played when call drops or hangs up.
 * 3. Call Hold Tone: soft prompt tone when call is placed on hold + periodic reminder.
 * 4. Call Resume / Unhold Tone: rising confirmation chime when call is taken off hold.
 */
object CallSoundEffectsManager {

    private const val TAG = "CallSoundEffects"
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var ringbackToneGenerator: ToneGenerator? = null

    @Volatile
    private var isRingbackActive = false

    private var holdReminderRunnable: Runnable? = null

    /**
     * Start the outgoing supervisory ringback tone.
     * Played to the caller while the recipient device is CALLING / RINGING.
     * Uses STREAM_VOICE_CALL so it routes through earpiece, speaker, or bluetooth headset.
     */
    @Synchronized
    fun startRingbackTone(context: Context) {
        if (isRingbackActive) return
        isRingbackActive = true
        Log.i(TAG, "Starting outgoing ringback tone")

        try {
            stopRingbackTone()
            ringbackToneGenerator = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 85)
            ringbackToneGenerator?.startTone(ToneGenerator.TONE_SUP_RINGTONE)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start ToneGenerator ringback tone on STREAM_VOICE_CALL: ${e.message}")
            try {
                // Fallback: try with STREAM_RING or STREAM_MUSIC if STREAM_VOICE_CALL failed on device
                ringbackToneGenerator = ToneGenerator(AudioManager.STREAM_RING, 80)
                ringbackToneGenerator?.startTone(ToneGenerator.TONE_SUP_RINGTONE)
            } catch (e2: Exception) {
                Log.e(TAG, "Secondary ToneGenerator fallback also failed: ${e2.message}")
            }
        }
    }

    /**
     * Stop the outgoing ringback tone.
     * Called when the call is answered, declined, or ended.
     */
    @Synchronized
    fun stopRingbackTone() {
        if (!isRingbackActive && ringbackToneGenerator == null) return
        isRingbackActive = false
        Log.d(TAG, "Stopping outgoing ringback tone")
        try {
            ringbackToneGenerator?.stopTone()
            ringbackToneGenerator?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping ringback tone: ${e.message}")
        } finally {
            ringbackToneGenerator = null
        }
    }

    /**
     * Play the professional Call Ended / Hangup tone (3 crisp disconnect beeps).
     */
    fun playCallEndedTone(context: Context) {
        stopRingbackTone()
        stopHoldReminder()

        Thread {
            try {
                val toneGen = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 90)
                toneGen.startTone(ToneGenerator.TONE_PROP_PROMPT, 180)
                Thread.sleep(240)
                toneGen.startTone(ToneGenerator.TONE_PROP_PROMPT, 180)
                Thread.sleep(240)
                toneGen.startTone(ToneGenerator.TONE_PROP_PROMPT, 280)
                Thread.sleep(320)
                toneGen.release()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to play call ended tone: ${e.message}")
            }
        }.start()
    }

    /**
     * Play the Call Hold tone when call is placed on hold.
     * Also starts a gentle 8-second periodic reminder chime.
     */
    fun playHoldTone(context: Context) {
        stopRingbackTone()
        stopHoldReminder()

        // 1. Initial double-chime for hold
        Thread {
            try {
                val toneGen = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 85)
                toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 220)
                Thread.sleep(280)
                toneGen.release()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to play hold tone: ${e.message}")
            }
        }.start()

        // 2. Periodic gentle reminder chime every 8 seconds
        holdReminderRunnable = object : Runnable {
            override fun run() {
                try {
                    val toneGen = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 60)
                    toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
                    handler.postDelayed({
                        try { toneGen.release() } catch (_: Exception) {}
                    }, 200)
                } catch (_: Exception) {}
                handler.postDelayed(this, 8000L)
            }
        }
        handler.postDelayed(holdReminderRunnable!!, 8000L)
    }

    /**
     * Play the Call Resume / Unhold tone when call is taken off hold.
     */
    fun playUnholdTone(context: Context) {
        stopHoldReminder()

        Thread {
            try {
                val toneGen = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 85)
                toneGen.startTone(ToneGenerator.TONE_PROP_ACK, 250)
                Thread.sleep(300)
                toneGen.release()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to play unhold tone: ${e.message}")
            }
        }.start()
    }

    /**
     * Stop hold reminder ticker.
     */
    fun stopHoldReminder() {
        holdReminderRunnable?.let { handler.removeCallbacks(it) }
        holdReminderRunnable = null
    }

    /**
     * Reset all call sound effects (e.g. when app goes IDLE).
     */
    fun resetAll() {
        stopRingbackTone()
        stopHoldReminder()
    }
}
