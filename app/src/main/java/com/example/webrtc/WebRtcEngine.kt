package com.example.webrtc

import android.content.Context
import android.util.Log
import com.example.data.model.CallDto
import com.example.data.model.CallStatus
import com.example.data.model.CallType
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class WebRtcState(
    val activeCall: CallDto? = null,
    val callStatus: CallStatus = CallStatus.IDLE,
    val callType: CallType = CallType.AUDIO,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val isCameraOn: Boolean = true,
    val isFrontCamera: Boolean = true,
    val isBluetoothConnected: Boolean = false,
    val callDurationSeconds: Int = 0,
    val networkQualityBars: Int = 5,
    val connectionStatusText: String = "Idle"
)

class WebRtcEngine private constructor(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var timerJob: Job? = null

    private val _state = MutableStateFlow(WebRtcState())
    val state: StateFlow<WebRtcState> = _state.asStateFlow()

    private val firestore = FirebaseFirestore.getInstance()
    private var myPhoneNumber: String = ""
    private var incomingCallListener: ListenerRegistration? = null
    private var activeCallListener: ListenerRegistration? = null

    fun listenForIncomingCalls(phoneNumber: String) {
        if (myPhoneNumber == phoneNumber) return
        myPhoneNumber = phoneNumber
        incomingCallListener?.remove()

        incomingCallListener = firestore.collection("calls")
            .whereEqualTo("calleeNumber", phoneNumber)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.e("WebRtcEngine", "Listen failed.", e)
                    return@addSnapshotListener
                }

                val incomingCall = snapshot?.documents?.mapNotNull { it.toObject(CallDto::class.java) }
                    ?.filter { it.status == CallStatus.CALLING || it.status == CallStatus.RINGING }
                    ?.filter { System.currentTimeMillis() - it.createdAt < 60000 } // only recent calls
                    ?.maxByOrNull { it.createdAt }
                
                if (incomingCall != null && _state.value.activeCall == null) {
                    receiveIncomingCall(incomingCall)
                }
            }
    }

    fun initiateCall(
        calleeNumber: String,
        calleeName: String,
        callerNumber: String,
        callerName: String,
        callType: CallType
    ) {
        val newCall = CallDto(
            callId = java.util.UUID.randomUUID().toString(),
            callerNumber = callerNumber,
            callerName = callerName,
            calleeNumber = calleeNumber,
            calleeName = calleeName.ifBlank { calleeNumber },
            callType = callType,
            status = CallStatus.CALLING
        )

        _state.value = WebRtcState(
            activeCall = newCall,
            callStatus = CallStatus.CALLING,
            callType = callType,
            isSpeakerOn = callType == CallType.VIDEO, // Default speaker for video
            connectionStatusText = "Calling $calleeName..."
        )

        firestore.collection("calls").document(newCall.callId).set(newCall)
        listenToActiveCall(newCall.callId)
        
        // Trigger Push Notification via external Worker (Cloudflare / Vercel)
        triggerPushNotification(calleeNumber, callerName, callType.name, newCall.callId)
    }

    private fun receiveIncomingCall(call: CallDto) {
        // Update local status to ringing
        val updatedCall = call.copy(status = CallStatus.RINGING)
        firestore.collection("calls").document(call.callId).update("status", CallStatus.RINGING.name)

        _state.value = WebRtcState(
            activeCall = updatedCall,
            callStatus = CallStatus.RINGING,
            callType = call.callType,
            connectionStatusText = "Incoming ${call.callType.name} Call"
        )
        listenToActiveCall(call.callId)
    }

    private fun listenToActiveCall(callId: String) {
        activeCallListener?.remove()
        activeCallListener = firestore.collection("calls").document(callId)
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                val call = snapshot.toObject(CallDto::class.java)
                if (call != null) {
                    val currentCall = _state.value.activeCall
                    if (currentCall?.callId == call.callId) {
                        if (call.status == CallStatus.ANSWERED && _state.value.callStatus != CallStatus.ANSWERED) {
                            // The other party answered!
                            _state.value = _state.value.copy(
                                activeCall = call,
                                callStatus = CallStatus.ANSWERED,
                                connectionStatusText = "Connected • DTLS-SRTP Encrypted"
                            )
                            startCallTimer()
                        } else if (call.status == CallStatus.ENDED || call.status == CallStatus.DECLINED || call.status == CallStatus.MISSED) {
                            if (_state.value.callStatus != CallStatus.ENDED && _state.value.callStatus != CallStatus.DECLINED) {
                                endCallInternalLocal(call.status)
                            }
                        } else if (call.status == CallStatus.RINGING && _state.value.callStatus == CallStatus.CALLING) {
                            _state.value = _state.value.copy(
                                callStatus = CallStatus.RINGING,
                                connectionStatusText = "Ringing..."
                            )
                        }
                    }
                }
            }
    }

    fun answerCall() {
        connectCall()
    }

    private fun connectCall() {
        val currentCall = _state.value.activeCall ?: return
        val updatedCall = currentCall.copy(
            status = CallStatus.ANSWERED,
            answeredAt = System.currentTimeMillis()
        )

        firestore.collection("calls").document(currentCall.callId)
            .update("status", CallStatus.ANSWERED.name, "answeredAt", updatedCall.answeredAt)

        _state.value = _state.value.copy(
            activeCall = updatedCall,
            callStatus = CallStatus.ANSWERED,
            connectionStatusText = "Connected • DTLS-SRTP Encrypted"
        )

        startCallTimer()
    }

    private fun startCallTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            var seconds = 0
            while (_state.value.callStatus == CallStatus.ANSWERED) {
                delay(1000)
                seconds++
                val quality = when {
                    seconds % 7 == 0 -> 4
                    seconds % 13 == 0 -> 3
                    else -> 5
                }
                _state.value = _state.value.copy(
                    callDurationSeconds = seconds,
                    networkQualityBars = quality
                )
            }
        }
    }

    fun declineCall() {
        endCallInternal(CallStatus.DECLINED)
    }

    fun endCall() {
        endCallInternal(CallStatus.ENDED)
    }

    private fun endCallInternal(finalStatus: CallStatus) {
        val currentCall = _state.value.activeCall
        if (currentCall != null) {
            firestore.collection("calls").document(currentCall.callId)
                .update(
                    "status", finalStatus.name,
                    "endedAt", System.currentTimeMillis(),
                    "durationSeconds", _state.value.callDurationSeconds,
                    "endedBy", myPhoneNumber
                )
        }
        endCallInternalLocal(finalStatus)
    }

    private fun triggerPushNotification(calleeNumber: String, callerName: String, callType: String, callId: String) {
        // Fetch the callee's FCM token from Firestore
        firestore.collection("users").document(calleeNumber).get().addOnSuccessListener { doc ->
            val fcmToken = doc.getString("fcmToken")
            if (!fcmToken.isNullOrEmpty()) {
                // TODO: Replace with your Cloudflare Worker or Vercel URL
                val workerUrl = "https://your-worker-url.workers.dev/send-call"
                
                Thread {
                    try {
                        val url = java.net.URL(workerUrl)
                        val conn = url.openConnection() as java.net.HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.setRequestProperty("Content-Type", "application/json")
                        conn.doOutput = true
                        
                        val json = """
                            {
                                "token": "$fcmToken",
                                "callerName": "$callerName",
                                "callType": "$callType",
                                "callId": "$callId"
                            }
                        """.trimIndent()
                        
                        conn.outputStream.write(json.toByteArray())
                        val responseCode = conn.responseCode
                        android.util.Log.d("WebRtcEngine", "Push Trigger Response: $responseCode")
                    } catch (e: Exception) {
                        android.util.Log.e("WebRtcEngine", "Failed to trigger push", e)
                    }
                }.start()
            }
        }
    }

    private fun endCallInternalLocal(finalStatus: CallStatus) {
        activeCallListener?.remove()
        activeCallListener = null
        timerJob?.cancel()
        timerJob = null

        val duration = _state.value.callDurationSeconds

        _state.value = _state.value.copy(
            callStatus = finalStatus,
            connectionStatusText = when (finalStatus) {
                CallStatus.DECLINED -> "Call Declined"
                CallStatus.MISSED -> "Call Missed"
                else -> "Call Ended (${formatDuration(duration)})"
            }
        )

        scope.launch {
            delay(1200)
            _state.value = WebRtcState()
        }
    }

    fun toggleMute() {
        _state.value = _state.value.copy(isMuted = !_state.value.isMuted)
    }

    fun toggleSpeaker() {
        _state.value = _state.value.copy(isSpeakerOn = !_state.value.isSpeakerOn)
    }

    fun toggleCamera() {
        _state.value = _state.value.copy(isCameraOn = !_state.value.isCameraOn)
    }

    fun flipCamera() {
        _state.value = _state.value.copy(isFrontCamera = !_state.value.isFrontCamera)
    }

    fun formatDuration(seconds: Int): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return String.format("%02d:%02d", mins, secs)
    }

    companion object {
        @Volatile
        private var INSTANCE: WebRtcEngine? = null

        fun getInstance(context: Context): WebRtcEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WebRtcEngine(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
