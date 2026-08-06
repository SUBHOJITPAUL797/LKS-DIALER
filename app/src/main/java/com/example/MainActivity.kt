package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            LksDialerTheme {
                val context = LocalContext.current
                val firebaseManager = remember { FirebaseManager.getInstance(context) }
                val webRtcEngine = remember { WebRtcEngine.getInstance(context) }

                val currentUser by firebaseManager.currentUser.collectAsState()
                val rtcState by webRtcEngine.state.collectAsState()

                LaunchedEffect(currentUser?.phoneNumber) {
                    currentUser?.phoneNumber?.let {
                        webRtcEngine.listenForIncomingCalls(it)
                    }
                }

                var navState by remember {
                    mutableStateOf(if (currentUser != null) AppNavState.MAIN else AppNavState.WELCOME)
                }
                var selectedTab by remember { mutableStateOf(MainTab.DIALER) }
                var newPhoneNumber by remember { mutableStateOf("") }
                var newDeviceId by remember { mutableStateOf("") }

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
                                    onProfileComplete = { name ->
                                        firebaseManager.loginWithPhone(newPhoneNumber, name, newDeviceId)
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
                                                    val myNum = currentUser?.phoneNumber ?: "+919999999999"
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
                                                    val myNum = currentUser?.phoneNumber ?: "+919999999999"
                                                    val myName = currentUser?.displayName ?: "Me"
                                                    webRtcEngine.initiateCall(number, name, myNum, myName, type)
                                                }
                                            )
                                            MainTab.CONTACTS -> ContactsScreen(
                                                firebaseManager = firebaseManager,
                                                onStartCall = { number, name, type ->
                                                    val myNum = currentUser?.phoneNumber ?: "+919999999999"
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
                            
                            when (rtcState.callStatus) {
                                CallStatus.CALLING -> {
                                    OutgoingCallScreen(
                                        calleeName = activeCall.calleeName,
                                        calleeNumber = activeCall.calleeNumber,
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
                                            webRtcEngine = webRtcEngine,
                                            onEndCall = {
                                                firebaseManager.logCall(
                                                    direction = direction,
                                                    otherPartyNumber = otherNumber,
                                                    otherPartyName = otherName,
                                                    callType = activeCall.callType,
                                                    status = CallStatus.ANSWERED,
                                                    durationSeconds = rtcState.callDurationSeconds
                                                )
                                                webRtcEngine.endCall()
                                            }
                                        )
                                    } else {
                                        ActiveAudioCallScreen(
                                            state = rtcState,
                                            webRtcEngine = webRtcEngine,
                                            onEndCall = {
                                                firebaseManager.logCall(
                                                    direction = direction,
                                                    otherPartyNumber = otherNumber,
                                                    otherPartyName = otherName,
                                                    callType = activeCall.callType,
                                                    status = CallStatus.ANSWERED,
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
                }
            }
        }
    }
}
