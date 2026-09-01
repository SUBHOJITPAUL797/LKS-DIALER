package com.example.services

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.model.CallStatus
import com.example.data.model.CallType
import com.example.webrtc.WebRtcEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * FloatingCallBubbleService
 * Renders a sleek, moveable floating pill overlay over other apps using WindowManager.
 * 1. MODE_INCOMING: Moveable top pill with caller info, Green Answer (answers in background), Red Decline.
 * 2. MODE_ACTIVE: Moveable pill with live timer, Mute, Speaker, and Hangup controls.
 */
class FloatingCallBubbleService : Service() {

    companion object {
        private const val TAG = "FloatingCallBubble"
        private const val NOTIFICATION_ID = 2002
        private const val CHANNEL_ID = "floating_call_channel"

        const val ACTION_SHOW_INCOMING = "com.example.SHOW_INCOMING_PILL"
        const val ACTION_SHOW_ACTIVE = "com.example.SHOW_ACTIVE_PILL"
        const val ACTION_HIDE = "com.example.HIDE_PILL"

        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_CALLER_NAME = "caller_name"
        const val EXTRA_CALLER_NUMBER = "caller_number"
        const val EXTRA_CALL_TYPE = "call_type"

        @Volatile var instance: FloatingCallBubbleService? = null
        @Volatile var isShowingPill: Boolean = false

        fun silenceRingtone(context: Context) {
            com.example.util.LksIncomingRingtonePlayer.silence()
            instance?.stopRinging()
            LksKeepAliveService.silenceRingtone(context)
        }

        fun showIncoming(
            context: Context,
            callId: String,
            callerName: String,
            callerNumber: String,
            callType: CallType
        ) {
            // NOTE: Don't block on canDrawOverlays here — the service must start
            // for ringtone playback even without overlay permission. The pill UI
            // rendering is gated inside onStartCommand.
            val intent = Intent(context, FloatingCallBubbleService::class.java).apply {
                action = ACTION_SHOW_INCOMING
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_CALLER_NAME, callerName)
                putExtra(EXTRA_CALLER_NUMBER, callerNumber)
                putExtra(EXTRA_CALL_TYPE, callType.name)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start FloatingCallBubbleService", e)
            }
        }

