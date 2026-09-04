package com.example.webrtc

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.data.model.CallDto
import com.example.data.model.CallStatus
import com.example.data.model.CallType
import com.example.data.model.IceCandidateDto
import com.example.data.repository.FirebaseManager
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.UUID

enum class AudioDeviceType {
    EARPIECE,
    SPEAKERPHONE,
    BLUETOOTH,
    WIRED_HEADSET
}

data class AudioDeviceOption(
    val id: String,
    val name: String,
    val type: AudioDeviceType,
    val isSelected: Boolean = false,
    val rawDevice: Any? = null
)

data class WebRtcState(
    val activeCall: CallDto? = null,
    val callStatus: CallStatus = CallStatus.IDLE,
    val callType: CallType = CallType.AUDIO,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val selectedAudioDevice: AudioDeviceType = AudioDeviceType.EARPIECE,
    val activeAudioDeviceName: String = "Earpiece",
    val availableAudioDevices: List<AudioDeviceOption> = emptyList(),
    val isCameraOn: Boolean = true,
    val isFrontCamera: Boolean = true,
    val callDurationSeconds: Int = 0,
    val callStartedAtMillis: Long = 0L,
    val networkQualityBars: Int = 5,
    val connectionStatusText: String = "Idle",
    val localVideoTrack: VideoTrack? = null,
    val remoteVideoTrack: VideoTrack? = null,
    val isVideoUpgradeRequested: Boolean = false,
    val didIRequestVideoUpgrade: Boolean = false,
    val isOnHold: Boolean = false,
    val isHeldLocally: Boolean = false
)

