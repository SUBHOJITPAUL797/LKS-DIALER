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
    private var reconnectJob: Job? = null
    private var resetIdleJob: Job? = null

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
    private var iceCandidateListener: ListenerRegistration? = null
    
    private val seenCallIds = mutableSetOf<String>()
    private val sentIceCandidateHashes = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
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

    // ICE servers: populated dynamically at call start via fetchIceServers().
    // Fallback list used if dynamic fetch fails (STUN-only — works for ~70% of networks).
    // TURN relay is fetched from Cloudflare Worker so credentials rotate and are not baked into APK.
    private var iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun3.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun4.l.google.com:19302").createIceServer(),
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
            .createIceServer()
    )

    /**
     * Fetch fresh TURN credentials from Cloudflare Worker before each call.
     * Credentials are short-lived (24h) and generated server-side via HMAC so
     * they are never hardcoded in the APK. Falls back to static list on failure.
     */
    private fun fetchIceServersAsync(onDone: () -> Unit) {
        Thread {
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
        }.start()
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
                Log.d("WebRtcEngine", "onIceConnectionChange: $newState")
                when (newState) {
                    PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> {
                        reconnectJob?.cancel()
                        reconnectJob = null
                        _state.value = _state.value.copy(connectionStatusText = "Connected • WebRTC")
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        _state.value = _state.value.copy(connectionStatusText = "Reconnecting...")
                        if (reconnectJob == null || reconnectJob?.isActive == false) {
                            reconnectJob = scope.launch {
                                delay(15000)
                                if (_state.value.callStatus == CallStatus.ANSWERED) {
                                    Log.w("WebRtcEngine", "ICE DISCONNECTED 15s timeout — ending call")
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
                        }
                        if (reconnectJob == null || reconnectJob?.isActive == false) {
                            reconnectJob = scope.launch {
                                delay(12000)
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
        resetIdleJob?.cancel()
        resetIdleJob = null
        reconnectJob?.cancel()
        reconnectJob = null

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
        
        // Fetch fresh TURN credentials from Worker before creating PeerConnection.
        // Falls back to static TURN list automatically if Worker is unreachable.
        fetchIceServersAsync {
            scope.launch(kotlinx.coroutines.Dispatchers.Main) {
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
                    }
                }

                // Filter for FRESH incoming calls (<45s old) not initiated by self
                val incomingCall = activeCalls
                    .filter { (it.status == CallStatus.CALLING || it.status == CallStatus.RINGING) && (now - it.createdAt <= 45_000L) }
                    .filter { it.callerNumber != myPhoneNumber && it.callerNumber.replace(Regex("[^0-9]"), "") != cleanDigits }
                    .maxByOrNull { it.createdAt }

                if (incomingCall != null && _state.value.activeCall == null && incomingCall.callId !in seenCallIds) {
                    seenCallIds.add(incomingCall.callId)

                    val firebaseMgr = com.example.data.repository.FirebaseManager.getInstance(context)
                    if (firebaseMgr.isDndEnabled() || firebaseMgr.isNumberBlocked(incomingCall.callerNumber)) {
                        Log.d("WebRtcEngine", "Incoming call auto-declined by DND or Blocklist: ${incomingCall.callId} from ${incomingCall.callerNumber}")
                        firestore.collection("calls").document(incomingCall.callId).update("status", CallStatus.DECLINED.name)
                        return@addSnapshotListener
                    }
                    
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
        resetIdleJob?.cancel()
        resetIdleJob = null
        reconnectJob?.cancel()
        reconnectJob = null

        hasProcessedOffer = false
        hasProcessedAnswer = false

        val firebaseMgr = com.example.data.repository.FirebaseManager.getInstance(context)
        if (firebaseMgr.isDndEnabled() || (callerNumber != null && firebaseMgr.isNumberBlocked(callerNumber))) {
            Log.d("WebRtcEngine", "attachToCall auto-declined by DND or Blocklist: $callId from $callerNumber")
            try {
                firestore.collection("calls").document(callId).update("status", CallStatus.DECLINED.name)
            } catch (_: Exception) {}
            return
        }

        fetchIceServersAsync {}
        
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
        audioRouteManager.configureAudio(callType)
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
                                _state.value.localVideoTrack?.setEnabled(false)
                            } else {
                                localAudioTrack?.setEnabled(!_state.value.isMuted)
                                _state.value.localVideoTrack?.setEnabled(_state.value.isCameraOn)
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
        } else {
            localAudioTrack?.setEnabled(!_state.value.isMuted)
            _state.value.localVideoTrack?.setEnabled(_state.value.isCameraOn)
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
        signalingManager.deleteCallAndCandidates(callId)
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
        reconnectJob?.cancel()
        reconnectJob = null
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
                fallbackCallType = prevCall.callType
            )
        }
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

        // Phase 1 — Security: delete Firestore SDP/ICE data 5s after call ends.
        // The call document contains offerSdp, answerSdp and ICE candidates with network topology.
        // Leaving these forever is a privacy leak and adds unbounded Firestore storage cost.
        val endedCallId = prevCall?.callId
        if (!endedCallId.isNullOrBlank()) {
            scope.launch {
                delay(5000)
                Log.d("WebRtcEngine", "Deleting Firestore call document + ICE candidates for $endedCallId")
                deleteCallAndCandidates(endedCallId)
            }
        }
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
