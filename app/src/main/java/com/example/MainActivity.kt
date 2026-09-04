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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput

enum class MainTab(val title: String, val icon: ImageVector) {
    DIALER("Dialer", Icons.Default.Dialpad),
    RECENTS("Recents", Icons.Default.History),
    CONTACTS("Contacts", Icons.Default.People),
    PROFILE("Account", Icons.Default.Person)
}

enum class AppNavState {
    WELCOME,
    PHONE_INPUT,
    PROFILE_SETUP,
    MAIN,
    SETTINGS
}

class MainActivity : ComponentActivity() {

    companion object {
        @Volatile
        var isForeground: Boolean = false
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
        // Dismiss floating pill when user is viewing the full-screen MainActivity
        com.example.services.FloatingCallBubbleService.hide(this)
        
        // Cancel the redundant heads-up notification card immediately with retries so it NEVER covers the full-screen UI
        dismissIncomingCallNotificationBanner()
    }

    private fun dismissIncomingCallNotificationBanner() {
        val nm = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager ?: return
        nm.cancel(1001)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed({ nm.cancel(1001) }, 300L)
        handler.postDelayed({ nm.cancel(1001) }, 800L)
        handler.postDelayed({ nm.cancel(1001) }, 1500L)
    }

    override fun onPause() {
        super.onPause()
        isForeground = false
    }

