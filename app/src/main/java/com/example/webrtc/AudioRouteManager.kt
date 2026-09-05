package com.example.webrtc

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.example.data.model.CallType

/**
 * AudioRouteManager
 * Dedicated manager for VoIP audio routing across Speaker, Earpiece, Bluetooth SCO/BLE,
 * and Wired Headsets, with Samsung Voice Focus enhancement and Telecom subsystem synchronization.
 */
class AudioRouteManager(
    private val context: Context,
    private val onRouteChanged: (
        selectedDevice: AudioDeviceOption,
        availableDevices: List<AudioDeviceOption>,
        isSpeakerOn: Boolean
    ) -> Unit,
    private val onCellularInterruption: (isInterrupted: Boolean) -> Unit
) {
    companion object {
        private const val TAG = "AudioRouteManager"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var headsetReceiver: BroadcastReceiver? = null

    private var lastNonBluetoothAudioDevice: AudioDeviceType = AudioDeviceType.EARPIECE
    @Volatile
    private var lastUserExplicitSelectionTime: Long = 0L
    @Volatile
    private var userExplicitSelectedDevice: AudioDeviceType? = null
    @Volatile
    private var isCellularCallInterrupting: Boolean = false
    @Volatile
    private var callAudioFocusGrantedAt: Long = 0L
    private var samsungVoiceFocusEffect: Any? = null

    var currentAvailableDevices: List<AudioDeviceOption> = emptyList()
        private set
    var currentSelectedDevice: AudioDeviceType = AudioDeviceType.EARPIECE
        private set

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        Log.d(TAG, "AudioFocus changed: $focusChange")
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                val timeSinceFocusGrant = System.currentTimeMillis() - callAudioFocusGrantedAt
                if (timeSinceFocusGrant < 5000L) {
                    Log.d(TAG, "Suppressing auto-hold: audio focus granted only ${timeSinceFocusGrant}ms ago (startup transient)")
                    return@OnAudioFocusChangeListener
                }
                Log.i(TAG, "Cellular call interruption detected — placing VoIP call on hold")
                isCellularCallInterrupting = true
                onCellularInterruption(true)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (isCellularCallInterrupting) {
                    Log.i(TAG, "Regained audio focus after cellular call — resuming VoIP call")
                    isCellularCallInterrupting = false
                    onCellularInterruption(false)
                }
            }
        }
    }

    fun configureAudio(callType: CallType) {
        lastNonBluetoothAudioDevice = if (callType == CallType.VIDEO) AudioDeviceType.SPEAKERPHONE else AudioDeviceType.EARPIECE
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        registerAudioDeviceListeners()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            audioFocusRequest = focusRequest
            audioManager.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
        callAudioFocusGrantedAt = System.currentTimeMillis()

        // Ensure voice call volume is clear and un-ducked
        try {
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            val curVol = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            if (curVol < (maxVol * 0.7f).toInt()) {
                audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (maxVol * 0.85f).toInt(), 0)
            }
        } catch (_: Exception) {}

        applySamsungVoiceFocusIfAvailable()
        refreshAvailableAudioDevices(defaultCallType = callType)
    }

    private fun registerAudioDeviceListeners() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioDeviceCallback == null) {
            audioDeviceCallback = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                    Log.d(TAG, "Audio devices added, refreshing device list")
                    refreshAvailableAudioDevices()
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                    Log.d(TAG, "Audio devices removed, refreshing device list")
                    refreshAvailableAudioDevices()
                }
            }
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        }

        if (headsetReceiver == null) {
            headsetReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        Intent.ACTION_HEADSET_PLUG,
                        BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED,
                        BluetoothDevice.ACTION_ACL_CONNECTED,
                        BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                            Log.d(TAG, "Audio hardware broadcast received: ${intent.action}")
                            refreshAvailableAudioDevices()
                        }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_HEADSET_PLUG)
                addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
            context.registerReceiver(headsetReceiver, filter)
        }
    }

    private fun unregisterAudioDeviceListeners() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioDeviceCallback != null) {
            try {
                audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
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
        val deviceList = mutableListOf<AudioDeviceOption>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val commDevices = audioManager.availableCommunicationDevices
            var hasEarpiece = false
            var hasSpeaker = false

            for (dev in commDevices) {
                when (dev.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_HEARING_AID -> {
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
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_USB_DEVICE -> {
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
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> {
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
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> {
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
                    val allDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    val btOutput = allDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        it.type == AudioDeviceInfo.TYPE_HEARING_AID
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
                @Suppress("DEPRECATION")
                val btAdapter = BluetoothAdapter.getDefaultAdapter()
                if (btAdapter != null && btAdapter.isEnabled) {
                    val headsetState = btAdapter.getProfileConnectionState(BluetoothProfile.HEADSET)
                    val a2dpState = btAdapter.getProfileConnectionState(BluetoothProfile.A2DP)
                    if (headsetState == BluetoothProfile.STATE_CONNECTED ||
                        a2dpState == BluetoothProfile.STATE_CONNECTED) {
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
            if (audioManager.isWiredHeadsetOn) {
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

        val currentSelected = currentSelectedDevice
        val hadBluetoothBefore = currentAvailableDevices.any { it.type == AudioDeviceType.BLUETOOTH }
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
            currentAvailableDevices = updatedList
            currentSelectedDevice = target.type
            onRouteChanged(target, updatedList, target.type == AudioDeviceType.SPEAKERPHONE)
            return
        }

        if (defaultCallType != null) {
            targetDevice = when {
                btOption != null -> btOption
                wiredOption != null -> wiredOption
                defaultCallType == CallType.VIDEO -> speakerOption ?: earpieceOption ?: deviceList.first()
                else -> earpieceOption ?: speakerOption ?: deviceList.first()
            }
            selectAudioDevice(targetDevice, updateDeviceList = false)
        } else {
            targetDevice = when {
                !hadBluetoothBefore && hasBluetoothNow && btOption != null -> {
                    if (currentSelected != AudioDeviceType.BLUETOOTH) {
                        lastNonBluetoothAudioDevice = currentSelected
                    }
                    Log.i(TAG, "🎧 Bluetooth connected during call -> Auto-switching to Bluetooth")
                    btOption
                }
                hadBluetoothBefore && !hasBluetoothNow && currentSelected == AudioDeviceType.BLUETOOTH -> {
                    Log.i(TAG, "🎧 Bluetooth disconnected during call -> Reverting to: $lastNonBluetoothAudioDevice")
                    when (lastNonBluetoothAudioDevice) {
                        AudioDeviceType.SPEAKERPHONE -> speakerOption ?: earpieceOption ?: deviceList.first()
                        AudioDeviceType.WIRED_HEADSET -> wiredOption ?: earpieceOption ?: deviceList.first()
                        else -> earpieceOption ?: speakerOption ?: deviceList.first()
                    }
                }
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
        currentAvailableDevices = updatedList
        currentSelectedDevice = targetDevice.type
        onRouteChanged(targetDevice, updatedList, targetDevice.type == AudioDeviceType.SPEAKERPHONE)
    }

    fun selectAudioDevice(device: AudioDeviceOption, updateDeviceList: Boolean = true) {
        if (updateDeviceList) {
            lastUserExplicitSelectionTime = System.currentTimeMillis()
            userExplicitSelectedDevice = device.type
        }

        if (device.type != AudioDeviceType.BLUETOOTH) {
            lastNonBluetoothAudioDevice = device.type
        }

        Log.i(TAG, "🔊 Switching audio route to: ${device.type} (${device.name})")

        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            // Guarantee audio focus
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                    .build()
                audioFocusRequest = focusRequest
                audioManager.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    audioFocusChangeListener,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN
                )
            }

            // Sync with Telecom Connection
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    val telecomRoute = when (device.type) {
                        AudioDeviceType.SPEAKERPHONE -> android.telecom.CallAudioState.ROUTE_SPEAKER
                        AudioDeviceType.BLUETOOTH -> android.telecom.CallAudioState.ROUTE_BLUETOOTH
                        AudioDeviceType.EARPIECE -> android.telecom.CallAudioState.ROUTE_EARPIECE
                        AudioDeviceType.WIRED_HEADSET -> android.telecom.CallAudioState.ROUTE_WIRED_HEADSET
                    }
                    com.example.services.LksConnectionService.setAudioRoute(telecomRoute)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set Telecom audio route: ${e.message}")
                }
            }

            when (device.type) {
                AudioDeviceType.SPEAKERPHONE -> {
                    @Suppress("DEPRECATION")
                    try {
                        if (audioManager.isBluetoothScoOn) {
                            audioManager.isBluetoothScoOn = false
                            audioManager.stopBluetoothSco()
                        }
                    } catch (_: Exception) {}

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val speaker = (device.rawDevice as? AudioDeviceInfo)
                            ?: audioManager.availableCommunicationDevices.firstOrNull {
                                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                            }
                        if (speaker != null) {
                            val res = audioManager.setCommunicationDevice(speaker)
                            Log.d(TAG, "setCommunicationDevice(SPEAKER): $res")
                            if (!res) {
                                audioManager.clearCommunicationDevice()
                                audioManager.setCommunicationDevice(speaker)
                            }
                        }
                    }
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = true
                }
                AudioDeviceType.EARPIECE -> {
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = false
                    @Suppress("DEPRECATION")
                    try {
                        if (audioManager.isBluetoothScoOn) {
                            audioManager.isBluetoothScoOn = false
                            audioManager.stopBluetoothSco()
                        }
                    } catch (_: Exception) {}

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val earpiece = (device.rawDevice as? AudioDeviceInfo)
                            ?: audioManager.availableCommunicationDevices.firstOrNull {
                                it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                            }
                        if (earpiece != null) {
                            val res = audioManager.setCommunicationDevice(earpiece)
                            Log.d(TAG, "setCommunicationDevice(EARPIECE): $res")
                            if (!res) {
                                audioManager.clearCommunicationDevice()
                                audioManager.setCommunicationDevice(earpiece)
                            }
                        } else {
                            audioManager.clearCommunicationDevice()
                        }
                    }
                }
                AudioDeviceType.BLUETOOTH -> {
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = false

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        audioManager.clearCommunicationDevice()
                        val bt = if (device.rawDevice is AudioDeviceInfo) {
                            device.rawDevice as AudioDeviceInfo
                        } else {
                            audioManager.availableCommunicationDevices.firstOrNull {
                                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                                it.type == AudioDeviceInfo.TYPE_HEARING_AID
                            }
                        }
                        if (bt != null) {
                            val res = audioManager.setCommunicationDevice(bt)
                            Log.d(TAG, "setCommunicationDevice(BLUETOOTH - ${bt.productName}): $res")
                            if (!res) {
                                @Suppress("DEPRECATION")
                                try {
                                    audioManager.startBluetoothSco()
                                    audioManager.isBluetoothScoOn = true
                                } catch (_: Exception) {}
                            }
                        } else {
                            @Suppress("DEPRECATION")
                            try {
                                audioManager.startBluetoothSco()
                                audioManager.isBluetoothScoOn = true
                            } catch (_: Exception) {}
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        try {
                            audioManager.startBluetoothSco()
                            audioManager.isBluetoothScoOn = true
                        } catch (_: Exception) {}
                    }
                }
                AudioDeviceType.WIRED_HEADSET -> {
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = false
                    @Suppress("DEPRECATION")
                    try {
                        audioManager.stopBluetoothSco()
                        audioManager.isBluetoothScoOn = false
                    } catch (_: Exception) {}

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        audioManager.clearCommunicationDevice()
                        val wired = if (device.rawDevice is AudioDeviceInfo) {
                            device.rawDevice as AudioDeviceInfo
                        } else {
                            audioManager.availableCommunicationDevices.firstOrNull {
                                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                                it.type == AudioDeviceInfo.TYPE_USB_DEVICE
                            }
                        }
                        if (wired != null) {
                            val res = audioManager.setCommunicationDevice(wired)
                            Log.d(TAG, "setCommunicationDevice(WIRED - ${wired.productName}): $res")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting audio device: ${device.name}", e)
        }

        if (updateDeviceList) {
            val updatedList = currentAvailableDevices.map {
                it.copy(isSelected = it.id == device.id || it.type == device.type)
            }
            currentAvailableDevices = updatedList
            currentSelectedDevice = device.type
            onRouteChanged(device, updatedList, device.type == AudioDeviceType.SPEAKERPHONE)
        }
    }

    fun selectAudioDeviceType(type: AudioDeviceType) {
        val target = currentAvailableDevices.firstOrNull { it.type == type }
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

        if (now - lastUserExplicitSelectionTime < 5000L) {
            if (explicit != null && explicit != targetType) {
                Log.d(TAG, "Ignoring Telecom route change to $targetType (user explicitly selected $explicit ${now - lastUserExplicitSelectionTime}ms ago)")
                return
            }
        }

        if (explicit == AudioDeviceType.SPEAKERPHONE && (targetType == AudioDeviceType.EARPIECE || targetType == AudioDeviceType.BLUETOOTH)) {
            Log.d(TAG, "Ignoring Telecom automatic transition to $targetType because user explicitly chose SPEAKERPHONE")
            return
        }
        if (explicit == AudioDeviceType.EARPIECE && targetType == AudioDeviceType.BLUETOOTH) {
            Log.d(TAG, "Ignoring Telecom automatic transition to BLUETOOTH because user explicitly chose EARPIECE")
            return
        }

        if (currentSelectedDevice != targetType) {
            Log.i(TAG, "Syncing route from Telecom: $targetType")
            userExplicitSelectedDevice = targetType
            selectAudioDeviceType(targetType)
        }
    }

    fun toggleSpeaker() {
        if (currentAvailableDevices.isEmpty()) {
            refreshAvailableAudioDevices()
        }
        val current = currentSelectedDevice
        if (current == AudioDeviceType.SPEAKERPHONE) {
            val bt = currentAvailableDevices.firstOrNull { it.type == AudioDeviceType.BLUETOOTH }
            val wired = currentAvailableDevices.firstOrNull { it.type == AudioDeviceType.WIRED_HEADSET }
            val earpiece = currentAvailableDevices.firstOrNull { it.type == AudioDeviceType.EARPIECE }
            val target = bt ?: wired ?: earpiece ?: AudioDeviceOption(id = "earpiece_default", name = "Phone Earpiece", type = AudioDeviceType.EARPIECE)
            selectAudioDevice(target)
        } else {
            val speaker = currentAvailableDevices.firstOrNull { it.type == AudioDeviceType.SPEAKERPHONE }
                ?: AudioDeviceOption(id = "speaker_default", name = "Speaker", type = AudioDeviceType.SPEAKERPHONE)
            selectAudioDevice(speaker)
        }
    }

    private fun applySamsungVoiceFocusIfAvailable() {
        try {
            val effects = android.media.audiofx.AudioEffect.queryEffects() ?: return
            val samsungEffect = effects.firstOrNull { descriptor ->
                val name = descriptor.name?.lowercase() ?: ""
                val implementor = descriptor.implementor?.lowercase() ?: ""
                name.contains("voice focus") || name.contains("voicefocus") ||
                    name.contains("samsung") && name.contains("focus") ||
                    implementor.contains("samsung") && name.contains("noise")
            }
            if (samsungEffect != null) {
                val ctor = android.media.audiofx.AudioEffect::class.java
                    .getDeclaredConstructor(java.util.UUID::class.java, java.util.UUID::class.java, Int::class.java, Int::class.java)
                ctor.isAccessible = true
                val effect = ctor.newInstance(samsungEffect.type, samsungEffect.uuid, 0, 0)
                val setEnabled = android.media.audiofx.AudioEffect::class.java.getDeclaredMethod("setEnabled", Boolean::class.java)
                setEnabled.isAccessible = true
                setEnabled.invoke(effect, true)
                samsungVoiceFocusEffect = effect
                Log.i(TAG, "✅ Samsung Voice Focus effect enabled: ${samsungEffect.name}")
            }
        } catch (e: Exception) {
            Log.d(TAG, "Samsung Voice Focus not available: ${e.message}")
        }
    }

    fun resetAudioRouting() {
        unregisterAudioDeviceListeners()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(audioFocusChangeListener)
            }
            audioFocusRequest = null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                @Suppress("DEPRECATION")
                try {
                    audioManager.stopBluetoothSco()
                    audioManager.isBluetoothScoOn = false
                } catch (_: Exception) {}
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                try {
                    audioManager.stopBluetoothSco()
                    audioManager.isBluetoothScoOn = false
                } catch (_: Exception) {}
            }
            audioManager.mode = AudioManager.MODE_NORMAL
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
        } catch (_: Exception) {}

        try {
            if (samsungVoiceFocusEffect != null) {
                val releaseMethod = samsungVoiceFocusEffect?.javaClass?.getMethod("release")
                releaseMethod?.invoke(samsungVoiceFocusEffect)
            }
        } catch (_: Exception) {}
        samsungVoiceFocusEffect = null
        isCellularCallInterrupting = false
    }
}
