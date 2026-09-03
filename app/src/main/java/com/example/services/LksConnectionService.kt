package com.example.services

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import androidx.annotation.RequiresApi
import com.example.data.model.CallType
import com.example.webrtc.WebRtcEngine

/**
 * LksConnectionService
 * Self-Managed ConnectionService that bridges Android Telecom with LKS WebRtcEngine.
 * Handles Bluetooth HFP events (answer, hangup, reject) from wireless earbuds/headsets.
 */
@RequiresApi(Build.VERSION_CODES.O)
class LksConnectionService : ConnectionService() {

    companion object {
        private const val TAG = "LksConnectionService"
        var activeConnection: LksCallConnection? = null
            private set

        fun setCallActive() {
            activeConnection?.let {
                it.setActive()
                Log.d(TAG, "Telecom Connection set to ACTIVE")
            }
        }

        fun clearActiveConnection() {
            activeConnection = null
        }

        fun disconnectCall() {
            activeConnection?.let {
                it.setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
                it.destroy()
                activeConnection = null
                Log.d(TAG, "Telecom Connection set to DISCONNECTED and destroyed")
            }
        }
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        Log.d(TAG, "onCreateIncomingConnection received by Telecom")
        activeConnection?.let {
            try {
                it.setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
                it.destroy()
            } catch (_: Exception) {}
        }

        val extras = request?.extras ?: Bundle()
        val callId = extras.getString("call_id") ?: ""
        val callerName = extras.getString("caller_name") ?: "LKS Caller"
        val callerNumber = extras.getString("caller_number") ?: ""
        val callTypeStr = extras.getString("call_type") ?: "AUDIO"
        val callType = try { CallType.valueOf(callTypeStr) } catch (_: Exception) { CallType.AUDIO }

        val connection = LksCallConnection(this, callId, callerName, callerNumber, callType, isIncoming = true)
        connection.setCallerDisplayName(callerName, TelecomManager.PRESENTATION_ALLOWED)
        val addressUri = Uri.fromParts(PhoneAccount.SCHEME_TEL, callerNumber.ifBlank { "LKS" }, null)
        connection.setAddress(addressUri, TelecomManager.PRESENTATION_ALLOWED)
        connection.setRinging()
        connection.extras = extras

        activeConnection = connection
        return connection
    }

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        Log.d(TAG, "onCreateOutgoingConnection received by Telecom")
        activeConnection?.let {
            try {
                it.setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
                it.destroy()
            } catch (_: Exception) {}
        }

        val extras = request?.extras?.getBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS) ?: request?.extras ?: Bundle()
        val callId = extras.getString("call_id") ?: ""
        val calleeName = extras.getString("callee_name") ?: "LKS Contact"
        val calleeNumber = extras.getString("callee_number") ?: ""
        val callTypeStr = extras.getString("call_type") ?: "AUDIO"
        val callType = try { CallType.valueOf(callTypeStr) } catch (_: Exception) { CallType.AUDIO }

        val connection = LksCallConnection(this, callId, calleeName, calleeNumber, callType, isIncoming = false)
        connection.setCallerDisplayName(calleeName, TelecomManager.PRESENTATION_ALLOWED)
        val addressUri = Uri.fromParts(PhoneAccount.SCHEME_TEL, calleeNumber.ifBlank { "LKS" }, null)
        connection.setAddress(addressUri, TelecomManager.PRESENTATION_ALLOWED)
        connection.setDialing()
        connection.extras = extras

        activeConnection = connection
        return connection
    }

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        Log.e(TAG, "onCreateIncomingConnectionFailed in Telecom")
        super.onCreateIncomingConnectionFailed(connectionManagerPhoneAccount, request)
    }

    override fun onCreateOutgoingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        Log.e(TAG, "onCreateOutgoingConnectionFailed in Telecom")
        super.onCreateOutgoingConnectionFailed(connectionManagerPhoneAccount, request)
    }
}

class LksCallConnection(
    val context: android.content.Context,
    val callId: String,
    val peerName: String,
    val peerNumber: String,
    val callType: CallType,
    val isIncoming: Boolean
) : Connection() {

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            connectionProperties = PROPERTY_SELF_MANAGED
        }
        connectionCapabilities = CAPABILITY_HOLD or CAPABILITY_SUPPORT_HOLD or CAPABILITY_MUTE
        audioModeIsVoip = true
    }

    override fun onAnswer(videoState: Int) {
        Log.i("LksCallConnection", "🎯 Bluetooth Headset / System answered the call via Telecom! callId=$callId")
        setActive()
        WebRtcEngine.getInstanceIfCreated()?.answerCall()
    }

    override fun onReject() {
        Log.i("LksCallConnection", "🎯 Bluetooth Headset / System rejected the call via Telecom! callId=$callId")
        setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
        destroy()
        LksConnectionService.clearActiveConnection()
        WebRtcEngine.getInstanceIfCreated()?.endCall()
    }

    override fun onDisconnect() {
        Log.i("LksCallConnection", "🎯 Bluetooth Headset / System hung up the call via Telecom! callId=$callId")
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        destroy()
        LksConnectionService.clearActiveConnection()
        WebRtcEngine.getInstanceIfCreated()?.endCall()
    }

    override fun onSilence() {
        Log.i("LksCallConnection", "Ringtone silenced by hardware button on callId=$callId")
        com.example.util.LksIncomingRingtonePlayer.silence()
        FloatingCallBubbleService.silenceRingtone(context)
    }

    override fun onShowIncomingCallUi() {
        val km = context.getSystemService(android.content.Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        val isLocked = km?.isKeyguardLocked == true
        if (isLocked) {
            Log.d("LksCallConnection", "onShowIncomingCallUi triggered on locked device -> launching full screen UI")
            com.example.util.LksIncomingRingtonePlayer.start(context, peerNumber)
            try {
                val fullScreenIntent = android.content.Intent(context, com.example.MainActivity::class.java).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("incoming_call", true)
                    putExtra("call_id", callId)
                    putExtra("caller_name", peerName)
                    putExtra("caller_number", peerNumber)
                    putExtra("call_type", callType.name)
                }
                context.startActivity(fullScreenIntent)
            } catch (e: Exception) {
                Log.e("LksCallConnection", "Failed to launch incoming call UI from Telecom", e)
            }
        } else if (!com.example.MainActivity.isForeground) {
            Log.d("LksCallConnection", "onShowIncomingCallUi on unlocked device -> showing floating pill")
            // Safety: pill + ringtone may already be started by FCM. isShowingPill guard in showIncoming() prevents duplicate.
            com.example.services.FloatingCallBubbleService.showIncoming(context, callId, peerName, peerNumber, callType)
        }
    }
}
