package com.example.data.model

import com.google.firebase.firestore.PropertyName

data class UserDto(
    val phoneNumber: String = "",
    val displayName: String = "",
    val profilePictureUrl: String = "",
    val statusMessage: String = "Available on RB DIALER",
    val fcmToken: String = "",
    val webToken: String = "",
    @get:PropertyName("isOnline") @set:PropertyName("isOnline")
    var isOnline: Boolean = true,
    @get:PropertyName("online") @set:PropertyName("online")
    var online: Boolean? = null,
    val lastSeen: Long = 0L,
    val createdAt: Long = 0L,
    val privacyWhoCanCall: String = "EVERYONE",
    val registeredDeviceId: String = "",
    val blockedNumbers: List<String> = emptyList(),
    @get:PropertyName("isDndEnabled") @set:PropertyName("isDndEnabled")
    var isDndEnabled: Boolean = false,
    val publicKey: String = "",
    val callerTuneTitle: String = "",
    val callerTuneArtist: String = "",
    val callerTuneUrl: String = "",
    val callerTuneAudioBase64: String = "",
    val callerTuneSource: String = "DEFAULT",
    val callerTuneStartMs: Long = 0L,
    val callerTuneEndMs: Long = 0L
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
    val videoUpgradeRequestedBy: String? = null,
    @get:PropertyName("isOnHold") @set:PropertyName("isOnHold")
    var isOnHold: Boolean = false,
    @get:PropertyName("heldBy") @set:PropertyName("heldBy")
    var heldBy: String? = null,
    val callerTuneTitle: String? = null,
    val callerTuneAudioBase64: String? = null,
    val callerTuneUrl: String? = null,
    val callerTuneStartMs: Long = 0L,
    val callerTuneEndMs: Long = 0L,
    val callerFcmToken: String = "",
    val callerWebToken: String = "",
    val calleeFcmToken: String = "",
    val calleeWebToken: String = "",
    val callerCandidates: List<String> = emptyList(),
    val calleeCandidates: List<String> = emptyList(),
    val reason: String? = null,
    @get:PropertyName("iceRestartRequested") @set:PropertyName("iceRestartRequested")
    var iceRestartRequested: Long = 0L,
    val callerFilterConfig: String = "",
    val calleeFilterConfig: String = "",
    val promotedToGroupRoomId: String? = null
)

enum class VideoFilterPreset(
    val id: String,
    val title: String,
    val subtitle: String,
    val accentHex: Long
) {
    NONE("NONE", "Original", "True-to-life natural camera", 0xFF38BDF8),
    BEAUTY_GLOW("BEAUTY_GLOW", "Beauty Glow", "Soft skin diffusion & warm radiance", 0xFFF472B6),
    WARM_CINEMA("WARM_CINEMA", "Warm Cinema", "Golden hour anamorphic film tone", 0xFFF59E0B),
    CYBER_NEON("CYBER_NEON", "Cyber Neon", "Cyan & magenta dual-tone refraction", 0xFF00F5D4),
    MONOCHROME("MONOCHROME", "Monochrome", "High-contrast silver studio noir", 0xFFE2E8F0),
    COOL_ARCTIC("COOL_ARCTIC", "Cool Arctic", "Crisp sapphire daylight balance", 0xFF60A5FA),
    PORTRAIT_FOCUS("PORTRAIT_FOCUS", "Portrait Blur", "Soft depth-of-field edge focus", 0xFFA78BFA);

    companion object {
        fun fromId(id: String?): VideoFilterPreset =
            entries.find { it.id.equals(id, ignoreCase = true) } ?: NONE
    }
}

