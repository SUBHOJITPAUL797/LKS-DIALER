package com.example.data.p2p

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import org.webrtc.*
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

enum class TransferMode { P2P, RELAY, CLOUD }
enum class TransferStatus { CONNECTING, TRANSFERRING, DONE, FAILED, CANCELLED }

data class FileTransferProgress(
    val messageId: String,
    val fileName: String,
    val totalBytes: Long,
    val transferredBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val mode: TransferMode = TransferMode.P2P,
    val status: TransferStatus = TransferStatus.CONNECTING,
    val isIncoming: Boolean = false
) {
    val percent: Int get() = if (totalBytes > 0) ((transferredBytes * 100) / totalBytes).toInt() else 0
    val speedMbps: Float get() = speedBytesPerSec / (1024f * 1024f)
    val etaSeconds: Long
        get() = if (speedBytesPerSec > 0 && totalBytes > transferredBytes)
            (totalBytes - transferredBytes) / speedBytesPerSec else 0L
}

/**
 * P2P file transfer engine using WebRTC DataChannel.
 *
 * Signaling uses a dedicated Firestore collection `p2p_transfers/{sessionId}`
 * (separate from chat inbox) for scalability.
 *
 * Protocol:
 *  - Text message 1: JSON header {type, messageId, fileName, totalBytes, totalChunks}
 *  - Binary messages 2..N: raw file chunks in order (16KB each)
 *
 * Offline fallback is handled by the caller (ChatRepository).
 */
class P2pFileTransfer(private val context: Context) {

