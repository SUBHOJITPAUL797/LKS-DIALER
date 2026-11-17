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
import android.util.Log
import android.view.WindowManager

import com.example.data.model.CallDirection
import com.example.data.model.CallStatus
import com.example.data.model.CallType
import com.example.data.model.ContactDto
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
import com.example.webrtc.GroupCallEngine
import com.example.util.GitHubUpdater
import com.example.util.UpdateInfo
import com.example.util.LksIncomingRingtonePlayer
import com.example.ui.components.UpdateDialog
import com.example.ui.components.OngoingCallTopBar
import com.example.ui.components.surfaceFinishOverlay
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput

import com.example.data.repository.ChatRepository
import com.example.ui.screens.chat.ChatListScreen
import com.example.ui.screens.chat.ChatConversationScreen
import com.example.ui.screens.chat.ChatStorageManagementScreen
import com.example.ui.screens.settings.LinkedDevicesScreen

enum class MainTab(val title: String, val icon: ImageVector) {
    DIALER("Dialer", Icons.Default.Dialpad),
    RECENTS("Recents", Icons.Default.History),
    CHATS("Chats", Icons.Default.ChatBubbleOutline),
    CONTACTS("Contacts", Icons.Default.People),
    PROFILE("Account", Icons.Default.Person)
}

private sealed interface AppRoute {
    data object Welcome : AppRoute
    data object PhoneInput : AppRoute
    data class ProfileSetup(val phoneNumber: String, val deviceId: String) : AppRoute
    data class Main(val tab: MainTab = MainTab.DIALER) : AppRoute
    data class Settings(
        val initialCategory: com.example.ui.screens.settings.SettingsCategory =
            com.example.ui.screens.settings.SettingsCategory.ALL
    ) : AppRoute
    data class ChatConversation(
        val peerNumber: String,
        val peerName: String,
        val peerAvatar: String,
        val returnRoute: AppRoute
    ) : AppRoute
    data class StorageManagement(val returnRoute: AppRoute) : AppRoute
    data class LinkedDevices(val returnRoute: AppRoute) : AppRoute
}

private sealed interface ShareIntakeState {
    data object None : ShareIntakeState
    data class NeedsTargetPicker(val payload: com.example.util.SharedIncomingPayload) : ShareIntakeState
    data class ReadyForChat(
        val photos: List<java.io.File>? = null,
        val text: String? = null,
        val documents: List<Pair<java.io.File, String>>? = null
    ) : ShareIntakeState
}

private sealed interface GroupCallPickerState {
    data object Hidden : GroupCallPickerState
    data class Open(val preselectedPeer: ContactDto? = null) : GroupCallPickerState
}

private sealed interface UpdateModalPresentationState {
    data object Hidden : UpdateModalPresentationState
    data class DeferredAuto(val info: UpdateInfo) : UpdateModalPresentationState
    data class VisibleAuto(val info: UpdateInfo) : UpdateModalPresentationState
    data class VisibleExplicit(val info: UpdateInfo) : UpdateModalPresentationState

    val visibleInfo: UpdateInfo?
        get() = when (this) {
            is VisibleAuto -> info
            is VisibleExplicit -> info
            else -> null
        }
}

private fun com.example.util.SharedIncomingPayload.toReadyShareState(): ShareIntakeState.ReadyForChat =
    when (type) {
        com.example.util.SharedPayloadType.IMAGES -> ShareIntakeState.ReadyForChat(
            photos = files,
            text = text
        )
        com.example.util.SharedPayloadType.TEXT -> ShareIntakeState.ReadyForChat(text = text)
        com.example.util.SharedPayloadType.DOCUMENTS -> ShareIntakeState.ReadyForChat(
            documents = files.mapIndexed { idx, file ->
                Pair(file, originalNames.getOrNull(idx) ?: file.name)
            }
        )
    }

class MainActivity : ComponentActivity() {

    companion object {
        @Volatile
        var isForeground: Boolean = false
            private set

        @Volatile
        var isInPipMode: Boolean = false
            private set

        @Volatile
        var lastUserInteractionAtMillis: Long = 0L
            private set
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        lastUserInteractionAtMillis = System.currentTimeMillis()
        return super.dispatchTouchEvent(ev)
    }

    // Needed so FLAG_ACTIVITY_SINGLE_TOP re-delivers the intent
    // when the activity is already running (e.g. user taps Accept while app is open)
    private val _incomingIntent = androidx.compose.runtime.mutableStateOf<android.content.Intent?>(null)

    // Shake-to-Report Crash & Diagnostic Logger
    private var shakeDetector: com.example.util.logging.ShakeDetector? = null
    private val _showShakeDialog = androidx.compose.runtime.mutableStateOf(false)

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
        shakeDetector?.start()
        com.example.data.repository.FirebaseManager.getInstance(this).startPresenceHeartbeat()
        com.example.data.repository.ChatRepository.getInstance(this).setAppForeground(true)
        com.example.data.repository.ChatRepository.getInstance(this).fetchPendingMessagesAndReceipts()