data class VideoFilterConfig(
    val preset: VideoFilterPreset = VideoFilterPreset.NONE,
    val brightness: Float = 0f,       // -0.35f .. +0.35f
    val contrast: Float = 1f,         // 0.70f .. 1.45f
    val warmth: Float = 0f,           // -0.40f .. +0.40f
    val vignette: Float = 0.15f,      // 0.00f .. 0.85f
    val studioRimLight: Boolean = false,
    val hudOverlayEnabled: Boolean = false
) {
    val isDefault: Boolean
        get() = preset == VideoFilterPreset.NONE &&
             kotlin.math.abs(brightness) < 0.02f &&
             kotlin.math.abs(contrast - 1f) < 0.02f &&
             kotlin.math.abs(warmth) < 0.02f &&
             vignette <= 0.16f &&
             !studioRimLight &&
             !hudOverlayEnabled

    fun toCompactString(): String {
        return listOf(
            preset.id,
            "%.2f".format(java.util.Locale.US, brightness),
            "%.2f".format(java.util.Locale.US, contrast),
            "%.2f".format(java.util.Locale.US, warmth),
            "%.2f".format(java.util.Locale.US, vignette),
            if (studioRimLight) "1" else "0",
            if (hudOverlayEnabled) "1" else "0"
        ).joinToString("|")
    }

    companion object {
        fun fromCompactString(raw: String?): VideoFilterConfig {
            if (raw.isNullOrBlank()) return VideoFilterConfig()
            val parts = raw.split("|")
            if (parts.isEmpty()) return VideoFilterConfig()
            return try {
                VideoFilterConfig(
                    preset = VideoFilterPreset.fromId(parts.getOrNull(0)),
                    brightness = parts.getOrNull(1)?.toFloatOrNull()?.coerceIn(-0.35f, 0.35f) ?: 0f,
                    contrast = parts.getOrNull(2)?.toFloatOrNull()?.coerceIn(0.70f, 1.45f) ?: 1f,
                    warmth = parts.getOrNull(3)?.toFloatOrNull()?.coerceIn(-0.40f, 0.40f) ?: 0f,
                    vignette = parts.getOrNull(4)?.toFloatOrNull()?.coerceIn(0f, 0.85f) ?: 0.15f,
                    studioRimLight = parts.getOrNull(5) == "1",
                    hudOverlayEnabled = parts.getOrNull(6) == "1"
                )
            } catch (_: Throwable) {
                VideoFilterConfig()
            }
        }
    }
}

enum class GroupLayoutMode {
    GRID, SPEAKER
}

data class GroupCallRoomDto(
    val roomId: String = "",
    val title: String = "Team Conference",
    val hostPhone: String = "",
    val hostName: String = "",
    val coHostPhones: List<String> = emptyList(),
    val callType: CallType = CallType.VIDEO,
    val status: String = "ACTIVE", // ACTIVE, ENDED
    @get:PropertyName("isLocked") @set:PropertyName("isLocked")
    var isLocked: Boolean = false,
    @get:PropertyName("waitingRoomEnabled") @set:PropertyName("waitingRoomEnabled")
    var waitingRoomEnabled: Boolean = false,
    val spotlightPhone: String? = null,
    val invitedPhones: List<String> = emptyList(),
    val activeParticipantCount: Int = 1,
    val muteAllTimestamp: Long = 0L,
    val unmuteAllRequestTimestamp: Long = 0L,
    val createdAt: Long = 0L,
    val endedAt: Long? = null
)

data class GroupParticipantDto(
    val phoneNumber: String = "",
    val displayName: String = "",
    val profilePicUrl: String = "",
    val role: String = "PARTICIPANT", // HOST, CO_HOST, PARTICIPANT
    val joinState: String = "INVITED", // INVITED, WAITING_LOBBY, JOINED, REMOVED, LEFT, DECLINED
    @get:PropertyName("isMuted") @set:PropertyName("isMuted")
    var isMuted: Boolean = false,
    @get:PropertyName("isVideoEnabled") @set:PropertyName("isVideoEnabled")
    var isVideoEnabled: Boolean = true,
    @get:PropertyName("isHandRaised") @set:PropertyName("isHandRaised")
    var isHandRaised: Boolean = false,
    val activeReaction: String = "",
    val reactionTimestamp: Long = 0L,
    @get:PropertyName("unmuteRequestedByHost") @set:PropertyName("unmuteRequestedByHost")
    var unmuteRequestedByHost: Boolean = false,
    @get:PropertyName("videoStopRequestedByHost") @set:PropertyName("videoStopRequestedByHost")
    var videoStopRequestedByHost: Boolean = false,
    val filterConfigStr: String = "",
    val joinedAt: Long = 0L,
    val updatedAt: Long = 0L
)

