package com.example.data.model

import com.google.firebase.firestore.PropertyName

data class UserDto(
    val phoneNumber: String = "",
    val displayName: String = "",
    val profilePictureUrl: String = "",
    val statusMessage: String = "Available on LKS DIALER",
    val fcmToken: String = "",
    val isOnline: Boolean = true,
    val lastSeen: Long = 0L,
    val createdAt: Long = 0L,
    val privacyWhoCanCall: String = "EVERYONE",
    val registeredDeviceId: String = ""
)

enum class CallType {
    AUDIO, VIDEO
}

enum class CallStatus {
    IDLE, CALLING, RINGING, ANSWERED, ENDED, DECLINED, MISSED, FAILED
}

enum class VideoUpgradeStatus {
    REQUESTED, ACCEPTED, DECLINED, COMPLETED
}

data class CallDto(
    val callId: String = "",
    val callerNumber: String = "",
    val calleeNumber: String = "",
    val callerName: String = "",
    val callerProfilePic: String = "",
    val calleeName: String = "",
    val calleeProfilePic: String = "",
    val callType: CallType = CallType.AUDIO,
    val status: CallStatus = CallStatus.IDLE,
    val offerSdp: String? = null,
    val answerSdp: String? = null,
    val createdAt: Long = 0L,
    val answeredAt: Long? = null,
    val endedAt: Long? = null,
    val durationSeconds: Int = 0,
    val endedBy: String? = null,
    val videoUpgradeStatus: VideoUpgradeStatus? = null,
    @get:PropertyName("isOnHold") @set:PropertyName("isOnHold")
    var isOnHold: Boolean = false,
    @get:PropertyName("heldBy") @set:PropertyName("heldBy")
    var heldBy: String? = null
)

data class IceCandidateDto(
    val serverUrl: String = "",
    val sdpMid: String = "",
    val sdpMLineIndex: Int = 0,
    val sdpCandidate: String = "",
    val type: String = "" // "offerCandidate" or "answerCandidate"
)

enum class CallDirection {
    OUTGOING, INCOMING, MISSED
}

data class CallLogDto(
    val id: String = "",
    val callId: String = "",
    val direction: CallDirection = CallDirection.OUTGOING,
    val otherPartyNumber: String = "",
    val otherPartyName: String = "",
    val otherPartyProfilePic: String = "",
    val callType: CallType = CallType.AUDIO,
    val status: CallStatus = CallStatus.ANSWERED,
    val startedAt: Long = 0L,
    val durationSeconds: Int = 0
)

data class ContactDto(
    val id: String = "",
    val name: String = "",
    val phoneNumber: String = "",
    val profilePictureUrl: String = "",
    val isVoiceLinkUser: Boolean = false,
    val statusMessage: String = "Available"
)

data class CountryCode(
    val countryName: String = "",
    val flagEmoji: String = "",
    val dialCode: String = "",
    val isoCode: String = ""
)
