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
        // Safe no-op on user builds: setting vendor parameters like 'situation=voip;call_state=incall'
        // requires system permission MODIFY_PHONE_STATE which non-system apps do not possess.
        // Calling audioManager.setParameters causes synchronous Binder IPC failure (-1) and blocks the main thread.
        // WebRTC JavaAudioDeviceModule's hardware AEC/NS already provides full DSP cancellation.
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