    override fun onDestroy() {
        super.onDestroy()
        isForeground = false
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        triggerFloatingCallBubbleIfActive()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            triggerFloatingCallBubbleIfActive()
        }
    }

    private fun triggerFloatingCallBubbleIfActive() {
        isForeground = false
        val rtcState = com.example.webrtc.WebRtcEngine.getInstanceIfCreated()?.state?.value ?: return
        val activeCall = rtcState.activeCall ?: return

        if ((rtcState.callStatus == com.example.data.model.CallStatus.ANSWERED || rtcState.callStatus == com.example.data.model.CallStatus.CALLING)) {
            if (rtcState.callType == com.example.data.model.CallType.VIDEO && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().build())
                } catch (_: Exception) {}
            }
            // Show Draggable Active Call Pill over other apps
            com.example.services.FloatingCallBubbleService.showActive(
                this,
                activeCall.callId,
                activeCall.callerName,
                activeCall.callerNumber,
                activeCall.callType
            )
        } else if (rtcState.callStatus == com.example.data.model.CallStatus.RINGING) {
            // Show Incoming Call Pill over other apps if user backgrounds the app during ringing
            com.example.services.FloatingCallBubbleService.showIncoming(
                this,
                activeCall.callId,
                activeCall.callerName,
                activeCall.callerNumber,
                activeCall.callType
            )
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
                        com.example.services.FloatingCallBubbleService.hide(context)
                        (context as? MainActivity)?.dismissIncomingCallNotificationBanner()
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
                    }

                    if (rtcState.callStatus != com.example.data.model.CallStatus.IDLE && rtcState.callStatus != com.example.data.model.CallStatus.MISSED && rtcState.callStatus != com.example.data.model.CallStatus.ENDED) {
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
                    }
                    
                    // Check for updates
                    updateInfo = gitHubUpdater.checkForUpdates()
                }

                // State declarations MUST come before any LaunchedEffects that use them
                var navState by remember {
                    mutableStateOf(if (currentUser != null) AppNavState.MAIN else AppNavState.WELCOME)
                }
                var selectedTab by remember { mutableStateOf(MainTab.DIALER) }
                var newPhoneNumber by remember { mutableStateOf("") }
                var newDeviceId by remember { mutableStateOf("") }

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
                            selectedTab = MainTab.RECENTS
                        }

                        val callBackNumber = incoming.getStringExtra("call_back_number")
                        if (!callBackNumber.isNullOrBlank() && hasMicPermission) {
                            val myNum = currentUser?.phoneNumber ?: ""
                            val myName = currentUser?.displayName ?: "Me"
                            val callBackName = incoming.getStringExtra("call_back_name") ?: callBackNumber
                            val callBackTypeStr = incoming.getStringExtra("call_back_type") ?: "AUDIO"
                            val callBackType = try { CallType.valueOf(callBackTypeStr) } catch (_: Exception) { CallType.AUDIO }
                            if (myNum.isNotBlank()) {
                                webRtcEngine.initiateCall(callBackNumber, callBackName, myNum, myName, callBackType)
                            }
                        }
                        
                        _incomingIntent.value = null
                    }
                }

                // Check active call overlay
                val activeCall = rtcState.activeCall

                Box(modifier = Modifier.fillMaxSize()) {
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
                                                icon = { Icon(tab.icon, contentDescription = tab.title) },
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
                                                // BUG-17 FIX: Don't allow calls without a valid caller number
                                                val myNum = currentUser?.phoneNumber ?: return@DialerScreen
                                                val myName = currentUser?.displayName ?: "Me"
                                                webRtcEngine.initiateCall(
                                                    calleeNumber = number,
                                                    calleeName = name,
                                                    callerNumber = myNum,
                                                    callerName = myName,
                                                    callType = type
                                                )
                                            },
                                            onNavigateToSettings = { navState = AppNavState.SETTINGS }
                                        )
                                        MainTab.RECENTS -> CallHistoryScreen(
                                            firebaseManager = firebaseManager,
                                            onStartCall = { number, name, type ->
                                                val myNum = currentUser?.phoneNumber ?: return@CallHistoryScreen
                                                val myName = currentUser?.displayName ?: "Me"
                                                webRtcEngine.initiateCall(number, name, myNum, myName, type)
                                            }
                                        )
                                        MainTab.CONTACTS -> ContactsScreen(
                                            firebaseManager = firebaseManager,
                                            onStartCall = { number, name, type ->
                                                val myNum = currentUser?.phoneNumber ?: return@ContactsScreen
                                                val myName = currentUser?.displayName ?: "Me"
                                                webRtcEngine.initiateCall(number, name, myNum, myName, type)
                                            }
                                        )
                                        MainTab.PROFILE -> ProfileScreen(
                                            firebaseManager = firebaseManager
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Full Screen Calling Overlays
                    if (activeCall != null && rtcState.callStatus != CallStatus.IDLE) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) { detectTapGestures { } }
                        ) {
                        val isIncoming = (activeCall.callerNumber != currentUser?.phoneNumber) ||
                                (currentUser?.phoneNumber != null && com.example.util.ContactsHelper.numbersMatch(activeCall.calleeNumber, currentUser?.phoneNumber ?: ""))
                        val otherPartyNumber = if (isIncoming) activeCall.callerNumber else activeCall.calleeNumber
                        val otherPartyUser = firebaseManager.lookupUserByNumber(otherPartyNumber)
                        val otherPartyProfilePic = otherPartyUser?.profilePictureUrl ?: ""
                        
                        when (rtcState.callStatus) {
                            CallStatus.CALLING -> {
                                OutgoingCallScreen(
                                    calleeName = activeCall.calleeName,
                                    calleeNumber = activeCall.calleeNumber,
                                    profilePicUrl = otherPartyProfilePic,
                                    callType = activeCall.callType,
                                    statusText = rtcState.connectionStatusText,
                                    webRtcEngine = webRtcEngine,
                                    onEndCall = {
                                        firebaseManager.logCall(
                                            direction = CallDirection.OUTGOING,
                                            otherPartyNumber = activeCall.calleeNumber,
                                            otherPartyName = activeCall.calleeName,
                                            callType = activeCall.callType,
                                            status = CallStatus.ENDED,
                                            durationSeconds = rtcState.callDurationSeconds
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
                                                durationSeconds = rtcState.callDurationSeconds
                                            )
                                            webRtcEngine.endCall()
                                        }
                                    )
                                } else {
                                    OutgoingCallScreen(
                                        calleeName = activeCall.calleeName,
                                        calleeNumber = activeCall.calleeNumber,
                                        profilePicUrl = otherPartyProfilePic,
                                        callType = activeCall.callType,
                                        statusText = rtcState.connectionStatusText,
                                        webRtcEngine = webRtcEngine,
                                        onEndCall = {
                                            firebaseManager.logCall(
                                                direction = CallDirection.OUTGOING,
                                                otherPartyNumber = activeCall.calleeNumber,
                                                otherPartyName = activeCall.calleeName,
                                                callType = activeCall.callType,
                                                status = CallStatus.ENDED,
                                                durationSeconds = rtcState.callDurationSeconds
                                            )
                                            webRtcEngine.endCall()
                                        }
                                    )
                                }
                            }
                            CallStatus.ANSWERED -> {
                                val direction = if (isIncoming) CallDirection.INCOMING else CallDirection.OUTGOING
                                val otherNumber = if (isIncoming) activeCall.callerNumber else activeCall.calleeNumber
                                val otherName = if (isIncoming) activeCall.callerName else activeCall.calleeName
                                
                                if (activeCall.callType == CallType.VIDEO) {
                                    ActiveVideoCallScreen(
                                        state = rtcState,
                                        profilePicUrl = otherPartyProfilePic,
                                        // BUG-26 FIX: Show the OTHER party's name, not own name
                                        displayName = otherName,
                                        displayNumber = otherNumber,
                                        webRtcEngine = webRtcEngine,
                                        onEndCall = {
                                            firebaseManager.logCall(
                                                direction = direction,
                                                otherPartyNumber = otherNumber,
                                                otherPartyName = otherName,
                                                callType = activeCall.callType,
                                                // BUG-21 FIX: Log as ENDED not ANSWERED
                                                status = CallStatus.ENDED,
                                                durationSeconds = rtcState.callDurationSeconds
                                            )
                                            webRtcEngine.endCall()
                                        }
                                    )
                                } else {
                                    ActiveAudioCallScreen(
                                        state = rtcState,
                                        profilePicUrl = otherPartyProfilePic,
                                        // BUG-26 FIX: Show the OTHER party's name, not own name
                                        displayName = otherName,
                                        displayNumber = otherNumber,
                                        webRtcEngine = webRtcEngine,
                                        onEndCall = {
                                            firebaseManager.logCall(
                                                direction = direction,
                                                otherPartyNumber = otherNumber,
                                                otherPartyName = otherName,
                                                callType = activeCall.callType,
                                                // BUG-21 FIX: Log as ENDED not ANSWERED
                                                status = CallStatus.ENDED,
                                                durationSeconds = rtcState.callDurationSeconds
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
}
