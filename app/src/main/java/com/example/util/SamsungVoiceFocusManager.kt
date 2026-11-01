package com.example.util

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * SamsungVoiceFocusManager
 *
 * Configures Samsung One UI's native "Voice Focus" hardware AI noise-cancellation
 * and vendor AudioPolicy parameters via AudioManager.setParameters.
 * Never creates competing AudioRecord/AudioTrack instances so WebRTC retains 100% control
 * over hardware routing and loudspeaker streams.
 */
object SamsungVoiceFocusManager {

    private const val TAG = "SamsungVoiceFocus"

    @Volatile
    private var isRunning = false

    /**
     * Start the Voice Focus audio pipeline when a call begins or is answered.
     */
    @Synchronized
    fun start(isSpeaker: Boolean, context: Context) {
        isRunning = true
        Log.i(TAG, "Activating Samsung Voice Focus HAL parameters (isSpeaker=$isSpeaker)")
        updateRoute(isSpeaker, context)
    }

    /**
     * Dynamically update Samsung Voice Focus parameters when switching between Earpiece and Speakerphone (Loudspeaker).
     */
    fun updateRoute(isSpeaker: Boolean, context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        // Rock-solid OEM guard: Only apply Samsung proprietary Voice Focus parameters on Samsung hardware
        if (!android.os.Build.MANUFACTURER.contains("samsung", ignoreCase = true)) {
            return
        }
        Log.i(TAG, "Updating Samsung Voice Focus parameters: isSpeaker=$isSpeaker")
        try {
            if (isSpeaker) {
                // Single consolidated parameter write to eliminate Binder IPC latency on Samsung
                audioManager.setParameters("situation=voip;device=speaker;mic_mode=voice_focus;sec_audio_mic_mode=1;voice_focus=on;samsung_voice_focus=on;samsung_voice_focus=speaker;call_state=incall;voip=on;g_call_mode=voip")
            } else {
                audioManager.setParameters("situation=voip;device=earpiece;mic_mode=voice_focus;sec_audio_mic_mode=1;voice_focus=on;samsung_voice_focus=on;samsung_voice_focus=earpiece;dual_mic_noise_reduction=on;sec_rx_noise_reduction=on;two_mic_solution=on;call_state=incall;voip=on;g_call_mode=voip")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update Voice Focus route parameters: ${e.message}")
        }
    }

    /**
     * Stop Voice Focus when a call ends.
     */
    @Synchronized
    fun stop() {
        if (!isRunning) return
        Log.i(TAG, "Stopping Samsung Voice Focus")
        isRunning = false
    }
}
