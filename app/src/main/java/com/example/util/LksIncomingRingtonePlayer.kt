package com.example.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * LksIncomingRingtonePlayer
 * Industry-grade, bulletproof ringtone and vibration manager.
 * 
 * 1. Resolves ACTUAL media URI (not virtual settings URI) for seamless MediaPlayer playback.
 * 2. Requests transient AudioFocus on STREAM_RING / USAGE_NOTIFICATION_RINGTONE.
 * 3. CPU Partial WakeLock ensures playback continuous across locked / deep sleep states.
 * 4. Dual-Engine: Looping MediaPlayer (primary) + RingtoneManager (fallback).
 * 5. Full volume output and automatic loop continuation.
 */
object LksIncomingRingtonePlayer {
    private const val TAG = "LksRingtonePlayer"

    private var appContext: Context? = null
    private var mediaPlayer: MediaPlayer? = null
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    @Volatile
    var isRinging: Boolean = false
        private set

    /**
     * Start playing incoming call ringtone and vibration.
     * Thread-safe and idempotent.
     */
    @Synchronized
    fun start(context: Context, callerNumber: String) {
        if (isRinging && (mediaPlayer?.isPlaying == true || ringtone?.isPlaying == true)) {
            Log.d(TAG, "Already ringing actively, skipping duplicate start")
            return
        }

        isRinging = true
        Log.i(TAG, "🔔 STARTING RINGTONE for caller: $callerNumber")

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

        // 2. Check AudioManager and request audio focus
        val audioManager = appCtx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val ringerMode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL

        // Start Vibration (if not in silent mode)
        if (ringerMode != AudioManager.RINGER_MODE_SILENT) {
            startVibration(appCtx)
        }

        // If phone is in explicit Silent or Vibrate mode, do not play audio
        if (ringerMode == AudioManager.RINGER_MODE_SILENT || ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
            Log.d(TAG, "Device is in Silent/Vibrate mode ($ringerMode), vibration only")
            return
        }

        // Request Audio Focus on STREAM_RING
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setLegacyStreamType(AudioManager.STREAM_RING)
                            .build()
                    )
                    .build()
                audioFocusRequest = focusReq
                audioManager?.requestAudioFocus(focusReq)
            } else {
                @Suppress("DEPRECATION")
                audioManager?.requestAudioFocus(null, AudioManager.STREAM_RING, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
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

        // 4. Primary engine: MediaPlayer on STREAM_RING
        var playerSuccess = false
        try {
            val player = MediaPlayer()
            player.setDataSource(appCtx, resolvedUri)
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
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
                Log.w(TAG, "MediaPlayer error ($what, $extra), switching to RingtoneManager fallback")
                try { mp.release() } catch (_: Exception) {}
                mediaPlayer = null
                playWithRingtoneManagerFallback(appCtx, resolvedUri)
                true
            }
            player.prepare()
            player.start()
            mediaPlayer = player
            playerSuccess = true
            Log.i(TAG, "✅ Ringtone PLAYING successfully via MediaPlayer: $resolvedUri")
        } catch (e: Exception) {
            Log.w(TAG, "MediaPlayer initialization failed (${e.message}), trying RingtoneManager fallback")
            try { mediaPlayer?.release() } catch (_: Exception) {}
            mediaPlayer = null
        }

        // 5. Secondary engine fallback: android.media.Ringtone
        if (!playerSuccess) {
            playWithRingtoneManagerFallback(appCtx, resolvedUri)
        }
    }

    private fun playWithRingtoneManagerFallback(context: Context, uri: Uri) {
        try {
            var r = RingtoneManager.getRingtone(context, uri)
            if (r == null) {
                val fallbackUri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                r = RingtoneManager.getRingtone(context, fallbackUri)
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
                Log.i(TAG, "✅ Ringtone PLAYING successfully via RingtoneManager fallback")
            } else {
                Log.e(TAG, "❌ Failed to create Ringtone instance for fallback")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ringtone fallback failed: ${e.message}", e)
        }
    }

    /**
     * Stop ringtone and vibration immediately.
     */
    @Synchronized
    fun stop() {
        if (!isRinging && mediaPlayer == null && ringtone == null && vibrator == null) {
            return
        }

        isRinging = false
        Log.i(TAG, "⏹️ STOPPING RINGTONE and vibration")

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
            vibrator?.cancel()
        } catch (_: Exception) {}
        vibrator = null

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        wakeLock = null

        // Abandon audio focus
        try {
            val am = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
                am?.abandonAudioFocusRequest(audioFocusRequest!!)
            }
        } catch (_: Exception) {}
        audioFocusRequest = null
    }

    /**
     * Silence ringtone (e.g. user pressed volume key) while keeping call active.
     */
    @Synchronized
    fun silence() {
        Log.i(TAG, "🔇 SILENCING RINGTONE")
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
            vibrator?.cancel()
        } catch (_: Exception) {}
        vibrator = null
    }

    private fun startVibration(context: Context) {
        try {
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