class WebRtcEngine private constructor(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var timerJob: Job? = null

    private var hasProcessedOffer = false
    private var hasProcessedAnswer = false

    private val _state = MutableStateFlow(WebRtcState())
    val state: StateFlow<WebRtcState> = _state.asStateFlow()
    private var audioDeviceCallback: android.media.AudioDeviceCallback? = null
    private var headsetReceiver: android.content.BroadcastReceiver? = null
    private val headsetButtonManager = com.example.services.HeadsetButtonManager(context)
    private var lastNonBluetoothAudioDevice: AudioDeviceType = AudioDeviceType.EARPIECE
    @Volatile
    private var lastUserExplicitSelectionTime: Long = 0L
    @Volatile
    private var userExplicitSelectedDevice: AudioDeviceType? = null
    @Volatile
    private var isCellularCallInterrupting: Boolean = false
    private var samsungVoiceFocusEffect: Any? = null
    private val queuedRemoteIceCandidates = mutableListOf<IceCandidate>()

    private val audioFocusChangeListener = android.media.AudioManager.OnAudioFocusChangeListener { focusChange ->
        Log.d("WebRtcEngine", "AudioFocus changed: $focusChange")
        when (focusChange) {
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Cellular phone call or high-priority audio interruption — silence mic and put on hold
                Log.i("WebRtcEngine", "Cellular call interruption — muting mic and placing VoIP call on hold")
                isCellularCallInterrupting = true
                putCallOnHold(true, isCellularInterruption = true)
            }
            android.media.AudioManager.AUDIOFOCUS_GAIN -> {
                if (isCellularCallInterrupting) {
                    Log.i("WebRtcEngine", "Regained audio focus after cellular call — resuming VoIP call")
                    isCellularCallInterrupting = false
                    putCallOnHold(false)
                }
            }
            android.media.AudioManager.AUDIOFOCUS_LOSS -> {
                Log.i("WebRtcEngine", "Permanent audio focus loss — muting local audio track")
                localAudioTrack?.setEnabled(false)
            }
        }
    }

    private val firestore = FirebaseFirestore.getInstance()
    private var myPhoneNumber: String = ""
    
    private var incomingCallListener: ListenerRegistration? = null
    private var activeCallListener: ListenerRegistration? = null
    private var iceCandidateListener: ListenerRegistration? = null
    
    private val seenCallIds = mutableSetOf<String>()
    private val sentIceCandidateHashes = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var audioFocusRequest: android.media.AudioFocusRequest? = null
    private var incomingCallWakeLock: android.os.PowerManager.WakeLock? = null

    // WebRTC Core
    private val eglBase = EglBase.create()
    val eglBaseContext: EglBase.Context = eglBase.eglBaseContext
    
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    
    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun3.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun4.l.google.com:19302").createIceServer()
    )

    init {
        initWebRtc()
    }

    private fun initWebRtc() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )

        val options = PeerConnectionFactory.Options()
        val defaultVideoEncoderFactory = DefaultVideoEncoderFactory(eglBaseContext, true, true)
        val defaultVideoDecoderFactory = DefaultVideoDecoderFactory(eglBaseContext)
        
        // Use software AEC/NS via WebRTC's own DSP rather than hardware variants.
        // Hardware AEC on Samsung (and many OEMs) over-suppresses mic input, making the remote
        // party hear very low/quiet audio. WebRTC's built-in software processing is more consistent.
        val audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setAudioSource(android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .createAudioDeviceModule()

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoEncoderFactory(defaultVideoEncoderFactory)
            .setVideoDecoderFactory(defaultVideoDecoderFactory)
            .createPeerConnectionFactory()
    }
    
    private fun createAudioTrack() {
        if (peerConnectionFactory == null) return
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("echoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googTypingNoiseDetection", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAudioMirroring", "false"))
        }
        audioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory?.createAudioTrack("ARDAMSa0", audioSource)
        localAudioTrack?.setEnabled(true)
    }

    private fun createVideoTrack() {
        if (peerConnectionFactory == null) return
        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames
        var cameraDeviceName: String? = null
        
        // Find front facing camera by default
        for (deviceName in deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                cameraDeviceName = deviceName
                break
            }
        }
        if (cameraDeviceName == null) {
            for (deviceName in deviceNames) {
                if (enumerator.isBackFacing(deviceName)) {
                    cameraDeviceName = deviceName
                    break
                }
            }
        }
        
        if (cameraDeviceName != null) {
            videoCapturer = enumerator.createCapturer(cameraDeviceName, null)
            surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBaseContext)
            videoSource = peerConnectionFactory?.createVideoSource(videoCapturer!!.isScreencast)
            videoCapturer?.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
            videoCapturer?.startCapture(1280, 720, 30)
            
            val videoTrack = peerConnectionFactory?.createVideoTrack("ARDAMSv0", videoSource)
            videoTrack?.setEnabled(true)
            
            _state.value = _state.value.copy(localVideoTrack = videoTrack)
        }
    }

    private fun createPeerConnection(isCaller: Boolean, callId: String) {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
        }
        
        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                if (newState == PeerConnection.IceConnectionState.CONNECTED) {
                    _state.value = _state.value.copy(connectionStatusText = "P2P Connected   WebRTC")
                } else if (newState == PeerConnection.IceConnectionState.DISCONNECTED || newState == PeerConnection.IceConnectionState.FAILED) {
                    _state.value = _state.value.copy(connectionStatusText = "Reconnecting...")
                    // If we are the caller, we initiate the ICE restart
                    if (isCaller) {
                        scope.launch {
                            try {
                                peerConnection?.restartIce()
                            } catch (e: Exception) {
                                // Fallback for older WebRTC versions
                            }
                            val constraints = MediaConstraints()
                            constraints.mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
                            
                            peerConnection?.createOffer(object : SimpleSdpObserver() {
                                override fun onCreateSuccess(desc: SessionDescription?) {
                                    peerConnection?.setLocalDescription(SimpleSdpObserver(), desc)
                                    desc?.let {
                                        firestore.collection("calls").document(callId)
                                            .update("offerSdp", it.description)
                                    }
                                }
                            }, constraints)
                        }
                    }
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate != null) {
                    sendIceCandidate(callId, candidate, isCaller)
                }
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dataChannel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                val track = receiver?.track()
                scope.launch {
                    _state.value = _state.value.copy(connectionStatusText = "Connected • WebRTC")
                    if (track is VideoTrack) {
                        _state.value = _state.value.copy(remoteVideoTrack = track)
                    }
                }
            }
        })

        if (_state.value.callType == CallType.VIDEO) {
            createVideoTrack()
            _state.value.localVideoTrack?.let {
                peerConnection?.addTrack(it, listOf("ARDAMS"))
            }
        }
        
        createAudioTrack()
        localAudioTrack?.let {
            peerConnection?.addTrack(it, listOf("ARDAMS"))
        }
    }

    private fun sendIceCandidate(callId: String, candidate: IceCandidate, isCaller: Boolean) {
        val candidateKey = "${candidate.sdpMid}_${candidate.sdpMLineIndex}_${candidate.sdp}"
        if (!sentIceCandidateHashes.add(candidateKey)) {
            return // Skip duplicate candidate write to save Firestore operations
        }
        val type = if (isCaller) "offerCandidate" else "answerCandidate"
        val candidateDto = IceCandidateDto(
            serverUrl = candidate.serverUrl,
            sdpMid = candidate.sdpMid,
            sdpMLineIndex = candidate.sdpMLineIndex,
            sdpCandidate = candidate.sdp,
            type = type
        )
        firestore.collection("calls").document(callId)
            .collection("candidates").add(candidateDto)
    }

    private fun listenForIceCandidates(callId: String, isCaller: Boolean) {
        val targetType = if (isCaller) "answerCandidate" else "offerCandidate"
        iceCandidateListener?.remove()
        iceCandidateListener = firestore.collection("calls").document(callId)
            .collection("candidates")
            .whereEqualTo("type", targetType)
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                for (change in snapshot.documentChanges) {
                    if (change.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                        val dto = change.document.toObject(IceCandidateDto::class.java)
                        val candidate = IceCandidate(dto.sdpMid, dto.sdpMLineIndex, dto.sdpCandidate)
                        val pc = peerConnection
                        if (pc != null && pc.remoteDescription != null) {
                            pc.addIceCandidate(candidate)
                        } else {
                            synchronized(queuedRemoteIceCandidates) {
                                queuedRemoteIceCandidates.add(candidate)
                            }
                            Log.d("WebRtcEngine", "Queued ICE candidate until remote description is set: ${candidate.sdpMid}")
                        }
                    }
                }
            }
    }

    private fun drainQueuedRemoteIceCandidates() {
        val pc = peerConnection ?: return
        synchronized(queuedRemoteIceCandidates) {
            if (queuedRemoteIceCandidates.isNotEmpty()) {
                Log.i("WebRtcEngine", "⚡ Draining ${queuedRemoteIceCandidates.size} queued ICE candidates to PeerConnection")
                for (candidate in queuedRemoteIceCandidates) {
                    pc.addIceCandidate(candidate)
                }
                queuedRemoteIceCandidates.clear()
            }
        }
    }

    private fun preferOpusAndEnableFec(sdp: String): String {
        val lines = sdp.split("\r\n").toMutableList()
        var opusPayloadType: String? = null
        
        for (line in lines) {
            if (line.startsWith("a=rtpmap:") && line.contains("opus/48000", ignoreCase = true)) {
                opusPayloadType = line.substringAfter("a=rtpmap:").substringBefore(" ").trim()
                break
            }
        }
        
        if (opusPayloadType == null) return sdp
        
        var fmtpFound = false
        for (i in lines.indices) {
            val line = lines[i]
            if (line.startsWith("a=fmtp:$opusPayloadType")) {
                fmtpFound = true
                var newLine = line
                if (!newLine.contains("useinbandfec=")) newLine += ";useinbandfec=1"
                if (!newLine.contains("usedtx=")) newLine += ";usedtx=1"
                if (!newLine.contains("maxaveragebitrate=")) newLine += ";maxaveragebitrate=64000"
                if (!newLine.contains("minptime=")) newLine += ";minptime=10"
                lines[i] = newLine
                break
            }
        }
        
        if (!fmtpFound) {
            for (i in lines.indices) {
                if (lines[i].startsWith("a=rtpmap:$opusPayloadType")) {
                    lines.add(i + 1, "a=fmtp:$opusPayloadType minptime=10;useinbandfec=1;usedtx=1;maxaveragebitrate=64000")
                    break
                }
            }
        }
        
        return lines.joinToString("\r\n")
    }

    fun initiateCall(calleeNumber: String, calleeName: String, callerNumber: String, callerName: String, callType: CallType) {
        myPhoneNumber = callerNumber
        val newCall = CallDto(
            callId = UUID.randomUUID().toString(),
            callerNumber = callerNumber,
            callerName = callerName,
            calleeNumber = calleeNumber,
            calleeName = calleeName.ifBlank { calleeNumber },
            callType = callType,
            status = CallStatus.CALLING,
            createdAt = System.currentTimeMillis()
        )

        _state.value = WebRtcState(
            activeCall = newCall,
            callStatus = CallStatus.CALLING,
            callType = callType,
            isSpeakerOn = callType == CallType.VIDEO,
            connectionStatusText = "Calling ..."
        )

        hasProcessedOffer = false
        hasProcessedAnswer = false

        firestore.collection("calls").document(newCall.callId).set(newCall)
        
        // Trigger Push Notification via external Worker (Cloudflare / Vercel)
        triggerPushNotification(calleeNumber, callerName, callerNumber, callType.name, newCall.callId)
        
        com.example.services.ActiveCallService.start(context, newCall.callId, callType.name)
        headsetButtonManager.startListening()
        try {
            com.example.services.LksTelecomManager.reportOutgoingCall(context, newCall.callId, calleeName, calleeNumber, callType)
        } catch (_: Exception) {}
        configureAudio(callType)
        
        // Timeout logic: if it stays in CALLING (offline) for 15s, or RINGING (no answer) for 45s, hang up.
        scope.launch {
            delay(30000) // Wait 30 seconds for recipient device to wake up and acknowledge RINGING
            if (_state.value.callStatus == CallStatus.CALLING && _state.value.activeCall?.callId == newCall.callId) {
                _state.value = _state.value.copy(connectionStatusText = "User Offline / Unavailable")
                delay(2000)
                if (_state.value.callStatus == CallStatus.CALLING && _state.value.activeCall?.callId == newCall.callId) endCall()
                return@launch
            }
            
            // If it reached RINGING, give it another 45 seconds to answer
            delay(45000)
            if ((_state.value.callStatus == CallStatus.CALLING || _state.value.callStatus == CallStatus.RINGING) && _state.value.activeCall?.callId == newCall.callId) {
                _state.value = _state.value.copy(connectionStatusText = "No Answer")
                delay(2000)
                if ((_state.value.callStatus == CallStatus.CALLING || _state.value.callStatus == CallStatus.RINGING) && _state.value.activeCall?.callId == newCall.callId) endCall()
            }
        }
        
        createPeerConnection(isCaller = true, callId = newCall.callId)
        
        val constraints = MediaConstraints()
        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (callType == CallType.VIDEO) "true" else "false"))
        
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc != null) {
                    val tunedSdp = preferOpusAndEnableFec(desc.description)
                    val tunedDesc = SessionDescription(desc.type, tunedSdp)
                    peerConnection?.setLocalDescription(SimpleSdpObserver(), tunedDesc)
                    firestore.collection("calls").document(newCall.callId).update("offerSdp", tunedSdp)
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }, constraints)

        listenToActiveCall(newCall.callId, isCaller = true)
        listenForIceCandidates(newCall.callId, isCaller = true)
    }

    fun listenForIncomingCalls(phoneNumber: String) {
        if (phoneNumber.isBlank()) return
        myPhoneNumber = phoneNumber
        incomingCallListener?.remove()

        val variations = mutableListOf<String>()
        variations.add(phoneNumber)
        val cleanDigits = phoneNumber.replace(Regex("[^0-9]"), "")
        if (cleanDigits.isNotBlank()) {
            variations.add(cleanDigits)
            if (cleanDigits.length > 10) {
                variations.add(cleanDigits.takeLast(10))
            }
            if (!phoneNumber.startsWith("+")) {
                variations.add("+$phoneNumber")
            }
        }
        val distinctVariations = variations.distinct().take(10)

        incomingCallListener = firestore.collection("calls")
            .whereIn("calleeNumber", distinctVariations)
            .addSnapshotListener { snapshot, e ->
                if (e != null) return@addSnapshotListener
                val now = System.currentTimeMillis()
                val activeCalls = snapshot?.documents?.mapNotNull { it.toObject(CallDto::class.java) } ?: emptyList()

                // Auto-cleanup stale/expired calls (>45s old) and delete finished calls (>60s) from Firestore to keep DB lean & fast
                for (call in activeCalls) {
                    if ((call.status == CallStatus.CALLING || call.status == CallStatus.RINGING) && (now - call.createdAt > 45_000L)) {
                        Log.d("WebRtcEngine", "Cleaning up stale call document from Firestore: ${call.callId} (${now - call.createdAt}ms old)")
                        try {
                            firestore.collection("calls").document(call.callId).update("status", CallStatus.MISSED.name)
                        } catch (_: Exception) {}
                    } else if ((call.status == CallStatus.ENDED || call.status == CallStatus.DECLINED || call.status == CallStatus.MISSED) && (now - call.createdAt > 60_000L)) {
                        // Delete finished call document and orphaned candidates to prevent DB bloat
                        deleteCallAndCandidates(call.callId)
                    }
                }

                if (seenCallIds.size > 200) {
                    seenCallIds.clear()
                }

                // If we are currently ringing/calling on a call that the caller ended/cancelled, end it immediately
                val currentActive = _state.value.activeCall
                if (currentActive != null && (_state.value.callStatus == CallStatus.RINGING || _state.value.callStatus == CallStatus.CALLING)) {
                    val activeDoc = snapshot?.documents?.find { it.id == currentActive.callId }
                    if (activeDoc != null) {
                        val statusStr = activeDoc.getString("status")
                        if (statusStr == CallStatus.ENDED.name || statusStr == CallStatus.DECLINED.name || statusStr == CallStatus.MISSED.name) {
                            Log.d("WebRtcEngine", "Incoming call was terminated by caller: ${currentActive.callId} ($statusStr)")
                            endCallInternalLocal(try { CallStatus.valueOf(statusStr) } catch (_: Exception) { CallStatus.MISSED })
                        }
                    }
                }

                // Filter for FRESH incoming calls (<45s old) not initiated by self
                val incomingCall = activeCalls
                    .filter { (it.status == CallStatus.CALLING || it.status == CallStatus.RINGING) && (now - it.createdAt <= 45_000L) }
                    .filter { it.callerNumber != myPhoneNumber && it.callerNumber.replace(Regex("[^0-9]"), "") != cleanDigits }
                    .maxByOrNull { it.createdAt }

                if (incomingCall != null && _state.value.activeCall == null && incomingCall.callId !in seenCallIds) {
                    seenCallIds.add(incomingCall.callId)
                    
                    firestore.collection("calls").document(incomingCall.callId).update("status", CallStatus.RINGING.name)
                    headsetButtonManager.startListening()
                    try {
                        com.example.services.LksTelecomManager.reportIncomingCall(
                            context,
                            incomingCall.callId,
                            incomingCall.callerName,
                            incomingCall.callerNumber,
                            incomingCall.callType
                        )
                    } catch (_: Exception) {}
                    
                    _state.value = WebRtcState(
                        activeCall = incomingCall.copy(status = CallStatus.RINGING),
                        callStatus = CallStatus.RINGING,
                        callType = incomingCall.callType,
                        connectionStatusText = "Incoming Call"
                    )

                    // Wake screen and route to lockscreen activity or floating pill if unlocked
                    try {
                        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                        try {
                            if (incomingCallWakeLock?.isHeld == true) incomingCallWakeLock?.release()
                        } catch (_: Exception) {}
                        incomingCallWakeLock = pm?.newWakeLock(
                            android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                            android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                            android.os.PowerManager.ON_AFTER_RELEASE,
                            "lksdialer:incoming_call_wake_engine"
                        )?.apply { acquire(15000) }

                        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
                        val isLocked = km?.isKeyguardLocked == true

                        if (isLocked) {
                            val launchIntent = Intent(context, com.example.MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                putExtra("incoming_call", true)
                                putExtra("call_id", incomingCall.callId)
                                putExtra("caller_name", incomingCall.callerName)
                                putExtra("caller_number", incomingCall.callerNumber)
                                putExtra("call_type", incomingCall.callType.name)
                            }
                            context.startActivity(launchIntent)
                        } else if (!com.example.MainActivity.isForeground) {
                            com.example.services.FloatingCallBubbleService.showIncoming(
                                context,
                                incomingCall.callId,
                                incomingCall.callerName,
                                incomingCall.callerNumber,
                                incomingCall.callType
                            )
                        }
                    } catch (_: Exception) {}

                    listenToActiveCall(incomingCall.callId, isCaller = false)
                }
            }
    }

    fun attachToCall(
        callId: String, 
        autoAnswer: Boolean = false,
        callerName: String? = null,
        callerNumber: String? = null,
        callTypeStr: String? = null
    ) {
        hasProcessedOffer = false
        hasProcessedAnswer = false
        
        // Optimistically show the call screen if we have the data
        if (callerName != null && callerNumber != null && callTypeStr != null && _state.value.activeCall == null) {
            val type = try { CallType.valueOf(callTypeStr) } catch(e: Exception) { CallType.AUDIO }
            _state.value = WebRtcState(
                activeCall = CallDto(
                    callId = callId,
                    callerName = callerName,
                    callerNumber = callerNumber,
                    calleeNumber = myPhoneNumber,
                    callType = type,
                    status = if (autoAnswer) CallStatus.ANSWERED else CallStatus.RINGING
                ),
                callStatus = if (autoAnswer) CallStatus.ANSWERED else CallStatus.RINGING,
                callType = type,
                connectionStatusText = if (autoAnswer) "Connecting..." else "Incoming Call"
            )
            headsetButtonManager.startListening()
        }
        
        firestore.collection("calls").document(callId).get().addOnSuccessListener { doc ->
            val call = doc.toObject(CallDto::class.java)
            if (call != null) {
                if (call.status == CallStatus.ENDED || call.status == CallStatus.DECLINED || call.status == CallStatus.MISSED) {
                    Log.d("WebRtcEngine", "attachToCall: call $callId already finished with status ${call.status}")
                    endCallInternalLocal(call.status)
                    return@addOnSuccessListener
                }

                // AttachToCall is only used by the callee, so if the status is still CALLING, it should be RINGING
                val resolvedStatus = if (autoAnswer) CallStatus.ANSWERED 
                                     else if (call.status == CallStatus.CALLING) CallStatus.RINGING
                                     else call.status
                                     
                if (resolvedStatus == CallStatus.RINGING && call.status != CallStatus.RINGING) {
                    firestore.collection("calls").document(callId).update("status", CallStatus.RINGING.name)
                }

                _state.value = _state.value.copy(
                    activeCall = call.copy(status = resolvedStatus),
                    callType = call.callType,
                    callStatus = resolvedStatus,
                    connectionStatusText = if (autoAnswer) "Connecting P2P..." else "Incoming  Call"
                )
                headsetButtonManager.startListening()
                listenToActiveCall(callId, isCaller = false)
                if (autoAnswer) answerCall()
            }
        }
    }

    fun answerCall() {
        val call = _state.value.activeCall ?: return
        
        _state.value = _state.value.copy(
            callStatus = CallStatus.ANSWERED,
            connectionStatusText = "Connecting P2P..."
        )
        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.cancel(1001)
        com.example.util.LksIncomingRingtonePlayer.stop()
        
        com.example.services.ActiveCallService.start(context, call.callId, call.callType.name)
        headsetButtonManager.startListening()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                com.example.services.LksConnectionService.setCallActive()
            } catch (_: Exception) {}
        }
        configureAudio(call.callType)
        
        firestore.collection("calls").document(call.callId).update(
            "status", CallStatus.ANSWERED.name,
            "answeredAt", System.currentTimeMillis()
        )
        
        createPeerConnection(isCaller = false, callId = call.callId)
        processOfferSdpIfAvailable()
        
        listenForIceCandidates(call.callId, isCaller = false)
        startCallTimer()
    }

    private fun processOfferSdpIfAvailable() {
        val call = _state.value.activeCall ?: return
        val offerSdp = call.offerSdp ?: return
        val pc = peerConnection ?: return
        if (hasProcessedOffer && pc.remoteDescription?.description == offerSdp) return
        
        hasProcessedOffer = true
        val sessionDescription = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
        pc.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                Log.i("WebRtcEngine", "Remote offer SDP set successfully")
                drainQueuedRemoteIceCandidates()
                
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (_state.value.callType == CallType.VIDEO) "true" else "false"))
                }
                
                pc.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(desc: SessionDescription?) {
                        if (desc != null) {
                            val tunedSdp = preferOpusAndEnableFec(desc.description)
                            val tunedDesc = SessionDescription(desc.type, tunedSdp)
                            pc.setLocalDescription(object : SimpleSdpObserver() {
                                override fun onSetSuccess() {
                                    Log.i("WebRtcEngine", "Local answer SDP set successfully, uploading to Firestore")
                                    firestore.collection("calls").document(call.callId)
                                        .update("answerSdp", tunedSdp)
                                }
                                override fun onSetFailure(error: String?) {
                                    Log.e("WebRtcEngine", "Failed to set local answer: $error")
                                }
                            }, tunedDesc)
                        }
                    }
                    override fun onCreateFailure(error: String?) {
                        Log.e("WebRtcEngine", "Failed to create answer: $error")
                    }
                }, constraints)
            }
            override fun onSetFailure(error: String?) {
                Log.e("WebRtcEngine", "Failed to set remote offer SDP: $error")
                hasProcessedOffer = false
            }
        }, sessionDescription)
    }

    private fun configureAudio(callType: CallType) {
        lastNonBluetoothAudioDevice = if (callType == CallType.VIDEO) AudioDeviceType.SPEAKERPHONE else AudioDeviceType.EARPIECE
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        am.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
        
        registerAudioDeviceListeners()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val focusRequest = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            audioFocusRequest = focusRequest
            am.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(audioFocusChangeListener, android.media.AudioManager.STREAM_VOICE_CALL, android.media.AudioManager.AUDIOFOCUS_GAIN)
        }

        // Ensure voice call volume is clear and un-ducked
        try {
            val maxVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_VOICE_CALL)
            val curVol = am.getStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL)
            if (curVol < (maxVol * 0.7f).toInt()) {
                am.setStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL, (maxVol * 0.85f).toInt(), 0)
            }
        } catch (_: Exception) {}

        // Try to enable Samsung Voice Focus (AI noise suppression) if available on this device.
        // Samsung One UI exposes this as an AudioEffect. Silently ignored on non-Samsung devices.
        applySamsungVoiceFocusIfAvailable()

        refreshAvailableAudioDevices(defaultCallType = callType)
    }

    /** Tries to find and enable Samsung's proprietary Voice Focus audio effect for in-call mic enhancement.
     *  AudioEffect constructor is package-private so we use reflection — fail silently if unavailable. */
    private fun applySamsungVoiceFocusIfAvailable() {
        try {
            val effects = android.media.audiofx.AudioEffect.queryEffects() ?: return
            // Samsung Voice Focus UUIDs vary by One UI version; search by name as well.
            val samsungEffect = effects.firstOrNull { descriptor ->
                val name = descriptor.name?.lowercase() ?: ""
                val implementor = descriptor.implementor?.lowercase() ?: ""
                name.contains("voice focus") || name.contains("voicefocus") ||
                    name.contains("samsung") && name.contains("focus") ||
                    implementor.contains("samsung") && name.contains("noise")
            }
            if (samsungEffect != null) {
                // AudioEffect constructor is package-private — access via reflection
                val ctor = android.media.audiofx.AudioEffect::class.java
                    .getDeclaredConstructor(java.util.UUID::class.java, java.util.UUID::class.java, Int::class.java, Int::class.java)
                ctor.isAccessible = true
                val effect = ctor.newInstance(samsungEffect.type, samsungEffect.uuid, 0, 0)
                // Call setEnabled(true) via reflection
                val setEnabled = android.media.audiofx.AudioEffect::class.java.getDeclaredMethod("setEnabled", Boolean::class.java)
                setEnabled.isAccessible = true
                setEnabled.invoke(effect, true)
                samsungVoiceFocusEffect = effect
                Log.i("WebRtcEngine", "✅ Samsung Voice Focus effect enabled: ${samsungEffect.name}")
            } else {
                Log.d("WebRtcEngine", "Samsung Voice Focus not found on this device (non-Samsung or unsupported One UI version)")
            }
        } catch (e: Exception) {
            // Not supported or requires extra permissions — fail silently
            Log.d("WebRtcEngine", "Samsung Voice Focus not available: ${e.message}")
        }
    }

    private fun listenToActiveCall(callId: String, isCaller: Boolean) {
        activeCallListener?.remove()
        activeCallListener = firestore.collection("calls").document(callId)
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                val call = snapshot.toObject(CallDto::class.java)
                if (call != null) {
                    val currentCall = _state.value.activeCall
                    if (currentCall?.callId == call.callId) {
                        val oldCall = currentCall
                        _state.value = _state.value.copy(activeCall = call)
                        
                        if (isCaller && call.status == CallStatus.RINGING && _state.value.callStatus == CallStatus.CALLING) {
                            _state.value = _state.value.copy(
                                callStatus = CallStatus.RINGING,
                                connectionStatusText = "Ringing..."
                            )
                        }

                        // Video Upgrade Logic
                        if (call.videoUpgradeStatus == com.example.data.model.VideoUpgradeStatus.REQUESTED) {
                            if (!_state.value.didIRequestVideoUpgrade && call.callType == CallType.AUDIO && !_state.value.isVideoUpgradeRequested) {
                                // If I didn't request it, someone else did, so I am being requested!
                                _state.value = _state.value.copy(isVideoUpgradeRequested = true)
                            }
                        } else {
                            _state.value = _state.value.copy(
                                isVideoUpgradeRequested = false
                            )
                            if (call.videoUpgradeStatus == null) {
                                _state.value = _state.value.copy(
                                    didIRequestVideoUpgrade = false
                                )
                            }
                        }

                        if (call.videoUpgradeStatus == com.example.data.model.VideoUpgradeStatus.ACCEPTED && _state.value.callType == CallType.AUDIO) {
                            executeVideoUpgrade(isCaller)
                        }

                        if (call.videoUpgradeStatus == com.example.data.model.VideoUpgradeStatus.DECLINED && _state.value.callType == CallType.AUDIO) {
                            if (_state.value.didIRequestVideoUpgrade) {
                                // Show a toast or update state so the requester knows it was declined
                                _state.value = _state.value.copy(connectionStatusText = "Video Request Declined")
                                scope.launch {
                                    delay(3000)
                                    if (_state.value.callStatus == CallStatus.ANSWERED) {
                                        _state.value = _state.value.copy(connectionStatusText = "Connected • WebRTC")
                                    }
                                }
                                // Reset the status so we can request again
                                firestore.collection("calls").document(call.callId).update("videoUpgradeStatus", null)
                            }
                        }

                        if (isCaller && call.status == CallStatus.ANSWERED && _state.value.callStatus != CallStatus.ANSWERED) {
                            _state.value = _state.value.copy(
                                callStatus = CallStatus.ANSWERED,
                                connectionStatusText = if (call.answerSdp != null) "Connected • WebRTC" else "Connecting P2P..."
                            )
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                try {
                                    com.example.services.LksConnectionService.setCallActive()
                                } catch (_: Exception) {}
                            }
                            if (_state.value.callDurationSeconds == 0) {
                                startCallTimer()
                            }
                        }

                        if (isCaller && call.answerSdp != null && (!hasProcessedAnswer || call.answerSdp != oldCall?.answerSdp)) {
                            hasProcessedAnswer = true
                            val sessionDescription = SessionDescription(SessionDescription.Type.ANSWER, call.answerSdp)
                            peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
                                override fun onSetSuccess() {
                                    Log.i("WebRtcEngine", "Remote answer SDP set successfully")
                                    drainQueuedRemoteIceCandidates()
                                }
                                override fun onSetFailure(error: String?) {
                                    Log.e("WebRtcEngine", "Failed to set remote answer: $error")
                                }
                            }, sessionDescription)
                            _state.value = _state.value.copy(
                                callStatus = CallStatus.ANSWERED,
                                connectionStatusText = "Connected • WebRTC"
                            )
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                try {
                                    com.example.services.LksConnectionService.setCallActive()
                                } catch (_: Exception) {}
                            }
                            refreshAvailableAudioDevices(defaultCallType = null)
                            val desiredDevice = userExplicitSelectedDevice ?: _state.value.selectedAudioDevice
                            selectAudioDeviceType(desiredDevice)
                            if (_state.value.callDurationSeconds == 0) {
                                startCallTimer()
                            }
                        }
                        
                        if (!isCaller && call.offerSdp != null && (!hasProcessedOffer || call.offerSdp != oldCall?.offerSdp)) {
                            processOfferSdpIfAvailable()
                        }
                        
                        // Hold State synchronization
                        val remoteHold = snapshot.getBoolean("isOnHold") == true || snapshot.getBoolean("onHold") == true || call.isOnHold
                        val heldBy = snapshot.getString("heldBy") ?: call.heldBy ?: ""
                        val myPhone = myPhoneNumber.ifBlank {
                            com.example.data.repository.FirebaseManager.getInstance(context).currentUser.value?.phoneNumber ?: ""
                        }
                        val isHeldByMe = heldBy.isNotBlank() && (heldBy == myPhone || com.example.util.ContactsHelper.numbersMatch(heldBy, myPhone))
                        
                        if (remoteHold != _state.value.isOnHold || (remoteHold && _state.value.isHeldLocally != isHeldByMe)) {
                            val statusMsg = when {
                                !remoteHold -> "Connected • WebRTC"
                                isHeldByMe -> "Call On Hold"
                                else -> "Call On Hold (Other Party)"
                            }
                            Log.i("WebRtcEngine", "Hold state updated: remoteHold=$remoteHold, isHeldByMe=$isHeldByMe, heldBy=$heldBy, myPhone=$myPhone")
                            _state.value = _state.value.copy(
                                isOnHold = remoteHold,
                                isHeldLocally = isHeldByMe,
                                connectionStatusText = statusMsg
                            )
                            if (remoteHold) {
                                localAudioTrack?.setEnabled(false)
                            } else {
                                localAudioTrack?.setEnabled(!_state.value.isMuted)
                            }
                        }
                        
                        if (call.status == CallStatus.ENDED || call.status == CallStatus.DECLINED || call.status == CallStatus.MISSED) {
                            if (_state.value.callStatus != CallStatus.ENDED && _state.value.callStatus != CallStatus.DECLINED) {
                                endCallInternalLocal(call.status)
                            }
                        }
                    }
                }
            }
    }

    fun requestVideoUpgrade() {
        val currentCall = _state.value.activeCall ?: return
        _state.value = _state.value.copy(didIRequestVideoUpgrade = true)
        firestore.collection("calls").document(currentCall.callId)
            .update("videoUpgradeStatus", com.example.data.model.VideoUpgradeStatus.REQUESTED.name)
    }

    fun acceptVideoUpgrade() {
        val currentCall = _state.value.activeCall ?: return
        _state.value = _state.value.copy(isVideoUpgradeRequested = false)
        firestore.collection("calls").document(currentCall.callId)
            .update("videoUpgradeStatus", com.example.data.model.VideoUpgradeStatus.ACCEPTED.name)
    }

    fun declineVideoUpgrade() {
        val currentCall = _state.value.activeCall ?: return
        _state.value = _state.value.copy(isVideoUpgradeRequested = false)
        firestore.collection("calls").document(currentCall.callId)
            .update("videoUpgradeStatus", com.example.data.model.VideoUpgradeStatus.DECLINED.name)
    }

    private fun executeVideoUpgrade(isCaller: Boolean) {
        val currentCall = _state.value.activeCall ?: return
        
        // 1. Create and add local video track
        createVideoTrack()
        _state.value.localVideoTrack?.let {
            peerConnection?.addTrack(it, listOf("ARDAMS"))
        }
        
        // 2. Update UI state to video
        _state.value = _state.value.copy(callType = CallType.VIDEO)
        if (_state.value.selectedAudioDevice != AudioDeviceType.BLUETOOTH && _state.value.selectedAudioDevice != AudioDeviceType.WIRED_HEADSET) {
            selectAudioDeviceType(AudioDeviceType.SPEAKERPHONE)
        }
        
        // 3. Caller creates new offer for renegotiation
        if (isCaller) {
            val constraints = MediaConstraints()
            constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            
            peerConnection?.createOffer(object : SimpleSdpObserver() {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    if (desc != null) {
                        val tunedSdp = preferOpusAndEnableFec(desc.description)
                        val tunedDesc = SessionDescription(desc.type, tunedSdp)
                        peerConnection?.setLocalDescription(SimpleSdpObserver(), tunedDesc)
                        firestore.collection("calls").document(currentCall.callId)
                            .update(
                                "offerSdp", tunedSdp,
                                "callType", CallType.VIDEO.name
                            )
                    }
                }
            }, constraints)
        }
    }

    private fun startCallTimer() {
        timerJob?.cancel()
        val startMillis = if (_state.value.callStartedAtMillis > 0L) {
            _state.value.callStartedAtMillis
        } else {
            System.currentTimeMillis()
        }
        _state.value = _state.value.copy(callStartedAtMillis = startMillis)

        timerJob = scope.launch {
            while (_state.value.callStatus == CallStatus.ANSWERED) {
                val elapsed = ((System.currentTimeMillis() - startMillis) / 1000).toInt().coerceAtLeast(0)
                _state.value = _state.value.copy(callDurationSeconds = elapsed)
                delay(1000)
            }
        }
    }

    fun toggleMute() {
        val newMuted = !_state.value.isMuted
        localAudioTrack?.setEnabled(!newMuted)
        _state.value = _state.value.copy(isMuted = newMuted)
    }

    fun putCallOnHold(onHold: Boolean, isCellularInterruption: Boolean = false) {
        val currentCall = _state.value.activeCall ?: return
        val myNumber = myPhoneNumber.ifBlank {
            com.example.data.repository.FirebaseManager.getInstance(context).currentUser.value?.phoneNumber ?: ""
        }
        
        // 1. Mute or restore local audio track
        if (onHold) {
            localAudioTrack?.setEnabled(false)
        } else {
            localAudioTrack?.setEnabled(!_state.value.isMuted)
        }
        
        // 2. Update local UI state
        val statusText = when {
            !onHold -> "Connected • WebRTC"
            isCellularInterruption -> "On Hold (Cellular Call)"
            else -> "Call On Hold"
        }
        _state.value = _state.value.copy(
            isOnHold = onHold,
            isHeldLocally = onHold,
            connectionStatusText = statusText
        )
        
        // 3. Sync hold state with Telecom Connection (Bluetooth Headsets, Smartwatches, Android Auto)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                com.example.services.LksConnectionService.setCallOnHold(onHold)
            } catch (_: Exception) {}
        }

        // 4. Sync hold state to Firestore so remote party sees it immediately
        try {
            firestore.collection("calls").document(currentCall.callId)
                .update(
                    "onHold", onHold,
                    "isOnHold", onHold,
                    "heldBy", if (onHold) myNumber else null
                )
                .addOnSuccessListener {
                    Log.i("WebRtcEngine", "Synced call hold state to Firestore: onHold=$onHold, heldBy=$myNumber")
                }
                .addOnFailureListener { e ->
                    Log.w("WebRtcEngine", "Failed to sync hold state to Firestore: ${e.message}")
                }
        } catch (e: Exception) {
            Log.w("WebRtcEngine", "Exception updating hold state: ${e.message}")
        }
    }

    fun toggleHold() {
        val current = _state.value.isOnHold
        putCallOnHold(!current)
    }

    private fun registerAudioDeviceListeners() {
        unregisterAudioDeviceListeners()
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val callback = object : android.media.AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>?) {
                    scope.launch {
                        delay(350)
                        refreshAvailableAudioDevices()
                    }
                }
                override fun onAudioDevicesRemoved(removedDevices: Array<out android.media.AudioDeviceInfo>?) {
                    scope.launch {
                        delay(350)
                        refreshAvailableAudioDevices()
                    }
                }
            }
            audioDeviceCallback = callback
            am.registerAudioDeviceCallback(callback, android.os.Handler(android.os.Looper.getMainLooper()))
        }
        
        try {
            val filter = android.content.IntentFilter().apply {
                addAction(android.bluetooth.BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                addAction(android.content.Intent.ACTION_HEADSET_PLUG)
                addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: android.content.Intent?) {
                    scope.launch {
                        delay(350)
                        refreshAvailableAudioDevices()
                    }
                }
            }
            headsetReceiver = receiver
            androidx.core.content.ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED
            )
        } catch (e: Exception) {
            Log.e("WebRtcEngine", "Failed to register headset receiver", e)
        }
    }

    private fun unregisterAudioDeviceListeners() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && audioDeviceCallback != null) {
            try {
                am.unregisterAudioDeviceCallback(audioDeviceCallback)
            } catch (_: Exception) {}
            audioDeviceCallback = null
        }
        if (headsetReceiver != null) {
            try {
                context.unregisterReceiver(headsetReceiver)
            } catch (_: Exception) {}
            headsetReceiver = null
        }
    }

    @Synchronized
    fun refreshAvailableAudioDevices(defaultCallType: CallType? = null) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val deviceList = mutableListOf<AudioDeviceOption>()
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val commDevices = am.availableCommunicationDevices
            var hasEarpiece = false
            var hasSpeaker = false
            
            for (dev in commDevices) {
                when (dev.type) {
                    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
                    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    android.media.AudioDeviceInfo.TYPE_HEARING_AID -> {
                        val rawName = dev.productName?.toString()?.ifBlank { "Bluetooth" } ?: "Bluetooth"
                        val name = if (rawName.startsWith("Bluetooth", ignoreCase = true)) rawName else "Bluetooth ($rawName)"
                        deviceList.add(
                            AudioDeviceOption(
                                id = "bt_${dev.id}",
                                name = name,
                                type = AudioDeviceType.BLUETOOTH,
                                rawDevice = dev
                            )
                        )
                    }
                    android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
                    android.media.AudioDeviceInfo.TYPE_USB_DEVICE -> {
                        val name = dev.productName?.toString()?.ifBlank { "Wired Headset" } ?: "Wired Headset"
                        deviceList.add(
                            AudioDeviceOption(
                                id = "wired_${dev.id}",
                                name = name,
                                type = AudioDeviceType.WIRED_HEADSET,
                                rawDevice = dev
                            )
                        )
                    }
                    android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> {
                        hasSpeaker = true
                        deviceList.add(
                            AudioDeviceOption(
                                id = "speaker_${dev.id}",
                                name = "Speaker",
                                type = AudioDeviceType.SPEAKERPHONE,
                                rawDevice = dev
                            )
                        )
                    }
                    android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> {
                        hasEarpiece = true
                        deviceList.add(
                            AudioDeviceOption(
                                id = "earpiece_${dev.id}",
                                name = "Phone Earpiece",
                                type = AudioDeviceType.EARPIECE,
                                rawDevice = dev
                            )
                        )
                    }
                }
            }
            val hasBtInComm = deviceList.any { it.type == AudioDeviceType.BLUETOOTH }
            if (!hasBtInComm) {
                try {
                    val allDevices = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
                    val btOutput = allDevices.firstOrNull {
                        it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        it.type == android.media.AudioDeviceInfo.TYPE_HEARING_AID
                    }
                    if (btOutput != null) {
                        val rawName = btOutput.productName?.toString()?.ifBlank { "Bluetooth" } ?: "Bluetooth"
                        val name = if (rawName.startsWith("Bluetooth", ignoreCase = true)) rawName else "Bluetooth ($rawName)"
                        deviceList.add(
                            AudioDeviceOption(
                                id = "bt_${btOutput.id}",
                                name = name,
                                type = AudioDeviceType.BLUETOOTH,
                                rawDevice = btOutput
                            )
                        )
                    }
                } catch (_: Exception) {}
            }
            if (!hasSpeaker) {
                deviceList.add(
                    AudioDeviceOption(
                        id = "speaker_default",
                        name = "Speaker",
                        type = AudioDeviceType.SPEAKERPHONE
                    )
                )
            }
            if (!hasEarpiece) {
                deviceList.add(
                    AudioDeviceOption(
                        id = "earpiece_default",
                        name = "Phone Earpiece",
                        type = AudioDeviceType.EARPIECE
                    )
                )
            }
        } else {
            // Legacy Android (< API 31)
            var isBtConnected = false
            try {
                val btAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                if (btAdapter != null && btAdapter.isEnabled) {
                    val headsetState = btAdapter.getProfileConnectionState(android.bluetooth.BluetoothProfile.HEADSET)
                    val a2dpState = btAdapter.getProfileConnectionState(android.bluetooth.BluetoothProfile.A2DP)
                    if (headsetState == android.bluetooth.BluetoothProfile.STATE_CONNECTED ||
                        a2dpState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                        isBtConnected = true
                    }
                }
            } catch (_: Exception) {}
            
            if (isBtConnected) {
                deviceList.add(
                    AudioDeviceOption(
                        id = "bt_legacy",
                        name = "Bluetooth Headset",
                        type = AudioDeviceType.BLUETOOTH
                    )
                )
            }
            
            @Suppress("DEPRECATION")
            if (am.isWiredHeadsetOn) {
                deviceList.add(
                    AudioDeviceOption(
                        id = "wired_legacy",
                        name = "Wired Headset",
                        type = AudioDeviceType.WIRED_HEADSET
                    )
                )
            }
            
            deviceList.add(
                AudioDeviceOption(
                    id = "speaker_legacy",
                    name = "Speaker",
                    type = AudioDeviceType.SPEAKERPHONE
                )
            )
            deviceList.add(
                AudioDeviceOption(
                    id = "earpiece_legacy",
                    name = "Phone Earpiece",
                    type = AudioDeviceType.EARPIECE
                )
            )
        }

        val currentSelected = _state.value.selectedAudioDevice
        val hadBluetoothBefore = _state.value.availableAudioDevices.any { it.type == AudioDeviceType.BLUETOOTH }
        val hasBluetoothNow = deviceList.any { it.type == AudioDeviceType.BLUETOOTH }
        val btOption = deviceList.firstOrNull { it.type == AudioDeviceType.BLUETOOTH }
        val wiredOption = deviceList.firstOrNull { it.type == AudioDeviceType.WIRED_HEADSET }
        val speakerOption = deviceList.firstOrNull { it.type == AudioDeviceType.SPEAKERPHONE }
        val earpieceOption = deviceList.firstOrNull { it.type == AudioDeviceType.EARPIECE }
        val targetDevice: AudioDeviceOption

        val now = System.currentTimeMillis()
        if (defaultCallType == null && (now - lastUserExplicitSelectionTime < 2500L)) {
            val explicitType = userExplicitSelectedDevice ?: currentSelected
            val target = deviceList.firstOrNull { it.type == explicitType } 
                ?: (earpieceOption ?: speakerOption ?: deviceList.first())
            val updatedList = deviceList.map { 
                it.copy(isSelected = it.id == target.id || it.type == target.type)
            }
            _state.value = _state.value.copy(
                availableAudioDevices = updatedList,
                selectedAudioDevice = target.type,
                activeAudioDeviceName = target.name,
                isSpeakerOn = target.type == AudioDeviceType.SPEAKERPHONE
            )
            return
        }

        if (defaultCallType != null) {
            // Call start: Default device routing
            targetDevice = when {
                btOption != null -> btOption
                wiredOption != null -> wiredOption
                defaultCallType == CallType.VIDEO -> speakerOption ?: earpieceOption ?: deviceList.first()
                else -> earpieceOption ?: speakerOption ?: deviceList.first()
            }
            selectAudioDevice(targetDevice, updateDeviceList = false)
        } else {
            targetDevice = when {
                // Case 1: Bluetooth was newly connected during the call -> Auto-switch to Bluetooth!
                !hadBluetoothBefore && hasBluetoothNow && btOption != null -> {
                    if (currentSelected != AudioDeviceType.BLUETOOTH) {
                        lastNonBluetoothAudioDevice = currentSelected
                    }
                    Log.i("WebRtcEngine", "🎧 Bluetooth connected during call -> Auto-switching to Bluetooth (Saved previous: $lastNonBluetoothAudioDevice)")
                    btOption
                }
                // Case 2: Bluetooth was disconnected during the call -> Revert to previous non-bluetooth state!
                hadBluetoothBefore && !hasBluetoothNow && currentSelected == AudioDeviceType.BLUETOOTH -> {
                    Log.i("WebRtcEngine", "🎧 Bluetooth disconnected during call -> Reverting to: $lastNonBluetoothAudioDevice")
                    when (lastNonBluetoothAudioDevice) {
                        AudioDeviceType.SPEAKERPHONE -> speakerOption ?: earpieceOption ?: deviceList.first()
                        AudioDeviceType.WIRED_HEADSET -> wiredOption ?: earpieceOption ?: deviceList.first()
                        else -> earpieceOption ?: speakerOption ?: deviceList.first()
                    }
                }
                // Case 3: Keep the user's currently selected device if still available
                else -> {
                    val matchedCurrent = deviceList.firstOrNull { it.type == currentSelected }
                    matchedCurrent ?: (earpieceOption ?: speakerOption ?: deviceList.first())
                }
            }
            selectAudioDevice(targetDevice, updateDeviceList = false)
        }
        
        val updatedList = deviceList.map { 
            it.copy(isSelected = it.id == targetDevice.id || it.type == targetDevice.type)
        }
        
        _state.value = _state.value.copy(
            availableAudioDevices = updatedList,
            selectedAudioDevice = targetDevice.type,
            activeAudioDeviceName = targetDevice.name,
            isSpeakerOn = targetDevice.type == AudioDeviceType.SPEAKERPHONE
        )
    }

    fun selectAudioDevice(device: AudioDeviceOption, updateDeviceList: Boolean = true) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        
        if (updateDeviceList) {
            lastUserExplicitSelectionTime = System.currentTimeMillis()
            userExplicitSelectedDevice = device.type
        }
        
        if (device.type != AudioDeviceType.BLUETOOTH) {
            lastNonBluetoothAudioDevice = device.type
        }
        
        Log.i("WebRtcEngine", "🔊 Switching audio route to: ${device.type} (${device.name})")

        try {
            am.mode = android.media.AudioManager.MODE_IN_COMMUNICATION

            // Guarantee audio focus so Android and Samsung Audio HAL permit communication device switching
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val focusRequest = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                    .build()
                audioFocusRequest = focusRequest
                am.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(audioFocusChangeListener, android.media.AudioManager.STREAM_VOICE_CALL, android.media.AudioManager.AUDIOFOCUS_GAIN)
            }

            // Sync with Telecom Connection if active (critical for Android Telecom managed audio)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                try {
                    val telecomRoute = when (device.type) {
                        AudioDeviceType.SPEAKERPHONE -> android.telecom.CallAudioState.ROUTE_SPEAKER
                        AudioDeviceType.BLUETOOTH -> android.telecom.CallAudioState.ROUTE_BLUETOOTH
                        AudioDeviceType.EARPIECE -> android.telecom.CallAudioState.ROUTE_EARPIECE
                        AudioDeviceType.WIRED_HEADSET -> android.telecom.CallAudioState.ROUTE_WIRED_HEADSET
                    }
                    com.example.services.LksConnectionService.setAudioRoute(telecomRoute)
                } catch (e: Exception) {
                    Log.w("WebRtcEngine", "Failed to set Telecom audio route: ${e.message}")
                }
            }

            when (device.type) {
                AudioDeviceType.SPEAKERPHONE -> {
                    // Stop any active Bluetooth SCO so it doesn't intercept speaker audio on Samsung / Android
                    @Suppress("DEPRECATION")
                    try {
                        if (am.isBluetoothScoOn) {
                            am.isBluetoothScoOn = false
                            am.stopBluetoothSco()
                        }
                    } catch (_: Exception) {}
                    
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        val speaker = (device.rawDevice as? android.media.AudioDeviceInfo)
                            ?: am.availableCommunicationDevices.firstOrNull { 
                                it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER 
                            }
                        if (speaker != null) {
                            val res = am.setCommunicationDevice(speaker)
                            Log.d("WebRtcEngine", "setCommunicationDevice(SPEAKER): $res")
                            if (!res) {
                                am.clearCommunicationDevice()
                                am.setCommunicationDevice(speaker)
                            }
                        }
                    }
                    @Suppress("DEPRECATION")
                    am.isSpeakerphoneOn = true
                }
                AudioDeviceType.EARPIECE -> {
                    @Suppress("DEPRECATION")
                    am.isSpeakerphoneOn = false
                    @Suppress("DEPRECATION")
                    try {
                        if (am.isBluetoothScoOn) {
                            am.isBluetoothScoOn = false
                            am.stopBluetoothSco()
                        }
                    } catch (_: Exception) {}

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        val earpiece = (device.rawDevice as? android.media.AudioDeviceInfo)
                            ?: am.availableCommunicationDevices.firstOrNull { 
                                it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE 
                            }
                        if (earpiece != null) {
                            val res = am.setCommunicationDevice(earpiece)
                            Log.d("WebRtcEngine", "setCommunicationDevice(EARPIECE): $res")
                            if (!res) {
                                am.clearCommunicationDevice()
                                am.setCommunicationDevice(earpiece)
                            }
                        } else {
                            am.clearCommunicationDevice()
                        }
                    }
                }
                AudioDeviceType.BLUETOOTH -> {
                    @Suppress("DEPRECATION")
                    am.isSpeakerphoneOn = false
                    
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        am.clearCommunicationDevice()
                        val bt = if (device.rawDevice is android.media.AudioDeviceInfo) {
                            device.rawDevice as android.media.AudioDeviceInfo
                        } else {
                            am.availableCommunicationDevices.firstOrNull { 
                                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO || 
                                it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET ||
                                it.type == android.media.AudioDeviceInfo.TYPE_HEARING_AID
                            }
                        }
                        if (bt != null) {
                            val res = am.setCommunicationDevice(bt)
                            Log.d("WebRtcEngine", "setCommunicationDevice(BLUETOOTH - ${bt.productName}): $res, type=${bt.type}")
                            if (!res) {
                                @Suppress("DEPRECATION")
                                try {
                                    am.startBluetoothSco()
                                    am.isBluetoothScoOn = true
                                } catch (_: Exception) {}
                            }
                        } else {
                            @Suppress("DEPRECATION")
                            try {
                                am.startBluetoothSco()
                                am.isBluetoothScoOn = true
                            } catch (_: Exception) {}
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        try {
                            am.startBluetoothSco()
                            am.isBluetoothScoOn = true
                        } catch (_: Exception) {}
                    }
                }
                AudioDeviceType.WIRED_HEADSET -> {
                    @Suppress("DEPRECATION")
                    am.isSpeakerphoneOn = false
                    @Suppress("DEPRECATION")
                    try { am.stopBluetoothSco(); am.isBluetoothScoOn = false } catch (_: Exception) {}

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        am.clearCommunicationDevice()
                        val wired = if (device.rawDevice is android.media.AudioDeviceInfo) {
                            device.rawDevice as android.media.AudioDeviceInfo
                        } else {
                            am.availableCommunicationDevices.firstOrNull { 
                                it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET || 
                                it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                                it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET ||
                                it.type == android.media.AudioDeviceInfo.TYPE_USB_DEVICE
                            }
                        }
                        if (wired != null) {
                            val res = am.setCommunicationDevice(wired)
                            Log.d("WebRtcEngine", "setCommunicationDevice(WIRED - ${wired.productName}): $res")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("WebRtcEngine", "Error setting audio device: ${device.name}", e)
        }

        if (updateDeviceList) {
            val updatedList = _state.value.availableAudioDevices.map { 
                it.copy(isSelected = it.id == device.id || it.type == device.type)
            }
            _state.value = _state.value.copy(
                selectedAudioDevice = device.type,
                activeAudioDeviceName = device.name,
                isSpeakerOn = device.type == AudioDeviceType.SPEAKERPHONE,
                availableAudioDevices = updatedList
            )
        }
    }

    fun selectAudioDeviceType(type: AudioDeviceType) {
        val target = _state.value.availableAudioDevices.firstOrNull { it.type == type }
            ?: AudioDeviceOption(
                id = "${type.name.lowercase()}_default",
                name = when (type) {
                    AudioDeviceType.BLUETOOTH -> "Bluetooth"
                    AudioDeviceType.SPEAKERPHONE -> "Speaker"
                    AudioDeviceType.WIRED_HEADSET -> "Wired Headset"
                    AudioDeviceType.EARPIECE -> "Phone Earpiece"
                },
                type = type
            )
        selectAudioDevice(target)
    }

    fun onTelecomAudioRouteChanged(targetType: AudioDeviceType) {
        val now = System.currentTimeMillis()
        val explicit = userExplicitSelectedDevice

        // 1. Within 5 seconds of explicit user selection, strictly ignore ANY Telecom transition echo back to a different route
        if (now - lastUserExplicitSelectionTime < 5000L) {
            if (explicit != null && explicit != targetType) {
                Log.d("WebRtcEngine", "Ignoring Telecom route change to $targetType (user explicitly selected $explicit ${now - lastUserExplicitSelectionTime}ms ago)")
                return
            }
        }

        // 2. Permanent guards against Telecom/Samsung automatically snapping back to Bluetooth or Earpiece:
        // If user chose SPEAKERPHONE, do not allow Telecom to automatically revert to EARPIECE or BLUETOOTH
        if (explicit == AudioDeviceType.SPEAKERPHONE && (targetType == AudioDeviceType.EARPIECE || targetType == AudioDeviceType.BLUETOOTH)) {
            Log.d("WebRtcEngine", "Ignoring Telecom automatic transition to $targetType because user explicitly chose SPEAKERPHONE")
            return
        }
        // If user chose EARPIECE, do not allow Telecom to automatically revert to BLUETOOTH
        if (explicit == AudioDeviceType.EARPIECE && targetType == AudioDeviceType.BLUETOOTH) {
            Log.d("WebRtcEngine", "Ignoring Telecom automatic transition to BLUETOOTH because user explicitly chose EARPIECE")
            return
        }

        // 3. Genuine external change (e.g. user toggled output via Samsung system Quick Panel or Bluetooth headset button)
        if (_state.value.selectedAudioDevice != targetType) {
            Log.i("WebRtcEngine", "Syncing route from Telecom: $targetType")
            userExplicitSelectedDevice = targetType
            selectAudioDeviceType(targetType)
        }
    }

    fun toggleSpeaker() {
        if (_state.value.availableAudioDevices.isEmpty()) {
            refreshAvailableAudioDevices()
        }
        val current = _state.value.selectedAudioDevice
        if (current == AudioDeviceType.SPEAKERPHONE) {
            val bt = _state.value.availableAudioDevices.firstOrNull { it.type == AudioDeviceType.BLUETOOTH }
            val wired = _state.value.availableAudioDevices.firstOrNull { it.type == AudioDeviceType.WIRED_HEADSET }
            val earpiece = _state.value.availableAudioDevices.firstOrNull { it.type == AudioDeviceType.EARPIECE }
            val target = bt ?: wired ?: earpiece ?: AudioDeviceOption(id = "earpiece_default", name = "Phone Earpiece", type = AudioDeviceType.EARPIECE)
            selectAudioDevice(target)
        } else {
            val speaker = _state.value.availableAudioDevices.firstOrNull { it.type == AudioDeviceType.SPEAKERPHONE }
                ?: AudioDeviceOption(id = "speaker_default", name = "Speaker", type = AudioDeviceType.SPEAKERPHONE)
            selectAudioDevice(speaker)
        }
    }

    fun toggleCamera() {
        val newCameraOn = !_state.value.isCameraOn
        _state.value.localVideoTrack?.setEnabled(newCameraOn)
        _state.value = _state.value.copy(isCameraOn = newCameraOn)
    }

    fun switchCamera() {
        if (videoCapturer is CameraVideoCapturer) {
            val cameraCapturer = videoCapturer as CameraVideoCapturer
            cameraCapturer.switchCamera(null)
            _state.value = _state.value.copy(isFrontCamera = !_state.value.isFrontCamera)
        }
    }

    fun endCall() {
        val call = _state.value.activeCall
        if (call != null) {
            val endStatus = if (_state.value.callStatus == CallStatus.CALLING || _state.value.callStatus == CallStatus.RINGING) CallStatus.MISSED else CallStatus.ENDED
            firestore.collection("calls").document(call.callId).update(
                "status", endStatus.name,
                "endedAt", System.currentTimeMillis()
            )
            
            // If caller hangs up before it's answered, write a missed call log and send missed call push to callee
            val currentPhone = FirebaseManager.getInstance(context).currentUser.value?.phoneNumber ?: myPhoneNumber
            val isCaller = currentPhone.isNotBlank() && (
                currentPhone == call.callerNumber ||
                currentPhone.replace(Regex("[^0-9]"), "") == call.callerNumber.replace(Regex("[^0-9]"), "")
            )
            if (isCaller && endStatus == CallStatus.MISSED) {
                FirebaseManager.getInstance(context).logMissedCallForOfflineUser(
                    calleeNumber = call.calleeNumber,
                    callerNumber = call.callerNumber,
                    callerName = call.callerName,
                    callType = call.callType
                )
                triggerPushNotification(
                    calleeNumber = call.calleeNumber,
                    callerName = call.callerName,
                    callerNumber = call.callerNumber,
                    callType = call.callType.name,
                    callId = call.callId,
                    type = "missed_call"
                )
            }
            // Auto-cleanup call and candidate documents after 30 seconds to keep DB lean & fast
            val callIdToDelete = call.callId
            scope.launch {
                delay(30000)
                deleteCallAndCandidates(callIdToDelete)
            }
        }
        endCallInternalLocal(CallStatus.ENDED)
    }

    private fun deleteCallAndCandidates(callId: String) {
        try {
            val callRef = firestore.collection("calls").document(callId)
            callRef.collection("candidates").get().addOnSuccessListener { snap ->
                val batch = firestore.batch()
                for (doc in snap.documents) {
                    batch.delete(doc.reference)
                }
                batch.delete(callRef)
                batch.commit()
            }.addOnFailureListener {
                try { callRef.delete() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    /**
     * Forces the engine to end the call immediately when a 'cancel_call' or 'missed_call' push notification
     * is received, preventing the UI from lingering in a ringing state.
     */
    fun forceEndCallFromPush(callId: String) {
        scope.launch {
            val activeCallId = _state.value.activeCall?.callId
            if (activeCallId == null || activeCallId == callId || _state.value.callStatus == CallStatus.RINGING || _state.value.callStatus == CallStatus.CALLING) {
                Log.d("WebRtcEngine", "forceEndCallFromPush: ending active call $activeCallId for push $callId")
                endCallInternalLocal(CallStatus.MISSED)
            }
        }
    }

    private fun endCallInternalLocal(status: CallStatus) {
        // Guard: prevent double-cleanup
        if (_state.value.callStatus == CallStatus.IDLE && status != CallStatus.IDLE) return
        
        timerJob?.cancel()
        timerJob = null
        activeCallListener?.remove()
        activeCallListener = null
        iceCandidateListener?.remove()
        iceCandidateListener = null
        
        // Reset flags for next call
        hasProcessedOffer = false
        hasProcessedAnswer = false
        sentIceCandidateHashes.clear()
        synchronized(queuedRemoteIceCandidates) {
            queuedRemoteIceCandidates.clear()
        }

        try {
            if (incomingCallWakeLock?.isHeld == true) incomingCallWakeLock?.release()
        } catch (_: Exception) {}
        incomingCallWakeLock = null
        
        try { localAudioTrack?.dispose() } catch (_: Exception) {}
        localAudioTrack = null
        try { audioSource?.dispose() } catch (_: Exception) {}
        audioSource = null

        try { _state.value.localVideoTrack?.dispose() } catch (_: Exception) {}
        try { _state.value.remoteVideoTrack?.dispose() } catch (_: Exception) {}
        
        try {
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
        } catch (_: Exception) {}
        videoCapturer = null
        try { videoSource?.dispose() } catch (_: Exception) {}
        videoSource = null
        try { surfaceTextureHelper?.dispose() } catch (_: Exception) {}
        surfaceTextureHelper = null
        
        try {
            peerConnection?.close()
            peerConnection?.dispose()
        } catch (_: Exception) {}
        peerConnection = null
        
        val prevCall = _state.value.activeCall
        _state.value = _state.value.copy(
            callStatus = status,
            activeCall = prevCall,
            connectionStatusText = when (status) {
                CallStatus.ENDED -> "Call Ended"
                CallStatus.DECLINED -> "Call Declined"
                CallStatus.MISSED -> "Call Missed"
                else -> status.name
            },
            localVideoTrack = null,
            remoteVideoTrack = null
        )
        
        // Briefly delay then reset to IDLE so the UI can show the end status
        scope.launch {
            delay(1500)
            if (_state.value.callStatus != CallStatus.ANSWERED && _state.value.callStatus != CallStatus.CALLING && _state.value.callStatus != CallStatus.RINGING) {
                _state.value = WebRtcState(callStatus = CallStatus.IDLE)
            }
        }
        
        // Reset audio routing
        unregisterAudioDeviceListeners()
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(audioFocusChangeListener)
            }
            audioFocusRequest = null

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                // Always stop BT SCO first — clearCommunicationDevice alone won't tear down the SCO link
                @Suppress("DEPRECATION")
                try { am.stopBluetoothSco(); am.isBluetoothScoOn = false } catch (_: Exception) {}
                am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                try { am.stopBluetoothSco(); am.isBluetoothScoOn = false } catch (_: Exception) {}
            }
            am.mode = android.media.AudioManager.MODE_NORMAL
            am.isSpeakerphoneOn = false
        } catch (_: Exception) {}
        
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            notificationManager.cancel(1001)
        } catch (_: Exception) {}
        
        try {
            headsetButtonManager.stopListening()
        } catch (_: Exception) {}

        try {
            if (samsungVoiceFocusEffect != null) {
                val releaseMethod = samsungVoiceFocusEffect?.javaClass?.getMethod("release")
                releaseMethod?.invoke(samsungVoiceFocusEffect)
            }
        } catch (_: Exception) {}
        samsungVoiceFocusEffect = null
        isCellularCallInterrupting = false

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                com.example.services.LksConnectionService.disconnectCall()
            } catch (_: Exception) {}
        }

        com.example.services.ActiveCallService.stop(context)
        com.example.services.FloatingCallBubbleService.hide(context)
        com.example.util.LksIncomingRingtonePlayer.stop()
    }

    private fun triggerPushNotification(calleeNumber: String, callerName: String, callerNumber: String, callType: String, callId: String, type: String = "incoming_call") {
        val variations = mutableListOf<String>()
        variations.add(calleeNumber)
        val cleanDigits = calleeNumber.replace(Regex("[^0-9]"), "")
        if (cleanDigits.isNotBlank()) {
            variations.add(cleanDigits)
            if (cleanDigits.length > 10) {
                variations.add(cleanDigits.takeLast(10))
            }
            if (!calleeNumber.startsWith("+")) {
                variations.add("+$calleeNumber")
            }
        }
        val distinctVariations = variations.distinct().take(10)

        fun sendPush(fcmToken: String, webToken: String) {
            val workerUrl = com.example.BuildConfig.CALL_WORKER_URL
            val workerSecret = com.example.BuildConfig.CALL_WORKER_SECRET
            
            Thread {
                var conn: java.net.HttpURLConnection? = null
                try {
                    val url = java.net.URL(workerUrl)
                    conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("X-Worker-Secret", workerSecret)
                        doOutput = true
                    }
                    
                    val jsonObj = org.json.JSONObject().apply {
                        put("token", fcmToken)
                        put("webToken", webToken)
                        put("callerName", callerName)
                        put("callerNumber", callerNumber)
                        put("callType", callType)
                        put("callId", callId)
                        put("type", type)
                    }
                    val json = jsonObj.toString()
                    
                    conn.outputStream.use { it.write(json.toByteArray()) }
                    val responseCode = conn.responseCode
                    android.util.Log.d("WebRtcEngine", "Push Trigger Response: $responseCode")
                } catch (e: Exception) {
                    android.util.Log.e("WebRtcEngine", "Failed to trigger push", e)
                } finally {
                    conn?.disconnect()
                }
            }.start()
        }

        fun tryDirectLookup(index: Int) {
            if (index >= distinctVariations.size) return
            val docId = distinctVariations[index]
            firestore.collection("users").document(docId).get().addOnSuccessListener { doc ->
                val fcmToken = doc.getString("fcmToken") ?: ""
                val webToken = doc.getString("webToken") ?: ""
                if (fcmToken.isNotEmpty() || webToken.isNotEmpty()) {
                    sendPush(fcmToken, webToken)
                } else {
                    tryDirectLookup(index + 1)
                }
            }.addOnFailureListener {
                tryDirectLookup(index + 1)
            }
        }

        firestore.collection("users").whereIn("phoneNumber", distinctVariations).get().addOnSuccessListener { querySnapshot ->
            val userDoc = querySnapshot.documents.firstOrNull { 
                !it.getString("fcmToken").isNullOrEmpty() || !it.getString("webToken").isNullOrEmpty() 
            }
            val fcmToken = userDoc?.getString("fcmToken") ?: ""
            val webToken = userDoc?.getString("webToken") ?: ""
            
            if (fcmToken.isNotEmpty() || webToken.isNotEmpty()) {
                sendPush(fcmToken, webToken)
            } else {
                tryDirectLookup(0)
            }
        }.addOnFailureListener {
            tryDirectLookup(0)
        }
    }

    fun formatDuration(seconds: Int): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return String.format("%02d:%02d", mins, secs)
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) {}
        override fun onSetFailure(p0: String?) {}
    }

    companion object {
        @Volatile
        private var INSTANCE: WebRtcEngine? = null

        fun getInstance(context: Context): WebRtcEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WebRtcEngine(context.applicationContext).also { INSTANCE = it }
            }
        }
        
        fun getInstanceIfCreated(): WebRtcEngine? {
            return INSTANCE
        }
    }
}