    companion object {
        private const val TAG = "P2pFileTransfer"
        private const val CHUNK_SIZE = 16 * 1024            // 16 KB per DataChannel send
        private const val BUFFER_LOW_THRESHOLD = 65536L     // 64 KB backpressure
        private const val ICE_TIMEOUT_MS = 8000L
        private const val COLLECTION = "p2p_transfers"

        @Volatile private var sharedFactory: PeerConnectionFactory? = null
        private val factoryLock = Any()

        private fun getOrCreateFactory(context: Context): PeerConnectionFactory {
            return sharedFactory ?: synchronized(factoryLock) {
                sharedFactory ?: run {
                    PeerConnectionFactory.initialize(
                        PeerConnectionFactory.InitializationOptions
                            .builder(context.applicationContext)
                            .setEnableInternalTracer(false)
                            .createInitializationOptions()
                    )
                    val f = PeerConnectionFactory.builder()
                        .setOptions(PeerConnectionFactory.Options())
                        .createPeerConnectionFactory()
                    sharedFactory = f
                    f
                }
            }
        }

        private val STATIC_ICE_SERVERS: List<PeerConnection.IceServer> = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun3.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun4.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder(
                listOf(
                    "turn:a.relay.metered.ca:80",
                    "turn:a.relay.metered.ca:80?transport=tcp",
                    "turn:a.relay.metered.ca:443",
                    "turn:a.relay.metered.ca:443?transport=tcp"
                )
            ).setUsername("openrelayproject").setPassword("openrelayproject").createIceServer()
        )
    }

    private val firestore = FirebaseFirestore.getInstance()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val listeners = mutableListOf<ListenerRegistration>()

    @Volatile private var isCancelled = false
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null

    // --- Helper: build RTCConfiguration ---
    private fun buildRtcConfig(): PeerConnection.RTCConfiguration =
        PeerConnection.RTCConfiguration(STATIC_ICE_SERVERS).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }

    // --- Helper: coroutine SdpObserver wrappers ---
    private suspend fun PeerConnection.createOfferSuspend(): SessionDescription? =
        suspendCancellableCoroutine { cont ->
            createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) { cont.resume(sdp) {} }
                override fun onCreateFailure(error: String?) {
                    Log.w(TAG, "createOffer failed: $error")
                    cont.resume(null) {}
                }
                override fun onSetSuccess() {}
                override fun onSetFailure(error: String?) {}
            }, MediaConstraints())
        }

    private suspend fun PeerConnection.createAnswerSuspend(): SessionDescription? =
        suspendCancellableCoroutine { cont ->
            createAnswer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) { cont.resume(sdp) {} }
                override fun onCreateFailure(error: String?) {
                    Log.w(TAG, "createAnswer failed: $error")
                    cont.resume(null) {}
                }
                override fun onSetSuccess() {}
                override fun onSetFailure(error: String?) {}
            }, MediaConstraints())
        }

    private suspend fun PeerConnection.setLocalSuspend(sdp: SessionDescription) =
        suspendCancellableCoroutine<Unit> { cont ->
            setLocalDescription(object : SdpObserver {
                override fun onSetSuccess() { cont.resume(Unit) {} }
                override fun onSetFailure(error: String?) {
                    Log.w(TAG, "setLocalDescription failed: $error")
                    cont.resume(Unit) {}
                }
                override fun onCreateSuccess(sdp: SessionDescription?) {}
                override fun onCreateFailure(error: String?) {}
            }, sdp)
        }

    private suspend fun PeerConnection.setRemoteSuspend(sdp: SessionDescription) =
        suspendCancellableCoroutine<Unit> { cont ->
            setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() { cont.resume(Unit) {} }
                override fun onSetFailure(error: String?) {
                    Log.w(TAG, "setRemoteDescription failed: $error")
                    cont.resume(Unit) {}
                }
                override fun onCreateSuccess(sdp: SessionDescription?) {}
                override fun onCreateFailure(error: String?) {}
            }, sdp)
        }

    // --- SENDER: Initiate P2P transfer ---
    /**
     * Called by sender. Returns true if P2P transfer succeeded.
     * Returns false -> caller should fall back to Firestore relay.
     */
    suspend fun sendFile(
        sessionId: String,
        myPhone: String,
        recipientPhone: String,
        file: File,
        onProgress: (FileTransferProgress) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val deferred = CompletableDeferred<Boolean>()

        try {
            val fileName = file.name
            val totalBytes = file.length()
            val totalChunks = ((totalBytes + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()

            onProgress(FileTransferProgress(
                messageId = sessionId, fileName = fileName,
                totalBytes = totalBytes, status = TransferStatus.CONNECTING
            ))

            val factory = getOrCreateFactory(context)
            val rtcConfig = buildRtcConfig()

            val pc = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate ?: return
                    firestore.collection(COLLECTION).document(sessionId)
                        .collection("sender_ice")
                        .add(mapOf(
                            "sdpMid" to (candidate.sdpMid ?: ""),
                            "sdpMLineIndex" to candidate.sdpMLineIndex,
                            "candidate" to candidate.sdp,
                            "ts" to System.currentTimeMillis()
                        ))
                }
                override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                    Log.d(TAG, "Sender ICE: $s")
                    if (s == PeerConnection.IceConnectionState.FAILED && !deferred.isCompleted)
                        deferred.complete(false)
                }
                override fun onConnectionChange(s: PeerConnection.PeerConnectionState?) {
                    Log.d(TAG, "Sender PC: $s")
                    if ((s == PeerConnection.PeerConnectionState.FAILED ||
                            s == PeerConnection.PeerConnectionState.CLOSED) &&
                        !deferred.isCompleted) deferred.complete(false)
                }
                override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
                override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
                override fun onAddStream(s: MediaStream?) {}
                override fun onRemoveStream(s: MediaStream?) {}
                override fun onDataChannel(dc: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            })

            if (pc == null) { deferred.complete(false); return@withContext false }
            peerConnection = pc

            // Create DataChannel BEFORE creating offer
            val dcInit = DataChannel.Init().apply {
                ordered = true
                maxRetransmits = -1
                protocol = "lks-file"
            }
            val dc = pc.createDataChannel("lks-file", dcInit)
            if (dc == null) { deferred.complete(false); cleanup(sessionId); return@withContext false }
            dataChannel = dc

            dc.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(prev: Long) {}
                override fun onStateChange() {
                    Log.d(TAG, "Sender DC state: ${dc.state()}")
                    when (dc.state()) {
                        DataChannel.State.OPEN -> {
                            scope.launch {
                                val success = streamFile(
                                    dc = dc, file = file,
                                    totalChunks = totalChunks,
                                    sessionId = sessionId, fileName = fileName,
                                    totalBytes = totalBytes, onProgress = onProgress
                                )
                                if (!deferred.isCompleted) deferred.complete(success)
                            }
                        }
                        DataChannel.State.CLOSED, DataChannel.State.CLOSING -> {
                            if (!deferred.isCompleted) deferred.complete(false)
                        }
                        else -> {}
                    }
                }
                override fun onMessage(b: DataChannel.Buffer?) {}
            })

            // Create and set local offer
            val offerSdp = pc.createOfferSuspend() ?: run {
                deferred.complete(false); cleanup(sessionId); return@withContext false
            }
            pc.setLocalSuspend(offerSdp)

            // Write session document to dedicated p2p_transfers collection
            firestore.collection(COLLECTION).document(sessionId).set(mapOf(
                "sessionId" to sessionId,
                "senderPhone" to myPhone,
                "recipientPhone" to recipientPhone,
                "fileName" to fileName,
                "fileSize" to totalBytes,
                "offerSdp" to offerSdp.description,
                "status" to "PENDING",
                "timestamp" to System.currentTimeMillis()
            )).await()

            // Listen for answer SDP
            var answerApplied = false
            val answerListener = firestore.collection(COLLECTION).document(sessionId)
                .addSnapshotListener { snap, err ->
                    if (err != null || snap == null || isCancelled) return@addSnapshotListener
                    val answerSdp = snap.getString("answerSdp") ?: return@addSnapshotListener
                    if (answerApplied || answerSdp.isBlank()) return@addSnapshotListener
                    answerApplied = true
                    pc.setRemoteDescription(object : SdpObserver {
                        override fun onSetSuccess() { Log.d(TAG, "Sender: answer applied") }
                        override fun onSetFailure(e: String?) { Log.w(TAG, "Sender set remote fail: $e") }
                        override fun onCreateSuccess(s: SessionDescription?) {}
                        override fun onCreateFailure(e: String?) {}
                    }, SessionDescription(SessionDescription.Type.ANSWER, answerSdp))
                }
            listeners.add(answerListener)

            // Listen for receiver ICE candidates
            val rxIceListener = firestore.collection(COLLECTION).document(sessionId)
                .collection("receiver_ice")
                .addSnapshotListener { snap, err ->
                    if (err != null || snap == null) return@addSnapshotListener
                    for (ch in snap.documentChanges) {
                        if (ch.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                            val d = ch.document
                            pc.addIceCandidate(IceCandidate(
                                d.getString("sdpMid") ?: "",
                                (d.getLong("sdpMLineIndex") ?: 0).toInt(),
                                d.getString("candidate") ?: ""
                            ))
                        }
                    }
                }
            listeners.add(rxIceListener)

            // ICE connection timeout
            scope.launch {
                delay(ICE_TIMEOUT_MS)
                if (!deferred.isCompleted && dc.state() != DataChannel.State.OPEN) {
                    Log.w(TAG, "ICE timeout -- falling back to relay")
                    deferred.complete(false)
                }
            }

            deferred.await()

        } catch (e: Exception) {
            Log.e(TAG, "sendFile error: ${e.message}", e)
            false
        } finally {
            cleanup(sessionId)
        }
    }

    // --- Stream file chunks over open DataChannel ---
    private suspend fun streamFile(
        dc: DataChannel,
        file: File,
        totalChunks: Int,
        sessionId: String,
        fileName: String,
        totalBytes: Long,
        onProgress: (FileTransferProgress) -> Unit
    ): Boolean {
        return try {
            // 1. Send JSON header (text frame)
            val header = JSONObject().apply {
                put("type", "FILE_HEADER")
                put("messageId", sessionId)
                put("fileName", fileName)
                put("totalBytes", totalBytes)
                put("totalChunks", totalChunks)
            }.toString().toByteArray(Charsets.UTF_8)
            dc.send(DataChannel.Buffer(ByteBuffer.wrap(header), false))

            var sentBytes = 0L
            var lastSpeedTs = System.currentTimeMillis()
            var lastSpeedBytes = 0L
            var currentSpeed = 0L

            // 2. Stream file in 16KB binary chunks
            file.inputStream().buffered().use { fis ->
                val buf = ByteArray(CHUNK_SIZE)
                var chunkIndex = 0

                while (true) {
                    if (isCancelled || dc.state() != DataChannel.State.OPEN) return false

                    val read = fis.read(buf)
                    if (read <= 0) break

                    // Backpressure: wait until buffer drains below threshold
                    var waited = 0
                    while (dc.bufferedAmount() > BUFFER_LOW_THRESHOLD) {
                        delay(5)
                        waited += 5
                        if (waited > 30_000 || isCancelled) return false
                    }

                    val chunk = if (read == CHUNK_SIZE) buf.clone() else buf.copyOf(read)
                    dc.send(DataChannel.Buffer(ByteBuffer.wrap(chunk), true))
                    sentBytes += read
                    chunkIndex++

                    // Speed calculation (every ~200ms)
                    val now = System.currentTimeMillis()
                    if (now - lastSpeedTs >= 200) {
                        currentSpeed = ((sentBytes - lastSpeedBytes) * 1000L) / (now - lastSpeedTs).coerceAtLeast(1)
                        lastSpeedTs = now
                        lastSpeedBytes = sentBytes
                    }

                    onProgress(FileTransferProgress(
                        messageId = sessionId, fileName = fileName,
                        totalBytes = totalBytes, transferredBytes = sentBytes,
                        speedBytesPerSec = currentSpeed,
                        mode = TransferMode.P2P, status = TransferStatus.TRANSFERRING
                    ))
                }
            }

            // Mark done
            try {
                firestore.collection(COLLECTION).document(sessionId)
                    .update("status", "DONE").await()
            } catch (_: Exception) {}

            onProgress(FileTransferProgress(
                messageId = sessionId, fileName = fileName,
                totalBytes = totalBytes, transferredBytes = totalBytes,
                mode = TransferMode.P2P, status = TransferStatus.DONE
            ))
            true

        } catch (e: Exception) {
            Log.e(TAG, "streamFile error: ${e.message}", e)
            false
        }
    }

    // --- RECEIVER: Accept incoming P2P file transfer ---
    /**
     * Called when a P2P_OFFER inbox message is received.
     * Creates PeerConnection as answerer, listens to DataChannel, assembles file.
     */
    fun receiveFile(
        sessionId: String,
        outputDir: File,
        onProgress: (FileTransferProgress) -> Unit,
        onComplete: (assembledFile: File?, messageId: String) -> Unit
    ) {
        scope.launch {
            try {
                val sessionDoc = firestore.collection(COLLECTION).document(sessionId).get().await()
                val offerSdpStr = sessionDoc.getString("offerSdp") ?: run {
                    onComplete(null, sessionId); return@launch
                }
                val fileName = sessionDoc.getString("fileName") ?: "received_file"
                val totalBytes = sessionDoc.getLong("fileSize") ?: 0L

                onProgress(FileTransferProgress(
                    messageId = sessionId, fileName = fileName, totalBytes = totalBytes,
                    status = TransferStatus.CONNECTING, mode = TransferMode.P2P, isIncoming = true
                ))

                val factory = getOrCreateFactory(context)
                val rtcConfig = buildRtcConfig()

                // Chunk tracking -- ordered DataChannel so implicit indexing is safe
                val chunksDir = File(context.cacheDir, "p2p_chunks_$sessionId")
                chunksDir.mkdirs()
                var totalChunks = -1
                var receivedChunkCount = 0
                var receivedBytes = 0L
                var lastSpeedTs = System.currentTimeMillis()
                var lastSpeedBytes = 0L
                var currentSpeed = 0L

                val pc = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
                    override fun onIceCandidate(candidate: IceCandidate?) {
                        candidate ?: return
                        firestore.collection(COLLECTION).document(sessionId)
                            .collection("receiver_ice")
                            .add(mapOf(
                                "sdpMid" to (candidate.sdpMid ?: ""),
                                "sdpMLineIndex" to candidate.sdpMLineIndex,
                                "candidate" to candidate.sdp,
                                "ts" to System.currentTimeMillis()
                            ))
                    }
                    override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                        Log.d(TAG, "Receiver ICE: $s")
                    }
                    override fun onDataChannel(dc: DataChannel?) {
                        dc ?: return
                        Log.d(TAG, "Receiver: DataChannel opened: ${dc.label()}")
                        dataChannel = dc

                        dc.registerObserver(object : DataChannel.Observer {
                            override fun onBufferedAmountChange(prev: Long) {}
                            override fun onStateChange() {
                                Log.d(TAG, "Receiver DC: ${dc.state()}")
                                if (dc.state() == DataChannel.State.OPEN) {
                                    onProgress(FileTransferProgress(
                                        messageId = sessionId, fileName = fileName,
                                        totalBytes = totalBytes, status = TransferStatus.TRANSFERRING,
                                        mode = TransferMode.P2P, isIncoming = true
                                    ))
                                }
                            }
                            override fun onMessage(buffer: DataChannel.Buffer?) {
                                buffer ?: return
                                val data = ByteArray(buffer.data.remaining())
                                buffer.data.get(data)

                                if (!buffer.binary) {
                                    // Text = JSON header
                                    try {
                                        val json = JSONObject(String(data, Charsets.UTF_8))
                                        if (json.optString("type") == "FILE_HEADER") {
                                            totalChunks = json.getInt("totalChunks")
                                            Log.d(TAG, "Receiver: expecting $totalChunks chunks for '$fileName'")
                                        }
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Header parse error: ${e.message}")
                                    }
                                } else {
                                    // Binary = file chunk -- write to temp file by sequential index
                                    try {
                                        File(chunksDir, "part_$receivedChunkCount").writeBytes(data)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Chunk write error: ${e.message}"); return
                                    }
                                    receivedChunkCount++
                                    receivedBytes += data.size

                                    val now = System.currentTimeMillis()
                                    if (now - lastSpeedTs >= 200) {
                                        currentSpeed = ((receivedBytes - lastSpeedBytes) * 1000L) / (now - lastSpeedTs).coerceAtLeast(1)
                                        lastSpeedTs = now
                                        lastSpeedBytes = receivedBytes
                                    }

                                    onProgress(FileTransferProgress(
                                        messageId = sessionId, fileName = fileName,
                                        totalBytes = totalBytes, transferredBytes = receivedBytes,
                                        speedBytesPerSec = currentSpeed,
                                        mode = TransferMode.P2P, status = TransferStatus.TRANSFERRING,
                                        isIncoming = true
                                    ))

                                    // Assemble when all chunks received
                                    if (totalChunks > 0 && receivedChunkCount >= totalChunks) {
                                        scope.launch {
                                            try {
                                                if (!outputDir.exists()) outputDir.mkdirs()
                                                val ext = fileName.substringAfterLast('.', "bin")
                                                val outFile = File(outputDir, "doc_${sessionId}.$ext")
                                                FileOutputStream(outFile).use { fos ->
                                                    for (i in 0 until totalChunks) {
                                                        File(chunksDir, "part_$i").takeIf { it.exists() }?.let {
                                                            fos.write(it.readBytes())
                                                            it.delete()
                                                        }
                                                    }
                                                }
                                                try { chunksDir.delete() } catch (_: Exception) {}

                                                onProgress(FileTransferProgress(
                                                    messageId = sessionId, fileName = fileName,
                                                    totalBytes = totalBytes, transferredBytes = totalBytes,
                                                    mode = TransferMode.P2P, status = TransferStatus.DONE, isIncoming = true
                                                ))
                                                onComplete(outFile, sessionId)
                                                cleanup(sessionId)
                                            } catch (e: Exception) {
                                                Log.e(TAG, "Assembly error: ${e.message}", e)
                                                onComplete(null, sessionId)
                                            }
                                        }
                                    }
                                }
                            }
                        })
                    }
                    override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
                    override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
                    override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
                    override fun onAddStream(s: MediaStream?) {}
                    override fun onRemoveStream(s: MediaStream?) {}
                    override fun onRenegotiationNeeded() {}
                    override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?) {}
                    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                    override fun onConnectionChange(s: PeerConnection.PeerConnectionState?) {
                        Log.d(TAG, "Receiver PC: $s")
                        if (s == PeerConnection.PeerConnectionState.FAILED ||
                            s == PeerConnection.PeerConnectionState.CLOSED) {
                            scope.launch { onComplete(null, sessionId) }
                        }
                    }
                })

                if (pc == null) { onComplete(null, sessionId); return@launch }
                peerConnection = pc

                // Set remote offer
                pc.setRemoteSuspend(SessionDescription(SessionDescription.Type.OFFER, offerSdpStr))

                // Create and set local answer
                val answerSdp = pc.createAnswerSuspend() ?: run {
                    onComplete(null, sessionId); cleanup(sessionId); return@launch
                }
                pc.setLocalSuspend(answerSdp)

                // Write answer back to Firestore session
                firestore.collection(COLLECTION).document(sessionId)
                    .update(mapOf("answerSdp" to answerSdp.description, "status" to "ANSWERING"))
                    .await()

                // Listen for sender ICE candidates
                val txIceListener = firestore.collection(COLLECTION).document(sessionId)
                    .collection("sender_ice")
                    .addSnapshotListener { snap, err ->
                        if (err != null || snap == null) return@addSnapshotListener
                        for (ch in snap.documentChanges) {
                            if (ch.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                                val d = ch.document
                                pc.addIceCandidate(IceCandidate(
                                    d.getString("sdpMid") ?: "",
                                    (d.getLong("sdpMLineIndex") ?: 0).toInt(),
                                    d.getString("candidate") ?: ""
                                ))
                            }
                        }
                    }
                listeners.add(txIceListener)

            } catch (e: Exception) {
                Log.e(TAG, "receiveFile error: ${e.message}", e)
                onComplete(null, sessionId)
                cleanup(sessionId)
            }
        }
    }

    /** Cancel an in-progress transfer and clean up. */
    fun cancel(sessionId: String) {
        isCancelled = true
        cleanup(sessionId)
    }

    private fun cleanup(sessionId: String) {
        listeners.forEach { try { it.remove() } catch (_: Exception) {} }
        listeners.clear()
        try { dataChannel?.close() } catch (_: Exception) {}
        try { peerConnection?.close() } catch (_: Exception) {}
        dataChannel = null
        peerConnection = null
        // Clean up Firestore session after delay (give other side time to finish reading)
        scope.launch {
            delay(8000)
            try {
                val sessionRef = firestore.collection(COLLECTION).document(sessionId)
                listOf("sender_ice", "receiver_ice").forEach { sub ->
                    sessionRef.collection(sub).get().await().documents
                        .forEach { it.reference.delete() }
                }
                sessionRef.delete().await()
            } catch (_: Exception) {}
        }
    }

    /** Release scope when no longer needed. */
    fun release() {
        scope.cancel()
    }
}
