package com.example.util

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.AudioAttributes
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.FileInputStream

/**
 * CallSoundEffectsManager
 * Provides professional telecom audio progress feedback:
 * 1. Outgoing Connecting Tone: Telecom connecting pulse ("tuk... tuk...") played while callStatus == CALLING.
 * 2. Outgoing Ringback Tone: Supervisory "tuuut... tuuut..." played while callStatus == RINGING (if no caller tune).
 * 3. Outgoing Caller Tune: Plays callee's custom Hello Tune immediately on RINGING with zero delay (pre-buffered).
 * 4. Call Ended / Disconnect Tone: 1 single crisp prompt beep played when call drops or hangs up.
 * 5. Call Hold Tone: Soft prompt double-chime when call is placed on hold + periodic reminder.
 * 6. Call Resume / Unhold Tone: Rising confirmation chime when call is taken off hold.
 */
object CallSoundEffectsManager {

    private const val TAG = "CallSoundEffects"
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var connectingToneGenerator: ToneGenerator? = null
    @Volatile
    private var isConnectingActive = false
    private var connectingRunnable: Runnable? = null

    @Volatile
    private var ringbackToneGenerator: ToneGenerator? = null
    @Volatile
    private var isRingbackActive = false
    private var ringbackRunnable: Runnable? = null

    @Volatile
    private var callerTunePlayer: MediaPlayer? = null
    @Volatile
    private var isCallerTunePrepared = false
    @Volatile
    private var isRingingState = false
    @Volatile
    private var isCallAnswered = false

    private data class PendingTune(
        val audioBase64: String?,
        val audioUrl: String?,
        val title: String?,
        val startMs: Long,
        val isBase64Playing: Boolean
    )

    @Volatile
    private var pendingTune: PendingTune? = null

    private var holdReminderRunnable: Runnable? = null

    /**
     * Start the outgoing connecting tone ("tuk... tuk...").
     * Played to the caller while the call is being routed/connected (callStatus == CALLING).
     * Cadence: double "tuk... tuk..." pulse every 1.4s, conveying active telecom connection.
     */
    @Synchronized
    fun startConnectingTone(context: Context) {
        if (isConnectingActive) return
        isCallAnswered = false
        stopConnectingTone()
        internalStopToneGenerator()

        isConnectingActive = true
        isRingingState = false
        Log.i(TAG, "Starting outgoing connecting tone (tuk... tuk...)")

        try {
            connectingToneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
        } catch (e: Exception) {
            Log.w(TAG, "Primary STREAM_MUSIC ToneGenerator error: ${e.message}")
            try {
                connectingToneGenerator = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80)
            } catch (e2: Exception) {
                try {
                    connectingToneGenerator = ToneGenerator(AudioManager.STREAM_RING, 75)
                } catch (e3: Exception) {
                    Log.e(TAG, "All ToneGenerator fallbacks failed: ${e3.message}")
                }
            }
        }

        connectingRunnable = object : Runnable {
            override fun run() {
                if (!isConnectingActive) return
                try {
                    // Pulse 1: "tuk" (65ms)
                    connectingToneGenerator?.startTone(ToneGenerator.TONE_PROP_PROMPT, 65)

                    // Pulse 2: "tuk" (65ms) after 160ms
                    handler.postDelayed({
                        if (isConnectingActive) {
                            try {
                                connectingToneGenerator?.startTone(ToneGenerator.TONE_PROP_PROMPT, 65)
                            } catch (_: Exception) {}
                        }
                    }, 160L)
                } catch (e: Exception) {
                    Log.w(TAG, "Connecting pulse error: ${e.message}")
                }

                // Repeat cadence every 1400ms: "tuk... tuk...      tuk... tuk..."
                if (isConnectingActive) {
                    handler.postDelayed(this, 1400L)
                }
            }
        }