data class GroupPeerLinkDto(
    val linkId: String = "",          // sorted "phoneA_phoneB"
    val roomId: String = "",
    val initiatorPhone: String = "",  // lexicographically smaller phone creates offer
    val responderPhone: String = "",
    val offerSdp: String? = null,
    val answerSdp: String? = null,
    val initiatorCandidates: List<String> = emptyList(),
    val responderCandidates: List<String> = emptyList(),
    val updatedAt: Long = 0L
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
    val durationSeconds: Int = 0,
    val recordingPath: String? = null,
    val callerNumber: String = "",
    val calleeNumber: String = ""
) {
    fun resolveOtherPartyNumber(): String {
        if (otherPartyNumber.isNotBlank()) return otherPartyNumber
        if (direction == CallDirection.OUTGOING) {
            if (calleeNumber.isNotBlank()) return calleeNumber
            if (callerNumber.isNotBlank()) return callerNumber
        } else {
            if (callerNumber.isNotBlank()) return callerNumber
            if (calleeNumber.isNotBlank()) return calleeNumber
        }
        val candidate = callId.substringBefore('_')
        if (candidate.matches(Regex("^[+]?[0-9]{7,15}$"))) return candidate
        return ""
    }

    fun resolveOtherPartyName(): String {
        if (otherPartyName.isNotBlank() && otherPartyName != otherPartyNumber && otherPartyName != "Unknown") return otherPartyName
        val num = resolveOtherPartyNumber()
        if (num.isNotBlank()) return num
        return if (otherPartyName.isNotBlank()) otherPartyName else "Unknown"
    }
}

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

data class BlockedContactInfo(
    val phoneNumber: String = "",
    val displayName: String = "",
    val profilePictureUrl: String = "",
    val isLksUser: Boolean = false,
    val statusMessage: String = ""
)

data class ChatMessageDto(
    val messageId: String = "",
    val senderNumber: String = "",
    val recipientNumber: String = "",
    val senderPublicKey: String = "",
    val ciphertext: String = "",
    val iv: String = "",
    val mediaType: String = "TEXT",
    val mediaDurationMs: Long = 0L,
    val timestamp: Long = 0L,
    val expiresAt: Long = 0L,
    val groupId: String? = null,
    val groupName: String? = null,
    val senderName: String? = null,
    val isViewOnce: Boolean = false
)

data class GroupDto(
    val groupId: String = "",
    val name: String = "",
    val adminPhone: String = "",
    val members: List<String> = emptyList(),
    val createdTimestamp: Long = 0L,
    val iconUrl: String = ""
)

data class ChatReceiptDto(
    val receiptId: String = "",
    val messageId: String = "",
    val senderNumber: String = "",
    val recipientNumber: String = "",
    val status: String = "DELIVERED",
    val timestamp: Long = 0L
)

data class LinkedDeviceDto(
    @get:PropertyName("sessionId") @set:PropertyName("sessionId")
    var sessionId: String = "",
    @get:PropertyName("deviceName") @set:PropertyName("deviceName")
    var deviceName: String = "",
    @get:PropertyName("userAgent") @set:PropertyName("userAgent")
    var userAgent: String = "",
    @get:PropertyName("linkedAt") @set:PropertyName("linkedAt")
    var linkedAt: Long = 0L,
    @get:PropertyName("lastActive") @set:PropertyName("lastActive")
    var lastActive: Long = 0L,
    @get:PropertyName("revoked") @set:PropertyName("revoked")
    var revoked: Boolean = false
) {
    // Explicit no-argument constructor for Firebase Firestore deserialization
    constructor() : this("", "", "", 0L, 0L, false)
}


