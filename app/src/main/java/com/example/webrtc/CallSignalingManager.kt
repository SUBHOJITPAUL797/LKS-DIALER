package com.example.webrtc

import android.util.Log
import com.example.data.model.CallDto
import com.example.data.model.CallStatus
import com.example.data.model.IceCandidateDto
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import org.webrtc.IceCandidate
import java.util.concurrent.ConcurrentHashMap

/**
 * CallSignalingManager
 * Dedicated manager for WebRTC SDP & ICE candidate signaling over Cloud Firestore.
 * Handles candidate deduplication, remote candidate listening, hold state sync,
 * and automated teardown & cleanup of call documents to prevent database bloat.
 */
class CallSignalingManager(
    private val firestore: FirebaseFirestore
) {
    companion object {
        private const val TAG = "CallSignalingManager"
    }

    private var activeCallListener: ListenerRegistration? = null
    private var iceCandidateListener: ListenerRegistration? = null
    private val sentIceCandidateHashes = ConcurrentHashMap.newKeySet<String>()

    /**
     * Listens to real-time changes on the active call document in Firestore.
     */
    fun listenToCall(
        callId: String,
        onCallUpdate: (CallDto) -> Unit
    ) {
        activeCallListener?.remove()
        activeCallListener = firestore.collection("calls").document(callId)
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                val call = snapshot.toObject(CallDto::class.java)
                if (call != null) {
                    onCallUpdate(call)
                }
            }
    }

    /**
     * Listens for incoming remote ICE candidates (answer candidates for caller, offer candidates for callee).
     */
    fun listenForIceCandidates(
        callId: String,
        isCaller: Boolean,
        onCandidateReceived: (IceCandidateDto) -> Unit
    ) {
        val targetType = if (isCaller) "answerCandidate" else "offerCandidate"
        iceCandidateListener?.remove()
        iceCandidateListener = firestore.collection("calls").document(callId)
            .collection("candidates")
            .whereEqualTo("type", targetType)
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                for (change in snapshot.documentChanges) {
                    if (change.type == DocumentChange.Type.ADDED) {
                        val dto = change.document.toObject(IceCandidateDto::class.java)
                        onCandidateReceived(dto)
                    }
                }
            }
    }

    /**
     * Uploads a gathered local ICE candidate to Firestore with deduplication.
     */
    fun sendIceCandidate(
        callId: String,
        candidate: IceCandidate,
        isCaller: Boolean
    ) {
        val candidateKey = "${candidate.sdpMid}_${candidate.sdpMLineIndex}_${candidate.sdp}"
        if (!sentIceCandidateHashes.add(candidateKey)) {
            return // Skip duplicate candidate write
        }
        val type = if (isCaller) "offerCandidate" else "answerCandidate"
        val candidateDto = IceCandidateDto(
            serverUrl = candidate.serverUrl,
            sdpMid = candidate.sdpMid,
            sdpMLineIndex = candidate.sdpMLineIndex,
            sdpCandidate = candidate.sdp,
            type = type
        )
        firestore.collection("calls").document(callId)
            .collection("candidates").add(candidateDto)
    }

    /**
     * Updates the status of the call document in Firestore.
     */
    fun updateCallStatus(callId: String, status: CallStatus) {
        try {
            firestore.collection("calls").document(callId).update("status", status.name)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update call status: ${e.message}")
        }
    }

    /**
     * Sets or updates the local SDP offer.
     */
    fun updateOfferSdp(callId: String, offerSdp: String) {
        try {
            firestore.collection("calls").document(callId).update("offerSdp", offerSdp)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update offer SDP: ${e.message}")
        }
    }

    /**
     * Sets or updates the local SDP answer.
     */
    fun updateAnswerSdp(callId: String, answerSdp: String) {
        try {
            firestore.collection("calls").document(callId).update("answerSdp", answerSdp)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update answer SDP: ${e.message}")
        }
    }

    /**
     * Syncs call hold state and who initiated hold to Firestore.
     */
    fun updateHoldState(callId: String, onHold: Boolean, heldBy: String?) {
        try {
            firestore.collection("calls").document(callId)
                .update(
                    "onHold", onHold,
                    "isOnHold", onHold,
                    "heldBy", heldBy
                )
                .addOnSuccessListener {
                    Log.i(TAG, "Synced hold state to Firestore: onHold=$onHold, heldBy=$heldBy")
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Failed to sync hold state: ${e.message}")
                }
        } catch (e: Exception) {
            Log.w(TAG, "Exception updating hold state: ${e.message}")
        }
    }

    /**
     * Deletes the call document and its candidate subcollection from Firestore
     * to keep database lean, clean, and zero-bloat.
     */
    fun deleteCallAndCandidates(callId: String) {
        if (callId.isBlank()) return
        Log.i(TAG, "Cleaning up call document & candidates for $callId")
        try {
            val callDoc = firestore.collection("calls").document(callId)
            callDoc.collection("candidates").get().addOnSuccessListener { snapshot ->
                val batch = firestore.batch()
                for (doc in snapshot.documents) {
                    batch.delete(doc.reference)
                }
                batch.delete(callDoc)
                batch.commit().addOnSuccessListener {
                    Log.i(TAG, "Successfully deleted call $callId and ${snapshot.size()} candidates from Firestore")
                }.addOnFailureListener { e ->
                    Log.w(TAG, "Failed to delete call document batch: ${e.message}")
                }
            }.addOnFailureListener {
                callDoc.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception deleting call document: ${e.message}")
        }
    }

    /**
     * Unregisters all active Firestore snapshot listeners and clears candidate cache.
     */
    fun cleanup() {
        activeCallListener?.remove()
        activeCallListener = null
        iceCandidateListener?.remove()
        iceCandidateListener = null
        sentIceCandidateHashes.clear()
    }
}
