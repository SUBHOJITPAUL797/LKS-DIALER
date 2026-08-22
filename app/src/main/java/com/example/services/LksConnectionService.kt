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
        val extras = request?.extras ?: Bundle()
        val callId = extras.getString("call_id") ?: ""
        val callerName = extras.getString("caller_name") ?: "LKS Caller"
        val callerNumber = extras.getString("caller_number") ?: ""
        val callTypeStr = extras.getString("call_type") ?: "AUDIO"
        val callType = try { CallType.valueOf(callTypeStr) } catch (_: Exception) { CallType.AUDIO }

        val connection = LksCallConnection(callId, callerName, callerNumber, callType, isIncoming = true)
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
        val extras = request?.extras?.getBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS) ?: request?.extras ?: Bundle()
        val callId = extras.getString("call_id") ?: ""
        val calleeName = extras.getString("callee_name") ?: "LKS Contact"
        val calleeNumber = extras.getString("callee_number") ?: ""
        val callTypeStr = extras.getString("call_type") ?: "AUDIO"
        val callType = try { CallType.valueOf(callTypeStr) } catch (_: Exception) { CallType.AUDIO }

        val connection = LksCallConnection(callId, calleeName, calleeNumber, callType, isIncoming = false)
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
        WebRtcEngine.getInstanceIfCreated()?.endCall()
    }

    override fun onDisconnect() {
        Log.i("LksCallConnection", "🎯 Bluetooth Headset / System hung up the call via Telecom! callId=$callId")
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        destroy()
        WebRtcEngine.getInstanceIfCreated()?.endCall()
    }

    override fun onSilence() {
        Log.i("LksCallConnection", "Ringtone silenced by hardware button on callId=$callId")
    }

    override fun onShowIncomingCallUi() {
        Log.d("LksCallConnection", "onShowIncomingCallUi triggered")
    }
}
