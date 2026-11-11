package com.example.webrtc

import android.content.Context
import android.util.Log
import com.example.data.model.*
import com.example.data.repository.FirebaseManager
import com.example.ui.screens.call.VideoFilterManager
import com.example.util.ContactsHelper
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class GroupCallUiState(
    val room: GroupCallRoomDto? = null,
    val incomingInviteRoom: GroupCallRoomDto? = null,
    val participants: List<GroupParticipantDto> = emptyList(),
    val localVideoTrack: VideoTrack? = null,
    val remoteVideoTracks: Map<String, VideoTrack> = emptyMap(),
    val isInWaitingLobby: Boolean = false,
    val isMuted: Boolean = false,
    val isVideoEnabled: Boolean = true,
    val isSpeakerOn: Boolean = true,
    val isFrontCamera: Boolean = true,
    val layoutMode: GroupLayoutMode = GroupLayoutMode.GRID,
    val localPinnedPhone: String? = null,
    val callDurationSeconds: Int = 0,
    val isMinimized: Boolean = false,
    val unmuteRequestedByHost: Boolean = false,
    val filterConfig: VideoFilterConfig = VideoFilterConfig(),
    val statusBannerText: String? = null
) {
    val isActive: Boolean
        get() = room != null && room.status == "ACTIVE"

    val joinedParticipants: List<GroupParticipantDto>
        get() = participants.filter { it.joinState == "JOINED" }

    val waitingParticipants: List<GroupParticipantDto>
        get() = participants.filter { it.joinState == "WAITING_LOBBY" }

    val invitedParticipants: List<GroupParticipantDto>
        get() = participants.filter { it.joinState == "INVITED" }
}

