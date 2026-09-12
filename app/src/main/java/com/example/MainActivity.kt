package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.WindowManager

import com.example.data.model.CallDirection
import com.example.data.model.CallStatus
import com.example.data.model.CallType
import com.example.data.repository.FirebaseManager
import com.example.ui.screens.call.*
import com.example.ui.screens.contacts.ContactsScreen
import com.example.ui.screens.dialer.DialerScreen
import com.example.ui.screens.onboarding.*
import com.example.ui.screens.profile.ProfileScreen
import com.example.ui.screens.recents.CallHistoryScreen
import com.example.ui.screens.settings.SettingsScreen
import com.example.ui.theme.LksDialerTheme
import com.example.ui.theme.TealPrimary
import com.example.webrtc.WebRtcEngine
import com.example.util.GitHubUpdater
import com.example.util.UpdateInfo
import com.example.util.LksIncomingRingtonePlayer
import com.example.ui.components.UpdateDialog
import com.example.ui.components.OngoingCallTopBar
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput

import com.example.data.repository.ChatRepository
import com.example.ui.screens.chat.ChatListScreen
import com.example.ui.screens.chat.ChatConversationScreen

enum class MainTab(val title: String, val icon: ImageVector) {
    DIALER("Dialer", Icons.Default.Dialpad),
    RECENTS("Recents", Icons.Default.History),
    CHATS("Chats", Icons.Default.ChatBubbleOutline),
    CONTACTS("Contacts", Icons.Default.People),
    PROFILE("Account", Icons.Default.Person)
}

enum class AppNavState {
    WELCOME,
    PHONE_INPUT,
    PROFILE_SETUP,
    MAIN,
    SETTINGS,
    CHAT_CONVERSATION
}

class MainActivity : ComponentActivity() {

    companion object {
        @Volatile
        var isForeground: Boolean = false
            private set

        @Volatile
        var isInPipMode: Boolean = false
            private set
    }

