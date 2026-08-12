package com.example.webrtc

import android.content.Context
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

data class WebRtcState(
    val activeCall: CallDto? = null,
    val callStatus: CallStatus = CallStatus.IDLE,
    val callType: CallType = CallType.AUDIO,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val isCameraOn: Boolean = true,
    val isFrontCamera: Boolean = true,
    val callDurationSeconds: Int = 0,
    val networkQualityBars: Int = 5,
    val connectionStatusText: String = "Idle",
    val localVideoTrack: VideoTrack? = null,
    val remoteVideoTrack: VideoTrack? = null,
    val isVideoUpgradeRequested: Boolean = false
)

class WebRtcEngine private constructor(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var timerJob: Job? = null

    private var hasProcessedOffer = false
    private var hasProcessedAnswer = false

    private val _state = MutableStateFlow(WebRtcState())
    val state: StateFlow<WebRtcState> = _state.asStateFlow()

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
                    _state.value = _state.value.copy(connectionStatusText = "P2P Connected • WebRTC")
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
        triggerPushNotification(calleeNumber, callerName, callType.name, newCall.callId)
        
        com.example.services.ActiveCallService.start(context, newCall.callId, callType.name)
        
        // Timeout logic: if it stays in CALLING (offline) for 15s, or RINGING (no answer) for 45s, hang up.
        scope.launch {
            delay(15000) // Wait 15 seconds for RINGING
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
        if (myPhoneNumber == phoneNumber) return
        myPhoneNumber = phoneNumber
        incomingCallListener?.remove()

        incomingCallListener = firestore.collection("calls")
            .whereEqualTo("calleeNumber", phoneNumber)
            .addSnapshotListener { snapshot, e ->
                if (e != null) return@addSnapshotListener
                val incomingCall = snapshot?.documents?.mapNotNull { it.toObject(CallDto::class.java) }
                    ?.filter { it.status == CallStatus.CALLING || it.status == CallStatus.RINGING }
                    ?.maxByOrNull { it.createdAt }

                if (incomingCall != null && _state.value.activeCall == null && incomingCall.callId !in seenCallIds) {
                    seenCallIds.add(incomingCall.callId)
                    
                    firestore.collection("calls").document(incomingCall.callId).update("status", CallStatus.RINGING.name)
                    
                    _state.value = WebRtcState(
                        activeCall = incomingCall.copy(status = CallStatus.RINGING),
                        callStatus = CallStatus.RINGING,
                        callType = incomingCall.callType,
                        connectionStatusText = "Incoming  Call"
                    )
                    listenToActiveCall(incomingCall.callId, isCaller = false)
                }
            }
    }

    fun attachToCall(callId: String, autoAnswer: Boolean = false) {
        hasProcessedOffer = false
        hasProcessedAnswer = false
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
        am.isSpeakerphoneOn = callType == CallType.VIDEO
        
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
                        _state.value = _state.value.copy(activeCall = call)
                        
                        if (isCaller && call.status == CallStatus.RINGING && _state.value.callStatus == CallStatus.CALLING) {
                            _state.value = _state.value.copy(
                                callStatus = CallStatus.RINGING,
                                connectionStatusText = "Ringing..."
                            )
                        }

                        // Video Upgrade Logic
                        if (call.videoUpgradeStatus == com.example.data.model.VideoUpgradeStatus.REQUESTED) {
                            if (!isCaller && call.callType == CallType.AUDIO && !_state.value.isVideoUpgradeRequested) {
                                // If I am the callee and someone requested it
                                _state.value = _state.value.copy(isVideoUpgradeRequested = true)
                            } else if (isCaller && call.callType == CallType.AUDIO && !_state.value.isVideoUpgradeRequested) {
                                // If I am the caller and the callee requested it
                                _state.value = _state.value.copy(isVideoUpgradeRequested = true)
                            }
                        } else {
                            _state.value = _state.value.copy(isVideoUpgradeRequested = false)
                        }

                        if (call.videoUpgradeStatus == com.example.data.model.VideoUpgradeStatus.ACCEPTED && _state.value.callType == CallType.AUDIO) {
                            executeVideoUpgrade(isCaller)
                        }

                        if (call.videoUpgradeStatus == com.example.data.model.VideoUpgradeStatus.DECLINED && _state.value.callType == CallType.AUDIO) {
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

                        if (isCaller && call.answerSdp != null && call.answerSdp != _state.value.activeCall?.answerSdp) {
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
                        
                        if (!isCaller && call.offerSdp != null && call.offerSdp != _state.value.activeCall?.offerSdp) {
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
        _state.value = _state.value.copy(
            callType = CallType.VIDEO,
            isSpeakerOn = true
        )
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        am.isSpeakerphoneOn = true
        
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

    fun toggleSpeaker() {
        val newSpeaker = !_state.value.isSpeakerOn
        // Note: Real app requires AudioManager routing
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        am.isSpeakerphoneOn = newSpeaker
        _state.value = _state.value.copy(isSpeakerOn = newSpeaker)
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
            }
        }
        endCallInternalLocal(CallStatus.ENDED)
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
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            am.mode = android.media.AudioManager.MODE_NORMAL
            am.isSpeakerphoneOn = false
        } catch (_: Exception) {}
        
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            notificationManager.cancel(1001)
        } catch (_: Exception) {}
        
        com.example.services.ActiveCallService.stop(context)
    }

    private fun triggerPushNotification(calleeNumber: String, callerName: String, callType: String, callId: String) {
        // Fetch the callee's FCM token from Firestore
        firestore.collection("users").document(calleeNumber).get().addOnSuccessListener { doc ->
            val fcmToken = doc.getString("fcmToken")
            if (!fcmToken.isNullOrEmpty()) {
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
                                "callerName": "$callerName",
                                "callType": "$callType",
                                "callId": "$callId"
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
    }
}