class GroupCallEngine(
    private val context: Context,
    private val firebaseManager: FirebaseManager
) {
    private val firestore = FirebaseFirestore.getInstance()
    private fun groupCallsCollection() =
        firestore.collection("calls").document("group_conference_hub").collection("rooms")
    private val engineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mediaTeardownScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    val eglBase: EglBase = EglBase.create()
    private val audioRouteManager = AudioRouteManager(
        context = context,
        onRouteChanged = { _, _, isSpeakerOn ->
            _state.value = _state.value.copy(isSpeakerOn = isSpeakerOn)
        },
        onCellularInterruption = { _ -> }
    )
    private val filterManager = VideoFilterManager.getInstance(context)

    private val _state = MutableStateFlow(
        GroupCallUiState(filterConfig = filterManager.filterConfig.value)
    )
    val state: StateFlow<GroupCallUiState> = _state.asStateFlow()

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private val peerConnections = ConcurrentHashMap<String, PeerConnection>()
    private val peerLinkListeners = ConcurrentHashMap<String, ListenerRegistration>()
    private val processedCandidates = ConcurrentHashMap<String, MutableSet<String>>()
    private val remoteSdpSetPeers = ConcurrentHashMap.newKeySet<String>()
    private val pendingCandidatesByPeer = ConcurrentHashMap<String, MutableList<IceCandidate>>()

    private var roomListener: ListenerRegistration? = null
    private var participantsListener: ListenerRegistration? = null
    private var incomingInvitesListener: ListenerRegistration? = null
    private var timerJob: Job? = null
    private var lastProcessedMuteAllTs: Long = 0L
    private var lastProcessedUnmuteAllTs: Long = 0L
    private val declinedOrLeftRoomIds = ConcurrentHashMap.newKeySet<String>()

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
            .setUsername("openrelayproject")
            .setPassword("openrelayproject")
            .createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=tcp")
            .setUsername("openrelayproject")
            .setPassword("openrelayproject")
            .createIceServer()
    )

    init {
        engineScope.launch {
            filterManager.filterConfig.collect { cfg ->
                _state.value = _state.value.copy(filterConfig = cfg)
                val activeRoomId = _state.value.room?.roomId
                val myPhone = firebaseManager.currentUser.value?.phoneNumber?.let {
                    ContactsHelper.normalizePhoneNumber(it)
                }
                if (!activeRoomId.isNullOrBlank() && !myPhone.isNullOrBlank()) {
                    try {
                        groupCallsCollection()
                            .document(activeRoomId)
                            .collection("participants")
                            .document(myPhone)
                            .update("filterConfigStr", cfg.toCompactString())
                    } catch (_: Throwable) {}
                }
            }
        }
        engineScope.launch {
            firebaseManager.currentUser.collect { user ->
                if (user != null && user.phoneNumber.isNotBlank()) {
                    attachIncomingGroupInviteListener(ContactsHelper.normalizePhoneNumber(user.phoneNumber))
                }
            }
        }
    }

    @Synchronized
    private fun ensureFactory(): PeerConnectionFactory? {
        peerConnectionFactory?.let { return it }
        try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )
            val isAecSupported = try {
                JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported()
            } catch (_: Exception) {
                false
            }
            val isNsSupported = try {
                JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported()
            } catch (_: Exception) {
                false
            }
            val adm = JavaAudioDeviceModule.builder(context)
                .setUseHardwareAcousticEchoCanceler(isAecSupported)
                .setUseHardwareNoiseSuppressor(isNsSupported)
                .createAudioDeviceModule()
            val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
            val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
            peerConnectionFactory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(adm)
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()
            adm.release()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to init GroupCallEngine PeerConnectionFactory", e)
        }
        return peerConnectionFactory
    }

    private fun attachIncomingGroupInviteListener(myPhone: String) {
        incomingInvitesListener?.remove()
        incomingInvitesListener = groupCallsCollection()
            .whereEqualTo("status", "ACTIVE")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                val currentActiveRoomId = _state.value.room?.roomId
                if (currentActiveRoomId != null) return@addSnapshotListener

                val now = System.currentTimeMillis()
                val normMyPhone = ContactsHelper.normalizePhoneNumber(myPhone)
                val matchingRoom = snapshot.documents.mapNotNull { doc ->
                    doc.toObject(GroupCallRoomDto::class.java)
                }.firstOrNull { room ->
                    val normHost = ContactsHelper.normalizePhoneNumber(room.hostPhone)
                    val normInvited = room.invitedPhones.map { ContactsHelper.normalizePhoneNumber(it) }
                    room.roomId.isNotBlank() &&
                        !declinedOrLeftRoomIds.contains(room.roomId) &&
                        normHost != normMyPhone &&
                        normInvited.contains(normMyPhone) &&
                        (now - room.createdAt) < 15 * 60 * 1000L
                }

                if (matchingRoom != null && _state.value.incomingInviteRoom?.roomId != matchingRoom.roomId) {
                    _state.value = _state.value.copy(incomingInviteRoom = matchingRoom)
                } else if (matchingRoom == null && _state.value.incomingInviteRoom != null) {
                    _state.value = _state.value.copy(incomingInviteRoom = null)
                }
            }
    }

    fun isHostOrCoHost(myPhone: String? = firebaseManager.currentUser.value?.phoneNumber): Boolean {
        val room = _state.value.room ?: return false
        if (myPhone.isNullOrBlank()) return false
        val normMy = ContactsHelper.normalizePhoneNumber(myPhone)
        return ContactsHelper.normalizePhoneNumber(room.hostPhone) == normMy ||
            room.coHostPhones.any { ContactsHelper.normalizePhoneNumber(it) == normMy }
    }

    fun setMinimized(minimized: Boolean) {
        _state.value = _state.value.copy(isMinimized = minimized)
    }

    fun setLayoutMode(mode: GroupLayoutMode) {
        _state.value = _state.value.copy(layoutMode = mode)
    }

    fun setLocalPinnedPhone(phone: String?) {
        val next = if (_state.value.localPinnedPhone == phone) null else phone
        _state.value = _state.value.copy(
            localPinnedPhone = next,
            layoutMode = if (next != null) GroupLayoutMode.SPEAKER else _state.value.layoutMode
        )
    }

    /**
     * Creates a new Group Conference Room where the caller is the HOST and invites up to 5 participants.
     */
    fun createGroupCall(
        title: String,
        invitedContacts: List<ContactDto>,
        callType: CallType,
        waitingRoomEnabled: Boolean = false
    ) {
        val me = firebaseManager.currentUser.value ?: return
        val myNormPhone = ContactsHelper.normalizePhoneNumber(me.phoneNumber)
        val roomId = "grp_${UUID.randomUUID().toString().take(10)}"
        val now = System.currentTimeMillis()
        val distinctGuests = invitedContacts
            .map { it.copy(phoneNumber = ContactsHelper.normalizePhoneNumber(it.phoneNumber)) }
            .filter { it.phoneNumber.isNotBlank() && it.phoneNumber != myNormPhone }
            .distinctBy { it.phoneNumber }
            .take(5)

        val invitedPhones = distinctGuests.map { it.phoneNumber }
        val roomTitle = title.trim().ifBlank { "${me.displayName.ifBlank { myNormPhone }}'s Meeting" }

        val roomDto = GroupCallRoomDto(
            roomId = roomId,
            title = roomTitle,
            hostPhone = myNormPhone,
            hostName = me.displayName.ifBlank { myNormPhone },
            coHostPhones = emptyList(),
            callType = callType,
            status = "ACTIVE",
            isLocked = false,
            waitingRoomEnabled = waitingRoomEnabled,
            spotlightPhone = null,
            invitedPhones = invitedPhones,
            activeParticipantCount = 1,
            createdAt = now
        )

        val hostParticipant = GroupParticipantDto(
            phoneNumber = myNormPhone,
            displayName = me.displayName.ifBlank { myNormPhone },
            profilePicUrl = me.profilePictureUrl,
            role = "HOST",
            joinState = "JOINED",
            isMuted = false,
            isVideoEnabled = callType == CallType.VIDEO,
            filterConfigStr = filterManager.filterConfig.value.toCompactString(),
            joinedAt = now,
            updatedAt = now
        )

        prepareLocalMedia(enableVideo = callType == CallType.VIDEO)

        _state.value = _state.value.copy(
            room = roomDto,
            incomingInviteRoom = null,
            participants = listOf(hostParticipant),
            isInWaitingLobby = false,
            isMuted = false,
            isVideoEnabled = callType == CallType.VIDEO,
            isSpeakerOn = true,
            isMinimized = false,
            callDurationSeconds = 0,
            statusBannerText = "Hosting Group ${callType.name.lowercase().replaceFirstChar { it.uppercase() }} Call"
        )

        startDurationTimer()

        val roomRef = groupCallsCollection().document(roomId)
        roomRef.set(roomDto).addOnSuccessListener {
            roomRef.collection("participants").document(myNormPhone).set(hostParticipant)
            distinctGuests.forEach { guest ->
                val guestDto = GroupParticipantDto(
                    phoneNumber = guest.phoneNumber,
                    displayName = guest.name.ifBlank { guest.phoneNumber },
                    profilePicUrl = guest.profilePictureUrl,
                    role = "PARTICIPANT",
                    joinState = "INVITED",
                    isMuted = false,
                    isVideoEnabled = callType == CallType.VIDEO,
                    joinedAt = 0L,
                    updatedAt = now
                )
                roomRef.collection("participants").document(guest.phoneNumber).set(guestDto)
            }
            attachRoomListeners(roomId, myNormPhone)
        }
    }

    /**
     * Invites additional contacts mid-call to an active Group Conference (up to 6 total).
     */
    fun inviteAdditionalParticipants(newContacts: List<ContactDto>) {
        val room = _state.value.room ?: return
        val now = System.currentTimeMillis()
        val currentPhones = _state.value.participants.map { ContactsHelper.normalizePhoneNumber(it.phoneNumber) }.toSet()
        val toAdd = newContacts
            .map { it.copy(phoneNumber = ContactsHelper.normalizePhoneNumber(it.phoneNumber)) }
            .filter { it.phoneNumber.isNotBlank() && !currentPhones.contains(it.phoneNumber) }
            .distinctBy { it.phoneNumber }
            .take((6 - _state.value.joinedParticipants.size).coerceAtLeast(1))

        if (toAdd.isEmpty()) return

        val roomRef = groupCallsCollection().document(room.roomId)
        val updatedInvited = (room.invitedPhones.map { ContactsHelper.normalizePhoneNumber(it) } + toAdd.map { it.phoneNumber }).distinct()
        roomRef.update("invitedPhones", updatedInvited)

        toAdd.forEach { guest ->
            val guestDto = GroupParticipantDto(
                phoneNumber = guest.phoneNumber,
                displayName = guest.name.ifBlank { guest.phoneNumber },
                profilePicUrl = guest.profilePictureUrl,
                role = "PARTICIPANT",
                joinState = "INVITED",
                isMuted = false,
                isVideoEnabled = room.callType == CallType.VIDEO,
                joinedAt = 0L,
                updatedAt = now
            )
            roomRef.collection("participants").document(guest.phoneNumber).set(guestDto)
        }
    }

    /**
     * Answers an incoming Group Call invitation.
     * If the Host enabled Waiting Room, places the user in WAITING_LOBBY until admitted.
     */
    fun answerIncomingGroupInvite() {
        val invite = _state.value.incomingInviteRoom ?: return
        val me = firebaseManager.currentUser.value ?: return
        val myNormPhone = ContactsHelper.normalizePhoneNumber(me.phoneNumber)
        val normHostPhone = ContactsHelper.normalizePhoneNumber(invite.hostPhone)
        val now = System.currentTimeMillis()

        val initialJoinState = if (invite.waitingRoomEnabled && normHostPhone != myNormPhone) {
            "WAITING_LOBBY"
        } else {
            "JOINED"
        }

        prepareLocalMedia(enableVideo = invite.callType == CallType.VIDEO)

        val myParticipant = GroupParticipantDto(
            phoneNumber = myNormPhone,
            displayName = me.displayName.ifBlank { myNormPhone },
            profilePicUrl = me.profilePictureUrl,
            role = if (normHostPhone == myNormPhone) "HOST" else "PARTICIPANT",
            joinState = initialJoinState,
            isMuted = false,
            isVideoEnabled = invite.callType == CallType.VIDEO,
            filterConfigStr = filterManager.filterConfig.value.toCompactString(),
            joinedAt = now,
            updatedAt = now
        )

        _state.value = _state.value.copy(
            room = invite,
            incomingInviteRoom = null,
            isInWaitingLobby = initialJoinState == "WAITING_LOBBY",
            isMuted = false,
            isVideoEnabled = invite.callType == CallType.VIDEO,
            isSpeakerOn = true,
            isMinimized = false,
            callDurationSeconds = 0
        )

        if (initialJoinState == "JOINED") {
            startDurationTimer()
        }

        val roomRef = groupCallsCollection().document(invite.roomId)
        roomRef.collection("participants")
            .document(myNormPhone)
            .set(myParticipant, SetOptions.merge())
            .addOnSuccessListener {
                attachRoomListeners(invite.roomId, myNormPhone)
            }
    }

    fun declineIncomingGroupInvite() {
        val invite = _state.value.incomingInviteRoom ?: return
        val me = firebaseManager.currentUser.value
        declinedOrLeftRoomIds.add(invite.roomId)
        _state.value = _state.value.copy(incomingInviteRoom = null)
        if (me != null) {
            val myNormPhone = ContactsHelper.normalizePhoneNumber(me.phoneNumber)
            groupCallsCollection()
                .document(invite.roomId)
                .collection("participants")
                .document(myNormPhone)
                .update("joinState", "DECLINED")
            firebaseManager.logCall(
                direction = CallDirection.INCOMING,
                otherPartyNumber = invite.hostPhone,
                otherPartyName = "${invite.title} (Group)",
                callType = invite.callType,
                status = CallStatus.DECLINED,
                durationSeconds = 0,
                callId = invite.roomId
            )
        }
    }

    /**
     * Directly joins a group room by ID (e.g. when a 1-to-1 call is promoted to a Group Call).
     */
    fun joinGroupRoomById(roomId: String) {
        groupCallsCollection().document(roomId).get()
            .addOnSuccessListener { doc ->
                val room = doc.toObject(GroupCallRoomDto::class.java) ?: return@addOnSuccessListener
                if (room.status != "ACTIVE") return@addOnSuccessListener
                _state.value = _state.value.copy(incomingInviteRoom = room)
                answerIncomingGroupInvite()
            }
    }

    private fun prepareLocalMedia(enableVideo: Boolean) {
        val factory = ensureFactory() ?: return
        try {
            audioRouteManager.configureAudio(com.example.data.model.CallType.VIDEO)

            if (localAudioTrack == null) {
                val audioConstraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                }
                localAudioSource = factory.createAudioSource(audioConstraints)
                localAudioTrack = factory.createAudioTrack("GRP_AUDIO_${UUID.randomUUID().toString().take(5)}", localAudioSource)
                localAudioTrack?.setEnabled(true)
            }

            if (enableVideo && localVideoTrack == null) {
                startLocalCamera(factory)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error preparing group local media", e)
        }
    }

    private fun startLocalCamera(factory: PeerConnectionFactory) {
        try {
            val enumerator = Camera2Enumerator(context)
            val deviceName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                ?: enumerator.deviceNames.firstOrNull()
                ?: return

            val capturer = enumerator.createCapturer(deviceName, null) ?: return
            val helper = SurfaceTextureHelper.create("GrpCamThread_${System.currentTimeMillis()}", eglBase.eglBaseContext)
            val videoSource = factory.createVideoSource(capturer.isScreencast)
            capturer.initialize(helper, context, videoSource.capturerObserver)
            capturer.startCapture(960, 540, 24)

            val track = factory.createVideoTrack("GRP_VIDEO_${UUID.randomUUID().toString().take(5)}", videoSource)
            track.setEnabled(true)

            videoCapturer = capturer
            surfaceTextureHelper = helper
            localVideoSource = videoSource
            localVideoTrack = track
            _state.value = _state.value.copy(localVideoTrack = track, isVideoEnabled = true)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start camera for group call", e)
        }
    }

    private fun attachRoomListeners(roomId: String, myPhone: String) {
        roomListener?.remove()
        participantsListener?.remove()

        val roomRef = groupCallsCollection().document(roomId)

        roomListener = roomRef.addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
            val room = snapshot.toObject(GroupCallRoomDto::class.java) ?: return@addSnapshotListener

            if (room.status == "ENDED") {
                endLocalSessionImmediate("Host ended the meeting for all")
                return@addSnapshotListener
            }

            // Check if Host triggered "Mute All"
            if (room.muteAllTimestamp > lastProcessedMuteAllTs && room.hostPhone != myPhone) {
                lastProcessedMuteAllTs = room.muteAllTimestamp
                if (!_state.value.isMuted) {
                    setMicMuted(true, syncToFirestore = true)
                    _state.value = _state.value.copy(statusBannerText = "Host muted all participants")
                }
            } else if (room.muteAllTimestamp > lastProcessedMuteAllTs) {
                lastProcessedMuteAllTs = room.muteAllTimestamp
            }

            // Check if Host triggered "Request Unmute All"
            if (room.unmuteAllRequestTimestamp > lastProcessedUnmuteAllTs && room.hostPhone != myPhone) {
                lastProcessedUnmuteAllTs = room.unmuteAllRequestTimestamp
                if (_state.value.isMuted) {
                    _state.value = _state.value.copy(unmuteRequestedByHost = true)
                }
            } else if (room.unmuteAllRequestTimestamp > lastProcessedUnmuteAllTs) {
                lastProcessedUnmuteAllTs = room.unmuteAllRequestTimestamp
            }

            _state.value = _state.value.copy(room = room)
        }

        participantsListener = roomRef.collection("participants")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                val allParticipants = snapshot.documents.mapNotNull {
                    it.toObject(GroupParticipantDto::class.java)
                }.sortedWith(
                    compareBy<GroupParticipantDto> {
                        when (it.role) {
                            "HOST" -> 0
                            "CO_HOST" -> 1
                            else -> 2
                        }
                    }.thenBy { it.joinedAt }
                )

                val meDto = allParticipants.find { it.phoneNumber == myPhone }
                if (meDto != null) {
                    if (meDto.joinState == "REMOVED") {
                        endLocalSessionImmediate("Removed from meeting by Host")
                        return@addSnapshotListener
                    }
                    if (meDto.joinState == "DECLINED") {
                        endLocalSessionImmediate("Entry declined by Host")
                        return@addSnapshotListener
                    }

                    val wasInLobby = _state.value.isInWaitingLobby
                    val nowJoined = meDto.joinState == "JOINED"
                    if (wasInLobby && nowJoined) {
                        _state.value = _state.value.copy(
                            isInWaitingLobby = false,
                            statusBannerText = "Admitted by Host"
                        )
                        startDurationTimer()
                    }

                    // Host remote mute enforcement
                    if (meDto.isMuted && !_state.value.isMuted) {
                        localAudioTrack?.setEnabled(false)
                        _state.value = _state.value.copy(
                            isMuted = true,
                            statusBannerText = "Microphone muted by Host"
                        )
                    }

                    // Host ask-to-unmute prompt
                    if (meDto.unmuteRequestedByHost && _state.value.isMuted) {
                        _state.value = _state.value.copy(unmuteRequestedByHost = true)
                    }

                    // Host stop-video enforcement
                    if (meDto.videoStopRequestedByHost && _state.value.isVideoEnabled) {
                        localVideoTrack?.setEnabled(false)
                        _state.value = _state.value.copy(
                            isVideoEnabled = false,
                            statusBannerText = "Camera turned off by Host"
                        )
                        roomRef.collection("participants").document(myPhone)
                            .update(mapOf("isVideoEnabled" to false, "videoStopRequestedByHost" to false))
                    }
                }

                _state.value = _state.value.copy(participants = allParticipants)

                // If we are JOINED, synchronize peer connections with all other JOINED participants
                if (meDto?.joinState == "JOINED") {
                    val joinedOthers = allParticipants
                        .filter { it.joinState == "JOINED" && it.phoneNumber != myPhone }
                        .map { it.phoneNumber }
                        .toSet()

                    // Remove disconnected peers
                    val stalePeers = peerConnections.keys.filter { !joinedOthers.contains(it) }
                    stalePeers.forEach { removePeerConnection(it) }

                    // Establish peer connections for newly joined peers
                    joinedOthers.forEach { remotePhone ->
                        if (!peerConnections.containsKey(remotePhone)) {
                            establishPeerLink(roomId, myPhone, remotePhone)
                        }
                    }
                }
            }
    }

    private fun establishPeerLink(roomId: String, myPhone: String, remotePhone: String) {
        val factory = peerConnectionFactory ?: return
        val sorted = listOf(myPhone, remotePhone).sorted()
        val linkId = "${sorted[0]}_${sorted[1]}"
        val isInitiator = myPhone == sorted[0]

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val linkRef = groupCallsCollection()
            .document(roomId)
            .collection("links")
            .document(linkId)

        val pc = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}

            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate == null) return
                val compact = "${candidate.sdpMid}|${candidate.sdpMLineIndex}|${candidate.sdp}"
                val field = if (isInitiator) "initiatorCandidates" else "responderCandidates"
                linkRef.update(field, com.google.firebase.firestore.FieldValue.arrayUnion(compact))
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dc: DataChannel?) {}
            override fun onRenegotiationNeeded() {}

            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track()
                if (track is VideoTrack) {
                    track.setEnabled(true)
                    engineScope.launch {
                        val updatedMap = _state.value.remoteVideoTracks.toMutableMap()
                        updatedMap[remotePhone] = track
                        _state.value = _state.value.copy(remoteVideoTracks = updatedMap)
                    }
                }
            }
        }) ?: return

        localAudioTrack?.let { pc.addTrack(it, listOf("GRP_STREAM_$myPhone")) }
        localVideoTrack?.let { pc.addTrack(it, listOf("GRP_STREAM_$myPhone")) }

        peerConnections[remotePhone] = pc
        processedCandidates[remotePhone] = ConcurrentHashMap.newKeySet()
        pendingCandidatesByPeer[remotePhone] = mutableListOf()

        if (isInitiator) {
            val linkDto = GroupPeerLinkDto(
                linkId = linkId,
                roomId = roomId,
                initiatorPhone = myPhone,
                responderPhone = remotePhone,
                updatedAt = System.currentTimeMillis()
            )
            linkRef.set(linkDto).addOnSuccessListener {
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                }
                pc.createOffer(object : SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription?) {
                        if (desc == null) return
                        pc.setLocalDescription(SimpleSdpObserver(), desc)
                        linkRef.update("offerSdp", desc.description)
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(err: String?) {}
                    override fun onSetFailure(err: String?) {}
                }, constraints)
            }
        }

        val listener = linkRef.addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
            val link = snapshot.toObject(GroupPeerLinkDto::class.java) ?: return@addSnapshotListener

            if (!isInitiator && !link.offerSdp.isNullOrBlank() && !remoteSdpSetPeers.contains(remotePhone)) {
                remoteSdpSetPeers.add(remotePhone)
                val offer = SessionDescription(SessionDescription.Type.OFFER, link.offerSdp)
                pc.setRemoteDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        flushPendingCandidates(remotePhone, pc)
                        val constraints = MediaConstraints().apply {
                            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                        }
                        pc.createAnswer(object : SdpObserver {
                            override fun onCreateSuccess(answerDesc: SessionDescription?) {
                                if (answerDesc == null) return
                                pc.setLocalDescription(SimpleSdpObserver(), answerDesc)
                                linkRef.update("answerSdp", answerDesc.description)
                            }
                            override fun onSetSuccess() {}
                            override fun onCreateFailure(err: String?) {}
                            override fun onSetFailure(err: String?) {}
                        }, constraints)
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) {}
                }, offer)
            } else if (isInitiator && !link.answerSdp.isNullOrBlank() && !remoteSdpSetPeers.contains(remotePhone)) {
                remoteSdpSetPeers.add(remotePhone)
                val answer = SessionDescription(SessionDescription.Type.ANSWER, link.answerSdp)
                pc.setRemoteDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        flushPendingCandidates(remotePhone, pc)
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) {}
                }, answer)
            }

            val remoteCands = if (isInitiator) link.responderCandidates else link.initiatorCandidates
            val seenSet = processedCandidates[remotePhone] ?: return@addSnapshotListener
            remoteCands.forEach { raw ->
                if (seenSet.add(raw)) {
                    val parts = raw.split("|", limit = 3)
                    if (parts.size == 3) {
                        val candidate = IceCandidate(
                            parts[0],
                            parts[1].toIntOrNull() ?: 0,
                            parts[2]
                        )
                        if (remoteSdpSetPeers.contains(remotePhone)) {
                            try {
                                pc.addIceCandidate(candidate)
                            } catch (_: Throwable) {}
                        } else {
                            pendingCandidatesByPeer[remotePhone]?.add(candidate)
                        }
                    }
                }
            }
        }
        peerLinkListeners[remotePhone] = listener
    }

    private fun flushPendingCandidates(remotePhone: String, pc: PeerConnection) {
        val list = pendingCandidatesByPeer[remotePhone] ?: return
        val copy = ArrayList(list)
        list.clear()
        copy.forEach { cand ->
            try {
                pc.addIceCandidate(cand)
            } catch (_: Throwable) {}
        }
    }

    private fun removePeerConnection(remotePhone: String) {
        peerLinkListeners.remove(remotePhone)?.remove()
        processedCandidates.remove(remotePhone)
        pendingCandidatesByPeer.remove(remotePhone)
        remoteSdpSetPeers.remove(remotePhone)

        // Detach remote video track from UI state first (0ms) so SurfaceViewRenderer removes sink safely
        val updatedTracks = _state.value.remoteVideoTracks.toMutableMap()
        updatedTracks.remove(remotePhone)
        _state.value = _state.value.copy(remoteVideoTracks = updatedTracks)

        val pc = peerConnections.remove(remotePhone)
        if (pc != null) {
            mediaTeardownScope.launch {
                delay(90)
                try {
                    pc.close()
                } catch (_: Throwable) {}
                try {
                    pc.dispose()
                } catch (_: Throwable) {}
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // LOCAL PARTICIPANT CONTROLS
    // ══════════════════════════════════════════════════════════════════════════

    fun toggleMic() {
        setMicMuted(!_state.value.isMuted, syncToFirestore = true)
    }

    private fun setMicMuted(muted: Boolean, syncToFirestore: Boolean) {
        localAudioTrack?.setEnabled(!muted)
        _state.value = _state.value.copy(isMuted = muted)
        if (syncToFirestore) {
            val roomId = _state.value.room?.roomId ?: return
            val myPhone = firebaseManager.currentUser.value?.phoneNumber ?: return
            groupCallsCollection().document(roomId)
                .collection("participants").document(myPhone)
                .update(mapOf("isMuted" to muted, "unmuteRequestedByHost" to false))
        }
    }

    fun respondToHostUnmuteRequest(acceptUnmute: Boolean) {
        val roomId = _state.value.room?.roomId
        val myPhone = firebaseManager.currentUser.value?.phoneNumber
        _state.value = _state.value.copy(unmuteRequestedByHost = false)
        if (acceptUnmute) {
            setMicMuted(false, syncToFirestore = true)
        } else if (!roomId.isNullOrBlank() && !myPhone.isNullOrBlank()) {
            groupCallsCollection().document(roomId)
                .collection("participants").document(myPhone)
                .update("unmuteRequestedByHost", false)
        }
    }

    fun toggleVideo() {
        val nextEnabled = !_state.value.isVideoEnabled
        val myPhone = firebaseManager.currentUser.value?.phoneNumber?.let {
            ContactsHelper.normalizePhoneNumber(it)
        } ?: return

        if (nextEnabled && localVideoTrack == null) {
            ensureFactory()?.let { factory ->
                startLocalCamera(factory)
                localVideoTrack?.let { newTrack ->
                    peerConnections.forEach { (_, pc) ->
                        try {
                            pc.addTrack(newTrack, listOf("GRP_STREAM_$myPhone"))
                        } catch (_: Throwable) {}
                    }
                }
            }
        }
        localVideoTrack?.setEnabled(nextEnabled)
        _state.value = _state.value.copy(isVideoEnabled = nextEnabled)

        val roomId = _state.value.room?.roomId ?: return
        groupCallsCollection().document(roomId)
            .collection("participants").document(myPhone)
            .update("isVideoEnabled", nextEnabled)
    }

    fun switchCamera() {
        try {
            videoCapturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                    _state.value = _state.value.copy(isFrontCamera = isFrontCamera)
                }
                override fun onCameraSwitchError(errorDescription: String?) {}
            })
        } catch (_: Throwable) {}
    }

    fun toggleSpeaker() {
        audioRouteManager.toggleSpeaker()
    }

    fun toggleRaiseHand() {
        val roomId = _state.value.room?.roomId ?: return
        val myPhone = firebaseManager.currentUser.value?.phoneNumber ?: return
        val meDto = _state.value.participants.find { it.phoneNumber == myPhone } ?: return
        val nextHand = !meDto.isHandRaised
        groupCallsCollection().document(roomId)
            .collection("participants").document(myPhone)
            .update("isHandRaised", nextHand)
    }

    fun sendReaction(emoji: String) {
        val roomId = _state.value.room?.roomId ?: return
        val myPhone = firebaseManager.currentUser.value?.phoneNumber ?: return
        groupCallsCollection().document(roomId)
            .collection("participants").document(myPhone)
            .update(
                mapOf(
                    "activeReaction" to emoji,
                    "reactionTimestamp" to System.currentTimeMillis()
                )
            )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ZOOM-GRADE HOST & WAITING ROOM MODERATION CONTROLS
    // ══════════════════════════════════════════════════════════════════════════

    fun admitWaitingParticipant(phoneNumber: String) {
        if (!isHostOrCoHost()) return
        val roomId = _state.value.room?.roomId ?: return
        groupCallsCollection().document(roomId)
            .collection("participants").document(phoneNumber)
            .update(
                mapOf(
                    "joinState" to "JOINED",
                    "joinedAt" to System.currentTimeMillis()
                )
            )
    }

    fun admitAllWaitingParticipants() {
        if (!isHostOrCoHost()) return
        val roomId = _state.value.room?.roomId ?: return
        val now = System.currentTimeMillis()
        _state.value.waitingParticipants.forEach { waiting ->
            groupCallsCollection().document(roomId)
                .collection("participants").document(waiting.phoneNumber)
                .update(mapOf("joinState" to "JOINED", "joinedAt" to now))
        }
    }

    fun declineWaitingParticipant(phoneNumber: String) {
        if (!isHostOrCoHost()) return
        val roomId = _state.value.room?.roomId ?: return
        groupCallsCollection().document(roomId)
            .collection("participants").document(phoneNumber)
            .update("joinState", "DECLINED")
    }

    fun hostMuteParticipant(phoneNumber: String) {
        if (!isHostOrCoHost()) return
        val roomId = _state.value.room?.roomId ?: return
        groupCallsCollection().document(roomId)
            .collection("participants").document(phoneNumber)
            .update(mapOf("isMuted" to true, "unmuteRequestedByHost" to false))
    }

    fun hostRequestUnmute(phoneNumber: String) {
        if (!isHostOrCoHost()) return
        val roomId = _state.value.room?.roomId ?: return
        groupCallsCollection().document(roomId)
            .collection("participants").document(phoneNumber)
            .update("unmuteRequestedByHost", true)
    }

    fun hostMuteAllParticipants() {
        if (!isHostOrCoHost()) return
        val room = _state.value.room ?: return
        val myPhone = firebaseManager.currentUser.value?.phoneNumber ?: ""
        val now = System.currentTimeMillis()
        groupCallsCollection().document(room.roomId)
            .update("muteAllTimestamp", now)

        _state.value.joinedParticipants
            .filter { it.phoneNumber != myPhone }
            .forEach { participant ->
                groupCallsCollection().document(room.roomId)
                    .collection("participants").document(participant.phoneNumber)
                    .update("isMuted", true)
            }
    }

    fun hostRequestUnmuteAll() {
        if (!isHostOrCoHost()) return
        val room = _state.value.room ?: return
        groupCallsCollection().document(room.roomId)
            .update("unmuteAllRequestTimestamp", System.currentTimeMillis())
    }

    fun hostStopParticipantVideo(phoneNumber: String) {
        if (!isHostOrCoHost()) return
        val roomId = _state.value.room?.roomId ?: return
        groupCallsCollection().document(roomId)
            .collection("participants").document(phoneNumber)
            .update("videoStopRequestedByHost", true)
    }

    fun hostSpotlightParticipant(phoneNumber: String?) {
        if (!isHostOrCoHost()) return
        val room = _state.value.room ?: return
        val nextSpotlight = if (room.spotlightPhone == phoneNumber) null else phoneNumber
        groupCallsCollection().document(room.roomId)
            .update("spotlightPhone", nextSpotlight)
    }

    fun hostToggleRoomLock() {
        if (!isHostOrCoHost()) return
        val room = _state.value.room ?: return
        groupCallsCollection().document(room.roomId)
            .update("isLocked", !room.isLocked)
    }

    fun hostToggleWaitingRoom() {
        if (!isHostOrCoHost()) return
        val room = _state.value.room ?: return
        groupCallsCollection().document(room.roomId)
            .update("waitingRoomEnabled", !room.waitingRoomEnabled)
    }

    fun hostTransferHostRole(newHostPhone: String, newHostName: String) {
        val room = _state.value.room ?: return
        val myPhone = firebaseManager.currentUser.value?.phoneNumber ?: return
        if (room.hostPhone != myPhone) return

        val roomRef = groupCallsCollection().document(room.roomId)
        roomRef.update(mapOf("hostPhone" to newHostPhone, "hostName" to newHostName))
        roomRef.collection("participants").document(myPhone).update("role", "PARTICIPANT")
        roomRef.collection("participants").document(newHostPhone).update("role", "HOST")
    }

    fun hostRemoveParticipant(phoneNumber: String) {
        if (!isHostOrCoHost()) return
        val roomId = _state.value.room?.roomId ?: return
        groupCallsCollection().document(roomId)
            .collection("participants").document(phoneNumber)
            .update("joinState", "REMOVED")
    }

    /**
     * Leaves the group call for the local user only.
     * If the local user is the Host and other joined participants remain,
     * transfers Host role to the next joined participant so the conference continues!
     */
    fun leaveGroupCall() {
        val room = _state.value.room ?: return
        val myPhone = firebaseManager.currentUser.value?.phoneNumber?.let {
            ContactsHelper.normalizePhoneNumber(it)
        } ?: ""
        val duration = _state.value.callDurationSeconds
        declinedOrLeftRoomIds.add(room.roomId)

        val remainingJoined = _state.value.joinedParticipants.filter {
            ContactsHelper.normalizePhoneNumber(it.phoneNumber) != myPhone
        }
        val roomRef = groupCallsCollection().document(room.roomId)

        if (myPhone.isNotBlank()) {
            roomRef.collection("participants").document(myPhone)
                .update("joinState", "LEFT")
        }

        if (remainingJoined.isEmpty()) {
            roomRef.update(mapOf("status" to "ENDED", "endedAt" to System.currentTimeMillis()))
        } else if (ContactsHelper.normalizePhoneNumber(room.hostPhone) == myPhone) {
            val nextHost = remainingJoined.first()
            roomRef.update(
                mapOf(
                    "hostPhone" to nextHost.phoneNumber,
                    "hostName" to nextHost.displayName
                )
            )
            roomRef.collection("participants").document(nextHost.phoneNumber)
                .update("role", "HOST")
        }

        firebaseManager.logCall(
            direction = if (ContactsHelper.normalizePhoneNumber(room.hostPhone) == myPhone) CallDirection.OUTGOING else CallDirection.INCOMING,
            otherPartyNumber = room.hostPhone,
            otherPartyName = "${room.title} (Group)",
            callType = room.callType,
            status = CallStatus.ENDED,
            durationSeconds = duration,
            callId = room.roomId
        )

        endLocalSessionImmediate(null)
    }

    /**
     * Host-only action: Ends the Group Call for ALL participants immediately.
     */
    fun endGroupCallForAll() {
        val room = _state.value.room ?: return
        val duration = _state.value.callDurationSeconds
        declinedOrLeftRoomIds.add(room.roomId)
        groupCallsCollection().document(room.roomId)
            .update(mapOf("status" to "ENDED", "endedAt" to System.currentTimeMillis()))

        firebaseManager.logCall(
            direction = CallDirection.OUTGOING,
            otherPartyNumber = room.hostPhone,
            otherPartyName = "${room.title} (Group)",
            callType = room.callType,
            status = CallStatus.ENDED,
            durationSeconds = duration,
            callId = room.roomId
        )

        endLocalSessionImmediate(null)
    }

    private fun startDurationTimer() {
        timerJob?.cancel()
        timerJob = engineScope.launch {
            while (isActive) {
                delay(1000)
                _state.value = _state.value.copy(
                    callDurationSeconds = _state.value.callDurationSeconds + 1
                )
            }
        }
    }

    /**
     * Zero-hang asynchronous teardown:
     * 1. Clears UI state and video track references IMMEDIATELY (0ms) on Main thread.
     * 2. Disposes Camera2, SurfaceTextureHelper, PeerConnections, and AudioRouteManager asynchronously on mediaTeardownScope.
     */
    private fun endLocalSessionImmediate(reasonBanner: String?) {
        timerJob?.cancel()
        timerJob = null
        roomListener?.remove()
        roomListener = null
        participantsListener?.remove()
        participantsListener = null

        peerLinkListeners.values.forEach { it.remove() }
        peerLinkListeners.clear()
        processedCandidates.clear()
        pendingCandidatesByPeer.clear()
        remoteSdpSetPeers.clear()

        val pcsToDispose = ArrayList(peerConnections.values)
        peerConnections.clear()

        val capturerToStop = videoCapturer
        val helperToDispose = surfaceTextureHelper
        val vTrackToDispose = localVideoTrack
        val vSourceToDispose = localVideoSource
        val aTrackToDispose = localAudioTrack
        val aSourceToDispose = localAudioSource

        videoCapturer = null
        surfaceTextureHelper = null
        localVideoTrack = null
        localVideoSource = null
        localAudioTrack = null
        localAudioSource = null

        // 1. Reset UI state immediately (0ms) so Compose SurfaceViewRenderers detach safely
        _state.value = GroupCallUiState(
            filterConfig = filterManager.filterConfig.value,
            statusBannerText = reasonBanner
        )

        // 2. Run hardware Camera2 & native PeerConnection teardown off the Main thread
        mediaTeardownScope.launch {
            try {
                capturerToStop?.stopCapture()
            } catch (_: Throwable) {}
            try {
                capturerToStop?.dispose()
            } catch (_: Throwable) {}

            delay(90)

            pcsToDispose.forEach { pc ->
                try {
                    pc.close()
                } catch (_: Throwable) {}
                try {
                    pc.dispose()
                } catch (_: Throwable) {}
            }

            try {
                vTrackToDispose?.dispose()
            } catch (_: Throwable) {}
            try {
                vSourceToDispose?.dispose()
            } catch (_: Throwable) {}
            try {
                helperToDispose?.dispose()
            } catch (_: Throwable) {}
            try {
                aTrackToDispose?.dispose()
            } catch (_: Throwable) {}
            try {
                aSourceToDispose?.dispose()
            } catch (_: Throwable) {}
            try {
                audioRouteManager.resetAudioRouting()
            } catch (_: Throwable) {}
        }
    }

    companion object {
        private const val TAG = "GroupCallEngine"

        @Volatile
        private var INSTANCE: GroupCallEngine? = null

        fun getInstance(context: Context, firebaseManager: FirebaseManager): GroupCallEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GroupCallEngine(context.applicationContext, firebaseManager).also {
                    INSTANCE = it
                }
            }
        }
    }
}

private class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(p0: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(p0: String?) {}
    override fun onSetFailure(p0: String?) {}
}
