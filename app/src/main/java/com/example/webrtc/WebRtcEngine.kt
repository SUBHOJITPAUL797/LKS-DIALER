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
    val networkQualityBars: Int = 5,
    val connectionStatusText: String = "Idle",
    val localVideoTrack: VideoTrack? = null,
    val remoteVideoTrack: VideoTrack? = null,
    val isVideoUpgradeRequested: Boolean = false,
    val didIRequestVideoUpgrade: Boolean = false
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

    private val firestore = FirebaseFirestore.getInstance()
    private var myPhoneNumber: String = ""
    
    private var incomingCallListener: ListenerRegistration? = null
    private var activeCallListener: ListenerRegistration? = null
    private var iceCandidateListener: ListenerRegistration? = null
    
    private val seenCallIds = mutableSetOf<String>()

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
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
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
        
        val audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
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
        val audioConstraints = MediaConstraints()
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
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        
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
                        peerConnection?.addIceCandidate(candidate)
                    }
                }
            }
    }

    fun initiateCall(calleeNumber: String, calleeName: String, callerNumber: String, callerName: String, callType: CallType) {
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
                if (_state.value.callStatus == CallStatus.CALLING) endCall()
                return@launch
            }
            
            // If it reached RINGING, give it another 45 seconds to answer
            delay(45000)
            if ((_state.value.callStatus == CallStatus.CALLING || _state.value.callStatus == CallStatus.RINGING) && _state.value.activeCall?.callId == newCall.callId) {
                _state.value = _state.value.copy(connectionStatusText = "No Answer")
                delay(2000)
                if (_state.value.callStatus == CallStatus.CALLING || _state.value.callStatus == CallStatus.RINGING) endCall()
            }
        }
        
        createPeerConnection(isCaller = true, callId = newCall.callId)
        
        val constraints = MediaConstraints()
        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (callType == CallType.VIDEO) "true" else "false"))
        
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc != null) {
                    peerConnection?.setLocalDescription(SimpleSdpObserver(), desc)
                    firestore.collection("calls").document(newCall.callId).update("offerSdp", desc.description)
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
                val incomingCall = snapshot?.documents?.mapNotNull { it.toObject(CallDto::class.java) }
                    ?.filter { it.status == CallStatus.CALLING || it.status == CallStatus.RINGING }
                    ?.maxByOrNull { it.createdAt }

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

                    // Wake screen and bring incoming call overlay to front if in background
                    try {
                        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                        val wl = pm?.newWakeLock(
                            android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                            android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                            android.os.PowerManager.ON_AFTER_RELEASE,
                            "lksdialer:incoming_call_wake_engine"
                        )
                        wl?.acquire(15000)

                        val launchIntent = Intent(context, com.example.MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            putExtra("incoming_call", true)
                            putExtra("call_id", incomingCall.callId)
                            putExtra("caller_name", incomingCall.callerName)
                            putExtra("caller_number", incomingCall.callerNumber)
                            putExtra("call_type", incomingCall.callType.name)
                        }
                        context.startActivity(launchIntent)
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
        if (hasProcessedOffer) return
        val call = _state.value.activeCall ?: return
        val offerSdp = call.offerSdp ?: return
        if (peerConnection == null || peerConnection?.remoteDescription != null) return
        
        hasProcessedOffer = true
        val sessionDescription = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
        peerConnection?.setRemoteDescription(SimpleSdpObserver(), sessionDescription)
        
        val constraints = MediaConstraints()
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc != null) {
                    peerConnection?.setLocalDescription(SimpleSdpObserver(), desc)
                    firestore.collection("calls").document(call.callId).update("answerSdp", desc.description)
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    private fun configureAudio(callType: CallType) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        am.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
        
        registerAudioDeviceListeners()
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val focusRequest = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener { }
                .build()
            am.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, android.media.AudioManager.STREAM_VOICE_CALL, android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }

        refreshAvailableAudioDevices(defaultCallType = callType)
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

                        if (isCaller && call.answerSdp != null && call.answerSdp != oldCall?.answerSdp) {
                            hasProcessedAnswer = true
                            val sessionDescription = SessionDescription(SessionDescription.Type.ANSWER, call.answerSdp)
                            peerConnection?.setRemoteDescription(SimpleSdpObserver(), sessionDescription)
                            if (_state.value.callStatus != CallStatus.ANSWERED) {
                                _state.value = _state.value.copy(
                                    callStatus = CallStatus.ANSWERED,
                                    connectionStatusText = "Connected • WebRTC"
                                )
                                configureAudio(_state.value.callType)
                                startCallTimer()
                            }
                        }
                        
                        if (!isCaller && call.offerSdp != null && call.offerSdp != oldCall?.offerSdp) {
                            // New offer received (e.g. during video upgrade renegotiation)
                            if (_state.value.callStatus == CallStatus.ANSWERED) {
                                val sessionDescription = SessionDescription(SessionDescription.Type.OFFER, call.offerSdp)
                                peerConnection?.setRemoteDescription(SimpleSdpObserver(), sessionDescription)
                                
                                val constraints = MediaConstraints()
                                constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                                constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                                
                                peerConnection?.createAnswer(object : SimpleSdpObserver() {
                                    override fun onCreateSuccess(desc: SessionDescription?) {
                                        peerConnection?.setLocalDescription(SimpleSdpObserver(), desc)
                                        desc?.let {
                                            firestore.collection("calls").document(call.callId)
                                                .update("answerSdp", it.description)
                                        }
                                    }
                                }, constraints)
                            } else {
                                processOfferSdpIfAvailable()
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
                    peerConnection?.setLocalDescription(SimpleSdpObserver(), desc)
                    desc?.let {
                        firestore.collection("calls").document(currentCall.callId)
                            .update(
                                "offerSdp", it.description,
                                "callType", CallType.VIDEO.name
                            )
                    }
                }
            }, constraints)
        }
    }

    private fun startCallTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            var seconds = 0
            while (_state.value.callStatus == CallStatus.ANSWERED) {
                delay(1000)
                seconds++
                _state.value = _state.value.copy(callDurationSeconds = seconds)
            }
        }
    }

    fun toggleMute() {
        val newMuted = !_state.value.isMuted
        localAudioTrack?.setEnabled(!newMuted)
        _state.value = _state.value.copy(isMuted = newMuted)
    }

    private fun registerAudioDeviceListeners() {
        unregisterAudioDeviceListeners()
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val callback = object : android.media.AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>?) {
                    scope.launch {
                        delay(400)
                        refreshAvailableAudioDevices()
                    }
                }
                override fun onAudioDevicesRemoved(removedDevices: Array<out android.media.AudioDeviceInfo>?) {
                    scope.launch {
                        delay(400)
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
                addAction(android.bluetooth.BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
                addAction(android.media.AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
                addAction(android.content.Intent.ACTION_HEADSET_PLUG)
            }
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: android.content.Intent?) {
                    scope.launch {
                        delay(400)
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

        // Determine which device should be active
        val currentSelected = _state.value.selectedAudioDevice
        val targetDevice: AudioDeviceOption
        
        if (defaultCallType != null) {
            val btOption = deviceList.firstOrNull { it.type == AudioDeviceType.BLUETOOTH }
            val wiredOption = deviceList.firstOrNull { it.type == AudioDeviceType.WIRED_HEADSET }
            val speakerOption = deviceList.firstOrNull { it.type == AudioDeviceType.SPEAKERPHONE }
            val earpieceOption = deviceList.firstOrNull { it.type == AudioDeviceType.EARPIECE }
            
            targetDevice = when {
                btOption != null -> btOption
                wiredOption != null -> wiredOption
                defaultCallType == CallType.VIDEO -> speakerOption ?: earpieceOption ?: deviceList.first()
                else -> earpieceOption ?: speakerOption ?: deviceList.first()
            }
            selectAudioDevice(targetDevice, updateDeviceList = false)
        } else {
            val hadBluetoothBefore = _state.value.availableAudioDevices.any { it.type == AudioDeviceType.BLUETOOTH }
            val hasBluetoothNow = deviceList.any { it.type == AudioDeviceType.BLUETOOTH }
            val btOption = deviceList.firstOrNull { it.type == AudioDeviceType.BLUETOOTH }
            
            targetDevice = if (!hadBluetoothBefore && hasBluetoothNow && btOption != null) {
                // Auto-switch to newly connected Bluetooth device
                btOption
            } else {
                val matchedCurrent = deviceList.firstOrNull { it.type == currentSelected }
                if (matchedCurrent != null) {
                    matchedCurrent
                } else {
                    val wiredOption = deviceList.firstOrNull { it.type == AudioDeviceType.WIRED_HEADSET }
                    val speakerOption = deviceList.firstOrNull { it.type == AudioDeviceType.SPEAKERPHONE }
                    val earpieceOption = deviceList.firstOrNull { it.type == AudioDeviceType.EARPIECE }
                    btOption ?: wiredOption ?: (if (_state.value.callType == CallType.VIDEO) speakerOption else earpieceOption) ?: deviceList.first()
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
        
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                if (device.rawDevice is android.media.AudioDeviceInfo) {
                    val success = am.setCommunicationDevice(device.rawDevice as android.media.AudioDeviceInfo)
                    Log.d("WebRtcEngine", "setCommunicationDevice(${device.name}): $success")
                    am.isSpeakerphoneOn = device.type == AudioDeviceType.SPEAKERPHONE
                } else {
                    val commDevices = am.availableCommunicationDevices
                    val target = when (device.type) {
                        AudioDeviceType.BLUETOOTH -> commDevices.firstOrNull { 
                            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO || 
                            it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET ||
                            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                        }
                        AudioDeviceType.WIRED_HEADSET -> commDevices.firstOrNull {
                            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                            it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
                        }
                        AudioDeviceType.SPEAKERPHONE -> commDevices.firstOrNull {
                            it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                        }
                        AudioDeviceType.EARPIECE -> commDevices.firstOrNull {
                            it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                        }
                    }
                    if (target != null) {
                        am.setCommunicationDevice(target)
                    } else {
                        if (device.type == AudioDeviceType.SPEAKERPHONE) {
                            am.clearCommunicationDevice()
                            am.isSpeakerphoneOn = true
                        } else {
                            am.clearCommunicationDevice()
                            am.isSpeakerphoneOn = false
                        }
                    }
                }
            } else {
                // Legacy Android (< API 31)
                when (device.type) {
                    AudioDeviceType.BLUETOOTH -> {
                        am.isSpeakerphoneOn = false
                        @Suppress("DEPRECATION")
                        am.startBluetoothSco()
                        @Suppress("DEPRECATION")
                        am.isBluetoothScoOn = true
                    }
                    AudioDeviceType.SPEAKERPHONE -> {
                        @Suppress("DEPRECATION")
                        try { am.stopBluetoothSco(); am.isBluetoothScoOn = false } catch (_: Exception) {}
                        am.isSpeakerphoneOn = true
                    }
                    AudioDeviceType.EARPIECE, AudioDeviceType.WIRED_HEADSET -> {
                        @Suppress("DEPRECATION")
                        try { am.stopBluetoothSco(); am.isBluetoothScoOn = false } catch (_: Exception) {}
                        am.isSpeakerphoneOn = false
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

    fun toggleSpeaker() {
        if (_state.value.availableAudioDevices.isEmpty()) {
            refreshAvailableAudioDevices()
        }
        val current = _state.value.selectedAudioDevice
        if (current == AudioDeviceType.SPEAKERPHONE) {
            val target = _state.value.availableAudioDevices.firstOrNull { it.type == AudioDeviceType.BLUETOOTH }
                ?: _state.value.availableAudioDevices.firstOrNull { it.type == AudioDeviceType.WIRED_HEADSET }
                ?: _state.value.availableAudioDevices.firstOrNull { it.type == AudioDeviceType.EARPIECE }
            if (target != null) {
                selectAudioDevice(target)
            }
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
            
            // If caller hangs up before it's answered, write a missed call log for the callee
            val isCaller = FirebaseManager.getInstance(context).currentUser.value?.phoneNumber == call.callerNumber
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
        }
        endCallInternalLocal(CallStatus.ENDED)
    }

    /**
     * Forces the engine to end the call immediately when a 'cancel_call' or 'missed_call' push notification
     * is received, preventing the UI from lingering in a ringing state.
     */
    fun forceEndCallFromPush(callId: String) {
        if (_state.value.activeCall?.callId == callId) {
            endCallInternalLocal(CallStatus.MISSED)
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
        
        try { localAudioTrack?.dispose() } catch (_: Exception) {}
        localAudioTrack = null
        try { _state.value.localVideoTrack?.dispose() } catch (_: Exception) {}
        try { _state.value.remoteVideoTrack?.dispose() } catch (_: Exception) {}
        
        try {
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
        } catch (_: Exception) {}
        videoCapturer = null
        try { surfaceTextureHelper?.dispose() } catch (_: Exception) {}
        surfaceTextureHelper = null
        
        try { peerConnection?.close() } catch (_: Exception) {}
        peerConnection = null
        
        _state.value = WebRtcState(callStatus = status)
        
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
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
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

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                com.example.services.LksConnectionService.disconnectCall()
            } catch (_: Exception) {}
        }

        com.example.services.ActiveCallService.stop(context)
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

        firestore.collection("users").whereIn("phoneNumber", distinctVariations).get().addOnSuccessListener { querySnapshot ->
            val userDoc = querySnapshot.documents.firstOrNull()
            val fcmToken = userDoc?.getString("fcmToken") ?: ""
            val webToken = userDoc?.getString("webToken") ?: ""
            
            if (fcmToken.isNotEmpty() || webToken.isNotEmpty()) {
                val workerUrl = com.example.BuildConfig.CALL_WORKER_URL
                val workerSecret = com.example.BuildConfig.CALL_WORKER_SECRET
                
                Thread {
                    try {
                        val url = java.net.URL(workerUrl)
                        val conn = url.openConnection() as java.net.HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.setRequestProperty("Content-Type", "application/json")
                        conn.setRequestProperty("X-Worker-Secret", workerSecret)
                        conn.doOutput = true
                        
                        val json = """
                            {
                                "token": "$fcmToken",
                                "webToken": "$webToken",
                                "callerName": "$callerName",
                                "callerNumber": "$callerNumber",
                                "callType": "$callType",
                                "callId": "$callId",
                                "type": "$type"
                            }
                        """.trimIndent()
                        
                        conn.outputStream.write(json.toByteArray())
                        val responseCode = conn.responseCode
                        android.util.Log.d("WebRtcEngine", "Push Trigger Response: $responseCode")
                    } catch (e: Exception) {
                        android.util.Log.e("WebRtcEngine", "Failed to trigger push", e)
                    }
                }.start()
            }
        }.addOnFailureListener {
            firestore.collection("users").document(calleeNumber).get().addOnSuccessListener { doc ->
                val fcmToken = doc.getString("fcmToken") ?: ""
                val webToken = doc.getString("webToken") ?: ""
                if (fcmToken.isNotEmpty() || webToken.isNotEmpty()) {
                    val workerUrl = com.example.BuildConfig.CALL_WORKER_URL
                    val workerSecret = com.example.BuildConfig.CALL_WORKER_SECRET
                    Thread {
                        try {
                            val url = java.net.URL(workerUrl)
                            val conn = url.openConnection() as java.net.HttpURLConnection
                            conn.requestMethod = "POST"
                            conn.setRequestProperty("Content-Type", "application/json")
                            conn.setRequestProperty("X-Worker-Secret", workerSecret)
                            conn.doOutput = true
                            val json = """
                                {
                                    "token": "$fcmToken",
                                    "webToken": "$webToken",
                                    "callerName": "$callerName",
                                    "callerNumber": "$callerNumber",
                                    "callType": "$callType",
                                    "callId": "$callId",
                                    "type": "$type"
                                }
                            """.trimIndent()
                            conn.outputStream.write(json.toByteArray())
                            val responseCode = conn.responseCode
                            android.util.Log.d("WebRtcEngine", "Fallback Push Trigger Response: $responseCode")
                        } catch (e: Exception) {
                            android.util.Log.e("WebRtcEngine", "Failed to trigger fallback push", e)
                        }
                    }.start()
                }
            }
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
