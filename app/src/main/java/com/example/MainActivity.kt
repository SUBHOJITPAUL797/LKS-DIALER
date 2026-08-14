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
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.os.Build

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
import com.example.ui.components.UpdateDialog

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

    // Needed so FLAG_ACTIVITY_SINGLE_TOP re-delivers the intent
    // when the activity is already running (e.g. user taps Accept while app is open)
    private val _incomingIntent = androidx.compose.runtime.mutableStateOf<android.content.Intent?>(null)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        _incomingIntent.value = intent
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Wake up screen on incoming call
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        // Pass the launch intent in so the Compose side can read it
        _incomingIntent.value = intent
        enableEdgeToEdge()

        setContent {
            LksDialerTheme {
                val context = LocalContext.current
                val firebaseManager = remember { FirebaseManager.getInstance(context) }
                val webRtcEngine = remember { WebRtcEngine.getInstance(context) }

                val currentUser by firebaseManager.currentUser.collectAsState()
                val rtcState by webRtcEngine.state.collectAsState()
                
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { _ -> }

                LaunchedEffect(Unit) {
                    permissionLauncher.launch(permissions.toTypedArray())
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        val notificationManager = context.getSystemService(android.app.NotificationManager::class.java)
                        if (!notificationManager.canUseFullScreenIntent()) {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                                data = android.net.Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
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
                            // Only auto-answer if the microphone permission is already granted, otherwise let the user see the incoming call screen and grant permission first
                            val safeAutoAnswer = autoAnswer && hasMicPermission
                            webRtcEngine.attachToCall(
                                callId = callId, 
                                autoAnswer = safeAutoAnswer,
                                callerName = callerName,
                                callerNumber = callerNumber,
                                callTypeStr = callType
                            )
                        } else if (autoAnswer && rtcState.activeCall != null && hasMicPermission) {
                            webRtcEngine.answerCall()
                        }
                    }
                }

                // Check active call overlay
                val activeCall = rtcState.activeCall

                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
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
                                    bottomBar = {
                                        NavigationBar {
                                            MainTab.entries.forEach { tab ->
                                                NavigationBarItem(
                                                    selected = selectedTab == tab,
                                                    onClick = { selectedTab = tab },
                                                    icon = { Icon(tab.icon, contentDescription = tab.title) },
                                                    label = { Text(tab.title) },
                                                    colors = NavigationBarItemDefaults.colors(
                                                        selectedIconColor = TealPrimary,
                                                        indicatorColor = TealPrimary.copy(alpha = 0.15f)
                                                    )
                                                )
                                            }
                                        }
                                    }
                                ) { mainPadding ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(mainPadding)
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
                            val isIncoming = activeCall.calleeNumber == currentUser?.phoneNumber
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