        // Seamless handoff: If user opened the app from the launcher while a call was incoming/active in the floating pill, adopt it!
        val bubbleCallId = com.example.services.FloatingCallBubbleService.currentCallId
        if (bubbleCallId.isNotBlank()) {
            val engine = com.example.webrtc.WebRtcEngine.getInstanceIfCreated() ?: com.example.webrtc.WebRtcEngine.getInstance(this)
            val currCall = engine.state.value.activeCall
            val isFinishedOrDifferent = currCall == null ||
                currCall.callId != bubbleCallId ||
                engine.state.value.callStatus == CallStatus.IDLE ||
                engine.state.value.callStatus == CallStatus.ENDED ||
                engine.state.value.callStatus == CallStatus.MISSED ||
                engine.state.value.callStatus == CallStatus.DECLINED
            if (isFinishedOrDifferent) {
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
        com.example.data.repository.ChatRepository.getInstance(this).fetchPendingMessagesAndReceipts()
    }

    override fun onPause() {
        super.onPause()
        isForeground = false
        shakeDetector?.stop()
        com.example.data.repository.FirebaseManager.getInstance(this).stopPresenceHeartbeat()
        com.example.data.repository.ChatRepository.getInstance(this).setAppForeground(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        isForeground = false
        isInPipMode = false
        shakeDetector?.stop()
        shakeDetector = null
        com.example.data.repository.ChatRepository.getInstance(this).setAppForeground(false)
        if (!isChangingConfigurations) {
            com.example.data.repository.FirebaseManager.getInstance(this).stopPresenceHeartbeat()
            val currentStatus = com.example.webrtc.WebRtcEngine.getInstanceIfCreated()?.state?.value?.callStatus
            if (currentStatus != com.example.data.model.CallStatus.RINGING) {
                com.example.util.LksIncomingRingtonePlayer.stop()
            }
            // User requested: When closing the PiP window via 'X', DO NOT end the call!
            // Continue the call in background and show the active floating pill instead!
            if (currentStatus == com.example.data.model.CallStatus.ANSWERED || currentStatus == com.example.data.model.CallStatus.CALLING || (currentStatus == com.example.data.model.CallStatus.RINGING && com.example.webrtc.WebRtcEngine.getInstanceIfCreated()?.state?.value?.isCaller == true)) {
                val rtcState = com.example.webrtc.WebRtcEngine.getInstanceIfCreated()?.state?.value
                val activeCall = rtcState?.activeCall
                if (activeCall != null && rtcState != null) {
                    val fb = com.example.data.repository.FirebaseManager.getInstance(this)
                    val myPhoneNorm = com.example.util.ContactsHelper.normalizePhoneNumber(fb.currentUser.value?.phoneNumber ?: "")
                    val callerNorm = com.example.util.ContactsHelper.normalizePhoneNumber(activeCall.callerNumber)
                    val isOutgoing = rtcState.isCaller || (myPhoneNorm.isNotBlank() && callerNorm == myPhoneNorm)
                    val peerNumber = if (isOutgoing) activeCall.calleeNumber else activeCall.callerNumber
                    val rawPeerName = if (isOutgoing) activeCall.calleeName else activeCall.callerName
                    val contactName = fb.syncedContacts.value.find { com.example.util.ContactsHelper.numbersMatch(it.phoneNumber, peerNumber) }?.name
                    val userName = fb.lookupUserByNumber(peerNumber)?.displayName
                    val resolvedPeerName = contactName?.takeIf { it.isNotBlank() }
                        ?: userName?.takeIf { it.isNotBlank() }
                        ?: rawPeerName.takeIf { it.isNotBlank() && !it.equals("Me", ignoreCase = true) }
                        ?: peerNumber
                    com.example.services.FloatingCallBubbleService.showActive(
                        this,
                        activeCall.callId,
                        resolvedPeerName,
                        peerNumber,
                        activeCall.callType
                    )
                }
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val rtcState = com.example.webrtc.WebRtcEngine.getInstanceIfCreated()?.state?.value
        if (rtcState != null && (rtcState.callStatus == com.example.data.model.CallStatus.ANSWERED || rtcState.callStatus == com.example.data.model.CallStatus.CALLING || (rtcState.callStatus == com.example.data.model.CallStatus.RINGING && rtcState.isCaller))) {
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
        isForeground = false
        com.example.data.repository.ChatRepository.getInstance(this).setAppForeground(false)
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

        val fb = com.example.data.repository.FirebaseManager.getInstance(this)
        val myPhoneNorm = com.example.util.ContactsHelper.normalizePhoneNumber(fb.currentUser.value?.phoneNumber ?: "")
        val callerNorm = com.example.util.ContactsHelper.normalizePhoneNumber(activeCall.callerNumber)
        val isOutgoing = rtcState.isCaller || (myPhoneNorm.isNotBlank() && callerNorm == myPhoneNorm)
        val peerNumber = if (isOutgoing) activeCall.calleeNumber else activeCall.callerNumber
        val rawPeerName = if (isOutgoing) activeCall.calleeName else activeCall.callerName
        val contactName = fb.syncedContacts.value.find { com.example.util.ContactsHelper.numbersMatch(it.phoneNumber, peerNumber) }?.name
        val userName = fb.lookupUserByNumber(peerNumber)?.displayName
        val resolvedPeerName = contactName?.takeIf { it.isNotBlank() }
            ?: userName?.takeIf { it.isNotBlank() }
            ?: rawPeerName.takeIf { it.isNotBlank() && !it.equals("Me", ignoreCase = true) }
            ?: peerNumber

        if (rtcState.callStatus == com.example.data.model.CallStatus.RINGING && !isOutgoing) {
            // Show Draggable Incoming Call Pill over home screen when app is minimized during ring
            com.example.services.FloatingCallBubbleService.showIncoming(
                this,
                activeCall.callId,
                resolvedPeerName,
                peerNumber,
                activeCall.callType
            )
        } else if (rtcState.callStatus == com.example.data.model.CallStatus.ANSWERED ||
                   rtcState.callStatus == com.example.data.model.CallStatus.CALLING ||
                   (rtcState.callStatus == com.example.data.model.CallStatus.RINGING && isOutgoing)) {
            // Show Draggable Active Call Pill over other apps (suppressed only if native PiP window is currently visible)
            if (!isInPipMode) {
                com.example.services.FloatingCallBubbleService.showActive(
                    this,
                    activeCall.callId,
                    resolvedPeerName,
                    peerNumber,
                    activeCall.callType
                )
            }
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        // Silence ringtone on volume button press during incoming call
        if (event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN ||
            event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_MUTE) {
            val isRingingNow = LksIncomingRingtonePlayer.isRinging ||
                (com.example.webrtc.WebRtcEngine.getInstanceIfCreated()?.state?.value?.let { 
                    it.callStatus == com.example.data.model.CallStatus.RINGING && 
                    it.activeCall?.calleeNumber == (com.example.data.repository.FirebaseManager.getInstance(this).currentUser.value?.phoneNumber ?: "")
                } ?: false)

            if (isRingingNow) {
                if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                    Log.i("MainActivity", "🔇 Volume key pressed in foreground during incoming call -> silencing ringtone & vibration")
                    LksIncomingRingtonePlayer.silence()
                    com.example.services.FloatingCallBubbleService.silenceRingtone(this)
                }
                // Consume both ACTION_DOWN and ACTION_UP so volume slider doesn't pop up
                return true
            }
        }
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            if (com.example.services.HeadsetButtonManager.handleHeadsetKeyEvent(event)) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == android.view.KeyEvent.KEYCODE_VOLUME_MUTE) {
            if (LksIncomingRingtonePlayer.isRinging) {
                Log.i("MainActivity", "🔇 onKeyDown volume key -> silencing ringtone & vibration")
                LksIncomingRingtonePlayer.silence()
                com.example.services.FloatingCallBubbleService.silenceRingtone(this)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == android.view.KeyEvent.KEYCODE_VOLUME_MUTE) {
            if (LksIncomingRingtonePlayer.isSilenced) {
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
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

        // Proactively fetch pending chat messages and receipts
        try {
            com.example.data.repository.ChatRepository.getInstance(this).fetchPendingMessagesAndReceipts()
        } catch (_: Exception) {}

        // Pass the launch intent in so the Compose side can read it
        _incomingIntent.value = intent
        enableEdgeToEdge()

        // Initialize Shake-to-Report Crash & Diagnostic Logger
        shakeDetector = com.example.util.logging.ShakeDetector(this) {
            _showShakeDialog.value = true
        }

        setContent {
            val context = LocalContext.current
            val themeManager = remember { com.example.ui.theme.ThemeManager.getInstance(context) }
            val currentThemeColor by themeManager.currentTheme.collectAsState()

            LksDialerTheme(themeColor = currentThemeColor) {
                val firebaseManager = remember { FirebaseManager.getInstance(context) }
                val webRtcEngine = remember { WebRtcEngine.getInstance(context) }

                val currentUser by firebaseManager.currentUser.collectAsState()
                val rtcState by webRtcEngine.state.collectAsState()
                
                LaunchedEffect(rtcState.callStatus, rtcState.activeCall, rtcState.isCaller) {
                    val window = (context as? android.app.Activity)?.window
                    val isIncomingRinging = rtcState.callStatus == com.example.data.model.CallStatus.RINGING && !rtcState.isCaller

                    if (isIncomingRinging) {
                        if (isForeground) {
                            com.example.services.FloatingCallBubbleService.hide(context)
                            (context as? MainActivity)?.dismissIncomingCallNotificationBanner()
                        }
                        if (!LksIncomingRingtonePlayer.isRinging && !LksIncomingRingtonePlayer.isSilenced) {
                            val callerNumber = rtcState.activeCall?.callerNumber ?: ""
                            val callId = rtcState.activeCall?.callId
                            LksIncomingRingtonePlayer.start(context, callerNumber, callId)
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
                var updateModalState by remember { mutableStateOf<UpdateModalPresentationState>(UpdateModalPresentationState.Hidden) }
                val downloadState by gitHubUpdater.downloadState.collectAsState()

                val permissions = mutableListOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.CAMERA,
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.READ_PHONE_NUMBERS,
                    Manifest.permission.CALL_PHONE
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
                    
                    // Resilient update check for mobile data users:
                    // Checks silently in background and queues into pendingAutoUpdateInfo (only shown when user is idle & not in a call)
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        try {
                            var info = gitHubUpdater.checkForUpdates()
                            if (info == null) {
                                kotlinx.coroutines.delay(4000)
                                info = gitHubUpdater.checkForUpdates()
                            }
                            if (info != null) {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    if (updateModalState is UpdateModalPresentationState.Hidden) {
                                        updateModalState = UpdateModalPresentationState.DeferredAuto(info)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.w("MainActivity", "Background update check failed: ${e.message}")
                        }
                    }
                }

                // Listen for network connectivity (e.g. mobile data turning on or reconnecting)
                // to silently queue update prompts when connectivity is established
                DisposableEffect(Unit) {
                    val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                    val callback = object : android.net.ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: android.net.Network) {
                            if (updateModalState is UpdateModalPresentationState.Hidden) {
                                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                    try {
                                        kotlinx.coroutines.delay(1200) // Brief link stabilization
                                        val info = gitHubUpdater.checkForUpdates()
                                        if (info != null) {
                                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                if (updateModalState is UpdateModalPresentationState.Hidden) {
                                                    updateModalState = UpdateModalPresentationState.DeferredAuto(info)
                                                }
                                            }
                                        }
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                    }
                    try {
                        cm?.registerDefaultNetworkCallback(callback)
                    } catch (_: Exception) {}
                    onDispose {
                        try {
                            cm?.unregisterNetworkCallback(callback)
                        } catch (_: Exception) {}
                    }
                }

                var currentRoute by remember {
                    mutableStateOf<AppRoute>(if (currentUser != null) AppRoute.Main() else AppRoute.Welcome)
                }
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

                // Shared incoming content from external apps (Share Sheet)
                var shareIntakeState by remember { mutableStateOf<ShareIntakeState>(ShareIntakeState.None) }

                // Synchronize Direct Share shortcuts for top recent conversations
                val conversationsForShortcuts by chatRepo.getConversationsFlow().collectAsState(initial = emptyList())
                LaunchedEffect(conversationsForShortcuts) {
                    if (conversationsForShortcuts.isNotEmpty()) {
                        com.example.util.ShareShortcutsManager.publishRecentChatShortcuts(context, conversationsForShortcuts)
                    }
                }

                // 1-Tap Universal OEM Autostart Dialog for Xiaomi, Samsung, OnePlus, Oppo, Vivo, etc.
                var showOemAutostartDialog by remember {
                    mutableStateOf(currentUser != null && com.example.util.UniversalOemAutostartHelper.isAggressiveOem() && com.example.util.UniversalOemAutostartHelper.isPromptNeeded(context))
                }

                if (showOemAutostartDialog) {
                    com.example.ui.components.OemAutostartDialog(
                        onDismiss = {
                            showOemAutostartDialog = false
                            com.example.util.UniversalOemAutostartHelper.markPromptDismissed(context)
                        }
                    )
                }

                LaunchedEffect(currentUser?.phoneNumber) {
                    currentUser?.phoneNumber?.let {
                        webRtcEngine.listenForIncomingCalls(it)
                    }
                }

                // BUG-06 FIX: Navigate to MAIN automatically if user is restored, or back to WELCOME on logout
                LaunchedEffect(currentUser) {
                    if (currentUser != null && currentRoute is AppRoute.Welcome) {
                        currentRoute = AppRoute.Main()
                    } else if (currentUser == null &&
                        currentRoute !is AppRoute.Welcome &&
                        currentRoute !is AppRoute.PhoneInput &&
                        currentRoute !is AppRoute.ProfileSetup
                    ) {
                        currentRoute = AppRoute.Welcome
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
                            if (autoAnswer && hasMicPermission) {
                                webRtcEngine.answerIncomingCall(
                                    callId = callId,
                                    callerName = callerName,
                                    callerNumber = callerNumber,
                                    callTypeStr = callType
                                )
                            } else if (rtcState.activeCall?.callId != callId) {
                                val isBusy = rtcState.activeCall != null &&
                                    (rtcState.callStatus == CallStatus.ANSWERED || rtcState.callStatus == CallStatus.CALLING || rtcState.callStatus == CallStatus.RINGING)
                                if (!isBusy) {
                                    webRtcEngine.attachToCall(
                                        callId = callId, 
                                        autoAnswer = false,
                                        callerName = callerName,
                                        callerNumber = callerNumber,
                                        callTypeStr = callType
                                    )
                                }
                            }
                        } else if (autoAnswer && rtcState.activeCall != null && hasMicPermission) {
                            webRtcEngine.answerCall()
                        }

                        val showUpdateDialog = incoming.getBooleanExtra("show_update_dialog", false)
                        if (showUpdateDialog) {
                            val version = incoming.getStringExtra("update_version") ?: ""
                            val notes = incoming.getStringExtra("update_notes") ?: ""
                            val url = incoming.getStringExtra("update_url") ?: ""
                            if (url.isNotBlank() && version.isNotBlank()) {
                                updateModalState = UpdateModalPresentationState.VisibleExplicit(
                                    UpdateInfo(
                                        isUpdateAvailable = true,
                                        latestVersion = version,
                                        releaseNotes = notes,
                                        downloadUrl = url,
                                        isMandatory = false
                                    )
                                )
                            }
                        }

                        val openTab = incoming.getStringExtra("open_tab")
                        if (openTab == "RECENTS") {
                            currentRoute = AppRoute.Main(MainTab.RECENTS)
                        } else if (openTab == "CHATS") {
                            currentRoute = AppRoute.Main(MainTab.CHATS)
                        }

                        val chatPeer = incoming.getStringExtra("chat_peer_number")
                        var resolvedChatPeerName = ""
                        if (!chatPeer.isNullOrBlank()) {
                            val isGroupPeer = chatPeer.startsWith("group_")
                            val peerUser = if (isGroupPeer) null else firebaseManager.lookupUserByNumber(chatPeer)
                            resolvedChatPeerName = incoming.getStringExtra("chat_peer_name")
                                ?: peerUser?.displayName?.takeIf { it.isNotBlank() }
                                ?: if (isGroupPeer) "Group" else chatPeer
                            val prefill = incoming.getStringExtra("prefill_text")
                            if (!prefill.isNullOrBlank()) {
                                shareIntakeState = ShareIntakeState.ReadyForChat(text = prefill)
                            }
                            val returnTarget = (currentRoute as? AppRoute.ChatConversation)?.returnRoute ?: currentRoute
                            currentRoute = AppRoute.ChatConversation(
                                peerNumber = chatPeer,
                                peerName = resolvedChatPeerName,
                                peerAvatar = peerUser?.profilePictureUrl ?: "",
                                returnRoute = returnTarget
                            )
                        }

                        val remoteInputResults = androidx.core.app.RemoteInput.getResultsFromIntent(incoming)
                        val replyText = remoteInputResults?.getCharSequence(ChatRepository.KEY_TEXT_REPLY)?.toString()
                        if (!replyText.isNullOrBlank() && !chatPeer.isNullOrBlank()) {
                            val normPeer = com.example.util.ContactsHelper.normalizePhoneNumber(chatPeer)
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                chatRepo.markConversationAsRead(normPeer)
                                chatRepo.sendMessage(
                                    recipientNumber = normPeer,
                                    recipientName = resolvedChatPeerName.ifBlank { normPeer },
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
                                currentRoute = AppRoute.Main()
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
                                    currentRoute = AppRoute.Main()
                                    kotlinx.coroutines.delay(200)
                                    webRtcEngine.initiateCall(targetNumber, targetNumber, myNum, myName, CallType.AUDIO)
                                }
                            }
                        }

                        // Android System Share Sheet: ACTION_SEND & ACTION_SEND_MULTIPLE
                        val action = incoming.action
                        if (action == android.content.Intent.ACTION_SEND || action == android.content.Intent.ACTION_SEND_MULTIPLE) {
                            if (currentUser == null) {
                                android.widget.Toast.makeText(context, "Please sign in to RB Dialer to share", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                val payload = com.example.util.SharePayloadHelper.extractSharedPayload(context, incoming)
                                if (payload != null) {
                                    val directPeer = payload.directTargetPeerNumber
                                    if (!directPeer.isNullOrBlank()) {
                                        val peerUser = firebaseManager.lookupUserByNumber(directPeer)
                                        shareIntakeState = payload.toReadyShareState()
                                        val returnTarget = (currentRoute as? AppRoute.ChatConversation)?.returnRoute ?: currentRoute
                                        currentRoute = AppRoute.ChatConversation(
                                            peerNumber = directPeer,
                                            peerName = peerUser?.displayName?.takeIf { it.isNotBlank() } ?: directPeer,
                                            peerAvatar = peerUser?.profilePictureUrl ?: "",
                                            returnRoute = returnTarget
                                        )
                                    } else {
                                        // Show WhatsApp-style "Send to..." contact picker modal
                                        shareIntakeState = ShareIntakeState.NeedsTargetPicker(payload)
                                    }
                                }
                            }
                        }
                        
                        _incomingIntent.value = null
                    }
                }

                // Check active call overlay
                val activeCall = rtcState.activeCall
                val isCallActive = activeCall != null && (
                    rtcState.callStatus == CallStatus.ANSWERED || 
                    rtcState.callStatus == CallStatus.CALLING || 
                    rtcState.callStatus == CallStatus.RINGING
                )
                val isCallRinging = activeCall != null && rtcState.callStatus == CallStatus.RINGING

                var lastCallClickTime by remember { mutableStateOf(0L) }
                val safeStartCall = { number: String, name: String, type: CallType ->
                    if (isCallActive) {
                        isCallMinimized = false
                    } else {
                        val now = System.currentTimeMillis()
                        if (now - lastCallClickTime > 500L) {
                            lastCallClickTime = now
                            val myNum = currentUser?.phoneNumber
                            if (!myNum.isNullOrBlank()) {
                                val myName = currentUser?.displayName?.ifBlank { null } ?: "Me"
                                webRtcEngine.initiateCall(
                                    calleeNumber = number,
                                    calleeName = name,
                                    callerNumber = myNum,
                                    callerName = myName,
                                    callType = type
                                )
                            }
                        }
                    }
                }

                val syncedContacts by firebaseManager.syncedContacts.collectAsState()
                val registeredUsers by firebaseManager.registeredUsers.collectAsState()
                val myPhoneNorm = com.example.util.ContactsHelper.normalizePhoneNumber(currentUser?.phoneNumber ?: "")
                val activeCallerNorm = com.example.util.ContactsHelper.normalizePhoneNumber(activeCall?.callerNumber ?: "")
                val isOutgoingCall = rtcState.isCaller || (myPhoneNorm.isNotBlank() && activeCallerNorm == myPhoneNorm)
                val isIncoming = !isOutgoingCall
                val otherPartyNumber = if (isIncoming) {
                    webRtcEngine.resolveCallerNumberForCall(activeCall?.callId ?: "", activeCall?.callerNumber)
                } else {
                    com.example.data.model.CallIdentitySanitizer.cleanNumber(activeCall?.calleeNumber)
                }
                val otherPartyContact = remember(otherPartyNumber, syncedContacts) {
                    if (otherPartyNumber.isNotBlank()) {
                        syncedContacts.find { com.example.util.ContactsHelper.numbersMatch(it.phoneNumber, otherPartyNumber) }
                    } else null
                }
                val otherPartyUser = remember(otherPartyNumber, registeredUsers) {
                    if (otherPartyNumber.isNotBlank()) firebaseManager.lookupUserByNumber(otherPartyNumber) else null
                }
                val otherPartyName = if (isIncoming) {
                    webRtcEngine.resolveCallerNameForCall(activeCall?.callId ?: "", activeCall?.callerName, otherPartyNumber)
                } else {
                    com.example.data.model.CallIdentitySanitizer.cleanName(activeCall?.calleeName)
                }
                val otherPartyDisplayName = otherPartyContact?.name?.let { com.example.data.model.CallIdentitySanitizer.cleanName(it) }?.takeIf { it.isNotBlank() }
                    ?: otherPartyUser?.displayName?.let { com.example.data.model.CallIdentitySanitizer.cleanName(it) }?.takeIf { it.isNotBlank() }
                    ?: otherPartyName.takeIf { it.isNotBlank() }
                    ?: otherPartyNumber.ifBlank { "Connecting..." }
                val otherPartyProfilePic = otherPartyUser?.profilePictureUrl?.ifBlank { null }
                    ?: otherPartyContact?.profilePictureUrl
                    ?: ""
                val direction = if (isIncoming) CallDirection.INCOMING else CallDirection.OUTGOING

                val groupCallEngine = remember { GroupCallEngine.getInstance(context, firebaseManager) }
                val groupCallState by groupCallEngine.state.collectAsState()
                var groupCallPickerState by remember { mutableStateOf<GroupCallPickerState>(GroupCallPickerState.Hidden) }

                LaunchedEffect(groupCallState.incomingInviteRoom?.roomId, groupCallState.isActive) {
                    val invite = groupCallState.incomingInviteRoom
                    val isOneToOneIncomingRinging = rtcState.callStatus == CallStatus.RINGING && !rtcState.isCaller
                    if (invite != null && !groupCallState.isActive) {
                        LksIncomingRingtonePlayer.start(context, invite.hostPhone, invite.roomId)
                    } else if (!isOneToOneIncomingRinging) {
                        LksIncomingRingtonePlayer.stop()
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // WhatsApp-style persistent in-app ongoing call banner when 1-to-1 call is minimized
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

                        // Persistent in-app ongoing Group Conference banner when minimized
                        if (groupCallState.isActive && groupCallState.isMinimized) {
                            MinimizedGroupCallTopBar(
                                state = groupCallState,
                                onExpand = { groupCallEngine.setMinimized(false) },
                                onLeaveOrEnd = { groupCallEngine.leaveGroupCall() }
                            )
                        }

                        val openChatWithPeer: (String, String, String?) -> Unit = { phone, name, avatar ->
                            val resolvedAvatar = if (!avatar.isNullOrBlank()) {
                                avatar
                            } else {
                                firebaseManager.lookupUserByNumber(phone)?.profilePictureUrl ?: ""
                            }
                            val returnTarget = (currentRoute as? AppRoute.ChatConversation)?.returnRoute ?: currentRoute
                            currentRoute = AppRoute.ChatConversation(
                                peerNumber = phone,
                                peerName = name,
                                peerAvatar = resolvedAvatar,
                                returnRoute = returnTarget
                            )
                        }

                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            when (val route = currentRoute) {
                                AppRoute.Welcome -> {
                                    WelcomeScreen(
                                        onGetStartedClick = { currentRoute = AppRoute.PhoneInput }
                                    )
                                }
                                AppRoute.PhoneInput -> {
                                    androidx.activity.compose.BackHandler {
                                        currentRoute = AppRoute.Welcome
                                    }
                                    PhoneInputScreen(
                                        firebaseManager = firebaseManager,
                                        onLoginSuccess = { _ ->
                                            currentRoute = AppRoute.Main()
                                        },
                                        onNewUser = { phone, deviceId ->
                                            currentRoute = AppRoute.ProfileSetup(
                                                phoneNumber = phone,
                                                deviceId = deviceId
                                            )
                                        },
                                        onBackClick = { currentRoute = AppRoute.Welcome }
                                    )
                                }
                                is AppRoute.ProfileSetup -> {
                                    androidx.activity.compose.BackHandler {
                                        currentRoute = AppRoute.PhoneInput
                                    }
                                    ProfileSetupScreen(
                                        phoneNumber = route.phoneNumber,
                                        deviceId = route.deviceId,
                                        onProfileComplete = { name, status ->
                                            firebaseManager.loginWithPhone(route.phoneNumber, name, route.deviceId, status)
                                            currentRoute = AppRoute.Main()
                                        },
                                        onBackClick = { currentRoute = AppRoute.PhoneInput }
                                    )
                                }
                                is AppRoute.Settings -> {
                                    androidx.activity.compose.BackHandler {
                                        currentRoute = AppRoute.Main()
                                    }
                                    SettingsScreen(
                                        firebaseManager = firebaseManager,
                                        initialCategory = route.initialCategory,
                                        onBackClick = { currentRoute = AppRoute.Main() },
                                        onNavigateToStorage = {
                                            currentRoute = AppRoute.StorageManagement(returnRoute = route)
                                        },
                                        onNavigateToLinkedDevices = {
                                            currentRoute = AppRoute.LinkedDevices(returnRoute = route)
                                        }
                                    )
                                }
                                is AppRoute.Main -> {
                                    val selectedTab = route.tab
                                    androidx.activity.compose.BackHandler(enabled = selectedTab != MainTab.DIALER) {
                                        currentRoute = route.copy(tab = MainTab.DIALER)
                                    }
                                    Scaffold(
                                        modifier = Modifier.fillMaxSize(),
                                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                                        bottomBar = {
                                            val activeSurfaceStyle = com.example.ui.theme.LocalSurfaceStyle.current
                                            val isDarkThemeActive by remember {
                                                com.example.ui.theme.ThemeManager.getInstance(context)
                                            }.isDarkMode.collectAsState()
                                            Column(
                                                modifier = Modifier.surfaceFinishOverlay(
                                                    style = activeSurfaceStyle,
                                                    cornerRadius = 0.dp,
                                                    accentColor = currentThemeColor.primary
                                                )
                                            ) {
                                                HorizontalDivider(
                                                    thickness = 1.dp,
                                                    color = activeSurfaceStyle.resolvedBorderColor(isDarkThemeActive)
                                                )
                                                NavigationBar(
                                                    containerColor = activeSurfaceStyle.resolvedCardColor(isDarkThemeActive),
                                                    tonalElevation = 0.dp
                                                ) {
                                                    MainTab.entries.forEach { tab ->
                                                        val isSelected = selectedTab == tab
                                                        NavigationBarItem(
                                                            selected = isSelected,
                                                            onClick = { currentRoute = route.copy(tab = tab) },
                                                            icon = {
                                                                if (tab == MainTab.CHATS && totalUnreadChats > 0) {
                                                                    BadgedBox(
                                                                        badge = {
                                                                            Badge(
                                                                                containerColor = com.example.ui.theme.CyberMint,
                                                                                contentColor = com.example.ui.theme.HudBackgroundPrimary
                                                                            ) {
                                                                                Text(
                                                                                    text = if (totalUnreadChats > 99) "99+" else totalUnreadChats.toString(),
                                                                                    fontFamily = com.example.ui.theme.JetBrainsMonoFontFamily,
                                                                                    fontWeight = FontWeight.Bold,
                                                                                    maxLines = 1
                                                                                )
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
                                                                    fontFamily = com.example.ui.theme.SpaceGroteskFontFamily,
                                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                                    maxLines = 1,
                                                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                                )
                                                            },
                                                            colors = NavigationBarItemDefaults.colors(
                                                                selectedIconColor = currentThemeColor.primary,
                                                                selectedTextColor = com.example.ui.theme.HudTextPrimary,
                                                                unselectedIconColor = com.example.ui.theme.HudTextSecondary,
                                                                unselectedTextColor = com.example.ui.theme.HudTextSecondary,
                                                                indicatorColor = currentThemeColor.primary.copy(alpha = 0.22f)
                                                            )
                                                        )
                                                    }
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
                                                    onStartCall = safeStartCall,
                                                    onNavigateToSettings = {
                                                        currentRoute = AppRoute.Settings(com.example.ui.screens.settings.SettingsCategory.ALL)
                                                    },
                                                    onNavigateToLinkedDevices = {
                                                        currentRoute = AppRoute.LinkedDevices(returnRoute = route)
                                                    },
                                                    onNavigateToChats = { currentRoute = route.copy(tab = MainTab.CHATS) },
                                                    onNavigateToContacts = { currentRoute = route.copy(tab = MainTab.CONTACTS) },
                                                    onOpenDirectChat = { number, name ->
                                                        openChatWithPeer(number, name, null)
                                                    }
                                                )
                                                MainTab.RECENTS -> CallHistoryScreen(
                                                    firebaseManager = firebaseManager,
                                                    onStartCall = safeStartCall,
                                                    onOpenChat = { number, name ->
                                                        openChatWithPeer(number, name, null)
                                                    },
                                                    onNavigateToDialer = { currentRoute = route.copy(tab = MainTab.DIALER) },
                                                    onOpenGroupCallModal = {
                                                        groupCallPickerState = GroupCallPickerState.Open(preselectedPeer = null)
                                                    }
                                                )
                                                MainTab.CHATS -> ChatListScreen(
                                                    firebaseManager = firebaseManager,
                                                    activeCallNumber = if (isCallActive) otherPartyNumber else null,
                                                    activeCallType = if (isCallActive) activeCall?.callType else null,
                                                    onOpenConversation = { phone, name, avatar ->
                                                        openChatWithPeer(phone, name, avatar)
                                                    },
                                                    onNavigateToStorage = {
                                                        currentRoute = AppRoute.StorageManagement(returnRoute = route)
                                                    },
                                                    onNavigateToSettings = {
                                                        currentRoute = AppRoute.Settings(com.example.ui.screens.settings.SettingsCategory.APPEARANCE)
                                                    },
                                                    onNavigateToLinkedDevices = {
                                                        currentRoute = AppRoute.LinkedDevices(returnRoute = route)
                                                    }
                                                )
                                                MainTab.CONTACTS -> ContactsScreen(
                                                    firebaseManager = firebaseManager,
                                                    onStartCall = safeStartCall,
                                                    onOpenChat = { number, name ->
                                                        openChatWithPeer(number, name, null)
                                                    },
                                                    onOpenGroupCallModal = {
                                                        groupCallPickerState = GroupCallPickerState.Open(preselectedPeer = null)
                                                    }
                                                )
                                                MainTab.PROFILE -> ProfileScreen(
                                                    firebaseManager = firebaseManager,
                                                    onOpenSettingsCategory = { category ->
                                                        currentRoute = AppRoute.Settings(category)
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                                is AppRoute.ChatConversation -> {
                                    val readyShare = shareIntakeState as? ShareIntakeState.ReadyForChat
                                    val returnFromChat = {
                                        currentRoute = if (route.returnRoute is AppRoute.StorageManagement) {
                                            route.returnRoute
                                        } else {
                                            AppRoute.Main()
                                        }
                                    }
                                    androidx.activity.compose.BackHandler {
                                        returnFromChat()
                                    }
                                    ChatConversationScreen(
                                        peerPhoneNumber = route.peerNumber,
                                        peerDisplayName = route.peerName,
                                        peerInitialAvatar = route.peerAvatar,
                                        firebaseManager = firebaseManager,
                                        initialSharedPhotos = readyShare?.photos,
                                        initialSharedText = readyShare?.text,
                                        initialSharedDocuments = readyShare?.documents,
                                        onSharedContentConsumed = {
                                            shareIntakeState = ShareIntakeState.None
                                        },
                                        onBackClick = {
                                            returnFromChat()
                                        },
                                        onStartCall = safeStartCall
                                    )
                                }
                                is AppRoute.StorageManagement -> {
                                    val returnFromStorage = {
                                        currentRoute = if (route.returnRoute is AppRoute.Settings) {
                                            route.returnRoute
                                        } else {
                                            AppRoute.Main()
                                        }
                                    }
                                    androidx.activity.compose.BackHandler {
                                        returnFromStorage()
                                    }
                                    ChatStorageManagementScreen(
                                        chatRepository = ChatRepository.getInstance(context),
                                        firebaseManager = firebaseManager,
                                        onBackClick = {
                                            returnFromStorage()
                                        },
                                        onOpenChat = { phone, name ->
                                            openChatWithPeer(phone, name, null)
                                        }
                                    )
                                }
                                is AppRoute.LinkedDevices -> {
                                    val returnFromLinked = {
                                        currentRoute = if (route.returnRoute is AppRoute.Settings) {
                                            route.returnRoute
                                        } else {
                                            AppRoute.Main()
                                        }
                                    }
                                    androidx.activity.compose.BackHandler {
                                        returnFromLinked()
                                    }
                                    LinkedDevicesScreen(
                                        firebaseManager = firebaseManager,
                                        onBackClick = {
                                            returnFromLinked()
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // WhatsApp-style "Send to..." Share Sheet contact picker
                    val targetPickerState = shareIntakeState as? ShareIntakeState.NeedsTargetPicker
                    if (targetPickerState != null) {
                        com.example.ui.screens.chat.ShareTargetPickerModal(
                            payload = targetPickerState.payload,
                            firebaseManager = firebaseManager,
                            chatRepository = chatRepo,
                            onSelectTarget = { phone, name, avatar ->
                                shareIntakeState = targetPickerState.payload.toReadyShareState()
                                val returnTarget = (currentRoute as? AppRoute.ChatConversation)?.returnRoute ?: currentRoute
                                currentRoute = AppRoute.ChatConversation(
                                    peerNumber = phone,
                                    peerName = name,
                                    peerAvatar = avatar,
                                    returnRoute = returnTarget
                                )
                            },
                            onDismiss = {
                                shareIntakeState = ShareIntakeState.None
                            }
                        )
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
                                        calleeNumber = otherPartyNumber,
                                        profilePicUrl = otherPartyProfilePic,
                                        callType = activeCall.callType,
                                        statusText = rtcState.connectionStatusText,
                                        webRtcEngine = webRtcEngine,
                                        onMinimize = { isCallMinimized = true },
                                        onEndCall = {
                                            firebaseManager.logCall(
                                                direction = CallDirection.OUTGOING,
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
                                CallStatus.RINGING -> {
                                    if (isIncoming) {
                                        IncomingCallOverlay(
                                            callerName = otherPartyDisplayName,
                                            callerNumber = otherPartyNumber,
                                            profilePicUrl = otherPartyProfilePic,
                                            callType = activeCall.callType,
                                            isSpam = firebaseManager.isNumberSpam(otherPartyNumber),
                                            onAnswer = { webRtcEngine.answerCall() },
                                            onDecline = {
                                                firebaseManager.logCall(
                                                    direction = CallDirection.INCOMING,
                                                    otherPartyNumber = otherPartyNumber,
                                                    otherPartyName = otherPartyDisplayName,
                                                    callType = activeCall.callType,
                                                    status = CallStatus.DECLINED,
                                                    durationSeconds = rtcState.callDurationSeconds,
                                                    callId = activeCall.callId
                                                )
                                                webRtcEngine.declineCall(activeCall.callId, otherPartyNumber)
                                            }
                                        )
                                    } else {
                                        OutgoingCallScreen(
                                            calleeName = otherPartyDisplayName,
                                            calleeNumber = otherPartyNumber,
                                            profilePicUrl = otherPartyProfilePic,
                                            callType = activeCall.callType,
                                            statusText = rtcState.connectionStatusText,
                                            webRtcEngine = webRtcEngine,
                                            onMinimize = { isCallMinimized = true },
                                            onEndCall = {
                                                firebaseManager.logCall(
                                                    direction = CallDirection.OUTGOING,
                                                    otherPartyNumber = otherPartyNumber,
                                                    otherPartyName = otherPartyDisplayName,
                                                    callType = activeCall.callType,
                                                    status = CallStatus.ENDED,
                                                    durationSeconds = rtcState.callDurationSeconds,
                                                    callId = activeCall.callId
                                                )
                                                webRtcEngine.endCall(activeCall.callId)
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
                                            onAddPersonToGroup = {
                                                groupCallPickerState = GroupCallPickerState.Open(
                                                    preselectedPeer = ContactDto(
                                                        id = otherPartyNumber,
                                                        name = otherPartyDisplayName,
                                                        phoneNumber = otherPartyNumber,
                                                        profilePictureUrl = otherPartyProfilePic,
                                                        isVoiceLinkUser = true
                                                    )
                                                )
                                            },
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
                                                webRtcEngine.endCall(activeCall.callId)
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
                                            onAddPersonToGroup = {
                                                groupCallPickerState = GroupCallPickerState.Open(
                                                    preselectedPeer = ContactDto(
                                                        id = otherPartyNumber,
                                                        name = otherPartyDisplayName,
                                                        phoneNumber = otherPartyNumber,
                                                        profilePictureUrl = otherPartyProfilePic,
                                                        isVoiceLinkUser = true
                                                    )
                                                )
                                            },
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
                                                webRtcEngine.endCall(activeCall.callId)
                                            }
                                        )
                                    }
                                }

                                CallStatus.DECLINED -> {
                                    OutgoingCallScreen(
                                        calleeName = otherPartyDisplayName,
                                        calleeNumber = otherPartyNumber,
                                        profilePicUrl = otherPartyProfilePic,
                                        callType = activeCall.callType,
                                        statusText = rtcState.connectionStatusText.ifBlank { "Call Declined" },
                                        webRtcEngine = webRtcEngine,
                                        onMinimize = null,
                                        onEndCall = {}
                                    )
                                }

                                CallStatus.ENDED, CallStatus.MISSED -> {
                                    OutgoingCallScreen(
                                        calleeName = otherPartyDisplayName,
                                        calleeNumber = otherPartyNumber,
                                        profilePicUrl = otherPartyProfilePic,
                                        callType = activeCall.callType,
                                        statusText = rtcState.connectionStatusText.ifBlank { if (rtcState.callStatus == CallStatus.MISSED) "Call Missed" else "Call Ended" },
                                        webRtcEngine = webRtcEngine,
                                        onMinimize = null,
                                        onEndCall = {}
                                    )
                                }

                                else -> {}
                            }
                        }
                    }

                    // Incoming Group Conference Call Overlay (iPhone Liquid Glass & Samsung One UI Swipe)
                    val incomingGroupRoom = groupCallState.incomingInviteRoom
                    if (incomingGroupRoom != null && !groupCallState.isActive) {
                        val hostPic = remember(incomingGroupRoom.hostPhone) {
                            firebaseManager.lookupUserByNumber(incomingGroupRoom.hostPhone)?.profilePictureUrl ?: ""
                        }
                        IncomingCallOverlay(
                            callerName = "${incomingGroupRoom.title} (${incomingGroupRoom.hostName})",
                            callerNumber = "Group ${incomingGroupRoom.callType.name} • ${incomingGroupRoom.invitedPhones.size + 1} Invited",
                            profilePicUrl = hostPic,
                            callType = incomingGroupRoom.callType,
                            isSpam = false,
                            onAnswer = {
                                LksIncomingRingtonePlayer.stop()
                                groupCallEngine.answerIncomingGroupInvite()
                            },
                            onDecline = {
                                LksIncomingRingtonePlayer.stop()
                                groupCallEngine.declineIncomingGroupInvite()
                            }
                        )
                    }

                    // Active Group Conference Screen (Zoom-Grade Multi-Participant Stage)
                    if (groupCallState.isActive && !groupCallState.isMinimized) {
                        androidx.activity.compose.BackHandler {
                            groupCallEngine.setMinimized(true)
                        }
                        ActiveGroupCallScreen(
                            state = groupCallState,
                            groupCallEngine = groupCallEngine,
                            firebaseManager = firebaseManager,
                            onMinimize = { groupCallEngine.setMinimized(true) }
                        )
                    }

                    // Start / Promote Group Conference Modal
                    val openGroupPicker = groupCallPickerState as? GroupCallPickerState.Open
                    if (openGroupPicker != null) {
                        StartGroupCallModal(
                            firebaseManager = firebaseManager,
                            isAddingToExistingCall = false,
                            preselectedContacts = listOfNotNull(openGroupPicker.preselectedPeer),
                            onStartGroupCall = { title, selectedContacts, callType, waitingRoom ->
                                // If promoting from an active 1-to-1 call, end the 1-to-1 call cleanly first
                                if (isCallActive && activeCall != null) {
                                    webRtcEngine.endCall(activeCall.callId)
                                }
                                groupCallEngine.createGroupCall(
                                    title = title,
                                    invitedContacts = selectedContacts,
                                    callType = callType,
                                    waitingRoomEnabled = waitingRoom
                                )
                            },
                            onDismiss = {
                                groupCallPickerState = GroupCallPickerState.Hidden
                            }
                        )
                    }
                }
                    
                    // Smart Non-Intrusive Update Window Gating:
                    // 1) Never show or keep UpdateDialog open during an active 1-to-1 call, group call, or incoming invite.
                    // 2) Only auto-surface when the user is on the Main hub route, no modal/sheet is open, and after a calm idle check.
                    val isAnyCallBusy = isCallActive || groupCallState.isActive || groupCallState.incomingInviteRoom != null
                    val hasActiveModal = showOemAutostartDialog ||
                        _showShakeDialog.value ||
                        groupCallPickerState !is GroupCallPickerState.Hidden ||
                        shareIntakeState !is ShareIntakeState.None

                    LaunchedEffect(
                        updateModalState,
                        isAnyCallBusy,
                        currentRoute,
                        hasActiveModal
                    ) {
                        val currentModal = updateModalState
                        if (isAnyCallBusy) {
                            // Immediately defer any open update dialog if a call starts or rings
                            val activeInfo = currentModal.visibleInfo
                            if (activeInfo != null && downloadState !is com.example.util.DownloadState.Downloading) {
                                updateModalState = UpdateModalPresentationState.DeferredAuto(activeInfo)
                            }
                            return@LaunchedEffect
                        }

                        if (currentModal !is UpdateModalPresentationState.DeferredAuto) return@LaunchedEffect
                        val candidate = currentModal.info
                        if (gitHubUpdater.isUpdateSnoozed(candidate.latestVersion)) {
                            updateModalState = UpdateModalPresentationState.Hidden
                            return@LaunchedEffect
                        }

                        while (updateModalState is UpdateModalPresentationState.DeferredAuto) {
                            val sinceTouch = if (lastUserInteractionAtMillis == 0L) Long.MAX_VALUE
                            else (System.currentTimeMillis() - lastUserInteractionAtMillis).coerceAtLeast(0L)
                            val canShow = com.example.util.UpdatePromptPolicy.shouldShowAutoUpdateDialog(
                                hasPendingUpdate = true,
                                isVersionSnoozed = gitHubUpdater.isUpdateSnoozed(candidate.latestVersion),
                                isOneToOneCallActive = isCallActive,
                                isGroupCallActive = groupCallState.isActive,
                                hasIncomingGroupInvite = groupCallState.incomingInviteRoom != null,
                                isOnMainHubRoute = currentRoute is AppRoute.Main,
                                hasActiveModalOrSheet = hasActiveModal,
                                millisSinceLastUserInteraction = sinceTouch,
                                minIdleWindowMs = 2500L
                            )
                            if (canShow) {
                                updateModalState = UpdateModalPresentationState.VisibleAuto(candidate)
                                break
                            }
                            kotlinx.coroutines.delay(1500L)
                        }
                    }

                    if (!isAnyCallBusy) {
                        updateModalState.visibleInfo?.let { info ->
                            UpdateDialog(
                                updateInfo = info,
                                downloadState = downloadState,
                                onDownloadClick = {
                                    gitHubUpdater.downloadUpdate(info.downloadUrl, info.latestVersion)
                                },
                                onInstallClick = { uri ->
                                    gitHubUpdater.installApk(uri)
                                },
                                onDismissRequest = {
                                    gitHubUpdater.dismissUpdate(info.latestVersion)
                                    updateModalState = UpdateModalPresentationState.Hidden
                                },
                                onMinimizeToBackground = {
                                    updateModalState = UpdateModalPresentationState.Hidden
                                }
                            )
                        }
                    }

                    // Show Shake-to-Report Crash & Diagnostic Dialog
                    if (_showShakeDialog.value) {
                        com.example.ui.components.ShakeReportDialog(
                            triggerType = "SHAKE_GESTURE",
                            onDismiss = { _showShakeDialog.value = false }
                        )
                    }

                    // Smooth RB DIALER Launch Splash Reveal Animation
                    var showLaunchSplash by remember { mutableStateOf(true) }
                    LaunchedEffect(Unit) {
                        kotlinx.coroutines.delay(1250L)
                        showLaunchSplash = false
                    }
                    com.example.ui.components.RbAppLaunchSplashOverlay(
                        visible = showLaunchSplash && !isCallActive
                    )
                }
            }
        }
    }
