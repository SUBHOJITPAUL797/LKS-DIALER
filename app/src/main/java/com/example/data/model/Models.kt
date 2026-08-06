package com.example.data.model

data class UserDto(
    val phoneNumber: String = "",
    val displayName: String = "",
    val profilePictureUrl: String = "",
    val statusMessage: String = "Available on LKS DIALER",
    val fcmToken: String = "",
    val isOnline: Boolean = true,
    val lastSeen: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis(),
    val privacyWhoCanCall: String = "EVERYONE",
    val registeredDeviceId: String = ""
)

enum class CallType {
    AUDIO, VIDEO
}

enum class CallStatus {
    IDLE, CALLING, RINGING, ANSWERED, ENDED, DECLINED, MISSED, FAILED
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
    val status: CallStatus = CallStatus.CALLING,
    val offerSdp: String? = null,
    val answerSdp: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val answeredAt: Long? = null,
    val endedAt: Long? = null,
    val durationSeconds: Int = 0,
    val endedBy: String? = null
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
    val startedAt: Long = System.currentTimeMillis(),
    val durationSeconds: Int = 0
)

data class ContactDto(
    val id: String = "",
    val name: String = "",
    val phoneNumber: String = "",
    val profilePictureUrl: String = "",
    val isVoiceLinkUser: Boolean = true,
    val statusMessage: String = "Available"
)

data class CountryCode(
    val countryName: String,
    val flagEmoji: String,
    val dialCode: String,
    val isoCode: String
)
