package com.example.util

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * CallAudioRecorder
 *
 * Lightweight, crash-proof in-call audio recorder.
 * Captures real-time PCM audio from WebRTC's JavaAudioDeviceModule without opening
 * conflicting AudioRecord instances.
 * Encodes audio into high-fidelity AAC (.m4a) with streamable ADTS framing.
 * If call disconnects abruptly or power is lost, recorded frames up to that exact
 * millisecond remain completely intact and playable.
 */
class CallAudioRecorder private constructor(private val context: Context) {

    companion object {
        private const val TAG = "CallAudioRecorder"
        private const val AAC_BITRATE = 64_000 // 64 kbps (crystal clear voice)
        private const val DEFAULT_SAMPLE_RATE = 16_000
        private const val DEFAULT_CHANNELS = 1

        @Volatile
        private var instance: CallAudioRecorder? = null

        fun getInstance(context: Context): CallAudioRecorder {
            return instance ?: synchronized(this) {
                instance ?: CallAudioRecorder(context.applicationContext).also { instance = it }
            }
        }

        fun getRecordingsDirectory(context: Context): File {
            val dir = File(context.getExternalFilesDir(null), "call_recordings")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

        fun formatDuration(seconds: Int): String {
            val mins = seconds / 60
            val secs = seconds % 60
            return String.format("%02d:%02d", mins, secs)
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDurationSeconds = MutableStateFlow(0)
    val recordingDurationSeconds: StateFlow<Int> = _recordingDurationSeconds.asStateFlow()

    private val _activeRecordingCallId = MutableStateFlow<String?>(null)
    val activeRecordingCallId: StateFlow<String?> = _activeRecordingCallId.asStateFlow()

    private var currentRecordingFile: File? = null
    private var outputStream: FileOutputStream? = null
    private var mediaCodec: MediaCodec? = null
    private var isCodecInitialized = false

    private var currentSampleRate = DEFAULT_SAMPLE_RATE
    private var currentChannels = DEFAULT_CHANNELS
    private var recordingStartTime = 0L

    private val pcmQueue = ConcurrentLinkedQueue<ByteArray>()
    private var encodeJob: Job? = null
    private var timerRunnable: Runnable? = null

    /**
     * Starts recording the ongoing call.
     * @param callId Identifier of the call
     * @param otherPartyName Name of the caller/callee
     * @param otherPartyNumber Phone number of the caller/callee
     * @return true if recording successfully started, false otherwise
     */
    @Synchronized
    fun startRecording(
        callId: String,
        otherPartyName: String = "",
        otherPartyNumber: String = ""
    ): Boolean {
        if (_isRecording.value) {
            Log.w(TAG, "Recording already in progress for callId: ${_activeRecordingCallId.value}")
            return true
        }

        return try {
            val recordingsDir = getRecordingsDirectory(context)
            val cleanCallId = callId.replace(Regex("[^a-zA-Z0-9_]"), "_").take(24)
            val timestamp = System.currentTimeMillis()
            val fileName = "REC_${cleanCallId}_${timestamp}.m4a"
            val file = File(recordingsDir, fileName)

            currentRecordingFile = file
            outputStream = FileOutputStream(file)
            _activeRecordingCallId.value = callId
            recordingStartTime = System.currentTimeMillis()
            _recordingDurationSeconds.value = 0
            _isRecording.value = true
            pcmQueue.clear()
            isCodecInitialized = false

            // Start 1-second UI duration timer
            startDurationTimer()

            // Launch background processing loop
            startEncodeLoop()

            Log.i(TAG, "🎙️ Call recording STARTED: ${file.absolutePath} for callId: $callId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}", e)
            cleanup()
            false
        }
    }

    /**
     * Receives raw PCM audio samples from WebRTC's JavaAudioDeviceModule.
     * Safely enqueues the audio frames for AAC encoding without blocking the WebRTC audio thread.
     */
    fun onPcmSamples(sampleRate: Int, channelCount: Int, pcmData: ByteArray) {
        if (!_isRecording.value || pcmData.isEmpty()) return

        currentSampleRate = if (sampleRate > 0) sampleRate else DEFAULT_SAMPLE_RATE
        currentChannels = if (channelCount in 1..2) channelCount else DEFAULT_CHANNELS

        pcmQueue.offer(pcmData.clone())
    }

    /**
     * Stops the active call recording, flushes codec, and saves the file.
     * @param callId Optional call ID to verify before stopping
     * @return The recorded File if successful, or null
     */
    @Synchronized
    fun stopRecording(callId: String? = null): File? {
        if (!_isRecording.value) return null
        if (callId != null && _activeRecordingCallId.value != null && _activeRecordingCallId.value != callId) {
            Log.w(TAG, "Stop requested for callId=$callId but active recording is for ${_activeRecordingCallId.value}")
            return null
        }

        Log.i(TAG, "⏹️ Stopping call recording for callId: ${_activeRecordingCallId.value}")
        val recordedFile = currentRecordingFile

        try {
            stopDurationTimer()
            _isRecording.value = false

            // Drain remaining queued PCM buffers
            drainRemainingPcm()

            // Close codec & stream safely
            mediaCodec?.apply {
                try {
                    stop()
                    release()
                } catch (e: Exception) {
                    Log.w(TAG, "Error releasing MediaCodec: ${e.message}")
                }
            }
            mediaCodec = null

            outputStream?.apply {
                try {
                    flush()
                    close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing FileOutputStream: ${e.message}")
                }
            }
            outputStream = null

            encodeJob?.cancel()
            encodeJob = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording: ${e.message}", e)
        } finally {
            _activeRecordingCallId.value = null
            _recordingDurationSeconds.value = 0
            isCodecInitialized = false
        }

        // Validate resulting file
        return if (recordedFile != null && recordedFile.exists() && recordedFile.length() > 512) {
            Log.i(TAG, "✅ Call recording saved successfully: ${recordedFile.absolutePath} (${recordedFile.length()} bytes)")
            recordedFile
        } else {
            Log.w(TAG, "Recording file empty or too short, cleaning up")
            recordedFile?.delete()
            null
        }
    }

    /**
     * Checks if recording is active for a specific call.
     */
    fun isRecordingForCall(callId: String): Boolean {
        return _isRecording.value && _activeRecordingCallId.value == callId
    }

    /**
     * Finds the recorded audio file for a given callId or stored path.
     */
    fun findRecordingFile(callId: String, recordingPath: String? = null): File? {
        if (!recordingPath.isNullOrBlank()) {
            val file = File(recordingPath)
            if (file.exists() && file.length() > 0) return file
        }

        val dir = getRecordingsDirectory(context)
        val cleanCallId = callId.replace(Regex("[^a-zA-Z0-9_]"), "_").take(24)
        val matchingFiles = dir.listFiles { _, name ->
            name.startsWith("REC_${cleanCallId}") && (name.endsWith(".m4a") || name.endsWith(".aac"))
        }

        return matchingFiles?.maxByOrNull { it.lastModified() }
    }

    /**
     * Lists all recorded call files stored on the device.
     */
    fun getAllRecordings(): List<CallRecordingInfo> {
        val dir = getRecordingsDirectory(context)
        val files = dir.listFiles { _, name ->
            name.startsWith("REC_") && (name.endsWith(".m4a") || name.endsWith(".aac"))
        } ?: return emptyList()

        return files.sortedByDescending { it.lastModified() }.mapNotNull { file ->
            try {
                val callId = file.name.removePrefix("REC_").substringBeforeLast("_")
                val durationMs = getAudioDurationMs(file)
                val durationSec = (durationMs / 1000).toInt()
                val sizeFormatted = formatFileSize(file.length())

                CallRecordingInfo(
                    file = file,
                    callId = callId,
                    timestamp = file.lastModified(),
                    durationSeconds = durationSec,
                    fileSizeFormatted = sizeFormatted
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Deletes a recording file safely.
     */
    fun deleteRecording(file: File): Boolean {
        return try {
            if (file.exists()) file.delete() else false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete recording: ${e.message}")
            false
        }
    }

    // ─── Encoder Internal Pipeline ───────────────────────────────────────────────

    private fun startDurationTimer() {
        timerRunnable = object : Runnable {
            override fun run() {
                if (_isRecording.value) {
                    val elapsedSec = ((System.currentTimeMillis() - recordingStartTime) / 1000).toInt()
                    _recordingDurationSeconds.value = elapsedSec
                    mainHandler.postDelayed(this, 1000)
                }
            }
        }
        mainHandler.postDelayed(timerRunnable!!, 1000)
    }

    private fun stopDurationTimer() {
        timerRunnable?.let { mainHandler.removeCallbacks(it) }
        timerRunnable = null
    }

    private fun startEncodeLoop() {
        encodeJob?.cancel()
        encodeJob = scope.launch {
            while (_isRecording.value || pcmQueue.isNotEmpty()) {
                val pcmChunk = pcmQueue.poll()
                if (pcmChunk != null) {
                    processPcmChunk(pcmChunk)
                } else {
                    delay(10)
                }
            }
        }
    }

    private fun drainRemainingPcm() {
        while (pcmQueue.isNotEmpty()) {
            val chunk = pcmQueue.poll() ?: break
            processPcmChunk(chunk)
        }
    }

    private fun initCodecIfNeeded() {
        if (isCodecInitialized) return
        try {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                currentSampleRate,
                currentChannels
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AAC_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            isCodecInitialized = true
            Log.i(TAG, "MediaCodec AAC encoder initialized: rate=$currentSampleRate, channels=$currentChannels")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaCodec: ${e.message}", e)
        }
    }

    private fun processPcmChunk(pcmData: ByteArray) {
        initCodecIfNeeded()
        val codec = mediaCodec ?: return

        try {
            val inputIndex = codec.dequeueInputBuffer(10_000L)
            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex)
                inputBuffer?.clear()
                inputBuffer?.put(pcmData)
                val presentationTimeUs = (System.currentTimeMillis() - recordingStartTime) * 1000
                codec.queueInputBuffer(inputIndex, 0, pcmData.size, presentationTimeUs, 0)
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0L)

            while (outputIndex >= 0) {
                val outputBuffer = codec.getOutputBuffer(outputIndex)
                if (outputBuffer != null && bufferInfo.size > 0) {
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        // Prepend 7-byte ADTS header to AAC frame
                        val packetSize = bufferInfo.size + 7
                        val adtsPacket = ByteArray(packetSize)
                        addAdtsHeader(adtsPacket, packetSize, currentSampleRate, currentChannels)

                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        outputBuffer.get(adtsPacket, 7, bufferInfo.size)

                        outputStream?.write(adtsPacket)
                    }
                }
                codec.releaseOutputBuffer(outputIndex, false)
                outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0L)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error processing PCM audio frame: ${e.message}")
        }
    }

    /**
     * Standard 7-byte ADTS header for AAC-LC.
     */
    private fun addAdtsHeader(packet: ByteArray, packetLen: Int, sampleRate: Int, channels: Int) {
        val profile = 2 // AAC LC
        val freqIdx = when (sampleRate) {
            96000 -> 0
            88200 -> 1
            64000 -> 2
            48000 -> 3
            44100 -> 4
            32000 -> 5
            24000 -> 6
            22050 -> 7
            16000 -> 8
            12000 -> 9
            11025 -> 10
            8000 -> 11
            7350 -> 12
            else -> 8 // default 16kHz
        }
        val chanCfg = if (channels in 1..2) channels else 1

        packet[0] = 0xFF.toByte()
        packet[1] = 0xF9.toByte()
        packet[2] = (((profile - 1) shl 6) or (freqIdx shl 2) or (chanCfg shr 2)).toByte()
        packet[3] = (((chanCfg and 3) shl 6) or (packetLen shr 11)).toByte()
        packet[4] = ((packetLen and 0x7FF) shr 3).toByte()
        packet[5] = (((packetLen and 7) shl 5) or 0x1F).toByte()
        packet[6] = 0xFC.toByte()
    }

    private fun getAudioDurationMs(file: File): Long {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            time?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format("%.1f MB", bytes.toDouble() / (1024 * 1024))
        }
    }

    private fun cleanup() {
        stopDurationTimer()
        _isRecording.value = false
        _activeRecordingCallId.value = null
        _recordingDurationSeconds.value = 0
        try { mediaCodec?.release() } catch (_: Exception) {}
        mediaCodec = null
        try { outputStream?.close() } catch (_: Exception) {}
        outputStream = null
        encodeJob?.cancel()
        encodeJob = null
        pcmQueue.clear()
        isCodecInitialized = false
    }
}

/**
 * Metadata for a saved call recording.
 */
data class CallRecordingInfo(
    val file: File,
    val callId: String,
    val timestamp: Long,
    val durationSeconds: Int,
    val fileSizeFormatted: String
)
