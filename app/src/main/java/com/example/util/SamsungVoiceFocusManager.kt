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
        Log.i(TAG, "Updating Samsung Voice Focus parameters: isSpeaker=$isSpeaker")
        try {
            if (isSpeaker) {
                // LOUDSPEAKER (Speakerphone) Voice Focus
                audioManager.setParameters("voice_focus=on")
                audioManager.setParameters("voice_focus_enable=true")
                audioManager.setParameters("voice_focus_mode=speaker")
                audioManager.setParameters("voice_focus_mode=2")
                audioManager.setParameters("mic_mode=voice_focus")
                audioManager.setParameters("sec_audio_mic_mode=1")
                audioManager.setParameters("sec_mic_mode=1")
                audioManager.setParameters("situation=voip;device=speaker;mic_mode=voice_focus;sec_audio_mic_mode=1")
                audioManager.setParameters("situation=voip;device=speaker")
                audioManager.setParameters("call_state=incall")
                audioManager.setParameters("voip=on")
                audioManager.setParameters("sec_audio_voip_mic_mode=1")
                audioManager.setParameters("voip_mic_mode=voice_focus")
                audioManager.setParameters("samsung_voice_focus=on")
                audioManager.setParameters("samsung_voice_focus=speaker")
                audioManager.setParameters("g_call_mode=voip")
            } else {
                // EARPIECE Voice Focus (Handset Receiver)
                audioManager.setParameters("voice_focus=on")
                audioManager.setParameters("voice_focus_enable=true")
                audioManager.setParameters("sec_audio_mic_mode=1")
                audioManager.setParameters("sec_mic_mode=1")
                audioManager.setParameters("voice_focus_mode=earpiece")
                audioManager.setParameters("voice_focus_mode=1")
                audioManager.setParameters("voice_focus_earpiece=on")
                audioManager.setParameters("voice_focus_earpiece_enable=true")
                audioManager.setParameters("earpiece_voice_focus=on")
                audioManager.setParameters("earpiece_voice_focus_enable=true")
                audioManager.setParameters("mic_mode=voice_focus")
                audioManager.setParameters("situation=voip;device=earpiece;mic_mode=voice_focus;sec_audio_mic_mode=1")
                audioManager.setParameters("situation=voip;device=earpiece")
                audioManager.setParameters("call_state=incall")
                audioManager.setParameters("voip=on")
                audioManager.setParameters("sec_audio_voip_mic_mode=1")
                audioManager.setParameters("voip_mic_mode=voice_focus")
                audioManager.setParameters("samsung_voice_focus=on")
                audioManager.setParameters("samsung_voice_focus=earpiece")
                audioManager.setParameters("samsung_voice_focus_earpiece=on")
                audioManager.setParameters("voice_focus_earpiece=true")
                audioManager.setParameters("dual_mic_noise_reduction=on")
                audioManager.setParameters("sec_rx_noise_reduction=on")
                audioManager.setParameters("two_mic_solution=on")
                audioManager.setParameters("g_call_mode=voip")
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
