package com.example.webrtc

/**
 * Audio output device types supported by LKS-DIALER.
 */
enum class AudioDeviceType {
    EARPIECE,
    SPEAKERPHONE,
    BLUETOOTH,
    WIRED_HEADSET
}

/**
 * Encapsulates an available audio device option for UI and audio routing.
 */
data class AudioDeviceOption(
    val id: String,
    val name: String,
    val type: AudioDeviceType,
    val isSelected: Boolean = false,
    val rawDevice: Any? = null
)