        fun showActive(
            context: Context,
            callId: String,
            peerName: String,
            peerNumber: String,
            callType: CallType
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                return
            }
            val intent = Intent(context, FloatingCallBubbleService::class.java).apply {
                action = ACTION_SHOW_ACTIVE
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_CALLER_NAME, peerName)
                putExtra(EXTRA_CALLER_NUMBER, peerNumber)
                putExtra(EXTRA_CALL_TYPE, callType.name)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start active FloatingCallBubbleService", e)
            }
        }

        fun hide(context: Context) {
            val intent = Intent(context, FloatingCallBubbleService::class.java).apply {
                action = ACTION_HIDE
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {}
        }
    }

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var currentMode: String? = null

    private var callId: String = ""
    private var callerName: String = ""
    private var callerNumber: String = ""
    private var callType: CallType = CallType.AUDIO

    private val handler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null
    private var callStartTime: Long = 0L
    private var lastPillX: Int = Int.MIN_VALUE
    private var lastPillY: Int = Int.MIN_VALUE

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var stateObserverJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        createNotificationChannel()
        try {
            startServiceForeground("Call in progress")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground in onCreate: ${e.message}")
        }
        observeEngineState()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isShowingPill = false
        stopRinging()
        stateObserverJob?.cancel()
        serviceJob.cancel()
        removeFloatingView()
    }

    private fun startRinging(callerNumber: String) {
        com.example.util.LksIncomingRingtonePlayer.start(this, callerNumber)
    }

    private fun stopRinging() {
        try { com.example.util.LksIncomingRingtonePlayer.stop() } catch (_: Exception) {}
        try { LksKeepAliveService.stopRingtone(this) } catch (_: Exception) {}
    }

    private fun observeEngineState() {
        stateObserverJob?.cancel()
        stateObserverJob = serviceScope.launch {
            val engine = WebRtcEngine.getInstanceIfCreated() ?: return@launch
            engine.state.collectLatest { rtcState ->
                when (rtcState.callStatus) {
                    CallStatus.ENDED, CallStatus.DECLINED, CallStatus.MISSED, CallStatus.IDLE -> {
                        stopRinging()
                        removeFloatingView()
                        stopSelf()
                    }
                    CallStatus.ANSWERED -> {
                        stopRinging()
                        if (currentMode == ACTION_SHOW_INCOMING) {
                            showActiveCallPill()
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        if (action == ACTION_HIDE) {
            // Only remove the floating overlay UI, do NOT stop ringtone here.
            // Ringtone should keep playing even when MainActivity takes over on the lock screen.
            stateObserverJob?.cancel()
            removeFloatingView()
            stopSelf()
            return START_NOT_STICKY
        }

        callId = intent.getStringExtra(EXTRA_CALL_ID) ?: ""
        callerName = intent.getStringExtra(EXTRA_CALLER_NAME) ?: "LKS User"
        callerNumber = intent.getStringExtra(EXTRA_CALLER_NUMBER) ?: ""
        val typeStr = intent.getStringExtra(EXTRA_CALL_TYPE) ?: "AUDIO"
        callType = try { CallType.valueOf(typeStr) } catch (_: Exception) { CallType.AUDIO }

        currentMode = action
        if (action == ACTION_SHOW_INCOMING) {
            startRinging(callerNumber)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
            val isLocked = km?.isKeyguardLocked == true
            // Only show floating pill overlay if unlocked (when locked, full-screen MainActivity is shown)
            val canOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true
            if (canOverlay && !isLocked) {
                showIncomingCallPill()
            }
        } else if (action == ACTION_SHOW_ACTIVE) {
            stopRinging()
            showActiveCallPill()
        }

        return START_NOT_STICKY
    }

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            resources.displayMetrics
        ).toInt()
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 1. INCOMING CALL PILL (Draggable Top Banner with Accept & Decline)
    // ─────────────────────────────────────────────────────────────────────────────
    @SuppressLint("ClickableViewAccessibility")
    private fun showIncomingCallPill() {
        removeFloatingView()
        val wm = windowManager ?: return

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = if (lastPillX != Int.MIN_VALUE) lastPillX else 0
            y = if (lastPillY != Int.MIN_VALUE) lastPillY else dpToPx(36f)
        }

        // Pill Card (Dark Teal Glassmorphism with rounded corners & elevation)
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(14f), dpToPx(10f), dpToPx(14f), dpToPx(10f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(28f).toFloat()
                colors = intArrayOf(0xFF1E293B.toInt(), 0xFF0F172A.toInt()) // Sleek Slate 900
                setStroke(dpToPx(1.5f), 0xFF00ADB5.toInt()) // Teal border
            }
            elevation = dpToPx(12f).toFloat()
        }

        // 🎯 Info Area (Avatar + Text) — Touch/Drag this area to move or tap to expand full-screen
        val infoArea = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
        }

        // Avatar Icon Circle
        val avatar = ImageView(this).apply {
            setImageResource(android.R.drawable.sym_action_call)
            setColorFilter(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF00ADB5.toInt())
            }
            setPadding(dpToPx(7f), dpToPx(7f), dpToPx(7f), dpToPx(7f))
            layoutParams = LinearLayout.LayoutParams(dpToPx(38f), dpToPx(38f))
        }

        // Text Info Container
        val textContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dpToPx(10f)
                marginEnd = dpToPx(14f)
            }
        }

        val nameView = TextView(this).apply {
            text = callerName.ifBlank { "LKS Call" }
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1
            maxWidth = dpToPx(130f)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        val subTextView = TextView(this).apply {
            text = "Incoming ${if (callType == CallType.VIDEO) "Video" else "Audio"}..."
            setTextColor(0xFF94A3B8.toInt()) // Slate 400
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }

        textContainer.addView(nameView)
        textContainer.addView(subTextView)

        infoArea.addView(avatar)
        infoArea.addView(textContainer)

        // Action Buttons Row (Only triggers buttons — NEVER opens full screen!)
        val buttonsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Red Decline Button
        val declineBtn = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFEF4444.toInt()) // Bright Red
            }
            setPadding(dpToPx(8f), dpToPx(8f), dpToPx(8f), dpToPx(8f))
            layoutParams = LinearLayout.LayoutParams(dpToPx(38f), dpToPx(38f)).apply {
                marginEnd = dpToPx(8f)
            }
            setOnClickListener {
                stopRinging()
                WebRtcEngine.getInstanceIfCreated()?.endCall()
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.cancel(CallMessagingService.NOTIFICATION_ID)
                removeFloatingView()
                stopSelf()
            }
        }

        // Green Answer Button - Answers directly in background without opening full screen!
        val answerBtn = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_call)
            setColorFilter(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF10B981.toInt()) // Emerald Green
            }
            setPadding(dpToPx(8f), dpToPx(8f), dpToPx(8f), dpToPx(8f))
            layoutParams = LinearLayout.LayoutParams(dpToPx(38f), dpToPx(38f))
            setOnClickListener {
                stopRinging()
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.cancel(CallMessagingService.NOTIFICATION_ID)
                // Answer directly via WebRtcEngine in background
                val engine = WebRtcEngine.getInstanceIfCreated()
                engine?.attachToCall(callId, autoAnswer = true, callerName, callerNumber, callType.name)
                // Switch pill to active in-call pill
                showActiveCallPill()
            }
        }

        buttonsContainer.addView(declineBtn)
        buttonsContainer.addView(answerBtn)

        pill.addView(infoArea)
        pill.addView(buttonsContainer)

        // 🎯 Touch & Drag Listener attached to infoArea so action buttons can receive clean clicks!
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        infoArea.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isDragging = true
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        try { wm.updateViewLayout(pill, params) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // Save position for pill persistence
                    lastPillX = params.x
                    lastPillY = params.y
                    if (!isDragging) {
                        stopRinging()
                        // Tapping the card area opens full screen
                        openFullScreenCallActivity(callId, autoAnswer = false)
                    }
                    true
                }
                else -> false
            }
        }

        try {
            wm.addView(pill, params)
            floatingView = pill
            isShowingPill = true
            Log.d(TAG, "Draggable incoming call pill attached successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add incoming call pill to WindowManager", e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 2. ACTIVE IN-CALL PILL (Draggable with Live Timer & Controls)
    // ─────────────────────────────────────────────────────────────────────────────
    @SuppressLint("ClickableViewAccessibility")
    private fun showActiveCallPill() {
        removeFloatingView()
        val wm = windowManager ?: return

        val engine = WebRtcEngine.getInstanceIfCreated()
        val engineStartTime = engine?.state?.value?.callStartedAtMillis ?: 0L
        val currentDuration = engine?.state?.value?.callDurationSeconds ?: 0
        callStartTime = when {
            engineStartTime > 0L -> engineStartTime
            currentDuration > 0 -> System.currentTimeMillis() - (currentDuration * 1000L)
            else -> System.currentTimeMillis()
        }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = if (lastPillX != Int.MIN_VALUE) lastPillX else 0
            y = if (lastPillY != Int.MIN_VALUE) lastPillY else dpToPx(36f)
        }

        // Draggable In-Call Pill Card
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(14f), dpToPx(10f), dpToPx(14f), dpToPx(10f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(24f).toFloat()
                colors = intArrayOf(0xFF111827.toInt(), 0xFF1F2937.toInt()) // Gray 900 -> Gray 800
                setStroke(dpToPx(1.5f), 0xFF10B981.toInt()) // Glowing Green active call border
            }
            elevation = dpToPx(12f).toFloat()
        }

        // 🎯 Info Area (Indicator + Name + Timer) — Touch/Drag this area to move or tap to expand full-screen
        val infoArea = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
        }

        // Pulsing Green Indicator Dot
        val liveIndicator = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF10B981.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(dpToPx(10f), dpToPx(10f)).apply {
                marginEnd = dpToPx(8f)
            }
        }

        // Name + Live Duration Timer
        val infoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val nameView = TextView(this).apply {
            text = callerName.ifBlank { "LKS Call" }
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1
            maxWidth = dpToPx(110f)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        val timerView = TextView(this).apply {
            text = "00:00"
            setTextColor(0xFF34D399.toInt()) // Emerald 400
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = android.graphics.Typeface.MONOSPACE
        }

        infoCol.addView(nameView)
        infoCol.addView(timerView)

        infoArea.addView(liveIndicator)
        infoArea.addView(infoCol)

        // Live Timer Loop
        timerRunnable?.let { handler.removeCallbacks(it) }
        timerRunnable = object : Runnable {
            override fun run() {
                val eng = WebRtcEngine.getInstanceIfCreated()
                val elapsedSeconds = if (eng != null && eng.state.value.callDurationSeconds > 0) {
                    eng.state.value.callDurationSeconds.toLong()
                } else {
                    ((System.currentTimeMillis() - callStartTime) / 1000).coerceAtLeast(0)
                }
                val mins = elapsedSeconds / 60
                val secs = elapsedSeconds % 60
                timerView.text = String.format("%02d:%02d", mins, secs)
                handler.postDelayed(this, 1000)
            }
        }

        // Action Buttons Row (Mute, Speaker, End — NEVER opens full screen!)
        val buttonsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Quick Mic Mute Toggle Button
        var isMuted = engine?.state?.value?.isMuted ?: false
        val muteBtn = ImageView(this).apply {
            setImageResource(if (isMuted) com.example.R.drawable.ic_mic_off else com.example.R.drawable.ic_mic_on)
            setColorFilter(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (isMuted) 0xFF7F1D1D.toInt() else 0xFF374151.toInt())
            }
            setPadding(dpToPx(7f), dpToPx(7f), dpToPx(7f), dpToPx(7f))
            layoutParams = LinearLayout.LayoutParams(dpToPx(34f), dpToPx(34f)).apply {
                marginStart = dpToPx(10f)
            }
            setOnClickListener {
                val eng = WebRtcEngine.getInstanceIfCreated() ?: return@setOnClickListener
                eng.toggleMute()
                isMuted = eng.state.value.isMuted
                setImageResource(if (isMuted) com.example.R.drawable.ic_mic_off else com.example.R.drawable.ic_mic_on)
                setColorFilter(if (isMuted) 0xFFEF4444.toInt() else Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (isMuted) 0xFF7F1D1D.toInt() else 0xFF374151.toInt())
                }
            }
        }

        // Quick Speaker Toggle Button
        var isSpeaker = engine?.state?.value?.isSpeakerOn ?: false
        val speakerBtn = ImageView(this).apply {
            setImageResource(if (isSpeaker) com.example.R.drawable.ic_speaker_on else com.example.R.drawable.ic_speaker_off)
            setColorFilter(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (isSpeaker) 0xFF134E4A.toInt() else 0xFF374151.toInt())
            }
            setPadding(dpToPx(7f), dpToPx(7f), dpToPx(7f), dpToPx(7f))
            layoutParams = LinearLayout.LayoutParams(dpToPx(34f), dpToPx(34f)).apply {
                marginStart = dpToPx(8f)
            }
            setOnClickListener {
                val eng = WebRtcEngine.getInstanceIfCreated() ?: return@setOnClickListener
                eng.toggleSpeaker()
                isSpeaker = eng.state.value.isSpeakerOn
                setImageResource(if (isSpeaker) com.example.R.drawable.ic_speaker_on else com.example.R.drawable.ic_speaker_off)
                setColorFilter(if (isSpeaker) 0xFF00ADB5.toInt() else Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (isSpeaker) 0xFF134E4A.toInt() else 0xFF374151.toInt())
                }
            }
        }

        // Red End Call Button
        val endBtn = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFEF4444.toInt())
            }
            setPadding(dpToPx(8f), dpToPx(8f), dpToPx(8f), dpToPx(8f))
            layoutParams = LinearLayout.LayoutParams(dpToPx(34f), dpToPx(34f)).apply {
                marginStart = dpToPx(8f)
            }
            setOnClickListener {
                stopRinging()
                WebRtcEngine.getInstanceIfCreated()?.endCall()
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.cancel(CallMessagingService.NOTIFICATION_ID)
                removeFloatingView()
                stopSelf()
            }
        }

        buttonsContainer.addView(muteBtn)
        buttonsContainer.addView(speakerBtn)
        buttonsContainer.addView(endBtn)

        pill.addView(infoArea)
        pill.addView(buttonsContainer)

        // 🎯 Touch & Drag Listener on infoArea with smooth edge drag
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        infoArea.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isDragging = true
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        try { wm.updateViewLayout(pill, params) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // Save position for pill persistence
                    lastPillX = params.x
                    lastPillY = params.y
                    if (!isDragging) {
                        stopRinging()
                        openFullScreenCallActivity(callId, autoAnswer = false)
                    }
                    true
                }
                else -> false
            }
        }

        try {
            wm.addView(pill, params)
            floatingView = pill
            isShowingPill = true
            timerRunnable?.let { handler.post(it) }
            Log.d(TAG, "Active call draggable pill attached to WindowManager")
        } catch (e: Exception) {
            timerRunnable?.let { handler.removeCallbacks(it) }
            timerRunnable = null
            Log.e(TAG, "Failed to add active call pill to WindowManager", e)
        }
    }

    private fun openFullScreenCallActivity(callId: String, autoAnswer: Boolean) {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("incoming_call", true)
                putExtra("call_id", callId)
                putExtra("auto_answer", autoAnswer)
                putExtra("caller_name", callerName)
                putExtra("caller_number", callerNumber)
                putExtra("call_type", callType.name)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch MainActivity from floating pill", e)
        }
    }

    private fun removeFloatingView() {
        timerRunnable?.let { handler.removeCallbacks(it) }
        timerRunnable = null
        floatingView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (_: Exception) {}
        }
        floatingView = null
        currentMode = null
        isShowingPill = false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LKS Floating Call Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active floating call pill notification"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    private fun startServiceForeground(text: String) {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LKS Call Active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground: ${e.message}")
            try { startForeground(NOTIFICATION_ID, notification) } catch (_: Exception) {}
        }
    }
}
