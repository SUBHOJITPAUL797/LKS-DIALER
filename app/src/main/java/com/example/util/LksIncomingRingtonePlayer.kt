package com.example.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * LksIncomingRingtonePlayer
 * Bulletproof, non-stopping ringtone and vibration manager.
 * 
 * 1. Looping MediaPlayer with AssetFileDescriptor fallback.
 * 2. Active Loop Monitor (ticks every 800ms) that detects if a 3-second system sound finished
 *    and immediately restarts playback, guaranteeing the ringtone NEVER stops after 3 seconds.
 * 3. Continuous hardware ToneGenerator fallback if audio files cannot be decoded.
 * 4. Requests exclusive AUDIOFOCUS_GAIN_TRANSIENT so system chimes cannot silence the ringtone.
 * 5. Partial WakeLock keeps CPU running continuously through lockscreen deep-sleep.
 */
object LksIncomingRingtonePlayer {
    private const val TAG = "LksRingtonePlayer"

    private var appContext: Context? = null
    private var mediaPlayer: MediaPlayer? = null
    private var ringtone: Ringtone? = null
    private var toneGenerator: ToneGenerator? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var isRinging: Boolean = false
        private set

    /**
     * Active Loop Monitor:
     * System ringtones played via RingtoneManager often play once for 3-4 seconds and stop because
     * Android's IRingtonePlayer IPC ignores isLooping for 3rd-party apps.
     * This monitor checks every 800ms: if the ringtone stopped playing while isRinging is true,
     * it immediately restarts it, ensuring an unbroken, continuous ring until answered.
     */
    private val loopMonitorRunnable = object : Runnable {
        override fun run() {
            if (!isRinging) return
            try {
                if (mediaPlayer != null) {
                    if (!mediaPlayer!!.isPlaying) {
                        Log.d(TAG, "MediaPlayer paused or finished loop cycle, restarting...")
                        mediaPlayer!!.start()
                    }
                } else if (ringtone != null) {
                    if (!ringtone!!.isPlaying) {
                        Log.d(TAG, "Ringtone completed 3-second cycle, replaying loop...")
                        ringtone!!.play()
                    }
                } else if (toneGenerator == null) {
                    Log.d(TAG, "No active audio player found during ring, activating ToneGenerator fallback...")
                    startToneGeneratorFallback()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Loop monitor error: ${e.message}")
            }
            if (isRinging) {
                mainHandler.postDelayed(this, 800L)
            }
        }
    }

    /**
     * Start playing incoming call ringtone and vibration.
     * Thread-safe and idempotent.
     */
    @Synchronized
    fun start(context: Context, callerNumber: String) {
        if (isRinging) {
            Log.d(TAG, "Already ringing actively, ensuring loop monitor is running")
            mainHandler.removeCallbacks(loopMonitorRunnable)
            mainHandler.postDelayed(loopMonitorRunnable, 800L)
            return
        }

        isRinging = true
        Log.i(TAG, "🔔 STARTING BULLETPROOF RINGTONE for caller: $callerNumber")

        val appCtx = context.applicationContext
        appContext = appCtx

        // 1. Acquire partial wake lock to keep CPU awake while ringing on locked screen
        try {
            val pm = appCtx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (wakeLock?.isHeld != true) {
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lksdialer:ringtone_wake")?.apply {
                    acquire(60_000L) // 60s safety timeout
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire ringtone wake lock: ${e.message}")
        }

        // 2. Set AudioManager mode to MODE_RINGTONE
        val audioManager = appCtx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val ringerMode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL

        try {
            audioManager?.mode = AudioManager.MODE_RINGTONE
            audioManager?.isSpeakerphoneOn = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set MODE_RINGTONE: ${e.message}")
        }

        // Start Vibration (if not in silent mode)
        if (ringerMode != AudioManager.RINGER_MODE_SILENT) {
            startVibration(appCtx)
        }

        // If phone is in explicit Silent or Vibrate mode, do not play audio
        if (ringerMode == AudioManager.RINGER_MODE_SILENT || ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
            Log.d(TAG, "Device is in Silent/Vibrate mode ($ringerMode), vibration only")
            return
        }

        // Request Audio Focus on STREAM_RING (AUDIOFOCUS_GAIN_TRANSIENT so nothing ducks us)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setLegacyStreamType(AudioManager.STREAM_RING)
                            .build()
                    )
                    .setOnAudioFocusChangeListener { focusChange ->
                        Log.d(TAG, "Ringtone AudioFocus changed: $focusChange")
                        if (focusChange == AudioManager.AUDIOFOCUS_GAIN && isRinging) {
                            try {
                                if (mediaPlayer?.isPlaying == false) mediaPlayer?.start()
                                if (ringtone?.isPlaying == false) ringtone?.play()
                            } catch (_: Exception) {}
                        }
                    }
                    .build()
                audioFocusRequest = focusReq
                audioManager?.requestAudioFocus(focusReq)
            } else {
                @Suppress("DEPRECATION")
                audioManager?.requestAudioFocus(null, AudioManager.STREAM_RING, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio focus request failed: ${e.message}")
        }

        // 3. Resolve ringtone URI
        val resolvedUri: Uri = try {
            LksRingtoneManager.getRingtoneForIncomingCall(appCtx, callerNumber)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve ringtone via LksRingtoneManager: ${e.message}")
            RingtoneManager.getActualDefaultRingtoneUri(appCtx, RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        }

        Log.d(TAG, "Resolved Ringtone URI: $resolvedUri")

        // 4. PRIMARY ENGINE: Looping MediaPlayer with AssetFileDescriptor fallback
        var playedViaMediaPlayer = false
        try {
            val player = MediaPlayer()
            var dataSourceSet = false

            if (resolvedUri.scheme == "file" && resolvedUri.path != null) {
                val file = java.io.File(resolvedUri.path!!)
                if (file.exists() && file.length() > 0) {
                    player.setDataSource(file.absolutePath)
                    dataSourceSet = true
                }
            }

            if (!dataSourceSet) {
                // Try opening AssetFileDescriptor for content:// media/settings URIs
                try {
                    appCtx.contentResolver.openAssetFileDescriptor(resolvedUri, "r")?.use { afd ->
                        player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                        dataSourceSet = true
                    }
                } catch (_: Exception) {}
            }

            if (!dataSourceSet) {
                player.setDataSource(appCtx, resolvedUri)
            }

            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setLegacyStreamType(AudioManager.STREAM_RING)
                    .build()
            )
            player.isLooping = true
            player.setVolume(1.0f, 1.0f)
            player.setOnCompletionListener {
                try {
                    if (isRinging) it.start()
                } catch (_: Exception) {}
            }
            player.setOnErrorListener { mp, what, extra ->
                Log.w(TAG, "MediaPlayer error ($what, $extra), switching to fallback")
                try { mp.release() } catch (_: Exception) {}
                mediaPlayer = null
                playFallbackRingtone(appCtx, resolvedUri)
                true
            }
            player.prepare()
            player.start()
            mediaPlayer = player
            playedViaMediaPlayer = true
            Log.i(TAG, "✅ Ringtone PLAYING via MediaPlayer: $resolvedUri")
        } catch (e: Exception) {
            Log.w(TAG, "MediaPlayer playback failed (${e.message}), attempting RingtoneManager fallback...")
            try { mediaPlayer?.release() } catch (_: Exception) {}
            mediaPlayer = null
        }

        // 5. FALLBACK ENGINE: RingtoneManager
        if (!playedViaMediaPlayer) {
            playFallbackRingtone(appCtx, resolvedUri)
        }

        // 6. Start Loop Monitor to guarantee playback never cuts out
        mainHandler.removeCallbacks(loopMonitorRunnable)
        mainHandler.postDelayed(loopMonitorRunnable, 800L)
    }

    private fun playFallbackRingtone(appCtx: Context, uri: Uri) {
        try {
            var r = RingtoneManager.getRingtone(appCtx, uri)
            if (r == null) {
                val fallbackUri = RingtoneManager.getActualDefaultRingtoneUri(appCtx, RingtoneManager.TYPE_RINGTONE)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                r = RingtoneManager.getRingtone(appCtx, fallbackUri)
            }

            if (r != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    r.audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setLegacyStreamType(AudioManager.STREAM_RING)
                        .build()
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    r.isLooping = true
                }
                r.play()
                ringtone = r
                Log.i(TAG, "✅ Ringtone PLAYING via IRingtonePlayer fallback")
            } else {
                startToneGeneratorFallback()
            }
        } catch (e: Exception) {
            Log.e(TAG, "RingtoneManager fallback also failed: ${e.message}, using ToneGenerator")
            startToneGeneratorFallback()
        }
    }