    // Needed so FLAG_ACTIVITY_SINGLE_TOP re-delivers the intent
    // when the activity is already running (e.g. user taps Accept while app is open)
    private val _incomingIntent = androidx.compose.runtime.mutableStateOf<android.content.Intent?>(null)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyLockscreenFlags()
        _incomingIntent.value = intent
    }

    private fun applyLockscreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val pm = getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
                if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                    android.util.Log.i("MainActivity", "Requested battery optimization exemption for 24/7 call readiness")
                }
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "Failed to launch battery optimization request: ${e.message}")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
        com.example.data.repository.FirebaseManager.getInstance(this).startPresenceHeartbeat()
        com.example.data.repository.ChatRepository.getInstance(this).setAppForeground(true)

        // Seamless handoff: If user opened the app from the launcher while a call was incoming/active in the floating pill, adopt it!
        val bubbleCallId = com.example.services.FloatingCallBubbleService.currentCallId
        if (bubbleCallId.isNotBlank()) {
            val engine = com.example.webrtc.WebRtcEngine.getInstanceIfCreated() ?: com.example.webrtc.WebRtcEngine.getInstance(this)
            if (engine.state.value.activeCall == null) {
                val bCallerName = com.example.services.FloatingCallBubbleService.currentCallerName
                val bCallerNumber = com.example.services.FloatingCallBubbleService.currentCallerNumber
                val bCallType = com.example.services.FloatingCallBubbleService.currentCallType
                android.util.Log.i("MainActivity", "Seamless handoff: adopting call from FloatingCallBubbleService: callId=$bubbleCallId, caller=$bCallerName")
                engine.attachToCall(
                    callId = bubbleCallId,
                    autoAnswer = false,
                    callerName = bCallerName,
                    callerNumber = bCallerNumber,
                    callTypeStr = bCallType.name
                )
            }
        }

        // Dismiss floating pill when user is viewing the full-screen MainActivity
        com.example.services.FloatingCallBubbleService.hide(this)
        
        // Cancel the redundant heads-up notification card immediately with retries so it NEVER covers the full-screen UI
        dismissIncomingCallNotificationBanner()
    }

    private fun dismissIncomingCallNotificationBanner() {
        // CRITICAL: NEVER dismiss notification 1001 when MainActivity is in background/on home screen!
        // The user relies on the heads-up notification card to see and answer incoming calls.
        if (!isForeground) return
        val nm = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager ?: return
        nm.cancel(1001)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed({ if (isForeground) nm.cancel(1001) }, 300L)
        handler.postDelayed({ if (isForeground) nm.cancel(1001) }, 800L)
        handler.postDelayed({ if (isForeground) nm.cancel(1001) }, 1500L)
    }

    override fun onStart() {
        super.onStart()
        isForeground = true
        com.example.data.repository.FirebaseManager.getInstance(this).startPresenceHeartbeat()
        com.example.data.repository.ChatRepository.getInstance(this).setAppForeground(true)
    }


    override fun onPause() {
        super.onPause()
        isForeground = false
        com.example.data.repository.FirebaseManager.getInstance(this).stopPresenceHeartbeat()
        com.example.data.repository.ChatRepository.getInstance(this).setAppForeground(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        isForeground = false
        isInPipMode = false
        com.example.data.repository.ChatRepository.getInstance(this).setAppForeground(false)
        if (!isChangingConfigurations) {
            com.example.data.repository.FirebaseManager.getInstance(this).stopPresenceHeartbeat()
            val currentStatus = com.example.webrtc.WebRtcEngine.getInstanceIfCreated()?.state?.value?.callStatus
            if (currentStatus != com.example.data.model.CallStatus.RINGING) {
                com.example.util.LksIncomingRingtonePlayer.stop()
            }
            // User requested: When closing the PiP window via 'X', DO NOT end the call!
            // Continue the call in background and show the active floating pill instead!
            if (currentStatus == com.example.data.model.CallStatus.ANSWERED || currentStatus == com.example.data.model.CallStatus.CALLING) {
                val rtcState = com.example.webrtc.WebRtcEngine.getInstanceIfCreated()?.state?.value
                val activeCall = rtcState?.activeCall
                if (activeCall != null) {
                    com.example.services.FloatingCallBubbleService.showActive(
                        this,
                        activeCall.callId,
                        activeCall.callerName,
                        activeCall.callerNumber,
                        activeCall.callType
                    )
                }
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val rtcState = com.example.webrtc.WebRtcEngine.getInstanceIfCreated()?.state?.value
        if (rtcState != null && (rtcState.callStatus == com.example.data.model.CallStatus.ANSWERED || rtcState.callStatus == com.example.data.model.CallStatus.CALLING)) {
            if (rtcState.callType == com.example.data.model.CallType.VIDEO && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (enterPipMode()) return // Successfully triggered PiP; do NOT show overlapping floating pill
            }
        }
        triggerFloatingCallBubbleIfActive()
    }

    /** Enter PiP in portrait (9:16) — call from button or onUserLeaveHint */
    fun enterPipMode(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return try {
            val params = android.app.PictureInPictureParams.Builder()
                .setAspectRatio(android.util.Rational(9, 16))
                .also { builder ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        builder.setSeamlessResizeEnabled(false)
                    }
                }
                .build()
            val entered = enterPictureInPictureMode(params)
            if (entered) {
                isInPipMode = true
            }
            entered
        } catch (_: Exception) { false }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            isForeground = false
            com.example.services.FloatingCallBubbleService.hide(this)
        } else {
            // Returning from PiP back to full screen — restore foreground flag
            isForeground = true
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            triggerFloatingCallBubbleIfActive()
            com.example.data.repository.FirebaseManager.getInstance(this).stopPresenceHeartbeat()
        }
    }


    private fun triggerFloatingCallBubbleIfActive() {
        isForeground = false
        // If already in PiP mode on Android O+, do not show overlapping floating bubble
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode) {
            return
        }

        val rtcState = com.example.webrtc.WebRtcEngine.getInstanceIfCreated()?.state?.value ?: return
        val activeCall = rtcState.activeCall ?: return

        val myPhone = com.example.data.repository.FirebaseManager.getInstance(this).currentUser.value?.phoneNumber ?: ""
        val callerNum = activeCall.callerNumber
        val isMyOutgoing = myPhone.isNotBlank() && callerNum.isNotBlank() && com.example.util.ContactsHelper.numbersMatch(myPhone, callerNum)

        if (rtcState.callStatus == com.example.data.model.CallStatus.RINGING && !isMyOutgoing) {
            // Show Draggable Incoming Call Pill over home screen when app is minimized during ring
            com.example.services.FloatingCallBubbleService.showIncoming(
                this,
                activeCall.callId,
                activeCall.callerName,
                activeCall.callerNumber,
                activeCall.callType
            )
        } else if ((rtcState.callStatus == com.example.data.model.CallStatus.ANSWERED || rtcState.callStatus == com.example.data.model.CallStatus.CALLING)) {
            // Show Draggable Active Call Pill over other apps (suppressed only if native PiP window is currently visible)
            if (!isInPipMode) {
                com.example.services.FloatingCallBubbleService.showActive(
                    this,
                    activeCall.callId,
                    activeCall.callerName,
                    activeCall.callerNumber,
                    activeCall.callType
                )
            }
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            // Silence ringtone on volume button press during incoming call
            if (event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN ||
                event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) {
                val engine = com.example.webrtc.WebRtcEngine.getInstanceIfCreated()
                if (engine != null && engine.state.value.callStatus == com.example.data.model.CallStatus.RINGING) {
                    LksIncomingRingtonePlayer.silence()
                    com.example.services.FloatingCallBubbleService.silenceRingtone(this)
                    return true
                }
            }
            if (com.example.services.HeadsetButtonManager.handleHeadsetKeyEvent(event)) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        applyLockscreenFlags()
        requestBatteryOptimizationExemption()

        // Start 24/7 keep-alive service for reliable FCM delivery
        try {
            val keepAliveIntent = android.content.Intent(this, com.example.services.LksKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(keepAliveIntent)
            } else {
                startService(keepAliveIntent)
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Failed to start keep-alive service: ${e.message}")
        }

        // Register self-managed phone account for Bluetooth HFP call controls
        try {
            com.example.services.LksTelecomManager.registerPhoneAccount(this)
        } catch (_: Exception) {}

        // Pass the launch intent in so the Compose side can read it
        _incomingIntent.value = intent
        enableEdgeToEdge()

        setContent {
            val context = LocalContext.current
            val themeManager = remember { com.example.ui.theme.ThemeManager.getInstance(context) }
            val currentThemeColor by themeManager.currentTheme.collectAsState()

            LksDialerTheme(themeColor = currentThemeColor) {
                val firebaseManager = remember { FirebaseManager.getInstance(context) }
                val webRtcEngine = remember { WebRtcEngine.getInstance(context) }

                val currentUser by firebaseManager.currentUser.collectAsState()
                val rtcState by webRtcEngine.state.collectAsState()
                
                LaunchedEffect(rtcState.callStatus, rtcState.activeCall) {
                    val window = (context as? android.app.Activity)?.window
                    val myPhone = currentUser?.phoneNumber ?: ""
                    val callerNum = rtcState.activeCall?.callerNumber ?: ""
                    val isMyOutgoing = myPhone.isNotBlank() && callerNum.isNotBlank() && com.example.util.ContactsHelper.numbersMatch(myPhone, callerNum)
                    val isIncomingRinging = rtcState.callStatus == com.example.data.model.CallStatus.RINGING && !isMyOutgoing

                    if (isIncomingRinging) {
                        if (isForeground) {
                            com.example.services.FloatingCallBubbleService.hide(context)
                            (context as? MainActivity)?.dismissIncomingCallNotificationBanner()
                        }
                        if (!LksIncomingRingtonePlayer.isRinging) {
                            val callerNumber = rtcState.activeCall?.callerNumber ?: ""
                            LksIncomingRingtonePlayer.start(context, callerNumber)
                        }
                    } else if (rtcState.callStatus == com.example.data.model.CallStatus.ANSWERED ||
                               rtcState.callStatus == com.example.data.model.CallStatus.ENDED ||
                               rtcState.callStatus == com.example.data.model.CallStatus.DECLINED ||
                               rtcState.callStatus == com.example.data.model.CallStatus.MISSED) {
                        // Explicitly terminal/answered states stop the ringtone.
                        // Do NOT stop on initial IDLE state so FCM-started ringtone continues uninterrupted!
                        LksIncomingRingtonePlayer.stop()
                        if (rtcState.callStatus != com.example.data.model.CallStatus.ANSWERED) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && (context as? android.app.Activity)?.isInPictureInPictureMode == true) {
                                (context as? android.app.Activity)?.moveTaskToBack(true)
                            }
                        }
                    }

                    val isCallActive = rtcState.callStatus == com.example.data.model.CallStatus.CALLING ||
                                       rtcState.callStatus == com.example.data.model.CallStatus.RINGING ||
                                       rtcState.callStatus == com.example.data.model.CallStatus.ANSWERED

                    if (isCallActive) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                            (context as? android.app.Activity)?.setShowWhenLocked(true)
                            (context as? android.app.Activity)?.setTurnScreenOn(true)
                        } else {
                            @Suppress("DEPRECATION")
                            window?.addFlags(
                                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                            )
                        }
                        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                            (context as? android.app.Activity)?.setShowWhenLocked(false)
                            (context as? android.app.Activity)?.setTurnScreenOn(false)
                        } else {
                            @Suppress("DEPRECATION")
                            window?.clearFlags(
                                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                            )
                        }
                        window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
                
                val gitHubUpdater = remember { GitHubUpdater(context) }
                var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
                val downloadState by gitHubUpdater.downloadState.collectAsState()

                val permissions = mutableListOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.CAMERA,
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.READ_PHONE_NUMBERS
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { _ -> 
                    firebaseManager.syncNativeContacts()
                }

                LaunchedEffect(Unit) {
                    permissionLauncher.launch(permissions.toTypedArray())
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        val prefs = context.getSharedPreferences("lks_dialer_prefs", android.content.Context.MODE_PRIVATE)
                        val hasPromptedFullScreen = prefs.getBoolean("full_screen_intent_prompted", false)
                        val notificationManager = context.getSystemService(android.app.NotificationManager::class.java)
                        if (!hasPromptedFullScreen && notificationManager != null && !notificationManager.canUseFullScreenIntent()) {
                            prefs.edit().putBoolean("full_screen_intent_prompted", true).apply()
                            try {
                                val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                                    data = android.net.Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        }
                    }
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val prefs = context.getSharedPreferences("lks_dialer_prefs", android.content.Context.MODE_PRIVATE)
                        val hasPromptedOverlay = prefs.getBoolean("overlay_permission_prompted", false)
                        if (!hasPromptedOverlay && !android.provider.Settings.canDrawOverlays(context)) {
                            prefs.edit().putBoolean("overlay_permission_prompted", true).apply()
                            try {
                                val intent = android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        }

                        // Battery optimization exemption for 24/7 background call reception & battery saver mode
                        val hasPromptedBattery = prefs.getBoolean("battery_optimization_prompted", false)
                        val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
                        val isIgnoring = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
                        if (!hasPromptedBattery && !isIgnoring) {
                            prefs.edit().putBoolean("battery_optimization_prompted", true).apply()
                            try {
                                val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = android.net.Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        }
                    }
                    
                    // Check for updates
                    updateInfo = gitHubUpdater.checkForUpdates()
                }

                var navState by remember {
                    mutableStateOf(if (currentUser != null) AppNavState.MAIN else AppNavState.WELCOME)
                }
                var selectedTab by remember { mutableStateOf(MainTab.DIALER) }
                var newPhoneNumber by remember { mutableStateOf("") }
                var newDeviceId by remember { mutableStateOf("") }
                var chatPeerNumber by remember { mutableStateOf("") }
                var chatPeerName by remember { mutableStateOf("") }
                var chatPeerAvatar by remember { mutableStateOf("") }
                var isCallMinimized by remember { mutableStateOf(false) }

                LaunchedEffect(rtcState.callStatus) {
                    if (rtcState.callStatus == CallStatus.IDLE ||
                        rtcState.callStatus == CallStatus.ENDED ||
                        rtcState.callStatus == CallStatus.DECLINED ||
                        rtcState.callStatus == CallStatus.MISSED) {
                        isCallMinimized = false
                    }
                }

                val chatRepo = remember { ChatRepository.getInstance(context) }
                val totalUnreadChats by chatRepo.getTotalUnreadCountFlow().collectAsState(initial = 0)

                LaunchedEffect(currentUser?.phoneNumber) {
                    currentUser?.phoneNumber?.let {
                        webRtcEngine.listenForIncomingCalls(it)
                    }
                }

                // BUG-06 FIX: Navigate to MAIN automatically if user is restored and we're on WELCOME
                LaunchedEffect(currentUser) {
                    if (currentUser != null && navState == AppNavState.WELCOME) {
                        navState = AppNavState.MAIN
                    }
                }

                // BUG-03 FIX: Use a pending flag that persists until activeCall is populated
                // (auto-answer from notification would race against Firestore listener on cold start)
                val latestIntent by _incomingIntent
                LaunchedEffect(latestIntent) {
                    latestIntent?.let { incoming ->
                        val callId = incoming.getStringExtra("call_id")
                        val autoAnswer = incoming.getBooleanExtra("auto_answer", false)
                        val callerName = incoming.getStringExtra("caller_name")
                        val callerNumber = incoming.getStringExtra("caller_number")
                        val callType = incoming.getStringExtra("call_type")
                        
                        if (autoAnswer) {
                            val notificationManager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                            notificationManager.cancel(1001) // NOTIFICATION_ID
                            com.example.util.LksIncomingRingtonePlayer.stop()
                            com.example.services.FloatingCallBubbleService.silenceRingtone(context)
                        }
                        
                        val hasMicPermission = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        
                        if (!callId.isNullOrBlank()) {
                            // If engine is already attached to this exact call, only auto-answer if requested
                            if (rtcState.activeCall?.callId == callId) {
                                if (autoAnswer && hasMicPermission && rtcState.callStatus != CallStatus.ANSWERED) {
                                    webRtcEngine.answerCall()
                                }
                            } else {
                                // Only auto-answer if the microphone permission is already granted
                                val safeAutoAnswer = autoAnswer && hasMicPermission
                                webRtcEngine.attachToCall(
                                    callId = callId, 
                                    autoAnswer = safeAutoAnswer,
                                    callerName = callerName,
                                    callerNumber = callerNumber,
                                    callTypeStr = callType
                                )
                            }
                        } else if (autoAnswer && rtcState.activeCall != null && hasMicPermission) {
                            webRtcEngine.answerCall()
                        }

                        val openTab = incoming.getStringExtra("open_tab")
                        if (openTab == "RECENTS") {
                            navState = AppNavState.MAIN
                            selectedTab = MainTab.RECENTS
                        } else if (openTab == "CHATS") {
                            navState = AppNavState.MAIN
                            selectedTab = MainTab.CHATS
                        }

                        val chatPeer = incoming.getStringExtra("chat_peer_number")
                        if (!chatPeer.isNullOrBlank()) {
                            chatPeerNumber = chatPeer
                            chatPeerName = incoming.getStringExtra("chat_peer_name") ?: chatPeer
                            navState = AppNavState.CHAT_CONVERSATION
                        }

                        val remoteInputResults = androidx.core.app.RemoteInput.getResultsFromIntent(incoming)
                        val replyText = remoteInputResults?.getCharSequence(ChatRepository.KEY_TEXT_REPLY)?.toString()
                        if (!replyText.isNullOrBlank() && !chatPeer.isNullOrBlank()) {
                            val normPeer = com.example.util.ContactsHelper.normalizePhoneNumber(chatPeer)
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                chatRepo.markConversationAsRead(normPeer)
                                chatRepo.sendMessage(
                                    recipientNumber = normPeer,
                                    recipientName = chatPeerName.ifBlank { normPeer },
                                    text = replyText,
                                    mediaType = com.example.data.local.ChatMediaType.TEXT
                                )
                            }
                        }

                        // Dismiss any notification ID passed in
                        val notifIdToCancel = incoming.getIntExtra("notification_id", -1)
                        if (notifIdToCancel != -1) {
                            val nm = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
                            nm?.cancel(notifIdToCancel)
                        }

                        val callBackNumber = incoming.getStringExtra("call_back_number")
                        if (!callBackNumber.isNullOrBlank()) {
                            val prefs = context.getSharedPreferences("dialer_prefs", android.content.Context.MODE_PRIVATE)
                            val myNum = currentUser?.phoneNumber?.ifBlank { null }
                                ?: prefs.getString("user_phone", "") ?: ""
                            val myName = currentUser?.displayName?.ifBlank { null }
                                ?: prefs.getString("user_name", "") ?: "Me"
                            val callBackName = incoming.getStringExtra("call_back_name") ?: callBackNumber
                            val callBackTypeStr = incoming.getStringExtra("call_back_type") ?: "AUDIO"
                            val callBackType = try { CallType.valueOf(callBackTypeStr) } catch (_: Exception) { CallType.AUDIO }

                            if (myNum.isNotBlank()) {
                                navState = AppNavState.MAIN
                                kotlinx.coroutines.delay(200)
                                webRtcEngine.initiateCall(callBackNumber, callBackName, myNum, myName, callBackType)
                            }
                        }

                        // Deep link: lksdialer://call/{phoneNumber}
                        val dataUri = incoming.data
                        if (dataUri?.scheme == "lksdialer" && dataUri.host == "call") {
                            val targetNumber = dataUri.lastPathSegment
                            if (!targetNumber.isNullOrBlank()) {
                                val prefs = context.getSharedPreferences("dialer_prefs", android.content.Context.MODE_PRIVATE)
                                val myNum = currentUser?.phoneNumber?.ifBlank { null }
                                    ?: prefs.getString("user_phone", "") ?: ""
                                val myName = currentUser?.displayName?.ifBlank { null }
                                    ?: prefs.getString("user_name", "") ?: "Me"
                                if (myNum.isNotBlank()) {
                                    navState = AppNavState.MAIN
                                    kotlinx.coroutines.delay(200)
                                    webRtcEngine.initiateCall(targetNumber, targetNumber, myNum, myName, CallType.AUDIO)
                                }
                            }
                        }
                        
                        _incomingIntent.value = null
                    }
                }

                // Check active call overlay
                val activeCall = rtcState.activeCall
                val isCallActive = activeCall != null && (rtcState.callStatus == CallStatus.ANSWERED || rtcState.callStatus == CallStatus.CALLING)
                val isCallRinging = activeCall != null && rtcState.callStatus == CallStatus.RINGING

                val isIncoming = (activeCall?.callerNumber != currentUser?.phoneNumber) ||
                        (currentUser?.phoneNumber != null && com.example.util.ContactsHelper.numbersMatch(activeCall?.calleeNumber ?: "", currentUser?.phoneNumber ?: ""))
                val otherPartyNumber = if (isIncoming) (activeCall?.callerNumber ?: "") else (activeCall?.calleeNumber ?: "")
                val otherPartyUser = remember(otherPartyNumber) {
                    if (otherPartyNumber.isNotBlank()) firebaseManager.lookupUserByNumber(otherPartyNumber) else null
                }
                val otherPartyName = if (isIncoming) (activeCall?.callerName ?: "") else (activeCall?.calleeName ?: "")
                val otherPartyDisplayName = otherPartyUser?.displayName?.takeIf { it.isNotBlank() } ?: otherPartyName.takeIf { it.isNotBlank() } ?: otherPartyNumber
                val otherPartyProfilePic = otherPartyUser?.profilePictureUrl ?: ""
                val direction = if (isIncoming) CallDirection.INCOMING else CallDirection.OUTGOING

                Box(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // WhatsApp-style persistent in-app ongoing call banner when call is minimized
                        if (isCallActive && isCallMinimized && activeCall != null) {
                            OngoingCallTopBar(
                                activeCall = activeCall,
                                rtcState = rtcState,
                                webRtcEngine = webRtcEngine,
                                displayName = otherPartyDisplayName,
                                displayNumber = otherPartyNumber,
                                onExpand = { isCallMinimized = false },
                                onEndCall = {
                                    firebaseManager.logCall(
                                        direction = direction,
                                        otherPartyNumber = otherPartyNumber,
                                        otherPartyName = otherPartyDisplayName,
                                        callType = activeCall.callType,
                                        status = CallStatus.ENDED,
                                        durationSeconds = rtcState.callDurationSeconds,
                                        callId = activeCall.callId
                                    )
                                    webRtcEngine.endCall()
                                }
                            )
                        }

                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            when (navState) {
                                AppNavState.WELCOME -> {
                                    WelcomeScreen(
                                        onGetStartedClick = { navState = AppNavState.PHONE_INPUT }
                                    )
                                }
                                AppNavState.PHONE_INPUT -> {
                                    PhoneInputScreen(
                                        firebaseManager = firebaseManager,
                                        onLoginSuccess = { user ->
                                            navState = AppNavState.MAIN
                                        },
                                        onNewUser = { phone, deviceId ->
                                            newPhoneNumber = phone
                                            newDeviceId = deviceId
                                            navState = AppNavState.PROFILE_SETUP
                                        },
                                        onBackClick = { navState = AppNavState.WELCOME }
                                    )
                                }
                                AppNavState.PROFILE_SETUP -> {
                                    ProfileSetupScreen(
                                        phoneNumber = newPhoneNumber,
                                        deviceId = newDeviceId,
                                        onProfileComplete = { name, status ->
                                            firebaseManager.loginWithPhone(newPhoneNumber, name, newDeviceId, status)
                                            navState = AppNavState.MAIN
                                        }
                                    )
                                }
                                AppNavState.SETTINGS -> {
                                    SettingsScreen(
                                        firebaseManager = firebaseManager,
                                        onBackClick = { navState = AppNavState.MAIN }
                                    )
                                }
                                AppNavState.MAIN -> {
                                    Scaffold(
                                        modifier = Modifier.fillMaxSize(),
                                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                                        bottomBar = {
                                            NavigationBar(
                                                containerColor = MaterialTheme.colorScheme.surface,
                                                tonalElevation = 6.dp
                                            ) {
                                                MainTab.entries.forEach { tab ->
                                                    val isSelected = selectedTab == tab
                                                    NavigationBarItem(
                                                        selected = isSelected,
                                                        onClick = { selectedTab = tab },
                                                        icon = {
                                                            if (tab == MainTab.CHATS && totalUnreadChats > 0) {
                                                                BadgedBox(
                                                                    badge = {
                                                                        Badge(
                                                                            containerColor = com.example.ui.theme.GreenCall,
                                                                            contentColor = Color.White
                                                                        ) {
                                                                            Text(if (totalUnreadChats > 99) "99+" else totalUnreadChats.toString())
                                                                        }
                                                                    }
                                                                ) {
                                                                    Icon(tab.icon, contentDescription = tab.title)
                                                                }
                                                            } else {
                                                                Icon(tab.icon, contentDescription = tab.title)
                                                            }
                                                        },
                                                        label = { 
                                                            Text(
                                                                text = tab.title,
                                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                                            ) 
                                                        },
                                                        colors = NavigationBarItemDefaults.colors(
                                                            selectedIconColor = currentThemeColor.primary,
                                                            selectedTextColor = currentThemeColor.primary,
                                                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            indicatorColor = currentThemeColor.primary.copy(alpha = 0.18f)
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    ) { mainPadding ->
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(bottom = mainPadding.calculateBottomPadding())
                                        ) {
                                            when (selectedTab) {
                                                MainTab.DIALER -> DialerScreen(
                                                    firebaseManager = firebaseManager,
                                                    onStartCall = { number, name, type ->
                                                        if (isCallActive) {
                                                            isCallMinimized = false
                                                        } else {
                                                            val myNum = currentUser?.phoneNumber ?: return@DialerScreen
                                                            val myName = currentUser?.displayName ?: "Me"
                                                            webRtcEngine.initiateCall(
                                                                calleeNumber = number,
                                                                calleeName = name,
                                                                callerNumber = myNum,
                                                                callerName = myName,
                                                                callType = type
                                                            )
                                                        }
                                                    },
                                                    onNavigateToSettings = { navState = AppNavState.SETTINGS }
                                                )
                                                MainTab.RECENTS -> CallHistoryScreen(
                                                    firebaseManager = firebaseManager,
                                                    onStartCall = { number, name, type ->
                                                        if (isCallActive) {
                                                            isCallMinimized = false
                                                        } else {
                                                            val myNum = currentUser?.phoneNumber ?: return@CallHistoryScreen
                                                            val myName = currentUser?.displayName ?: "Me"
                                                            webRtcEngine.initiateCall(number, name, myNum, myName, type)
                                                        }
                                                    },
                                                    onOpenChat = { number, name ->
                                                        chatPeerNumber = number
                                                        chatPeerName = name
                                                        navState = AppNavState.CHAT_CONVERSATION
                                                    }
                                                )
                                                MainTab.CHATS -> ChatListScreen(
                                                    firebaseManager = firebaseManager,
                                                    activeCallNumber = if (isCallActive) otherPartyNumber else null,
                                                    activeCallType = if (isCallActive) activeCall?.callType else null,
                                                    onOpenConversation = { phone, name, avatar ->
                                                        chatPeerNumber = phone
                                                        chatPeerName = name
                                                        chatPeerAvatar = avatar
                                                        navState = AppNavState.CHAT_CONVERSATION
                                                    }
                                                )
                                                MainTab.CONTACTS -> ContactsScreen(
                                                    firebaseManager = firebaseManager,
                                                    onStartCall = { number, name, type ->
                                                        if (isCallActive) {
                                                            isCallMinimized = false
                                                        } else {
                                                            val myNum = currentUser?.phoneNumber ?: return@ContactsScreen
                                                            val myName = currentUser?.displayName ?: "Me"
                                                            webRtcEngine.initiateCall(number, name, myNum, myName, type)
                                                        }
                                                    },
                                                    onOpenChat = { number, name ->
                                                        chatPeerNumber = number
                                                        chatPeerName = name
                                                        navState = AppNavState.CHAT_CONVERSATION
                                                    }
                                                )
                                                MainTab.PROFILE -> ProfileScreen(
                                                    firebaseManager = firebaseManager
                                                )
                                            }
                                        }
                                    }
                                }
                                AppNavState.CHAT_CONVERSATION -> {
                                    androidx.activity.compose.BackHandler {
                                        navState = AppNavState.MAIN
                                        selectedTab = MainTab.CHATS
                                    }
                                    ChatConversationScreen(
                                        peerPhoneNumber = chatPeerNumber,
                                        peerDisplayName = chatPeerName,
                                        peerInitialAvatar = chatPeerAvatar,
                                        firebaseManager = firebaseManager,
                                        onBackClick = {
                                            navState = AppNavState.MAIN
                                            selectedTab = MainTab.CHATS
                                        },
                                        onStartCall = { number, name, type ->
                                            if (isCallActive) {
                                                isCallMinimized = false
                                            } else {
                                                val myNum = currentUser?.phoneNumber ?: return@ChatConversationScreen
                                                val myName = currentUser?.displayName ?: "Me"
                                                webRtcEngine.initiateCall(number, name, myNum, myName, type)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Full Screen Calling Overlays (shown when not minimized)
                    if (activeCall != null && rtcState.callStatus != CallStatus.IDLE && (!isCallMinimized || isCallRinging)) {
                        androidx.activity.compose.BackHandler {
                            if (rtcState.callStatus == CallStatus.ANSWERED || rtcState.callStatus == CallStatus.CALLING) {
                                isCallMinimized = true
                            } else {
                                (context as? android.app.Activity)?.moveTaskToBack(true)
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) { detectTapGestures { } }
                        ) {
                            when (rtcState.callStatus) {
                                CallStatus.CALLING -> {
                                    OutgoingCallScreen(
                                        calleeName = otherPartyDisplayName,
                                        calleeNumber = activeCall.calleeNumber,
                                        profilePicUrl = otherPartyProfilePic,
                                        callType = activeCall.callType,
                                        statusText = rtcState.connectionStatusText,
                                        webRtcEngine = webRtcEngine,
                                        onMinimize = { isCallMinimized = true },
                                        onEndCall = {
                                            firebaseManager.logCall(
                                                direction = CallDirection.OUTGOING,
                                                otherPartyNumber = activeCall.calleeNumber,
                                                otherPartyName = otherPartyDisplayName,
                                                callType = activeCall.callType,
                                                status = CallStatus.ENDED,
                                                durationSeconds = rtcState.callDurationSeconds,
                                                callId = activeCall.callId
                                            )
                                            webRtcEngine.endCall()
                                        }
                                    )
                                }
                                CallStatus.RINGING -> {
                                    if (isIncoming) {
                                        IncomingCallOverlay(
                                            callerName = activeCall.callerName,
                                            callerNumber = activeCall.callerNumber,
                                            profilePicUrl = otherPartyProfilePic,
                                            callType = activeCall.callType,
                                            onAnswer = { webRtcEngine.answerCall() },
                                            onDecline = {
                                                firebaseManager.logCall(
                                                    direction = CallDirection.INCOMING,
                                                    otherPartyNumber = activeCall.callerNumber,
                                                    otherPartyName = activeCall.callerName,
                                                    callType = activeCall.callType,
                                                    status = CallStatus.DECLINED,
                                                    durationSeconds = rtcState.callDurationSeconds,
                                                    callId = activeCall.callId
                                                )
                                                webRtcEngine.declineCall()
                                            }
                                        )
                                    } else {
                                        OutgoingCallScreen(
                                            calleeName = otherPartyDisplayName,
                                            calleeNumber = activeCall.calleeNumber,
                                            profilePicUrl = otherPartyProfilePic,
                                            callType = activeCall.callType,
                                            statusText = rtcState.connectionStatusText,
                                            webRtcEngine = webRtcEngine,
                                            onMinimize = { isCallMinimized = true },
                                            onEndCall = {
                                                firebaseManager.logCall(
                                                    direction = CallDirection.OUTGOING,
                                                    otherPartyNumber = activeCall.calleeNumber,
                                                    otherPartyName = otherPartyDisplayName,
                                                    callType = activeCall.callType,
                                                    status = CallStatus.ENDED,
                                                    durationSeconds = rtcState.callDurationSeconds,
                                                    callId = activeCall.callId
                                                )
                                                webRtcEngine.endCall()
                                            }
                                        )
                                    }
                                }
                                CallStatus.ANSWERED -> {
                                    if (rtcState.callType == CallType.VIDEO || activeCall.callType == CallType.VIDEO) {
                                        ActiveVideoCallScreen(
                                            state = rtcState,
                                            profilePicUrl = otherPartyProfilePic,
                                            displayName = otherPartyDisplayName,
                                            displayNumber = otherPartyNumber,
                                            webRtcEngine = webRtcEngine,
                                            onMinimize = { isCallMinimized = true },
                                            onEndCall = {
                                                firebaseManager.logCall(
                                                    direction = direction,
                                                    otherPartyNumber = otherPartyNumber,
                                                    otherPartyName = otherPartyDisplayName,
                                                    callType = activeCall.callType,
                                                    status = CallStatus.ENDED,
                                                    durationSeconds = rtcState.callDurationSeconds,
                                                    callId = activeCall.callId
                                                )
                                                webRtcEngine.endCall()
                                            }
                                        )
                                    } else {
                                        ActiveAudioCallScreen(
                                            state = rtcState,
                                            profilePicUrl = otherPartyProfilePic,
                                            displayName = otherPartyDisplayName,
                                            displayNumber = otherPartyNumber,
                                            webRtcEngine = webRtcEngine,
                                            onMinimize = { isCallMinimized = true },
                                            onEndCall = {
                                                firebaseManager.logCall(
                                                    direction = direction,
                                                    otherPartyNumber = otherPartyNumber,
                                                    otherPartyName = otherPartyDisplayName,
                                                    callType = activeCall.callType,
                                                    status = CallStatus.ENDED,
                                                    durationSeconds = rtcState.callDurationSeconds,
                                                    callId = activeCall.callId
                                                )
                                                webRtcEngine.endCall()
                                            }
                                        )
                                    }
                                }

                                else -> {}
                            }
                        }
                    }
                }
                    
                    // Show Update Dialog if needed
                    updateInfo?.let { info ->
                        UpdateDialog(
                            updateInfo = info,
                            downloadState = downloadState,
                            onDownloadClick = {
                                gitHubUpdater.downloadUpdate(info.downloadUrl, info.latestVersion)
                            },
                            onDismissRequest = {
                                updateInfo = null
                            }
                        )
                }
            }
        }
    }
}
