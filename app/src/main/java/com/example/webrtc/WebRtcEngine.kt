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
import kotlinx.coroutines.tasks.await
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
    val isHeldLocally: Boolean = false,
    val activeCallerTuneTitle: String? = null
)

class WebRtcEngine private constructor(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var timerJob: Job? = null
    private var reconnectJob: Job? = null
    private var resetIdleJob: Job? = null
    private var videoUpgradeWatchdogJob: Job? = null
    private var activeCallPollingJob: Job? = null

    private var hasProcessedOffer = false
    private var hasProcessedAnswer = false

    private val _state = MutableStateFlow(WebRtcState())
    val state: StateFlow<WebRtcState> = _state.asStateFlow()
    private val headsetButtonManager = com.example.services.HeadsetButtonManager(context)
    private val queuedRemoteIceCandidates = mutableListOf<IceCandidate>()

    val audioRouteManager: AudioRouteManager by lazy {
        AudioRouteManager(
            context = context,
            onRouteChanged = { target, available, isSpeaker ->
                _state.value = _state.value.copy(
                    availableAudioDevices = available,
                    selectedAudioDevice = target.type,
                    activeAudioDeviceName = target.name,
                    isSpeakerOn = isSpeaker
                )
            },
            onCellularInterruption = { isInterrupted ->
                val currentStatus = _state.value.callStatus
                if (currentStatus == CallStatus.ANSWERED) {
                    putCallOnHold(isInterrupted, isCellularInterruption = isInterrupted)
                }
            }
        )
    }

    val signalingManager: CallSignalingManager by lazy {
        CallSignalingManager(firestore)
    }

    private val firestore = FirebaseFirestore.getInstance()
    private var myPhoneNumber: String = ""
    
    private var incomingCallListener: ListenerRegistration? = null
    private var activeCallListener: ListenerRegistration? = null
    private var currentListeningCallId: String? = null
    private var iceCandidateListener: ListenerRegistration? = null
    
    private val seenCallIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private fun recordSeenCall(callId: String) {
        if (callId.isNotBlank()) {
            if (seenCallIds.size > 200) {
                val it = seenCallIds.iterator()
                var c = 0
                while (it.hasNext() && c < 100) {
                    it.next()
                    it.remove()
                    c++
                }
            }
            seenCallIds.add(callId)
        }
    }
    private val sentIceCandidateHashes = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var incomingCallWakeLock: android.os.PowerManager.WakeLock? = null
    private var lastProcessedIceRestartTimestamp = 0L

    // Pre-warm state: answer SDP prepared during ringing so Accept tap is instant
    @Volatile private var prebuiltAnswerSdp: String? = null
    @Volatile private var prebuiltCallId: String? = null
    @Volatile private var preWarmDone = false

    // WebRTC Core
    private val eglBase = EglBase.create()
    val eglBaseContext: EglBase.Context = eglBase.eglBaseContext
    
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null  // kept to mute/unmute playout during pre-warm
    
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    
    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    // ICE servers: populated dynamically at call start via fetchIceServers().
    // Fallback list used if dynamic fetch fails (STUN-only — works for ~70% of networks).
    // TURN relay is fetched from Cloudflare Worker so credentials rotate and are not baked into APK.
    private var iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun3.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun4.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
        // Static fallback TURN — Metered.ca free tier (no auth required on free plan)
        // Will be REPLACED by short-lived credentials from Cloudflare Worker at call start
        PeerConnection.IceServer.builder("turn:a.relay.metered.ca:80")
            .setUsername("openrelayproject")
            .setPassword("openrelayproject")
            .createIceServer(),
        PeerConnection.IceServer.builder("turn:a.relay.metered.ca:80?transport=tcp")
            .setUsername("openrelayproject")
            .setPassword("openrelayproject")
            .createIceServer(),
        PeerConnection.IceServer.builder("turn:a.relay.metered.ca:443")
            .setUsername("openrelayproject")
            .setPassword("openrelayproject")
            .createIceServer(),
        PeerConnection.IceServer.builder("turn:a.relay.metered.ca:443?transport=tcp")
            .setUsername("openrelayproject")
            .setPassword("openrelayproject")
            .createIceServer(),
        PeerConnection.IceServer.builder("turns:a.relay.metered.ca:443")
            .setUsername("openrelayproject")
            .setPassword("openrelayproject")
            .createIceServer(),
        PeerConnection.IceServer.builder("turns:a.relay.metered.ca:443?transport=tcp")
            .setUsername("openrelayproject")
            .setPassword("openrelayproject")
            .createIceServer()
    )

    fun getIceServers(): List<PeerConnection.IceServer> = iceServers

    /**
     * Fetch fresh TURN credentials from Cloudflare Worker before each call.
     * Credentials are short-lived (24h) and generated server-side via HMAC so
     * they are never hardcoded in the APK. Falls back to static list on failure.
     */
    private fun fetchIceServersAsync(onDone: () -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                val workerUrl = com.example.BuildConfig.CALL_WORKER_URL
                    .removeSuffix("/").let { if (it.endsWith("/call")) it.dropLast(5) else it }
                val url = java.net.URL("$workerUrl/turn-credentials")
                val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("X-Worker-Secret", com.example.BuildConfig.CALL_WORKER_SECRET)
                    connectTimeout = 5000
                    readTimeout = 5000
                }
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    val json = org.json.JSONObject(body)
                    val serversJson = json.getJSONArray("iceServers")
                    val servers = mutableListOf<PeerConnection.IceServer>()
                    for (i in 0 until serversJson.length()) {
                        val s = serversJson.getJSONObject(i)
                        val urls = s.getJSONArray("urls")
                        val username = if (s.has("username")) s.getString("username") else null
                        val credential = if (s.has("credential")) s.getString("credential") else null
                        for (j in 0 until urls.length()) {
                            val builder = PeerConnection.IceServer.builder(urls.getString(j))
                            if (username != null) builder.setUsername(username)
                            if (credential != null) builder.setPassword(credential)
                            servers.add(builder.createIceServer())
                        }
                    }
                    if (servers.isNotEmpty()) {
                        iceServers = servers
                        Log.i("WebRtcEngine", "✅ ICE servers updated from Worker: ${servers.size} servers")
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.w("WebRtcEngine", "TURN fetch failed, using fallback ICE servers: ${e.message}")
            }
            onDone()
        }
    }


    init {
        initWebRtc()
        fetchIceServersAsync {}
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
        
        // Hardware Acoustic Echo Cancellation (AEC) and Noise Suppression (NS)
        // Check OEM DSP hardware support and enable them to completely cancel speaker echo
        // and background noise on both parties' devices.
        val isAecSupported = try {
            android.media.audiofx.AcousticEchoCanceler.isAvailable()
        } catch (_: Exception) {
            JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported()
        }
        val isNsSupported = try {
            android.media.audiofx.NoiseSuppressor.isAvailable()
        } catch (_: Exception) {
            JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported()
        }
        Log.i("WebRtcEngine", "Hardware AEC Supported: $isAecSupported, Hardware NS Supported: $isNsSupported")

        val audioDeviceModule: JavaAudioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(isAecSupported)
            .setUseHardwareNoiseSuppressor(isNsSupported)
            .setAudioSource(android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setUseStereoInput(false)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(errorMessage: String?) {
                    Log.e("WebRtcEngine", "AudioRecord Init Error: $errorMessage")
                }
                override fun onWebRtcAudioRecordStartError(errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode?, errorMessage: String?) {
                    Log.e("WebRtcEngine", "AudioRecord Start Error: $errorCode - $errorMessage")
                }
                override fun onWebRtcAudioRecordError(errorMessage: String?) {
                    Log.e("WebRtcEngine", "AudioRecord Runtime Error: $errorMessage")
                }
            })
            .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                override fun onWebRtcAudioTrackInitError(errorMessage: String?) {
                    Log.e("WebRtcEngine", "AudioTrack Init Error: $errorMessage")
                }
                override fun onWebRtcAudioTrackStartError(errorCode: JavaAudioDeviceModule.AudioTrackStartErrorCode?, errorMessage: String?) {
                    Log.e("WebRtcEngine", "AudioTrack Start Error: $errorCode - $errorMessage")
                }
                override fun onWebRtcAudioTrackError(errorMessage: String?) {
                    Log.e("WebRtcEngine", "AudioTrack Runtime Error: $errorMessage")
                }
            })
            .setAudioTrackStateCallback(object : JavaAudioDeviceModule.AudioTrackStateCallback {
                override fun onWebRtcAudioTrackStart() {
                    Log.i("WebRtcEngine", "WebRTC AudioTrack started - injecting recording tap")
                    hookWebRtcAudioTrackPlayout()
                }
                override fun onWebRtcAudioTrackStop() {
                    Log.i("WebRtcEngine", "WebRTC AudioTrack stopped")
                }
            })
            .setSamplesReadyCallback { audioSamples ->
                try {
                    com.example.util.CallAudioRecorder.getInstance(context).onLocalPcmSamples(
                        audioSamples.sampleRate,
                        audioSamples.channelCount,
                        audioSamples.data
                    )
                } catch (_: Exception) {}
            }
            .createAudioDeviceModule()

        this.audioDeviceModule = audioDeviceModule
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoEncoderFactory(defaultVideoEncoderFactory)
            .setVideoDecoderFactory(defaultVideoDecoderFactory)
            .createPeerConnectionFactory()
    }
    
    private fun createAudioTrack() {
        if (peerConnectionFactory == null) return
        try { localAudioTrack?.setEnabled(false) } catch (_: Exception) {}
        try { localAudioTrack?.dispose() } catch (_: Exception) {}
        localAudioTrack = null
        try { audioSource?.dispose() } catch (_: Exception) {}
        audioSource = null

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
        val shouldEnable = (_state.value.callStatus == CallStatus.ANSWERED || _state.value.callStatus == CallStatus.CALLING)
        localAudioTrack?.setEnabled(shouldEnable && !_state.value.isMuted && !_state.value.isOnHold)
    }

    private fun createVideoTrack(startCaptureNow: Boolean = true) {
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
            if (startCaptureNow) {
                try { videoCapturer?.startCapture(1280, 720, 30) } catch (_: Exception) {}
            }
            
            val videoTrack = peerConnectionFactory?.createVideoTrack("ARDAMSv0", videoSource)
            videoTrack?.setEnabled(startCaptureNow)
            
            _state.value = _state.value.copy(localVideoTrack = videoTrack)
        }
    }

    private fun startVideoCaptureIfNeeded() {
        try {
            videoCapturer?.startCapture(1280, 720, 30)
            _state.value.localVideoTrack?.setEnabled(true)
        } catch (_: Exception) {}
    }

    /**
     * Dedicated teardown for any active PeerConnection and media tracks.
     * Prevents orphaned audio/video tracks and native AudioRecord/AudioTrack conflicts.
     */
    private fun cleanupMediaAndPeerConnection() {
        try { localAudioTrack?.setEnabled(false) } catch (_: Exception) {}
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
    }

    /**
     * Ensures all local and remote audio tracks are active, unmuted, and routed properly.
     * Crucial when calls are answered to resolve one-way audio where tracks received during
     * ringing/connecting were muted to volume 0.0.
     */
    fun unmuteAllAudioTracks() {
        try {
            localAudioTrack?.setEnabled(!_state.value.isMuted && !_state.value.isOnHold)
            val receivers = peerConnection?.receivers ?: emptyList()
            for (receiver in receivers) {
                val track = receiver.track()
                if (track is AudioTrack) {
                    track.setEnabled(true)
                    track.setVolume(1.0)
                    Log.i("WebRtcEngine", "🔊 Unmuted remote AudioTrack: ${track.id()} to volume 1.0")
                }
            }
            audioRouteManager.reassertCurrentRoute()
            hookWebRtcAudioTrackPlayout()
        } catch (e: Exception) {
            Log.w("WebRtcEngine", "Error unmuting audio tracks: ${e.message}")
        }
    }

    /**
     * Triggered immediately when high-priority FCM 'call_answered' push arrives.
     * Bypasses any stalled gRPC listener on mobile data and connects the call in <200ms.
     */
    fun onCallAnsweredFromPush(callId: String) {
        val currentCall = _state.value.activeCall
        if (currentCall == null || currentCall.callId != callId) {
            Log.w("WebRtcEngine", "onCallAnsweredFromPush: ignored for non-matching callId $callId (active=${currentCall?.callId})")
            return
        }
        Log.i("WebRtcEngine", "⚡ High-priority FCM answer push received from callee for call $callId")
        com.example.util.CallSoundEffectsManager.stopRingbackTone()

        if (_state.value.callStatus != CallStatus.ANSWERED) {
            _state.value = _state.value.copy(
                callStatus = CallStatus.ANSWERED,
                connectionStatusText = if (currentCall.answerSdp != null) "Connected • WebRTC" else "Connecting P2P..."
            )
            unmuteAllAudioTracks()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                try {
                    com.example.services.LksConnectionService.setCallActive()
                } catch (_: Exception) {}
            }
            if (_state.value.callDurationSeconds == 0) {
                startCallTimer()
            }
        }

        // Aggressively fetch answerSdp directly from the Firestore server with rapid retries (<200ms cadence)
        scope.launch(Dispatchers.IO) {
            for (attempt in 1..15) {
                if (hasProcessedAnswer || isCallTerminated(callId)) break
                try {
                    val doc = firestore.collection("calls").document(callId).get(com.google.firebase.firestore.Source.SERVER).await()
                    if (doc.exists()) {
                        val call = doc.toObject(CallDto::class.java)
                        if (call != null) {
                            withContext(Dispatchers.Main) {
                                handleCallDocumentUpdate(call, doc, isCaller = true)
                            }
                            if (!call.answerSdp.isNullOrBlank()) {
                                Log.i("WebRtcEngine", "⚡ Retrieved answerSdp from server on attempt $attempt via push flow")
                                break
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("WebRtcEngine", "onCallAnsweredFromPush server fetch attempt $attempt failed: ${e.message}")
                }
                delay(200)
            }
        }
    }

    private fun createPeerConnection(isCaller: Boolean, callId: String) {
        // Industry Standard: Guarantee complete cleanup of any previous native connection/tracks
        cleanupMediaAndPeerConnection()

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            iceCandidatePoolSize = 2
        }
        
        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                Log.d("WebRtcEngine", "onIceConnectionChange: $newState")
                when (newState) {
                    PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> {
                        reconnectJob?.cancel()
                        reconnectJob = null
                        if (_state.value.callStatus == CallStatus.ANSWERED) {
                            _state.value = _state.value.copy(connectionStatusText = "Connected • WebRTC")
                            unmuteAllAudioTracks()
                        }
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        _state.value = _state.value.copy(connectionStatusText = "Reconnecting...")
                        if (reconnectJob == null || reconnectJob?.isActive == false) {
                            reconnectJob = scope.launch {
                                delay(25000)
                                if (_state.value.callStatus == CallStatus.ANSWERED) {
                                    Log.w("WebRtcEngine", "ICE DISCONNECTED 25s timeout — ending call")
                                    endCallInternalLocal(CallStatus.ENDED)
                                }
                            }
                        }
                    }
                    PeerConnection.IceConnectionState.FAILED -> {
                        _state.value = _state.value.copy(connectionStatusText = "Connection Failed • Retrying...")
                        if (isCaller) {
                            scope.launch {
                                try {
                                    peerConnection?.restartIce()
                                } catch (_: Exception) {}
                                val constraints = MediaConstraints().apply {
                                    mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
                                }
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
                        } else {
                            // If callee experiences ICE failure (e.g. cellular network dropped to No Service),
                            // request the caller to trigger an ICE restart via Firestore signaling!
                            scope.launch {
                                try {
                                    firestore.collection("calls").document(callId)
                                        .update("iceRestartRequested", System.currentTimeMillis())
                                } catch (_: Exception) {}
                            }
                        }
                        if (reconnectJob == null || reconnectJob?.isActive == false) {
                            reconnectJob = scope.launch {
                                delay(20000)
                                if (_state.value.callStatus == CallStatus.ANSWERED) {
                                    Log.w("WebRtcEngine", "ICE FAILED timeout — ending call")
                                    endCallInternalLocal(CallStatus.FAILED)
                                }
                            }
                        }
                    }
                    PeerConnection.IceConnectionState.CLOSED -> {
                        reconnectJob?.cancel()
                        reconnectJob = null
                    }
                    else -> {}
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
            override fun onAddStream(stream: MediaStream?) {
                val shouldEnable = _state.value.callStatus == CallStatus.ANSWERED
                stream?.audioTracks?.forEach { audioTrack ->
                    try {
                        audioTrack.setEnabled(shouldEnable)
                        audioTrack.setVolume(if (shouldEnable) 1.0 else 0.0)
                        Log.i("WebRtcEngine", "Remote AudioTrack from stream: ${audioTrack.id()}, enabled=$shouldEnable")
                        if (shouldEnable) {
                            audioRouteManager.reassertCurrentRoute()
                            hookWebRtcAudioTrackPlayout()
                        }
                    } catch (e: Exception) {
                        Log.w("WebRtcEngine", "Failed to enable remote AudioTrack: ${e.message}")
                    }
                }
            }
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dataChannel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                val track = receiver?.track()
                scope.launch {
                    val shouldEnable = _state.value.callStatus == CallStatus.ANSWERED
                    if (shouldEnable) {
                        _state.value = _state.value.copy(connectionStatusText = "Connected • WebRTC")
                    }
                    if (track is VideoTrack) {
                        videoUpgradeWatchdogJob?.cancel()
                        videoUpgradeWatchdogJob = null
                        _state.value = _state.value.copy(
                            remoteVideoTrack = track,
                            callType = CallType.VIDEO,
                            connectionStatusText = "Connected • WebRTC"
                        )
                    } else if (track is AudioTrack) {
                        try {
                            track.setEnabled(shouldEnable)
                            track.setVolume(if (shouldEnable) 1.0 else 0.0)
                            Log.i("WebRtcEngine", "Remote AudioTrack enabled=$shouldEnable: ${track.id()}")
                            if (shouldEnable) {
                                audioRouteManager.reassertCurrentRoute()
                                hookWebRtcAudioTrackPlayout()
                            }
                        } catch (e: Exception) {
                            Log.w("WebRtcEngine", "Failed to configure remote AudioTrack: ${e.message}")
                        }
                    }
                }
            }
        })

        if (_state.value.callType == CallType.VIDEO) {
            val startCapture = isCaller || _state.value.callStatus == CallStatus.ANSWERED
            createVideoTrack(startCaptureNow = startCapture)
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
        signalingManager.sendIceCandidate(callId, candidate, isCaller)
    }

    private fun listenForIceCandidates(callId: String, isCaller: Boolean) {
        signalingManager.listenForIceCandidates(callId, isCaller) { dto ->
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

    /**
     * ⚡ Pre-Warm: Called the moment we go to RINGING state.
     * Creates the PeerConnection and processes the offer SDP NOW (during ringtone playback)
     * so by the time the user taps Accept, the answer SDP is already fully built.
     * answerCall() then only needs to fire one Firestore write (~100ms) instead of
     * running the full 5-8 second SDP negotiation chain.
     */
    private fun preWarmForIncomingCall(call: com.example.data.model.CallDto) {
        // 🛡️ CRITICAL FIX: Pre-warming creates a WebRTC PeerConnection with JavaAudioDeviceModule.
        // As soon as remoteDescription is set, WebRTC C++ initializes an AudioTrack with
        // USAGE_VOICE_COMMUNICATION. On Samsung and Xiaomi/MIUI devices, having an active
        // VOICE_COMMUNICATION AudioTrack immediately causes the Android AudioPolicyService
        // to switch the hardware audio route to the EARPIECE, hijacking the ringtone MediaPlayer!
        // Standard SDP negotiation in answerCall() takes <100ms and runs cleanly AFTER the ringtone
        // is stopped and after audio routing is properly configured.
        Log.d("WebRtcEngine", "🛡️ Pre-warm skipped for ${call.callId} to preserve loudspeaker ringtone")
        prebuiltAnswerSdp = null
        prebuiltCallId = null
        preWarmDone = false
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
                if (!newLine.contains("maxaveragebitrate=")) newLine += ";maxaveragebitrate=64000"
                if (!newLine.contains("minptime=")) newLine += ";minptime=10"
                lines[i] = newLine
                break
            }
        }
        
        if (!fmtpFound) {
            for (i in lines.indices) {
                if (lines[i].startsWith("a=rtpmap:$opusPayloadType")) {
                    lines.add(i + 1, "a=fmtp:$opusPayloadType minptime=10;useinbandfec=1;maxaveragebitrate=64000")
                    break
                }
            }
        }
        
        return lines.joinToString("\r\n")
    }

    private val isInitiatingCall = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile
    private var lastInitiateCallTimestamp: Long = 0L

    fun initiateCall(calleeNumber: String, calleeName: String, callerNumber: String, callerName: String, callType: CallType) {
        com.example.util.logging.LksLogger.breadcrumb("CALL", "Initiating $callType call to $calleeNumber ($calleeName)")
        // Non-blocking re-entrance guard (industry standard: Signal/Opal use AtomicBoolean instead of @Synchronized)
        if (!isInitiatingCall.compareAndSet(false, true)) {
            Log.w("WebRtcEngine", "initiateCall dropped: another initiateCall is already in progress")
            return
        }
        try {
            val now = System.currentTimeMillis()
            if (now - lastInitiateCallTimestamp < 500L) {
                Log.w("WebRtcEngine", "initiateCall dropped: rapid call initiation debounce (${now - lastInitiateCallTimestamp}ms ago)")
                return
            }

            val currentCall = _state.value.activeCall
            val currentStatus = _state.value.callStatus
            if (currentCall != null && (currentStatus == CallStatus.CALLING || currentStatus == CallStatus.RINGING || currentStatus == CallStatus.ANSWERED)) {
                if (com.example.util.ContactsHelper.numbersMatch(currentCall.calleeNumber, calleeNumber)) {
                    Log.w("WebRtcEngine", "initiateCall dropped: already calling or in active call with $calleeNumber (status=$currentStatus)")
                    return
                } else {
                    Log.i("WebRtcEngine", "Switching call target: ending current call ${currentCall.callId} to call $calleeNumber")
                    endCall(currentCall.callId)
                }
            }

            lastInitiateCallTimestamp = now
            resetIdleJob?.cancel()
            resetIdleJob = null
            reconnectJob?.cancel()
            reconnectJob = null

            myPhoneNumber = callerNumber

            // Industry Standard: Guarantee complete cleanup of any previous native connection/tracks
            cleanupMediaAndPeerConnection()

            val myUser = FirebaseManager.getInstance(context).currentUser.value
            val myFcm = myUser?.fcmToken?.ifBlank { getCachedFcmToken(context) } ?: getCachedFcmToken(context)
            val myWeb = myUser?.webToken ?: ""

            val inMemoryCallee = FirebaseManager.getInstance(context).lookupUserByNumber(calleeNumber)
            val calleeTokens = pushTokenCache[calleeNumber]
            val calleeFcm = calleeTokens?.first?.ifBlank { inMemoryCallee?.fcmToken ?: "" } ?: inMemoryCallee?.fcmToken ?: ""
            val calleeWeb = calleeTokens?.second?.ifBlank { inMemoryCallee?.webToken ?: "" } ?: inMemoryCallee?.webToken ?: ""

            // Pre-seed Callee's custom caller tune if available in memory cache
            val calleeTuneTitle = inMemoryCallee?.callerTuneTitle?.takeIf { it.isNotBlank() }
            val calleeTuneAudio = inMemoryCallee?.callerTuneAudioBase64 ?: ""
            val calleeTuneUrl = inMemoryCallee?.callerTuneUrl ?: ""
            val calleeTuneStartMs = inMemoryCallee?.callerTuneStartMs ?: 0L
            val calleeTuneEndMs = inMemoryCallee?.callerTuneEndMs ?: 0L

            val newCall = CallDto(
                callId = UUID.randomUUID().toString(),
                callerNumber = callerNumber,
                callerName = callerName,
                calleeNumber = calleeNumber,
                calleeName = calleeName.ifBlank { calleeNumber },
                callType = callType,
                status = CallStatus.CALLING,
                createdAt = System.currentTimeMillis(),
                callerTuneTitle = calleeTuneTitle,
                callerTuneAudioBase64 = calleeTuneAudio,
                callerTuneUrl = calleeTuneUrl,
                callerTuneStartMs = calleeTuneStartMs,
                callerTuneEndMs = calleeTuneEndMs,
                callerFcmToken = myFcm,
                callerWebToken = myWeb,
                calleeFcmToken = calleeFcm,
                calleeWebToken = calleeWeb
            )

            _state.value = WebRtcState(
                activeCall = newCall,
                callStatus = CallStatus.CALLING,
                callType = callType,
                isSpeakerOn = callType == CallType.VIDEO,
                connectionStatusText = "Calling ...",
                activeCallerTuneTitle = calleeTuneTitle
            )

            hasProcessedOffer = false
            hasProcessedAnswer = false

            // Industry Standard: Initial call document set with retry for network resilience
            firestore.collection("calls").document(newCall.callId).set(newCall)
                .addOnFailureListener { e ->
                    Log.w("WebRtcEngine", "Initial call doc set failed: ${e.message}, retrying once in 500ms...")
                    scope.launch {
                        delay(500)
                        if (_state.value.activeCall?.callId == newCall.callId) {
                            firestore.collection("calls").document(newCall.callId).set(newCall)
                        }
                    }
                }
            
            // Trigger Push Notification via external Worker (0ms direct token delivery)
            triggerPushNotification(
                calleeNumber = calleeNumber,
                callerName = callerName,
                callerNumber = callerNumber,
                callType = callType.name,
                callId = newCall.callId,
                explicitFcmToken = calleeFcm,
                explicitWebToken = calleeWeb
            )

            // Fallback RINGING: If callee push acknowledgment is delayed, switch to RINGING after 3.5s
            scope.launch {
                delay(3500)
                if (_state.value.callStatus == CallStatus.CALLING && _state.value.activeCall?.callId == newCall.callId) {
                    Log.i("WebRtcEngine", "Fallback RINGING: Callee acknowledgment delayed, switching to RINGING status")
                    _state.value = _state.value.copy(
                        callStatus = CallStatus.RINGING,
                        connectionStatusText = "Ringing..."
                    )
                    val ringMap = mutableMapOf<String, Any>("status" to CallStatus.RINGING.name)
                    if (!calleeTuneTitle.isNullOrBlank()) {
                        ringMap["callerTuneTitle"] = calleeTuneTitle
                        if (calleeTuneAudio.isNotBlank()) ringMap["callerTuneAudioBase64"] = calleeTuneAudio
                        if (calleeTuneUrl.isNotBlank()) ringMap["callerTuneUrl"] = calleeTuneUrl
                        ringMap["callerTuneStartMs"] = calleeTuneStartMs
                    }
                    firestore.collection("calls").document(newCall.callId).update(ringMap)
                    com.example.util.CallSoundEffectsManager.onCallRinging(context)
                }
            }
            
            com.example.services.ActiveCallService.start(context, newCall.callId, callType.name)
            headsetButtonManager.startListening()
            try {
                com.example.services.LksTelecomManager.reportOutgoingCall(context, newCall.callId, calleeName, calleeNumber, callType)
            } catch (_: Exception) {}
            configureAudio(callType)
            com.example.util.CallSoundEffectsManager.startConnectingTone(context)
            if (!calleeTuneTitle.isNullOrBlank() && (calleeTuneAudio.isNotBlank() || calleeTuneUrl.isNotBlank())) {
                com.example.util.CallSoundEffectsManager.prepareCallerTune(
                    context, calleeTuneAudio, calleeTuneUrl, calleeTuneTitle, calleeTuneStartMs
                )
            } else {
                resolveAndPrepareCalleeTune(calleeNumber)
            }
            
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
            
            // Refresh TURN credentials in background for future calls / reconnects (non-blocking)
            fetchIceServersAsync {}

            // Create PeerConnection and generate Offer IMMEDIATELY using cached ICE servers (no network stall)
            try { audioDeviceModule?.setSpeakerMute(false) } catch (_: Exception) {}
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
                            .addOnFailureListener { e ->
                                Log.w("WebRtcEngine", "Failed to update offerSdp, retrying in 300ms: ${e.message}")
                                scope.launch {
                                    delay(300)
                                    try {
                                        firestore.collection("calls").document(newCall.callId).update("offerSdp", tunedSdp)
                                    } catch (_: Exception) {}
                                }
                            }
                    }
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(p0: String?) {}
                override fun onSetFailure(p0: String?) {}
            }, constraints)

            listenToActiveCall(newCall.callId, isCaller = true)
            listenForIceCandidates(newCall.callId, isCaller = true)
        } finally {
            isInitiatingCall.set(false)
        }
    } // end initiateCall

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
                    } else if (snapshot != null && _state.value.callStatus == CallStatus.RINGING) {
                        // Document no longer in snapshot (deleted or status changed) and we're still ringing → force end immediately
                        Log.d("WebRtcEngine", "Active call doc ${currentActive.callId} no longer in callee's snapshot → ending ringing call immediately")
                        endCallInternalLocal(CallStatus.MISSED)
                    }
                }

                // Filter for FRESH incoming calls (<45s old) not initiated by self
                val incomingCall = activeCalls
                    .filter { (it.status == CallStatus.CALLING || it.status == CallStatus.RINGING) && (now - it.createdAt <= 45_000L) }
                    .filter { it.callerNumber != myPhoneNumber && it.callerNumber.replace(Regex("[^0-9]"), "") != cleanDigits }
                    .maxByOrNull { it.createdAt }

                // 1. Industry Standard: Call Glare / Collision Resolution (Both parties call each other simultaneously)
                if (incomingCall != null && currentActive != null && _state.value.callStatus == CallStatus.CALLING &&
                    com.example.util.ContactsHelper.numbersMatch(currentActive.calleeNumber, incomingCall.callerNumber) &&
                    !isCallTerminated(incomingCall.callId)
                ) {
                    Log.i("WebRtcEngine", "⚡ Call Glare detected: simultaneous calls between $myPhoneNumber and ${incomingCall.callerNumber}")
                    val myClean = myPhoneNumber.replace(Regex("[^0-9]"), "")
                    val peerClean = incomingCall.callerNumber.replace(Regex("[^0-9]"), "")
                    if (myClean > peerClean) {
                        // We WIN tie-breaker as Caller. Maintain our outgoing call and decline peer's incoming call.
                        Log.i("WebRtcEngine", "Glare resolved: We win as Caller. Continuing outgoing call ${currentActive.callId}")
                        markCallTerminated(incomingCall.callId)
                        firestore.collection("calls").document(incomingCall.callId).update("status", CallStatus.DECLINED.name)
                    } else {
                        // We YIELD tie-breaker as Callee. Cancel our outgoing call and answer peer's incoming call!
                        Log.i("WebRtcEngine", "Glare resolved: We yield as Callee. Cancelling outgoing call ${currentActive.callId} and answering ${incomingCall.callId}")
                        endCallInternalLocal(CallStatus.ENDED)
                        seenCallIds.add(incomingCall.callId)
                        attachToCall(
                            callId = incomingCall.callId,
                            autoAnswer = false,
                            callerName = incomingCall.callerName,
                            callerNumber = incomingCall.callerNumber,
                            callTypeStr = incomingCall.callType.name
                        )
                    }
                    return@addSnapshotListener
                }

                // 2. Industry Standard: Newer Call Upgrade from Same Caller (Caller retried while callee was already ringing)
                if (incomingCall != null && currentActive != null && _state.value.callStatus == CallStatus.RINGING &&
                    com.example.util.ContactsHelper.numbersMatch(currentActive.callerNumber, incomingCall.callerNumber) &&
                    incomingCall.callId != currentActive.callId &&
                    incomingCall.createdAt >= currentActive.createdAt &&
                    !isCallTerminated(incomingCall.callId)
                ) {
                    Log.i("WebRtcEngine", "Caller initiated a newer call ${incomingCall.callId} (replacing ${currentActive.callId}). Upgrading active ringing call.")
                    markCallTerminated(currentActive.callId)
                    recordSeenCall(incomingCall.callId)
                    // Update new call in Firestore to RINGING
                    firestore.collection("calls").document(incomingCall.callId).update("status", CallStatus.RINGING.name)
                    // Update local state to point to new call
                    _state.value = _state.value.copy(activeCall = incomingCall)
                    listenToActiveCall(incomingCall.callId, isCaller = false)
                    listenForIceCandidates(incomingCall.callId, isCaller = false)
                    return@addSnapshotListener
                }

                // CRITICAL FIX: Guard on callStatus being IDLE/ENDED/DECLINED/MISSED or new incoming callId
                val isReadyForNewCall = _state.value.callStatus == CallStatus.IDLE ||
                    _state.value.callStatus == CallStatus.ENDED ||
                    _state.value.callStatus == CallStatus.DECLINED ||
                    _state.value.callStatus == CallStatus.MISSED ||
                    _state.value.activeCall == null ||
                    (incomingCall != null && _state.value.activeCall?.callId != incomingCall.callId)

                if (incomingCall != null && isReadyForNewCall && incomingCall.callId !in seenCallIds && !isCallTerminated(incomingCall.callId)) {
                    recordSeenCall(incomingCall.callId)

                    val firebaseMgr = com.example.data.repository.FirebaseManager.getInstance(context)
                    if (firebaseMgr.isDndEnabled() || firebaseMgr.isNumberBlocked(incomingCall.callerNumber)) {
                        Log.d("WebRtcEngine", "Incoming call auto-declined by DND or Blocklist: ${incomingCall.callId} from ${incomingCall.callerNumber}")
                        markCallTerminated(incomingCall.callId)
                        firestore.collection("calls").document(incomingCall.callId).update("status", CallStatus.DECLINED.name)
                        return@addSnapshotListener
                    }
                    
                    if (!isCallTerminated(incomingCall.callId)) {
                        val myTune = com.example.util.LksCallerTuneManager.getMyCallerTune(context)
                        val ringUpdates = mutableMapOf<String, Any>("status" to CallStatus.RINGING.name)
                        if (myTune.isConfigured) {
                            ringUpdates["callerTuneTitle"] = myTune.title
                            if (myTune.audioBase64.isNotBlank()) ringUpdates["callerTuneAudioBase64"] = myTune.audioBase64
                            if (myTune.audioUrl.isNotBlank()) ringUpdates["callerTuneUrl"] = myTune.audioUrl
                            ringUpdates["callerTuneStartMs"] = myTune.startMs
                            ringUpdates["callerTuneEndMs"] = myTune.endMs
                        }
                        firestore.collection("calls").document(incomingCall.callId).update(ringUpdates)
                    }
                    headsetButtonManager.startListening()

                    // Register with Android Telecom to get system-call priority on all OEMs
                    // (same mechanism WhatsApp/Telegram use — bypasses battery saver, app kill, Doze)
                    try {
                        com.example.services.LksTelecomManager.reportIncomingCall(
                            context = context,
                            callId = incomingCall.callId,
                            callerName = incomingCall.callerName,
                            callerNumber = incomingCall.callerNumber,
                            callType = incomingCall.callType
                        )
                    } catch (e: Exception) {
                        Log.w("WebRtcEngine", "Telecom reportIncomingCall failed (non-fatal): ${e.message}")
                    }

                    resetIdleJob?.cancel()
                    resetIdleJob = null
                    _state.value = WebRtcState(
                        activeCall = incomingCall.copy(status = CallStatus.RINGING),
                        callStatus = CallStatus.RINGING,
                        callType = incomingCall.callType,
                        connectionStatusText = "Incoming Call"
                    )

                    // Wake screen and route to lockscreen activity or floating pill if unlocked
                    try {
                        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
                        val isLocked = km?.isKeyguardLocked == true
                        val isInteractive = pm?.isInteractive == true

                        // Start custom incoming ringtone immediately (<10ms)
                        com.example.util.LksIncomingRingtonePlayer.start(context, incomingCall.callerNumber)

                        if (isLocked || !isInteractive) {
                            try {
                                if (incomingCallWakeLock?.isHeld == true) incomingCallWakeLock?.release()
                            } catch (_: Exception) {}
                            incomingCallWakeLock = pm?.newWakeLock(
                                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                                android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                                android.os.PowerManager.ON_AFTER_RELEASE,
                                "lksdialer:incoming_call_wake_engine"
                            )?.apply { acquire(15000) }

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
                            // Phone is UNLOCKED & user is outside the app:
                            val canDrawOverlays = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                                android.provider.Settings.canDrawOverlays(context)
                            } else true

                            if (canDrawOverlays) {
                                Log.i("WebRtcEngine", "Phone unlocked & outside app -> showing incoming call overlay pill")
                                com.example.services.FloatingCallBubbleService.showIncoming(
                                    context,
                                    incomingCall.callId,
                                    incomingCall.callerName,
                                    incomingCall.callerNumber,
                                    incomingCall.callType
                                )
                            } else {
                                Log.i("WebRtcEngine", "Overlay permission not granted -> launching call activity as fallback")
                                val launchIntent = Intent(context, com.example.MainActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                    putExtra("incoming_call", true)
                                    putExtra("call_id", incomingCall.callId)
                                    putExtra("caller_name", incomingCall.callerName)
                                    putExtra("caller_number", incomingCall.callerNumber)
                                    putExtra("call_type", incomingCall.callType.name)
                                }
                                context.startActivity(launchIntent)
                            }
                        }
                    } catch (_: Exception) {}

                    listenToActiveCall(incomingCall.callId, isCaller = false)

                    // ⚡ Pre-warm: build answer SDP NOW during ringing so Accept tap is instant
                    prebuiltAnswerSdp = null
                    preWarmDone = false
                    preWarmForIncomingCall(incomingCall)
                }
            }
    }

    /**
     * ⚡ Ultra-fast call answering entry point for Notification and Floating Bubble actions (<100ms).
     * Connects WebRTC audio immediately in the background without waiting for Activity lifecycle.
     */
    fun answerIncomingCall(
        callId: String,
        callerName: String? = null,
        callerNumber: String? = null,
        callTypeStr: String? = null
    ) {
        if (isCallTerminated(callId)) {
            Log.w("WebRtcEngine", "answerIncomingCall aborted: call $callId was already terminated locally")
            return
        }

        resetIdleJob?.cancel()
        resetIdleJob = null
        reconnectJob?.cancel()
        reconnectJob = null

        val type = try { CallType.valueOf(callTypeStr ?: "AUDIO") } catch (_: Exception) { CallType.AUDIO }

        // 0. High-Priority FCM Push Notification to Caller (0ms direct token delivery):
        val currentCall = _state.value.activeCall
        val callerNum = callerNumber ?: currentCall?.callerNumber ?: ""
        val targetFcm = currentCall?.callerFcmToken?.takeIf { it.isNotBlank() } ?: pushTokenCache[callerNum]?.first ?: ""
        val targetWeb = currentCall?.callerWebToken?.takeIf { it.isNotBlank() } ?: pushTokenCache[callerNum]?.second ?: ""
        if (callerNum.isNotBlank()) {
            triggerPushNotification(
                calleeNumber = callerNum,
                callerName = myPhoneNumber,
                callerNumber = myPhoneNumber,
                callType = type.name,
                callId = callId,
                type = "call_answered",
                explicitFcmToken = targetFcm,
                explicitWebToken = targetWeb
            )
        }

        // 1. Instantly mark status as ANSWERED in Firestore so the CALLER screen updates immediately (<50ms)
        try {
            firestore.collection("calls").document(callId).update(
                "status", CallStatus.ANSWERED.name,
                "answeredAt", System.currentTimeMillis()
            ).addOnFailureListener { e ->
                Log.w("WebRtcEngine", "Failed to update status to ANSWERED in answerIncomingCall, retrying: ${e.message}")
                scope.launch {
                    delay(300)
                    try {
                        firestore.collection("calls").document(callId).update(
                            "status", CallStatus.ANSWERED.name,
                            "answeredAt", System.currentTimeMillis()
                        )
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}

        // 2. Ensure real-time active call & ICE listeners are attached immediately
        listenToActiveCall(callId, isCaller = false)
        listenForIceCandidates(callId, isCaller = false)

        // 3. Fast-path: Check if WebRtcEngine already has this call cached with offerSdp!
        if (currentCall != null && currentCall.callId == callId && !currentCall.offerSdp.isNullOrBlank()) {
            Log.i("WebRtcEngine", "⚡ Fast-path answer: call $callId already cached with offerSdp")
            answerCall()
            return
        }

        // 4. Immediate background answer path (offerSdp being fetched or arriving):
        Log.i("WebRtcEngine", "⚡ Immediate answering in background for call $callId (offer pending)")
        val optimisticCall = currentCall?.takeIf { it.callId == callId } ?: CallDto(
            callId = callId,
            callerName = callerName ?: "LKS User",
            callerNumber = callerNumber ?: "",
            calleeNumber = myPhoneNumber,
            callType = type,
            status = CallStatus.ANSWERED
        )

        _state.value = _state.value.copy(
            activeCall = optimisticCall.copy(status = CallStatus.ANSWERED),
            callStatus = CallStatus.ANSWERED,
            callType = type,
            connectionStatusText = "Connecting P2P..."
        )

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.cancel(1001)
        com.example.util.LksIncomingRingtonePlayer.stop()
        com.example.util.CallSoundEffectsManager.stopRingbackTone()
        com.example.services.FloatingCallBubbleService.silenceRingtone(context)

        com.example.services.ActiveCallService.start(context, callId, type.name)
        headsetButtonManager.startListening()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try { com.example.services.LksConnectionService.setCallActive() } catch (_: Exception) {}
        }
        try { audioDeviceModule?.setSpeakerMute(false) } catch (_: Exception) {}
        configureAudio(type)

        // Create PeerConnection right away so it is ready the moment offerSdp is resolved
        createPeerConnection(isCaller = false, callId = callId)
        unmuteAllAudioTracks()
        if (type == CallType.VIDEO) {
            startVideoCaptureIfNeeded()
        }

        // Concurrently fetch document to acquire offerSdp as fast as possible from Firestore with retries
        scope.launch(Dispatchers.IO) {
            for (attempt in 1..12) {
                if (hasProcessedOffer || isCallTerminated(callId)) break
                try {
                    val doc = firestore.collection("calls").document(callId).get(com.google.firebase.firestore.Source.SERVER).await()
                    if (doc.exists()) {
                        val docOffer = doc.getString("offerSdp")
                        val docCall = doc.toObject(CallDto::class.java)
                        if (!docOffer.isNullOrBlank()) {
                            Log.i("WebRtcEngine", "⚡ Offer SDP retrieved via server fetch (attempt $attempt) for $callId -> processing SDP answer")
                            withContext(Dispatchers.Main) {
                                if (!isCallTerminated(callId)) {
                                    _state.value = _state.value.copy(
                                        activeCall = (docCall ?: _state.value.activeCall)?.copy(
                                            status = CallStatus.ANSWERED,
                                            offerSdp = docOffer
                                        )
                                    )
                                    if (!hasProcessedOffer) {
                                        processOfferSdpIfAvailable()
                                    }
                                }
                            }
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.d("WebRtcEngine", "answerIncomingCall offer fetch attempt $attempt failed: ${e.message}")
                }
                delay(250)
            }
        }
        startCallTimer()
    }

    fun attachToCall(
        callId: String, 
        autoAnswer: Boolean = false,
        callerName: String? = null,
        callerNumber: String? = null,
        callTypeStr: String? = null
    ) {
        if (autoAnswer) {
            answerIncomingCall(callId, callerName, callerNumber, callTypeStr)
            return
        }

        resetIdleJob?.cancel()
        resetIdleJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        if (_state.value.activeCall?.callId != callId) {
            hasProcessedOffer = false
            hasProcessedAnswer = false
            prebuiltAnswerSdp = null
            preWarmDone = false
        }
        val firebaseMgr = com.example.data.repository.FirebaseManager.getInstance(context)
        if (firebaseMgr.isDndEnabled() || (callerNumber != null && firebaseMgr.isNumberBlocked(callerNumber))) {
            Log.d("WebRtcEngine", "attachToCall auto-declined by DND or Blocklist: $callId from $callerNumber")
            try {
                firestore.collection("calls").document(callId).update("status", CallStatus.DECLINED.name)
            } catch (_: Exception) {}
            return
        }

        if (isCallTerminated(callId)) {
            Log.d("WebRtcEngine", "attachToCall aborted: call $callId was already terminated locally")
            return
        }

        fetchIceServersAsync {}
        
        val isNotCurrentlyInCall = _state.value.activeCall == null || 
            _state.value.callStatus == CallStatus.IDLE || 
            _state.value.callStatus == CallStatus.ENDED || 
            _state.value.callStatus == CallStatus.DECLINED || 
            _state.value.callStatus == CallStatus.MISSED ||
            _state.value.activeCall?.callId != callId

        val existingOfferSdp = if (_state.value.activeCall?.callId == callId) _state.value.activeCall?.offerSdp else null

        // Optimistically show the call screen if we have the data
        if (callerName != null && callerNumber != null && callTypeStr != null && isNotCurrentlyInCall) {
            val type = try { CallType.valueOf(callTypeStr) } catch(e: Exception) { CallType.AUDIO }
            _state.value = WebRtcState(
                activeCall = CallDto(
                    callId = callId,
                    callerName = callerName,
                    callerNumber = callerNumber,
                    calleeNumber = myPhoneNumber,
                    callType = type,
                    status = CallStatus.RINGING,
                    offerSdp = existingOfferSdp
                ),
                callStatus = CallStatus.RINGING,
                callType = type,
                connectionStatusText = "Incoming Call"
            )
            headsetButtonManager.startListening()
            listenToActiveCall(callId, isCaller = false)
            listenForIceCandidates(callId, isCaller = false)
        }

        firestore.collection("calls").document(callId).get().addOnSuccessListener { doc ->
            if (isCallTerminated(callId)) {
                Log.d("WebRtcEngine", "attachToCall doc callback: call $callId was terminated locally. Aborting.")
                return@addOnSuccessListener
            }
            if (_state.value.activeCall?.callId == callId && (_state.value.callStatus == CallStatus.ENDED || _state.value.callStatus == CallStatus.DECLINED)) {
                Log.d("WebRtcEngine", "attachToCall doc callback: call $callId was already ended/declined locally. Aborting.")
                return@addOnSuccessListener
            }
            val call = doc.toObject(CallDto::class.java)
            if (call != null) {
                if (call.status == CallStatus.ENDED || call.status == CallStatus.DECLINED || call.status == CallStatus.MISSED) {
                    Log.d("WebRtcEngine", "attachToCall: call $callId already finished with status ${call.status}")
                    markCallTerminated(callId)
                    endCallInternalLocal(call.status)
                    return@addOnSuccessListener
                }

                val isNewCallOrIdle = _state.value.activeCall?.callId != callId ||
                    _state.value.callStatus == CallStatus.IDLE ||
                    _state.value.callStatus == CallStatus.ENDED ||
                    _state.value.callStatus == CallStatus.DECLINED ||
                    _state.value.callStatus == CallStatus.MISSED

                if (_state.value.callStatus == CallStatus.ANSWERED && _state.value.activeCall?.callId == callId) {
                    _state.value = _state.value.copy(
                        activeCall = call.copy(status = CallStatus.ANSWERED)
                    )
                    listenToActiveCall(callId, isCaller = false)
                    listenForIceCandidates(callId, isCaller = false)
                    if (call.offerSdp != null && !hasProcessedOffer) {
                        processOfferSdpIfAvailable()
                    }
                } else if (isNewCallOrIdle || _state.value.callStatus == CallStatus.RINGING) {
                    resetIdleJob?.cancel()
                    resetIdleJob = null

                    val resolvedStatus = if (call.status == CallStatus.CALLING) CallStatus.RINGING else call.status
                                         
                    if (resolvedStatus == CallStatus.RINGING && call.status != CallStatus.RINGING && !isCallTerminated(callId)) {
                        firestore.collection("calls").document(callId).update("status", CallStatus.RINGING.name)
                    }

                    _state.value = _state.value.copy(
                        activeCall = call.copy(status = resolvedStatus),
                        callType = call.callType,
                        callStatus = resolvedStatus,
                        connectionStatusText = "Incoming Call"
                    )
                    headsetButtonManager.startListening()
                    listenToActiveCall(callId, isCaller = false)
                    listenForIceCandidates(callId, isCaller = false)
                }
            }
        }
    }

    fun answerCall() {
        val call = _state.value.activeCall ?: return
        com.example.util.logging.LksLogger.breadcrumb("CALL", "Answering call ${call.callId} from ${call.callerNumber}")

        // 0. High-Priority FCM Push Notification to Caller (0ms direct token delivery):
        val targetFcm = call.callerFcmToken.ifBlank { pushTokenCache[call.callerNumber]?.first ?: "" }
        val targetWeb = call.callerWebToken.ifBlank { pushTokenCache[call.callerNumber]?.second ?: "" }
        triggerPushNotification(
            calleeNumber = call.callerNumber,
            callerName = myPhoneNumber,
            callerNumber = myPhoneNumber,
            callType = call.callType.name,
            callId = call.callId,
            type = "call_answered",
            explicitFcmToken = targetFcm,
            explicitWebToken = targetWeb
        )

        _state.value = _state.value.copy(
            callStatus = CallStatus.ANSWERED,
            connectionStatusText = "Connecting P2P..."
        )

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.cancel(1001)
        com.example.util.LksIncomingRingtonePlayer.stop()
        com.example.util.CallSoundEffectsManager.stopRingbackTone()

        com.example.services.ActiveCallService.start(context, call.callId, call.callType.name)
        headsetButtonManager.startListening()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                com.example.services.LksConnectionService.setCallActive()
            } catch (_: Exception) {}
        }
        try { audioDeviceModule?.setSpeakerMute(false) } catch (_: Exception) {}
        configureAudio(call.callType)

        // ⚡ INSTANT PATH: Pre-warm completed during ringing — just write the answer SDP to Firestore.
        // This is a single Firestore write (~100ms) vs. the normal 5-8s SDP negotiation chain.
        val cachedAnswer = if (prebuiltCallId == call.callId) prebuiltAnswerSdp else null
        if (cachedAnswer != null && peerConnection != null) {
            Log.i("WebRtcEngine", "⚡ INSTANT ANSWER: using pre-warmed SDP for ${call.callId}")
            // Snapshot & clear pre-warm state for the next call
            prebuiltAnswerSdp = null
            prebuiltCallId = null
            preWarmDone = false

            // Write status + answerSdp atomically in one Firestore document update
            firestore.collection("calls").document(call.callId).update(
                "status", CallStatus.ANSWERED.name,
                "answeredAt", System.currentTimeMillis(),
                "answerSdp", cachedAnswer
            ).addOnFailureListener { e ->
                Log.w("WebRtcEngine", "Failed to update status and answerSdp, retrying: ${e.message}")
                scope.launch {
                    delay(300)
                    try {
                        firestore.collection("calls").document(call.callId).update(
                            "status", CallStatus.ANSWERED.name,
                            "answeredAt", System.currentTimeMillis(),
                            "answerSdp", cachedAnswer
                        )
                    } catch (_: Exception) {}
                }
            }
        } else {
            // 🔁 FALLBACK PATH: Pre-warm not ready (e.g. offer arrived late, or pre-warm failed).
            // Reset pre-warm flags so processOfferSdpIfAvailable() runs the full negotiation.
            Log.d("WebRtcEngine", "Pre-warm miss for ${call.callId} — falling back to standard SDP negotiation")
            prebuiltAnswerSdp = null
            prebuiltCallId = null
            preWarmDone = false
            hasProcessedOffer = false

            firestore.collection("calls").document(call.callId).update(
                "status", CallStatus.ANSWERED.name,
                "answeredAt", System.currentTimeMillis()
            ).addOnFailureListener { e ->
                Log.w("WebRtcEngine", "Failed to update status to ANSWERED, retrying: ${e.message}")
                scope.launch {
                    delay(300)
                    try {
                        firestore.collection("calls").document(call.callId).update(
                            "status", CallStatus.ANSWERED.name,
                            "answeredAt", System.currentTimeMillis()
                        )
                    } catch (_: Exception) {}
                }
            }

            createPeerConnection(isCaller = false, callId = call.callId)

            if (call.offerSdp.isNullOrBlank()) {
                scope.launch(Dispatchers.IO) {
                    for (attempt in 1..12) {
                        try {
                            val snap = firestore.collection("calls").document(call.callId)
                                .get(com.google.firebase.firestore.Source.SERVER).await()
                            val offer = snap.getString("offerSdp")
                            if (!offer.isNullOrBlank()) {
                                withContext(Dispatchers.Main) {
                                    val current = _state.value.activeCall
                                    if (current?.callId == call.callId) {
                                        _state.value = _state.value.copy(
                                            activeCall = current.copy(offerSdp = offer)
                                        )
                                        processOfferSdpIfAvailable()
                                    }
                                }
                                break
                            }
                        } catch (_: Exception) {}
                        delay(250)
                    }
                }
            } else {
                processOfferSdpIfAvailable()
            }
        }

        // Unmute local & remote audio tracks now that user tapped answer
        unmuteAllAudioTracks()

        // If video call, start camera capture if it was deferred during pre-warm
        if (call.callType == CallType.VIDEO) {
            startVideoCaptureIfNeeded()
        }

        // Phase 3: Reliable call logs — record call start immediately on answer
        com.example.data.repository.FirebaseManager.getInstance(context).recordCallStarted(
            callId = call.callId,
            direction = com.example.data.model.CallDirection.INCOMING,
            otherPartyNumber = call.callerNumber,
            otherPartyName = call.callerName,
            callType = call.callType
        )

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
                unmuteAllAudioTracks()
                
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
                                        .addOnFailureListener { e ->
                                            Log.w("WebRtcEngine", "Failed to update answerSdp, retrying: ${e.message}")
                                            scope.launch {
                                                delay(300)
                                                try {
                                                    firestore.collection("calls").document(call.callId).update("answerSdp", tunedSdp)
                                                } catch (_: Exception) {}
                                            }
                                        }
                                    unmuteAllAudioTracks()
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
        audioRouteManager.configureAudio(callType)
    }

    private fun listenToActiveCall(callId: String, isCaller: Boolean) {
        if (currentListeningCallId == callId && activeCallListener != null) {
            return
        }
        currentListeningCallId = callId
        activeCallListener?.remove()
        activeCallListener = firestore.collection("calls").document(callId)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.w("WebRtcEngine", "listenToActiveCall error on $callId: ${e.message}")
                    return@addSnapshotListener
                }
                if (snapshot == null || !snapshot.exists()) {
                    Log.i("WebRtcEngine", "Call doc $callId no longer exists in Firestore -> terminating call immediately")
                    markCallTerminated(callId)
                    endCallInternalLocal(CallStatus.ENDED)
                    return@addSnapshotListener
                }
                val call = snapshot.toObject(CallDto::class.java)
                if (call != null) {
                    if (call.callerNumber.isNotBlank() && (call.callerFcmToken.isNotBlank() || call.callerWebToken.isNotBlank())) {
                        pushTokenCache[call.callerNumber] = Pair(call.callerFcmToken, call.callerWebToken)
                    }
                    if (call.calleeNumber.isNotBlank() && (call.calleeFcmToken.isNotBlank() || call.calleeWebToken.isNotBlank())) {
                        pushTokenCache[call.calleeNumber] = Pair(call.calleeFcmToken, call.calleeWebToken)
                    }
                    handleCallDocumentUpdate(call, snapshot, isCaller)
                }
            }

        // Active server-side polling watchdog (350ms rapid polling):
        // When on mobile data outside the house, cellular towers switch and gRPC stream can stall
        // without dropping TCP. Polling Source.SERVER at 350ms ensures that answer status
        // and answerSdp are detected immediately even if the snapshot listener hung!
        activeCallPollingJob?.cancel()
        activeCallPollingJob = scope.launch(Dispatchers.IO) {
            delay(200)
            while (isActive && !hasProcessedAnswer && (_state.value.callStatus == CallStatus.CALLING || _state.value.callStatus == CallStatus.RINGING || _state.value.callStatus == CallStatus.ANSWERED)) {
                try {
                    val serverDoc = firestore.collection("calls").document(callId)
                        .get(com.google.firebase.firestore.Source.SERVER).await()
                    if (serverDoc.exists()) {
                        val serverCall = serverDoc.toObject(CallDto::class.java)
                        if (serverCall != null) {
                            if (serverCall.callerNumber.isNotBlank() && (serverCall.callerFcmToken.isNotBlank() || serverCall.callerWebToken.isNotBlank())) {
                                pushTokenCache[serverCall.callerNumber] = Pair(serverCall.callerFcmToken, serverCall.callerWebToken)
                            }
                            if (serverCall.calleeNumber.isNotBlank() && (serverCall.calleeFcmToken.isNotBlank() || serverCall.calleeWebToken.isNotBlank())) {
                                pushTokenCache[serverCall.calleeNumber] = Pair(serverCall.calleeFcmToken, serverCall.calleeWebToken)
                            }
                            withContext(Dispatchers.Main) {
                                handleCallDocumentUpdate(serverCall, serverDoc, isCaller)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.d("WebRtcEngine", "Active call polling error: ${e.message}")
                }
                val pollDelay = if (_state.value.callStatus == CallStatus.ANSWERED) 250L else 350L
                delay(pollDelay)
            }
        }
    }

    private fun handleCallDocumentUpdate(call: CallDto, snapshot: com.google.firebase.firestore.DocumentSnapshot?, isCaller: Boolean) {
        val currentCall = _state.value.activeCall
        if (currentCall?.callId == call.callId || currentCall == null) {
            val oldCall = currentCall
            _state.value = _state.value.copy(activeCall = call)
            
            if (isCaller && call.status == CallStatus.RINGING) {
                if (_state.value.callStatus == CallStatus.CALLING) {
                    _state.value = _state.value.copy(
                        callStatus = CallStatus.RINGING,
                        connectionStatusText = "Ringing..."
                    )
                }
                if (!call.callerTuneTitle.isNullOrBlank()) {
                    if (_state.value.activeCallerTuneTitle != call.callerTuneTitle) {
                        _state.value = _state.value.copy(activeCallerTuneTitle = call.callerTuneTitle)
                        com.example.util.CallSoundEffectsManager.startCallerTune(context, call.callerTuneAudioBase64, call.callerTuneUrl, call.callerTuneTitle, call.callerTuneStartMs)
                    } else {
                        com.example.util.CallSoundEffectsManager.onCallRinging(context)
                    }
                } else {
                    com.example.util.CallSoundEffectsManager.onCallRinging(context)
                }
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

            if (call.videoUpgradeStatus == com.example.data.model.VideoUpgradeStatus.DECLINED) {
                if (_state.value.callType == CallType.VIDEO) {
                    revertToAudioCall("Video Declined • Switched to Audio")
                }
                if (_state.value.didIRequestVideoUpgrade) {
                    // Show a toast or update state so the requester knows it was declined
                    _state.value = _state.value.copy(
                        connectionStatusText = "Video Request Declined",
                        didIRequestVideoUpgrade = false
                    )
                    scope.launch {
                        delay(3000)
                        if (_state.value.callStatus == CallStatus.ANSWERED && _state.value.callType == CallType.AUDIO) {
                            _state.value = _state.value.copy(connectionStatusText = "Connected • WebRTC")
                        }
                    }
                    // Reset the status so we can request again
                    firestore.collection("calls").document(call.callId).update("videoUpgradeStatus", null)
                }
            }

            // Fallback: If remote callType reverted to AUDIO while local is still in VIDEO
            if (call.callType == CallType.AUDIO && _state.value.callType == CallType.VIDEO &&
                call.videoUpgradeStatus != com.example.data.model.VideoUpgradeStatus.REQUESTED &&
                call.videoUpgradeStatus != com.example.data.model.VideoUpgradeStatus.ACCEPTED) {
                Log.i("WebRtcEngine", "Remote callType is AUDIO while local is VIDEO -> reverting to AUDIO")
                revertToAudioCall("Switched to Audio")
            }

            // ICE Restart Requested by Peer (e.g. mobile network dropped to No Service while on Wi-Fi)
            val iceRestartTs = snapshot?.getLong("iceRestartRequested") ?: 0L
            if (isCaller && iceRestartTs > lastProcessedIceRestartTimestamp && _state.value.callStatus == CallStatus.ANSWERED) {
                lastProcessedIceRestartTimestamp = iceRestartTs
                Log.i("WebRtcEngine", "Peer requested ICE restart ($iceRestartTs) — initiating IceRestart as caller")
                scope.launch {
                    try {
                        peerConnection?.restartIce()
                    } catch (_: Exception) {}
                    val constraints = MediaConstraints().apply {
                        mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
                    }
                    peerConnection?.createOffer(object : SimpleSdpObserver() {
                        override fun onCreateSuccess(desc: SessionDescription?) {
                            peerConnection?.setLocalDescription(SimpleSdpObserver(), desc)
                            desc?.let {
                                firestore.collection("calls").document(call.callId)
                                    .update("offerSdp", it.description)
                            }
                        }
                    }, constraints)
                }
            }

            if (isCaller && call.status == CallStatus.ANSWERED && _state.value.callStatus != CallStatus.ANSWERED) {
                com.example.util.CallSoundEffectsManager.stopRingbackTone()
                _state.value = _state.value.copy(
                    callStatus = CallStatus.ANSWERED,
                    connectionStatusText = if (call.answerSdp != null) "Connected • WebRTC" else "Connecting P2P..."
                )
                unmuteAllAudioTracks()
                // Phase 3: Reliable call logs — record outgoing call start immediately on answer
                com.example.data.repository.FirebaseManager.getInstance(context).recordCallStarted(
                    callId = call.callId,
                    direction = com.example.data.model.CallDirection.OUTGOING,
                    otherPartyNumber = call.calleeNumber,
                    otherPartyName = call.calleeName,
                    callType = call.callType
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
                activeCallPollingJob?.cancel()
                activeCallPollingJob = null
                com.example.util.CallSoundEffectsManager.stopRingbackTone()
                hasProcessedAnswer = true
                val sessionDescription = SessionDescription(SessionDescription.Type.ANSWER, call.answerSdp)
                peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        Log.i("WebRtcEngine", "Remote answer SDP set successfully")
                        drainQueuedRemoteIceCandidates()
                        unmuteAllAudioTracks()
                    }
                    override fun onSetFailure(error: String?) {
                        Log.e("WebRtcEngine", "Failed to set remote answer: $error")
                    }
                }, sessionDescription)
                _state.value = _state.value.copy(
                    callStatus = CallStatus.ANSWERED,
                    connectionStatusText = "Connected • WebRTC"
                )
                unmuteAllAudioTracks()
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    try {
                        com.example.services.LksConnectionService.setCallActive()
                    } catch (_: Exception) {}
                }
                refreshAvailableAudioDevices(defaultCallType = null)
                if (_state.value.callDurationSeconds == 0) {
                    startCallTimer()
                }
            }

            if (!isCaller && call.offerSdp != null && (!hasProcessedOffer || call.offerSdp != oldCall?.offerSdp)) {
                // If still ringing and pre-warm not done, retry pre-warm now that offer has arrived
                if (_state.value.callStatus == CallStatus.RINGING && !preWarmDone) {
                    preWarmForIncomingCall(call)
                } else {
                    processOfferSdpIfAvailable()
                }
            }

            // Hold State synchronization
            val remoteHold = snapshot?.getBoolean("isOnHold") == true || snapshot?.getBoolean("onHold") == true || call.isOnHold
            val heldBy = snapshot?.getString("heldBy") ?: call.heldBy ?: ""
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
                    com.example.util.CallSoundEffectsManager.playHoldTone(context)
                    localAudioTrack?.setEnabled(false)
                    _state.value.localVideoTrack?.setEnabled(false)
                } else {
                    com.example.util.CallSoundEffectsManager.playUnholdTone(context)
                    localAudioTrack?.setEnabled(!_state.value.isMuted)
                    _state.value.localVideoTrack?.setEnabled(_state.value.isCameraOn)
                }
            }
            
            if (call.status == CallStatus.ENDED || call.status == CallStatus.DECLINED || call.status == CallStatus.MISSED) {
                activeCallPollingJob?.cancel()
                activeCallPollingJob = null
                Log.i("WebRtcEngine", "handleCallDocumentUpdate: call ${call.callId} remote status is ${call.status} -> ending call immediately")
                markCallTerminated(call.callId)
                if (_state.value.callStatus != CallStatus.ENDED && _state.value.callStatus != CallStatus.DECLINED) {
                    endCallInternalLocal(call.status)
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

    private fun resolveAndPrepareCalleeTune(calleeNumber: String) {
        // Fast path: check in-memory cache first (0ms)
        val inMemory = FirebaseManager.getInstance(context).lookupUserByNumber(calleeNumber)
        if (inMemory != null && !inMemory.callerTuneTitle.isNullOrBlank()) {
            val title = inMemory.callerTuneTitle
            val audio = inMemory.callerTuneAudioBase64
            val url = inMemory.callerTuneUrl
            val startMs = inMemory.callerTuneStartMs
            _state.value = _state.value.copy(activeCallerTuneTitle = title)
            if (_state.value.callStatus == CallStatus.RINGING) {
                com.example.util.CallSoundEffectsManager.startCallerTune(context, audio, url, title, startMs)
            } else {
                com.example.util.CallSoundEffectsManager.prepareCallerTune(context, audio, url, title, startMs)
            }
            return
        }

        val cleanDigits = calleeNumber.replace(Regex("[^0-9]"), "")
        val variations = mutableListOf(calleeNumber)
        if (cleanDigits.isNotBlank()) {
            variations.add(cleanDigits)
            if (cleanDigits.length > 10) variations.add(cleanDigits.takeLast(10))
            if (!calleeNumber.startsWith("+")) variations.add("+$calleeNumber")
        }
        val distinctVars = variations.distinct().take(10)

        fun applyTuneFromDoc(doc: com.google.firebase.firestore.DocumentSnapshot?): Boolean {
            val title = doc?.getString("callerTuneTitle")
            val audio = doc?.getString("callerTuneAudioBase64")
            val url = doc?.getString("callerTuneUrl")
            val startMs = doc?.getLong("callerTuneStartMs") ?: 0L
            if (!title.isNullOrBlank() && (!audio.isNullOrBlank() || !url.isNullOrBlank())) {
                _state.value = _state.value.copy(activeCallerTuneTitle = title)
                if (_state.value.callStatus == CallStatus.RINGING) {
                    com.example.util.CallSoundEffectsManager.startCallerTune(context, audio, url, title, startMs)
                } else {
                    com.example.util.CallSoundEffectsManager.prepareCallerTune(context, audio, url, title, startMs)
                }
                return true
            }
            return false
        }

        fun tryDirectLookup(index: Int) {
            if (index >= distinctVars.size) return
            val docId = distinctVars[index]
            firestore.collection("users").document(docId).get()
                .addOnSuccessListener { doc ->
                    if (!applyTuneFromDoc(doc)) {
                        tryDirectLookup(index + 1)
                    }
                }
                .addOnFailureListener {
                    tryDirectLookup(index + 1)
                }
        }

        firestore.collection("users").whereIn("phoneNumber", distinctVars).get()
            .addOnSuccessListener { qs ->
                val docWithTune = qs.documents.firstOrNull { !it.getString("callerTuneTitle").isNullOrBlank() }
                if (docWithTune != null) {
                    applyTuneFromDoc(docWithTune)
                } else {
                    tryDirectLookup(0)
                }
            }
            .addOnFailureListener { e ->
                Log.w("WebRtcEngine", "whereIn query for caller tune failed: ${e.message}, falling back to direct lookup")
                tryDirectLookup(0)
            }
    }

    fun revertToAudioCall(reason: String = "Switched to Audio") {
        if (_state.value.callType != CallType.VIDEO) return
        Log.i("WebRtcEngine", "reverting to audio call: reason=$reason")
        videoUpgradeWatchdogJob?.cancel()
        videoUpgradeWatchdogJob = null

        // 1. Stop video capturer and dispose local video track
        try {
            videoCapturer?.stopCapture()
        } catch (_: Exception) {}

        try {
            _state.value.localVideoTrack?.setEnabled(false)
            _state.value.localVideoTrack?.dispose()
        } catch (_: Exception) {}

        // 2. Remove video track from peer connection senders
        try {
            peerConnection?.senders?.find { it.track() is VideoTrack }?.let { sender ->
                sender.setTrack(null, false)
            }
        } catch (_: Exception) {}

        // 3. Update state back to AUDIO
        _state.value = _state.value.copy(
            callType = CallType.AUDIO,
            localVideoTrack = null,
            remoteVideoTrack = null,
            isCameraOn = false,
            isVideoUpgradeRequested = false,
            didIRequestVideoUpgrade = false,
            connectionStatusText = reason
        )

        // 4. Reconfigure audio routing for audio call
        configureAudio(CallType.AUDIO)
        refreshAvailableAudioDevices(defaultCallType = CallType.AUDIO)

        // 5. Restore connection status text after 3 seconds
        scope.launch {
            delay(3000)
            if (_state.value.callStatus == CallStatus.ANSWERED && _state.value.callType == CallType.AUDIO) {
                _state.value = _state.value.copy(connectionStatusText = "Connected • WebRTC")
            }
        }
    }

    private fun executeVideoUpgrade(isCaller: Boolean) {
        val currentCall = _state.value.activeCall ?: return
        
        // 1. Create and add local video track
        createVideoTrack()
        _state.value.localVideoTrack?.let {
            peerConnection?.addTrack(it, listOf("ARDAMS"))
        }
        
        // 2. Update UI state to video
        _state.value = _state.value.copy(callType = CallType.VIDEO, connectionStatusText = "Connecting Video...")
        if (_state.value.selectedAudioDevice != AudioDeviceType.BLUETOOTH && _state.value.selectedAudioDevice != AudioDeviceType.WIRED_HEADSET) {
            selectAudioDeviceType(AudioDeviceType.SPEAKERPHONE)
        }
        
        // 3. Start 10-second watchdog timer: If remote video is not received within 10s, auto-revert to audio!
        videoUpgradeWatchdogJob?.cancel()
        videoUpgradeWatchdogJob = scope.launch {
            delay(10000)
            if (_state.value.callType == CallType.VIDEO && _state.value.remoteVideoTrack == null && _state.value.callStatus == CallStatus.ANSWERED) {
                Log.w("WebRtcEngine", "Video renegotiation watchdog timed out after 10s — safely reverting to audio")
                revertToAudioCall("Video Timeout • Continued on Audio")
                try {
                    firestore.collection("calls").document(currentCall.callId)
                        .update(
                            "videoUpgradeStatus", com.example.data.model.VideoUpgradeStatus.DECLINED.name,
                            "callType", CallType.AUDIO.name
                        )
                } catch (_: Exception) {}
            }
        }

        // 4. Caller creates new offer for renegotiation
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
                if (elapsed % 2 == 0) {
                    monitorCallQuality()
                }
                delay(1000)
            }
        }
    }

    private fun monitorCallQuality() {
        val pc = peerConnection ?: return
        try {
            pc.getStats(object : RTCStatsCollectorCallback {
                override fun onStatsDelivered(report: RTCStatsReport?) {
                    if (report == null) return
                    var rttMs = 80.0
                    var packetsLost = 0L
                    var packetsReceived = 0L

                    for (stat in report.statsMap.values) {
                        if (stat.type == "candidate-pair" && (stat.members["nominated"] == true || stat.members["state"] == "succeeded")) {
                            val currentRtt = stat.members["currentRoundTripTime"] as? Double
                            if (currentRtt != null) {
                                rttMs = currentRtt * 1000.0
                            }
                        }
                        if (stat.type == "inbound-rtp") {
                            val lost = (stat.members["packetsLost"] as? Number)?.toLong() ?: 0L
                            val received = (stat.members["packetsReceived"] as? Number)?.toLong() ?: 0L
                            packetsLost += lost
                            packetsReceived += received
                        }
                    }

                    val lossRate = if (packetsReceived > 0) (packetsLost.toDouble() / (packetsReceived + packetsLost)) * 100.0 else 0.0

                    val bars = when {
                        rttMs < 120.0 && lossRate < 2.0 -> 5
                        rttMs < 220.0 && lossRate < 5.0 -> 4
                        rttMs < 350.0 && lossRate < 10.0 -> 3
                        rttMs < 550.0 && lossRate < 20.0 -> 2
                        else -> 1
                    }

                    scope.launch(Dispatchers.Main) {
                        if (_state.value.networkQualityBars != bars && _state.value.callStatus == CallStatus.ANSWERED) {
                            _state.value = _state.value.copy(networkQualityBars = bars)
                            if (_state.value.callType == CallType.VIDEO) {
                                adaptVideoQuality(bars)
                            }
                        }
                    }
                }
            })
        } catch (_: Exception) {}
    }

    private fun adaptVideoQuality(qualityBars: Int) {
        val pc = peerConnection ?: return
        try {
            val videoSender = pc.senders.find { it.track() is VideoTrack } ?: return
            val params = videoSender.parameters
            if (params.encodings.isNotEmpty()) {
                val encoding = params.encodings[0]
                val (maxBitrate, maxFps) = when (qualityBars) {
                    5 -> 2_000_000 to 30 // 2 Mbps, 30fps
                    4 -> 1_200_000 to 30 // 1.2 Mbps, 30fps
                    3 -> 800_000 to 24   // 800 Kbps, 24fps
                    2 -> 400_000 to 15   // 400 Kbps, 15fps
                    else -> 200_000 to 15 // 200 Kbps, 15fps
                }
                encoding.maxBitrateBps = maxBitrate
                encoding.maxFramerate = maxFps
                videoSender.parameters = params
                Log.d("WebRtcEngine", "Adaptive Video: quality=$qualityBars maxBitrate=$maxBitrate bps maxFps=$maxFps")
            }
        } catch (e: Exception) {
            Log.w("WebRtcEngine", "Failed to adapt video quality: ${e.message}")
        }
    }


    fun toggleMute() {
        val newMuted = !_state.value.isMuted
        if (!_state.value.isOnHold) {
            localAudioTrack?.setEnabled(!newMuted)
        }
        _state.value = _state.value.copy(isMuted = newMuted)
    }

    fun putCallOnHold(onHold: Boolean, isCellularInterruption: Boolean = false) {
        val currentCall = _state.value.activeCall ?: return
        val myNumber = myPhoneNumber.ifBlank {
            com.example.data.repository.FirebaseManager.getInstance(context).currentUser.value?.phoneNumber ?: ""
        }
        
        // 1. Mute or restore local audio and video tracks
        if (onHold) {
            localAudioTrack?.setEnabled(false)
            _state.value.localVideoTrack?.setEnabled(false)
            com.example.util.CallSoundEffectsManager.playHoldTone(context)
        } else {
            localAudioTrack?.setEnabled(!_state.value.isMuted)
            _state.value.localVideoTrack?.setEnabled(_state.value.isCameraOn)
            com.example.util.CallSoundEffectsManager.playUnholdTone(context)
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
        signalingManager.updateHoldState(currentCall.callId, onHold, if (onHold) myNumber else null)
    }

    fun toggleHold() {
        val current = _state.value.isOnHold
        putCallOnHold(!current)
    }


    fun refreshAvailableAudioDevices(defaultCallType: CallType? = null) {
        audioRouteManager.refreshAvailableAudioDevices(defaultCallType)
    }

    fun selectAudioDevice(device: AudioDeviceOption, updateDeviceList: Boolean = true) {
        audioRouteManager.selectAudioDevice(device, updateDeviceList)
    }

    fun selectAudioDeviceType(type: AudioDeviceType) {
        audioRouteManager.selectAudioDeviceType(type)
    }

    fun onTelecomAudioRouteChanged(targetType: AudioDeviceType) {
        val status = _state.value.callStatus
        if (status == CallStatus.RINGING || status == CallStatus.IDLE) {
            Log.d("WebRtcEngine", "🛡️ Suppressing onTelecomAudioRouteChanged($targetType) while callStatus is $status (ringing/idle)")
            return
        }
        audioRouteManager.onTelecomAudioRouteChanged(targetType)
    }

    fun toggleSpeaker() {
        audioRouteManager.toggleSpeaker()
    }

    fun toggleCamera() {
        val newCameraOn = !_state.value.isCameraOn
        if (!_state.value.isOnHold) {
            _state.value.localVideoTrack?.setEnabled(newCameraOn)
        }
        _state.value = _state.value.copy(isCameraOn = newCameraOn)
    }

    fun switchCamera() {
        if (videoCapturer is CameraVideoCapturer) {
            val cameraCapturer = videoCapturer as CameraVideoCapturer
            cameraCapturer.switchCamera(null)
            _state.value = _state.value.copy(isFrontCamera = !_state.value.isFrontCamera)
        }
    }

    fun endCall(targetCallId: String? = null) {
        val call = _state.value.activeCall
        val callId = targetCallId?.takeIf { it.isNotBlank() } ?: call?.callId
        if (!callId.isNullOrBlank()) {
            markCallTerminated(callId)
            val isRingingOrCalling = _state.value.callStatus == CallStatus.CALLING || _state.value.callStatus == CallStatus.RINGING
            val endStatus = if (isRingingOrCalling) CallStatus.MISSED else CallStatus.ENDED
            
            firestore.collection("calls").document(callId).set(
                mapOf(
                    "status" to endStatus.name,
                    "endedAt" to System.currentTimeMillis()
                ),
                com.google.firebase.firestore.SetOptions.merge()
            ).addOnFailureListener {
                firestore.collection("calls").document(callId).update(
                    "status", endStatus.name,
                    "endedAt", System.currentTimeMillis()
                )
            }
            
            val currentPhone = FirebaseManager.getInstance(context).currentUser.value?.phoneNumber ?: myPhoneNumber
            val isCaller = call != null && currentPhone.isNotBlank() && (
                currentPhone == call.callerNumber ||
                currentPhone.replace(Regex("[^0-9]"), "") == call.callerNumber.replace(Regex("[^0-9]"), "")
            )
            if (isCaller && endStatus == CallStatus.MISSED && call != null) {
                FirebaseManager.getInstance(context).logMissedCallForOfflineUser(
                    calleeNumber = call.calleeNumber,
                    callerNumber = call.callerNumber,
                    callerName = call.callerName,
                    callType = call.callType
                )
                val targetFcm = call.calleeFcmToken
                val targetWeb = call.calleeWebToken
                triggerPushNotification(
                    calleeNumber = call.calleeNumber,
                    callerName = call.callerName,
                    callerNumber = call.callerNumber,
                    callType = call.callType.name,
                    callId = callId,
                    type = "missed_call",
                    explicitFcmToken = targetFcm.ifBlank { pushTokenCache[call.calleeNumber]?.first },
                    explicitWebToken = targetWeb.ifBlank { pushTokenCache[call.calleeNumber]?.second }
                )
            } else if (call != null) {
                // When ending an active/connected call, notify other party via push so they don't lag
                val otherPartyNumber = if (isCaller) call.calleeNumber else call.callerNumber
                val currentName = FirebaseManager.getInstance(context).currentUser.value?.displayName ?: currentPhone
                val targetFcm = if (isCaller) call.calleeFcmToken else call.callerFcmToken
                val targetWeb = if (isCaller) call.calleeWebToken else call.callerWebToken
                if (otherPartyNumber.isNotBlank()) {
                    triggerPushNotification(
                        calleeNumber = otherPartyNumber,
                        callerName = currentName,
                        callerNumber = currentPhone,
                        callType = call.callType.name,
                        callId = callId,
                        type = "call_ended",
                        explicitFcmToken = targetFcm.ifBlank { pushTokenCache[otherPartyNumber]?.first },
                        explicitWebToken = targetWeb.ifBlank { pushTokenCache[otherPartyNumber]?.second }
                    )
                }
            }

            val callIdToDelete = callId
            scope.launch {
                delay(30000)
                deleteCallAndCandidates(callIdToDelete)
            }
        }
        endCallInternalLocal(CallStatus.ENDED)
    }

    fun declineCall(targetCallId: String? = null, targetCallerNumber: String? = null) {
        val call = _state.value.activeCall
        val callId = targetCallId?.takeIf { it.isNotBlank() } ?: call?.callId
        val callerNumber = targetCallerNumber?.takeIf { it.isNotBlank() } ?: call?.callerNumber ?: ""
        val callType = call?.callType ?: CallType.VIDEO

        if (!callId.isNullOrBlank()) {
            markCallTerminated(callId)
            
            // 1. Update Firestore immediately with merge so it never fails
            firestore.collection("calls").document(callId).set(
                mapOf(
                    "status" to CallStatus.DECLINED.name,
                    "endedAt" to System.currentTimeMillis()
                ),
                com.google.firebase.firestore.SetOptions.merge()
            ).addOnFailureListener {
                firestore.collection("calls").document(callId).update(
                    "status", CallStatus.DECLINED.name,
                    "endedAt", System.currentTimeMillis()
                )
            }

            // 2. CRITICAL: Trigger instant push to caller so caller stops ringing in <200ms
            if (callerNumber.isNotBlank()) {
                val currentPhone = FirebaseManager.getInstance(context).currentUser.value?.phoneNumber ?: myPhoneNumber
                val currentName = FirebaseManager.getInstance(context).currentUser.value?.displayName ?: currentPhone
                val targetFcm = call?.callerFcmToken ?: ""
                val targetWeb = call?.callerWebToken ?: ""
                triggerPushNotification(
                    calleeNumber = callerNumber,
                    callerName = currentName,
                    callerNumber = currentPhone,
                    callType = callType.name,
                    callId = callId,
                    type = "call_declined",
                    explicitFcmToken = targetFcm.ifBlank { pushTokenCache[callerNumber]?.first },
                    explicitWebToken = targetWeb.ifBlank { pushTokenCache[callerNumber]?.second }
                )
            }

            val callIdToDelete = callId
            scope.launch {
                delay(30000)
                deleteCallAndCandidates(callIdToDelete)
            }
        }
        endCallInternalLocal(CallStatus.DECLINED)
    }

    private fun deleteCallAndCandidates(callId: String) {
        signalingManager.deleteCallAndCandidates(callId)
    }

    /**
     * Forces the engine to end the call immediately when a 'cancel_call', 'missed_call',
     * 'call_declined', or 'call_ended' push notification is received.
     */
    fun forceEndCallFromPush(callId: String, status: CallStatus = CallStatus.MISSED) {
        markCallTerminated(callId)
        scope.launch {
            val activeCallId = _state.value.activeCall?.callId
            // Industry Standard: ONLY end the call if the push callId matches the active call.
            // Prevents stale pushes from a previous call killing a live call with a different person.
            if (activeCallId != null && activeCallId == callId) {
                Log.d("WebRtcEngine", "forceEndCallFromPush: ending active call $activeCallId for push $callId with status $status")
                endCallInternalLocal(status)
            } else if (activeCallId == null && (_state.value.callStatus == CallStatus.IDLE || _state.value.callStatus == CallStatus.ENDED)) {
                // No active call — safe to process (e.g., clearing ringtone for a missed call push)
                Log.d("WebRtcEngine", "forceEndCallFromPush: no active call, processing cleanup for push $callId")
                endCallInternalLocal(status)
            } else {
                Log.w("WebRtcEngine", "forceEndCallFromPush: IGNORED stale push for $callId (active call is $activeCallId)")
            }
        }
    }

    private fun endCallInternalLocal(status: CallStatus) {
        // Guard: prevent double-cleanup
        if (_state.value.callStatus == CallStatus.IDLE && status != CallStatus.IDLE) return
        com.example.util.logging.LksLogger.breadcrumb("CALL", "Ending call locally with status: $status")
        
        lastInitiateCallTimestamp = 0L // Reset debounce so user can redial immediately
        com.example.util.CallSoundEffectsManager.stopRingbackTone()
        com.example.util.CallSoundEffectsManager.stopHoldReminder()

        timerJob?.cancel()
        timerJob = null
        activeCallPollingJob?.cancel()
        activeCallPollingJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        videoUpgradeWatchdogJob?.cancel()
        videoUpgradeWatchdogJob = null
        activeCallListener?.remove()
        activeCallListener = null
        currentListeningCallId = null
        iceCandidateListener?.remove()
        iceCandidateListener = null
        
        // Reset flags for next call
        hasProcessedOffer = false
        hasProcessedAnswer = false
        lastProcessedIceRestartTimestamp = 0L
        sentIceCandidateHashes.clear()
        // Reset pre-warm state for the next incoming call
        prebuiltAnswerSdp = null
        prebuiltCallId = null
        preWarmDone = false
        try { audioDeviceModule?.setSpeakerMute(false) } catch (_: Exception) {}
        synchronized(queuedRemoteIceCandidates) {
            queuedRemoteIceCandidates.clear()
        }

        try {
            if (incomingCallWakeLock?.isHeld == true) incomingCallWakeLock?.release()
        } catch (_: Exception) {}
        incomingCallWakeLock = null
        
        cleanupMediaAndPeerConnection()
        val prevCall = _state.value.activeCall

        // Automatically stop call recording if active and capture saved file
        val recordingFile = try {
            com.example.util.CallAudioRecorder.getInstance(context).stopRecording(prevCall?.callId)
        } catch (_: Exception) { null }

        // Phase 3: Reliable call logs — record call ended for both caller and callee
        if (prevCall != null) {
            val userPhone = com.example.data.repository.FirebaseManager.getInstance(context).currentUser.value?.phoneNumber ?: myPhoneNumber
            val isCaller = userPhone.isNotBlank() && (
                userPhone == prevCall.callerNumber ||
                userPhone.replace(Regex("[^0-9]"), "") == prevCall.callerNumber.replace(Regex("[^0-9]"), "")
            )
            val direction = if (status == CallStatus.MISSED) {
                com.example.data.model.CallDirection.MISSED
            } else if (isCaller) {
                com.example.data.model.CallDirection.OUTGOING
            } else {
                com.example.data.model.CallDirection.INCOMING
            }
            val otherNumber = if (isCaller) prevCall.calleeNumber else prevCall.callerNumber
            val otherName = if (isCaller) prevCall.calleeName else prevCall.callerName
            com.example.data.repository.FirebaseManager.getInstance(context).recordCallEnded(
                callId = prevCall.callId,
                status = status,
                durationSeconds = _state.value.callDurationSeconds,
                fallbackDirection = direction,
                fallbackOtherNumber = otherNumber,
                fallbackOtherName = otherName,
                fallbackCallType = prevCall.callType,
                recordingPath = recordingFile?.absolutePath
            )
        }

        // Keep prevCall for UI display (so "Call Ended" screen shows caller name/number for 1500ms).
        // The Firestore listener guard has been fixed to check callStatus (not activeCall == null)
        // so the next incoming call can arrive during this window without being blocked.
        _state.value = _state.value.copy(
            callStatus = status,
            activeCall = prevCall,   // ← keep for UI display during the 1500ms end screen
            connectionStatusText = when (status) {
                CallStatus.ENDED -> "Call Ended"
                CallStatus.DECLINED -> "Call Declined"
                CallStatus.MISSED -> "Call Missed"
                else -> status.name
            },
            activeCallerTuneTitle = null,
            localVideoTrack = null,
            remoteVideoTrack = null
        )
        
        // Briefly delay then reset to IDLE so the UI can show the end status
        resetIdleJob?.cancel()
        resetIdleJob = scope.launch {
            delay(1500)
            if (_state.value.callStatus != CallStatus.ANSWERED && _state.value.callStatus != CallStatus.CALLING && _state.value.callStatus != CallStatus.RINGING) {
                _state.value = WebRtcState(callStatus = CallStatus.IDLE)
            }
        }
        
        // Reset audio routing & release audio focus
        audioRouteManager.resetAudioRouting()
        signalingManager.cleanup()
        
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
        com.example.services.FloatingCallBubbleService.hide(context)
        com.example.util.LksIncomingRingtonePlayer.stop()
        try { com.example.services.LksKeepAliveService.stopRingtone(context) } catch (_: Exception) {}
        com.example.util.SamsungVoiceFocusManager.stop()
        com.example.util.CallSoundEffectsManager.stopRingbackTone()
        com.example.util.CallSoundEffectsManager.stopHoldReminder()
        if (status != CallStatus.IDLE) {
            com.example.util.CallSoundEffectsManager.playCallEndedTone(context)
        }
        // NOTE: 30s Firestore cleanup is scheduled by endCall()/declineCall() — not duplicated here.
    }

    /**
     * Hooks WebRTC's internal WebRtcAudioTrack to tap remote playout PCM audio.
     * Replaces the private audioTrack field with a transparent RecordingAudioTrackWrapper.
     * Completely safe, zero impact on audio latency or routing.
     */
    fun hookWebRtcAudioTrackPlayout() {
        try {
            val adm = audioDeviceModule ?: return
            val audioOutputField = JavaAudioDeviceModule::class.java.getDeclaredField("audioOutput").apply {
                isAccessible = true
            }
            val webRtcAudioTrack = audioOutputField.get(adm) ?: return

            val audioTrackField = webRtcAudioTrack.javaClass.getDeclaredField("audioTrack").apply {
                isAccessible = true
            }
            val origTrack = audioTrackField.get(webRtcAudioTrack) as? android.media.AudioTrack ?: return

            if (origTrack !is RecordingAudioTrackWrapper) {
                val wrapper = RecordingAudioTrackWrapper(origTrack, context)
                audioTrackField.set(webRtcAudioTrack, wrapper)
                Log.i("WebRtcEngine", "✅ Successfully hooked WebRtcAudioTrack playout for dual-party recording!")
            }
        } catch (e: Throwable) {
            Log.w("WebRtcEngine", "Could not hook WebRtcAudioTrack playout: ${e.message}")
        }
    }

    /**
     * Toggles in-call audio recording for the active call.
     * Returns true if recording started, false if stopped or failed.
     */
    fun toggleCallRecording(): Boolean {
        val active = _state.value.activeCall ?: return false
        val recorder = com.example.util.CallAudioRecorder.getInstance(context)
        return if (recorder.isRecording.value) {
            recorder.stopRecording(active.callId)
            false
        } else {
            hookWebRtcAudioTrackPlayout()
            recorder.startRecording(
                callId = active.callId,
                otherPartyName = active.calleeName.ifBlank { active.callerName },
                otherPartyNumber = active.calleeNumber.ifBlank { active.callerNumber }
            )
            true
        }
    }

    private fun triggerPushNotification(
        calleeNumber: String,
        callerName: String,
        callerNumber: String,
        callType: String,
        callId: String,
        type: String = "incoming_call",
        explicitFcmToken: String? = null,
        explicitWebToken: String? = null
    ) {
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

        fun cacheTokens(fcm: String, web: String) {
            if (fcm.isNotBlank() || web.isNotBlank()) {
                val pair = Pair(fcm, web)
                distinctVariations.forEach { pushTokenCache[it] = pair }
            }
        }

        fun sendPush(fcmToken: String, webToken: String) {
            val workerUrl = com.example.BuildConfig.CALL_WORKER_URL
            val workerSecret = com.example.BuildConfig.CALL_WORKER_SECRET
            
            scope.launch(Dispatchers.IO) {
                var conn: java.net.HttpURLConnection? = null
                try {
                    val url = java.net.URL(workerUrl)
                    conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("X-Worker-Secret", workerSecret)
                        connectTimeout = 5000
                        readTimeout = 5000
                        doOutput = true
                    }
                    
                    val myUser = com.example.data.repository.FirebaseManager.getInstance(context).currentUser.value
                    val myFcm = myUser?.fcmToken?.ifBlank { getCachedFcmToken(context) } ?: getCachedFcmToken(context)
                    val myWeb = myUser?.webToken ?: ""

                    val jsonObj = org.json.JSONObject().apply {
                        put("token", fcmToken)
                        put("webToken", webToken)
                        put("callerName", callerName)
                        put("callerNumber", callerNumber)
                        put("callType", callType)
                        put("callId", callId)
                        put("type", type)
                        put("callerFcmToken", myFcm)
                        put("callerWebToken", myWeb)
                        val myPic = myUser?.profilePictureUrl ?: ""
                        // NEVER send Base64 image strings in FCM payload (hard 4096-byte limit will reject push).
                        // Only send external HTTP/HTTPS image URLs.
                        if (myPic.isNotBlank() && (myPic.startsWith("http://") || myPic.startsWith("https://")) && myPic.length < 500) {
                            put("callerProfilePic", myPic)
                        }
                    }
                    val json = jsonObj.toString()
                    
                    conn.outputStream.use { it.write(json.toByteArray()) }
                    val responseCode = conn.responseCode
                    android.util.Log.d("WebRtcEngine", "Push Trigger Response: $responseCode (type=$type, callId=$callId)")
                } catch (e: Exception) {
                    android.util.Log.e("WebRtcEngine", "Failed to trigger push", e)
                } finally {
                    conn?.disconnect()
                }
            }
        }

        // 0. Fast path: If caller passed explicit tokens directly (0ms)
        if (!explicitFcmToken.isNullOrBlank() || !explicitWebToken.isNullOrBlank()) {
            val fcm = explicitFcmToken ?: ""
            val web = explicitWebToken ?: ""
            cacheTokens(fcm, web)
            sendPush(fcm, web)
            return
        }

        // 1. Check local in-memory pushTokenCache first (0ms)
        for (variant in distinctVariations) {
            val cached = pushTokenCache[variant]
            if (cached != null && (cached.first.isNotBlank() || cached.second.isNotBlank())) {
                Log.d("WebRtcEngine", "Push token found in pushTokenCache for $calleeNumber")
                sendPush(cached.first, cached.second)
                return
            }
        }

        // 2. Check in-memory registered users in FirebaseManager (0ms)
        val inMemoryUser = com.example.data.repository.FirebaseManager.getInstance(context).lookupUserByNumber(calleeNumber)
        if (inMemoryUser != null && (inMemoryUser.fcmToken.isNotBlank() || inMemoryUser.webToken.isNotBlank())) {
            Log.d("WebRtcEngine", "Push token found in FirebaseManager cache for $calleeNumber")
            cacheTokens(inMemoryUser.fcmToken, inMemoryUser.webToken)
            sendPush(inMemoryUser.fcmToken, inMemoryUser.webToken)
            return
        }

        // 3. Fallback to Firestore lookup if not found in memory
        fun tryDirectLookup(index: Int) {
            if (index >= distinctVariations.size) return
            val docId = distinctVariations[index]
            firestore.collection("users").document(docId).get().addOnSuccessListener { doc ->
                val fcmToken = doc.getString("fcmToken") ?: ""
                val webToken = doc.getString("webToken") ?: ""
                if (fcmToken.isNotEmpty() || webToken.isNotEmpty()) {
                    cacheTokens(fcmToken, webToken)
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
                cacheTokens(fcmToken, webToken)
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

        val pushTokenCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()

        fun getCachedFcmToken(context: Context): String {
            return try {
                context.getSharedPreferences("lks_prefs", Context.MODE_PRIVATE)
                    .getString("fcm_token", "") ?: ""
            } catch (_: Exception) { "" }
        }

        private val terminatedCallIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        fun markCallTerminated(callId: String) {
            if (callId.isNotBlank()) {
                terminatedCallIds.add(callId)
                if (terminatedCallIds.size > 200) {
                    val iterator = terminatedCallIds.iterator()
                    var count = 0
                    while (iterator.hasNext() && count < 100) {
                        iterator.next()
                        iterator.remove()
                        count++
                    }
                }
            }
        }

        fun isCallTerminated(callId: String): Boolean {
            return callId.isNotBlank() && terminatedCallIds.contains(callId)
        }

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
