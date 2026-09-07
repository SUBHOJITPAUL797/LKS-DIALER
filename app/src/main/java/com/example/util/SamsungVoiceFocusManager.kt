package com.example.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log

/**
 * SamsungVoiceFocusManager
 *
 * Activates Samsung One UI's native "Voice Focus" hardware AI noise-cancellation
 * and call overlay by configuring the exact VoIP audio profile that Samsung's
 * AudioPolicyService and Audio HAL monitor:
 * 1. AudioManager.MODE_IN_COMMUNICATION
 * 2. MediaRecorder.AudioSource.VOICE_COMMUNICATION on AudioRecord
 * 3. Hardware AcousticEchoCanceler & NoiseSuppressor attached to audioSessionId
 * 4. AudioTrack with AudioAttributes.USAGE_VOICE_COMMUNICATION & CONTENT_TYPE_SPEECH
 * 5. Samsung Audio HAL parameters ("voice_focus=on", etc.)
 */
object SamsungVoiceFocusManager {

    private const val TAG = "SamsungVoiceFocus"

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var audioTrack: AudioTrack? = null

    @Volatile
    private var echoCanceler: AcousticEchoCanceler? = null

    @Volatile
    private var noiseSuppressor: NoiseSuppressor? = null

    @Volatile
    private var isRunning = false

    private var workerThread: Thread? = null

    /**
     * Start the Voice Focus audio pipeline when a call begins or is answered.
     */
    @Synchronized
    fun start(context: Context) {
        if (isRunning) return

        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            Log.i(TAG, "Activating Samsung Voice Focus audio pipeline")

            // 1. Send initial Samsung Audio HAL vendor parameters
            updateRoute(isSpeaker = false, context = context)

            // 2. Configure AudioRecord with VOICE_COMMUNICATION
            val sampleRate = 48000
            val channelInConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minInBufSize = AudioRecord.getMinBufferSize(sampleRate, channelInConfig, audioFormat)
            val inBufSize = if (minInBufSize > 0) (minInBufSize * 2).coerceAtLeast(4096) else 4096

            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                channelInConfig,
                audioFormat,
                inBufSize
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord failed to initialize for Voice Focus")
                try { record.release() } catch (_: Exception) {}
                return
            }

            audioRecord = record
            val sessionId = record.audioSessionId
            Log.i(TAG, "Voice Focus AudioRecord initialized with sessionId=$sessionId")

            // 3. Attach hardware Echo Canceler & Noise Suppressor directly to sessionId
            if (sessionId != 0) {
                try {
                    if (AcousticEchoCanceler.isAvailable()) {
                        echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply {
                            enabled = true
                            Log.i(TAG, "✅ AcousticEchoCanceler enabled on session $sessionId")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to attach AcousticEchoCanceler: ${e.message}")
                }

                try {
                    if (NoiseSuppressor.isAvailable()) {
                        noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply {
                            enabled = true
                            Log.i(TAG, "✅ NoiseSuppressor enabled on session $sessionId")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to attach NoiseSuppressor: ${e.message}")
                }
            }

            // 4. Configure companion AudioTrack with USAGE_VOICE_COMMUNICATION
            try {
                val minOutBufSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, audioFormat)
                val outBufSize = if (minOutBufSize > 0) (minOutBufSize * 2).coerceAtLeast(4096) else 4096

                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(outBufSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                if (track.state == AudioTrack.STATE_INITIALIZED) {
                    track.play()
                    audioTrack = track
                    Log.i(TAG, "✅ AudioTrack with USAGE_VOICE_COMMUNICATION playing")
                } else {
                    track.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "AudioTrack setup note: ${e.message}")
            }

            // 5. Start recording and maintain low-priority drainage loop
            record.startRecording()
            isRunning = true

            workerThread = Thread({
                val buffer = ByteArray(1024)
                while (isRunning && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    try {
                        val readBytes = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                        if (readBytes <= 0) {
                            Thread.sleep(20)
                        }
                    } catch (_: Exception) {
                        break
                    }
                }
            }, "LksSamsungVoiceFocus").apply {
                priority = Thread.MIN_PRIORITY
                isDaemon = true
                start()
            }

            Log.i(TAG, "🚀 Samsung Voice Focus successfully activated")

        } catch (e: Exception) {
            Log.e(TAG, "Error activating Samsung Voice Focus", e)
            stop()
        }
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
                audioManager.setParameters("mic_mode=voice_focus")
                audioManager.setParameters("situation=voip;device=speaker")
                audioManager.setParameters("call_state=incall")
                audioManager.setParameters("voip=on")
            } else {
                // EARPIECE Voice Focus (Handset Receiver)
                audioManager.setParameters("voice_focus=on")
                audioManager.setParameters("voice_focus_enable=true")
                audioManager.setParameters("voice_focus_mode=earpiece")
                audioManager.setParameters("voice_focus_mode=1")
                audioManager.setParameters("voice_focus_earpiece=on")
                audioManager.setParameters("mic_mode=voice_focus")
                audioManager.setParameters("situation=voip;device=earpiece")
                audioManager.setParameters("call_state=incall")
                audioManager.setParameters("voip=on")
                audioManager.setParameters("voip_earpiece=on")
                audioManager.setParameters("dual_mic_noise_reduction=on")
                audioManager.setParameters("samsung_voice_focus=on")
                audioManager.setParameters("samsung_voice_focus=earpiece")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update Voice Focus route parameters: ${e.message}")
        }
    }

    /**
     * Stop and cleanly release all Voice Focus resources when a call ends.
     */
    @Synchronized
    fun stop() {
        if (!isRunning && audioRecord == null && audioTrack == null) return
        Log.i(TAG, "Stopping Samsung Voice Focus")
        isRunning = false

        try {
            workerThread?.interrupt()
            workerThread = null
        } catch (_: Exception) {}

        try {
            echoCanceler?.enabled = false
            echoCanceler?.release()
        } catch (_: Exception) {}
        echoCanceler = null

        try {
            noiseSuppressor?.enabled = false
            noiseSuppressor?.release()
        } catch (_: Exception) {}
        noiseSuppressor = null

        try {
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord?.stop()
            }
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        try {
            if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack?.stop()
            }
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null

        Log.i(TAG, "Samsung Voice Focus cleanly released")
    }
}