        connectingRunnable?.let { handler.post(it) }
    }

    @Synchronized
    fun stopConnectingTone() {
        if (!isConnectingActive && connectingToneGenerator == null) return
        isConnectingActive = false
        Log.d(TAG, "Stopping outgoing connecting tone")
        connectingRunnable?.let { handler.removeCallbacks(it) }
        connectingRunnable = null
        try {
            connectingToneGenerator?.stopTone()
            connectingToneGenerator?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping connecting tone: ${e.message}")
        } finally {
            connectingToneGenerator = null
        }
    }

    /**
     * Prepares (preloads) the callee's Caller Tune in the background while CALLING.
     * When call transitions to RINGING, playback starts INSTANTLY with 0ms delay.
     */
    @Synchronized
    fun prepareCallerTune(
        context: Context,
        audioBase64: String?,
        audioUrl: String? = null,
        title: String? = null,
        startMs: Long = 0L
    ) {
        if (audioBase64.isNullOrBlank() && audioUrl.isNullOrBlank()) {
            pendingTune = null
            return
        }

        // Avoid recreating if already preparing the same tune
        if (pendingTune != null && pendingTune?.title == title && callerTunePlayer != null) {
            return
        }

        stopCallerTune()
        isCallerTunePrepared = false

        try {
            val player = MediaPlayer()
            callerTunePlayer = player

            val httpHeaders = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36")
            var isBase64Playing = false

            if (!audioBase64.isNullOrBlank()) {
                val tempFile = LksCallerTuneManager.base64ToTempFile(context, audioBase64, "caller_playback")
                if (tempFile != null && LksCallerTuneManager.isValidAudioFile(tempFile)) {
                    val fis = FileInputStream(tempFile)
                    try {
                        player.setDataSource(fis.fd, 0, tempFile.length())
                        isBase64Playing = true
                    } finally {
                        fis.close()
                    }
                } else {
                    try { tempFile?.delete() } catch (_: Exception) {}
                    if (!audioUrl.isNullOrBlank() && !audioUrl.contains("youtube.com") && !audioUrl.contains("youtu.be")) {
                        Log.w(TAG, "Base64 caller tune missing or corrupted; falling back to URL: $audioUrl")
                        player.setDataSource(context, android.net.Uri.parse(audioUrl), httpHeaders)
                    } else {
                        callerTunePlayer = null
                        try { player.release() } catch (_: Exception) {}
                        return
                    }
                }
            } else if (!audioUrl.isNullOrBlank() && !audioUrl.contains("youtube.com") && !audioUrl.contains("youtu.be")) {
                player.setDataSource(context, android.net.Uri.parse(audioUrl), httpHeaders)
            } else {
                callerTunePlayer = null
                try { player.release() } catch (_: Exception) {}
                return
            }

            pendingTune = PendingTune(audioBase64, audioUrl, title, startMs, isBase64Playing)

            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setLegacyStreamType(AudioManager.STREAM_VOICE_CALL)
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            player.isLooping = true

            player.setOnPreparedListener { mp ->
                synchronized(this) {
                    if (callerTunePlayer == mp) {
                        isCallerTunePrepared = true
                        Log.i(TAG, "🎵 Caller Tune prepared in memory: $title (isRingingState=$isRingingState)")
                        if (isRingingState) {
                            startPreparedCallerTune(mp, context)
                        }
                    } else {
                        try { mp.release() } catch (_: Exception) {}
                    }
                }
            }

            player.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "Caller tune prepare error ($what, $extra) [isBase64=$isBase64Playing]")
                val wasRinging = isRingingState
                stopCallerTune()
                if (isBase64Playing && !audioUrl.isNullOrBlank() && !audioUrl.contains("youtube.com") && !audioUrl.contains("youtu.be")) {
                    Log.i(TAG, "Retrying caller tune playback directly via stream URL fallback: $audioUrl")
                    isRingingState = wasRinging
                    prepareCallerTune(context, null, audioUrl, title, startMs)
                } else if (wasRinging) {
                    startRingbackTone(context)
                }
                true
            }

            player.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare caller tune: ${e.message}", e)
            stopCallerTune()
        }
    }

    private fun startPreparedCallerTune(mp: MediaPlayer, context: Context) {
        try {
            stopConnectingTone()
            internalStopToneGenerator()

            val info = pendingTune
            if (info != null && !info.isBase64Playing && info.startMs > 0) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    mp.seekTo(info.startMs, MediaPlayer.SEEK_CLOSEST)
                } else {
                    mp.seekTo(info.startMs.toInt())
                }
            }
            mp.start()
            isRingbackActive = true
            Log.i(TAG, "🎵 Outgoing Caller Tune playing INSTANTLY: ${info?.title ?: "Song"} (startMs=${info?.startMs ?: 0})")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start prepared caller tune: ${e.message}")
            startRingbackTone(context)
        }
    }

    /**
     * Start Caller Tune immediately (or fallback to prepare).
     */
    @Synchronized
    fun startCallerTune(
        context: Context,
        audioBase64: String?,
        audioUrl: String? = null,
        title: String? = null,
        startMs: Long = 0L
    ) {
        if (isCallAnswered) return
        isRingingState = true
        stopConnectingTone()

        val existingPlayer = callerTunePlayer
        if (existingPlayer != null && isCallerTunePrepared && pendingTune?.title == title) {
            startPreparedCallerTune(existingPlayer, context)
            return
        }

        prepareCallerTune(context, audioBase64, audioUrl, title, startMs)
        val player = callerTunePlayer
        if (player != null && isCallerTunePrepared) {
            startPreparedCallerTune(player, context)
        }
    }

    /**
     * Called when outgoing call transitions to RINGING.
     * Stops "tuk... tuk..." connecting tone.
     * If caller tune is prepared, starts it with 0ms delay.
     * If caller tune is currently buffering, waits for onPrepared (NO false "ring ring").
     * If no caller tune exists for callee, plays standard ringback tone ("ring ring").
     */
    @Synchronized
    fun onCallRinging(context: Context) {
        if (isCallAnswered) return
        Log.i(TAG, "Call transitioned to RINGING")
        isRingingState = true
        stopConnectingTone()

        val player = callerTunePlayer
        if (player != null && isCallerTunePrepared) {
            startPreparedCallerTune(player, context)
            return
        }

        if (pendingTune != null && player != null) {
            Log.i(TAG, "Caller Tune is buffering in background; setting 3.5s timeout fallback to ringback tone")
            handler.postDelayed({
                synchronized(this) {
                    if (isRingingState && !isCallAnswered && !isCallerTunePrepared && !isRingbackActive) {
                        Log.i(TAG, "Caller Tune buffer timeout reached, falling back to standard ringback tone")
                        startRingbackTone(context)
                    }
                }
            }, 3500L)
            return
        }

        startRingbackTone(context)
    }

    /**
     * Start the outgoing supervisory ringback tone ("tuuut... tuuut... / ring ring").
     * Played to the caller while the recipient is ringing.
     * Uses STREAM_MUSIC for clear speaker/emulator playback with STREAM_VOICE_CALL fallback.
     */
    @Synchronized
    fun startRingbackTone(context: Context) {
        if (isCallAnswered) return
        if (isRingbackActive && ringbackToneGenerator != null) return
        stopConnectingTone()
        stopCallerTune()
        internalStopToneGenerator()

        isRingbackActive = true
        Log.i(TAG, "Starting outgoing supervisory ringback tone cadence (ring ring)")

        try {
            ringbackToneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 85)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start ToneGenerator on STREAM_MUSIC: ${e.message}")
            try {
                ringbackToneGenerator = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 85)
            } catch (e2: Exception) {
                try {
                    ringbackToneGenerator = ToneGenerator(AudioManager.STREAM_RING, 80)
                } catch (e3: Exception) {
                    Log.e(TAG, "All ringback ToneGenerators failed: ${e3.message}")
                }
            }
        }

        ringbackRunnable = object : Runnable {
            override fun run() {
                if (!isRingbackActive || isCallAnswered) return
                try {
                    // Play 1.6 second standard supervisory ring tone
                    ringbackToneGenerator?.startTone(ToneGenerator.TONE_SUP_RINGTONE, 1600)
                } catch (e: Exception) {
                    Log.w(TAG, "Ringback tone start error: ${e.message}")
                }

                // Standard telecom supervisory cycle: 1.6s ring + 2.2s silence = 3.8s cadence
                if (isRingbackActive && !isCallAnswered) {
                    handler.postDelayed(this, 3800L)
                }
            }
        }
        ringbackRunnable?.let { handler.post(it) }
    }

    @Synchronized
    fun stopCallerTune() {
        try {
            callerTunePlayer?.let { player ->
                if (player.isPlaying) {
                    try { player.stop() } catch (_: Exception) {}
                }
                try { player.release() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing caller tune player: ${e.message}")
        } finally {
            callerTunePlayer = null
            isCallerTunePrepared = false
            pendingTune = null
        }
    }

    /**
     * Stop all outgoing progress tones (connecting, ringback, caller tune).
     * Called when the call is answered, declined, or ended.
     */
    @Synchronized
    fun stopRingbackTone() {
        isCallAnswered = true
        stopConnectingTone()
        stopCallerTune()
        internalStopToneGenerator()
        isRingingState = false
    }

    private fun internalStopToneGenerator() {
        isRingbackActive = false
        ringbackRunnable?.let { handler.removeCallbacks(it) }
        ringbackRunnable = null
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
     * Play the professional Call Ended / Hangup tone (1 single crisp disconnect beep).
     */
    /**
     * Play standard telecom busy tone ("tu-tu-tu...") when the callee is on another call.
     */
    fun playBusyTone(context: Context) {
        stopConnectingTone()
        stopRingbackTone()
        stopHoldReminder()

        Thread {
            try {
                val toneGen = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 90)
                toneGen.startTone(ToneGenerator.TONE_SUP_BUSY, 1200)
                Thread.sleep(1300)
                toneGen.release()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to play busy tone: ${e.message}")
            }
        }.start()
    }

    /**
     * Play a gentle in-ear call waiting pip when a second call arrives while already in a call.
     * Does not interrupt ongoing speech or play through loud speaker.
     */
    fun playCallWaitingBeep(context: Context) {
        Thread {
            try {
                val toneGen = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70)
                toneGen.startTone(ToneGenerator.TONE_SUP_CALL_WAITING, 250)
                Thread.sleep(300)
                toneGen.release()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to play call waiting beep: ${e.message}")
            }
        }.start()
    }

    fun playCallEndedTone(context: Context) {
        stopRingbackTone()
        stopHoldReminder()

        Thread {
            try {
                val toneGen = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 90)
                toneGen.startTone(ToneGenerator.TONE_PROP_PROMPT, 220)
                Thread.sleep(260)
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
        holdReminderRunnable?.let { handler.postDelayed(it, 8000L) }
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
        isCallAnswered = false
        stopConnectingTone()
        stopRingbackTone()
        stopHoldReminder()
    }
}