    private fun startToneGeneratorFallback() {
        try {
            if (toneGenerator == null) {
                toneGenerator = ToneGenerator(AudioManager.STREAM_RING, 100)
                toneGenerator?.startTone(ToneGenerator.TONE_SUP_RINGTONE)
                Log.i(TAG, "✅ Continuous hardware ToneGenerator ringing active")
            }
        } catch (e: Exception) {
            Log.w(TAG, "ToneGenerator fallback failed: ${e.message}")
        }
    }

    /**
     * Stop ringtone and vibration immediately.
     */
    @Synchronized
    fun stop() {
        if (!isRinging && mediaPlayer == null && ringtone == null && toneGenerator == null && vibrator == null) {
            return
        }

        isRinging = false
        Log.i(TAG, "⏹️ STOPPING RINGTONE and vibration")
        mainHandler.removeCallbacks(loopMonitorRunnable)

        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null

        try {
            ringtone?.stop()
        } catch (_: Exception) {}
        ringtone = null

        try {
            toneGenerator?.stopTone()
            toneGenerator?.release()
        } catch (_: Exception) {}
        toneGenerator = null

        try {
            vibrator?.cancel()
        } catch (_: Exception) {}
        vibrator = null

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        wakeLock = null

        // Abandon audio focus & restore audio mode
        try {
            val am = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
                am?.abandonAudioFocusRequest(audioFocusRequest!!)
            }
            if (am?.mode == AudioManager.MODE_RINGTONE) {
                am.mode = AudioManager.MODE_NORMAL
            }
        } catch (_: Exception) {}
        audioFocusRequest = null
    }

    /**
     * Silence ringtone (e.g. user pressed volume key) while keeping call active.
     */
    @Synchronized
    fun silence() {
        isRinging = false
        Log.i(TAG, "🔇 SILENCING RINGTONE")
        mainHandler.removeCallbacks(loopMonitorRunnable)

        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null

        try {
            ringtone?.stop()
        } catch (_: Exception) {}
        ringtone = null

        try {
            toneGenerator?.stopTone()
            toneGenerator?.release()
        } catch (_: Exception) {}
        toneGenerator = null

        try {
            vibrator?.cancel()
        } catch (_: Exception) {}
        vibrator = null

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        wakeLock = null

        try {
            val am = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
                am?.abandonAudioFocusRequest(audioFocusRequest!!)
            }
            if (am?.mode == AudioManager.MODE_RINGTONE) {
                am.mode = AudioManager.MODE_NORMAL
            }
        } catch (_: Exception) {}
        audioFocusRequest = null
    }

    private fun startVibration(context: Context) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am?.ringerMode == AudioManager.RINGER_MODE_SILENT) {
                Log.d(TAG, "Device is in Silent mode, skipping vibration")
                return
            }

            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            val pattern = longArrayOf(0, 1000, 1000) // 1s vibrate, 1s pause
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0)) // 0 = repeat
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibration start failed: ${e.message}")
        }
    }
}
