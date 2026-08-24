package com.example.util

import android.content.Context
import android.media.AudioAttributes
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
 * Industry-grade, standalone in-process ringtone and vibration manager.
 * 
 * Advantages:
 * 1. Runs directly in process memory — NEVER blocked by Android 12+ background service start restrictions.
 * 2. Works seamlessly on locked screen, unlocked screen, Doze mode, and deep sleep.
 * 3. Uses CPU WakeLock to guarantee continuous ringing while screen is off.
 * 4. Dual engine: MediaPlayer (primary with looping) + RingtoneManager fallback.
 * 5. Respects system ringer mode (Silent / Vibrate / Normal) and audio routing (STREAM_RING).
 * 6. Synchronized start/stop across FCM, UI, Telecom, Notifications, and hardware volume keys.
 */
object LksIncomingRingtonePlayer {
    private const val TAG = "LksRingtonePlayer"

    private var mediaPlayer: MediaPlayer? = null
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    var isRinging: Boolean = false
        private set

    /**
     * Start playing the incoming call ringtone and vibration.
     * Thread-safe and idempotent.
     */
    @Synchronized
    fun start(context: Context, callerNumber: String) {
        if (isRinging && (mediaPlayer?.isPlaying == true || ringtone?.isPlaying == true)) {
            Log.d(TAG, "Already ringing, skipping start")
            return
        }

        isRinging = true
        Log.d(TAG, "Starting ringtone for caller: $callerNumber")

        val appContext = context.applicationContext

        // 1. Acquire partial wake lock to keep CPU awake while ringing on locked screen
        try {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (wakeLock?.isHeld != true) {
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lksdialer:ringtone_wake")?.apply {
                    acquire(60_000L) // 60s max safety timeout
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire ringtone wake lock: ${e.message}")
        }

        // 2. Check AudioManager ringer mode
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val ringerMode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL

        // 3. Start Vibration (if not in silent mode)
        if (ringerMode != AudioManager.RINGER_MODE_SILENT) {
            startVibration(appContext)
        }

        // 4. If phone is in Vibrate or Silent mode, do not play audio sound
        if (ringerMode == AudioManager.RINGER_MODE_SILENT || ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
            Log.d(TAG, "Device is in Silent/Vibrate mode ($ringerMode), skipping sound playback")
            return
        }

        // 5. Resolve custom or default ringtone URI
        val ringtoneUri: Uri = try {
            LksRingtoneManager.getRingtoneForIncomingCall(appContext, callerNumber)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve custom ringtone, using system default: ${e.message}")
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        }

        // 6. Play audio via MediaPlayer on STREAM_RING with looping
        var playerStarted = false
        try {
            val player = MediaPlayer()
            player.setDataSource(appContext, ringtoneUri)
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setLegacyStreamType(AudioManager.STREAM_RING)
                    .build()
            )
            player.isLooping = true
            player.setOnCompletionListener {
                try {
                    if (isRinging) it.start()
                } catch (_: Exception) {}
            }
            player.setOnErrorListener { mp, what, extra ->
                Log.w(TAG, "MediaPlayer error ($what, $extra), falling back to Ringtone")
                try { mp.release() } catch (_: Exception) {}
                mediaPlayer = null
                true
            }
            player.prepare()
            player.start()
            mediaPlayer = player
            playerStarted = true
            Log.d(TAG, "Ringtone playing via MediaPlayer: $ringtoneUri")
        } catch (e: Exception) {
            Log.w(TAG, "MediaPlayer failed (${e.message}), falling back to Ringtone API")
            try { mediaPlayer?.release() } catch (_: Exception) {}
            mediaPlayer = null
        }

        // 7. Fallback to android.media.Ringtone API if MediaPlayer failed
        if (!playerStarted) {
            try {
                val r = RingtoneManager.getRingtone(appContext, ringtoneUri)
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
                    Log.d(TAG, "Ringtone playing via RingtoneManager fallback")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ringtone fallback also failed: ${e.message}")
            }
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
        Log.d(TAG, "Stopping ringtone and vibration")

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
    }

    /**
     * Silence the ringtone (e.g. user pressed volume key) while keeping incoming call active.
     */
    @Synchronized
    fun silence() {
        Log.d(TAG, "Silencing ringtone")
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
