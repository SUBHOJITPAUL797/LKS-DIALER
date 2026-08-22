package com.example.services

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import com.example.data.model.CallStatus
import com.example.webrtc.WebRtcEngine

/**
 * HeadsetButtonManager
 * Intercepts physical and touch hardware button events from connected Bluetooth headsets,
 * TWS earbuds, wired headsets, and car steering wheel controls to:
 * - Answer incoming calls on single-click / tap
 * - Hang up / end ongoing calls on single-click / tap
 */
class HeadsetButtonManager(private val context: Context) {

    private var mediaSession: MediaSession? = null
    private var mediaReceiver: BroadcastReceiver? = null
    private var isRegistered = false

    fun startListening() {
        if (isRegistered) return
        isRegistered = true

        try {
            // 1. Setup native MediaSession for API 21+
            val componentName = ComponentName(context, HeadsetMediaButtonReceiver::class.java)
            mediaSession = MediaSession(context, "LksDialerCallSession").apply {
                val mediaButtonPendingIntent = PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(componentName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                setMediaButtonReceiver(mediaButtonPendingIntent)
                setCallback(object : MediaSession.Callback() {
                    override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                        val event = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                        }
                        if (event != null && event.action == KeyEvent.ACTION_DOWN) {
                            return handleHeadsetKeyEvent(event)
                        }
                        return super.onMediaButtonEvent(mediaButtonIntent)
                    }
                })

                val state = PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_STOP or
                        PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS
                    )
                    .setState(PlaybackState.STATE_PLAYING, 0, 1.0f)
                    .build()
                setPlaybackState(state)
                isActive = true
            }

            // 2. Register dynamic BroadcastReceiver with highest priority
            mediaReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent?.action == Intent.ACTION_MEDIA_BUTTON) {
                        val event = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                        }
                        if (event != null && event.action == KeyEvent.ACTION_DOWN) {
                            if (handleHeadsetKeyEvent(event)) {
                                try { abortBroadcast() } catch (_: Exception) {}
                            }
                        }
                    }
                }
            }

            val filter = IntentFilter(Intent.ACTION_MEDIA_BUTTON).apply {
                priority = IntentFilter.SYSTEM_HIGH_PRIORITY
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(mediaReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(mediaReceiver, filter)
            }

            // 3. Register with AudioManager for older Android versions
            @Suppress("DEPRECATION")
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            @Suppress("DEPRECATION")
            am?.registerMediaButtonEventReceiver(componentName)

            Log.d("HeadsetButtonManager", "Headset media button listener started successfully")
        } catch (e: Exception) {
            Log.e("HeadsetButtonManager", "Failed to start headset button listener", e)
        }
    }

    fun stopListening() {
        if (!isRegistered) return
        isRegistered = false

        try {
            mediaSession?.isActive = false
            mediaSession?.release()
            mediaSession = null
        } catch (_: Exception) {}

        try {
            mediaReceiver?.let { context.unregisterReceiver(it) }
            mediaReceiver = null
        } catch (_: Exception) {}

        try {
            @Suppress("DEPRECATION")
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val componentName = ComponentName(context, HeadsetMediaButtonReceiver::class.java)
            @Suppress("DEPRECATION")
            am?.unregisterMediaButtonEventReceiver(componentName)
        } catch (_: Exception) {}

        Log.d("HeadsetButtonManager", "Headset media button listener stopped")
    }

    companion object {
        fun handleHeadsetKeyEvent(event: KeyEvent): Boolean {
            val keyCode = event.keyCode
            Log.d("HeadsetButtonManager", "Handling Headset KeyEvent: keyCode=$keyCode, action=${event.action}")

            val rtcEngine = WebRtcEngine.getInstanceIfCreated() ?: return false
            val callStatus = rtcEngine.state.value.callStatus

            when (keyCode) {
                KeyEvent.KEYCODE_HEADSETHOOK,
                KeyEvent.KEYCODE_CALL,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    if (callStatus == CallStatus.RINGING) {
                        Log.i("HeadsetButtonManager", "Answering call via Bluetooth/Headset button!")
                        rtcEngine.answerCall()
                        return true
                    } else if (callStatus == CallStatus.ANSWERED || callStatus == CallStatus.CALLING) {
                        Log.i("HeadsetButtonManager", "Ending call via Bluetooth/Headset button!")
                        rtcEngine.endCall()
                        return true
                    }
                }
                KeyEvent.KEYCODE_ENDCALL,
                KeyEvent.KEYCODE_MEDIA_STOP,
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    if (callStatus == CallStatus.RINGING) {
                        Log.i("HeadsetButtonManager", "Declining call via Bluetooth/Headset button!")
                        rtcEngine.endCall()
                        return true
                    } else if (callStatus == CallStatus.ANSWERED || callStatus == CallStatus.CALLING) {
                        Log.i("HeadsetButtonManager", "Ending call via Bluetooth/Headset button!")
                        rtcEngine.endCall()
                        return true
                    }
                }
            }
            return false
        }
    }
}
