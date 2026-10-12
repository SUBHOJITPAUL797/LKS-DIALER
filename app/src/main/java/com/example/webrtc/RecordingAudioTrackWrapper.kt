package com.example.webrtc

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import com.example.util.CallAudioRecorder
import java.nio.ByteBuffer

/**
 * RecordingAudioTrackWrapper
 *
 * Transparent delegating wrapper around Android's AudioTrack used by WebRTC's WebRtcAudioTrack.
 * Taps playout PCM frames directly from WebRTC without interfering with hardware audio routing.
 * Ensures the remote party's voice is captured at pristine studio quality for call recording.
 */
class RecordingAudioTrackWrapper(
    private val delegate: AudioTrack,
    private val context: Context
) : AudioTrack(
    AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build(),
    AudioFormat.Builder()
        .setSampleRate(if (delegate.sampleRate > 0) delegate.sampleRate else 16000)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setChannelMask(if (delegate.channelCount == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO)
        .build(),
    maxOf(
        try {
            AudioTrack.getMinBufferSize(
                if (delegate.sampleRate > 0) delegate.sampleRate else 16000,
                if (delegate.channelCount == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
        } catch (_: Exception) {
            1024
        },
        1024
    ),
    AudioTrack.MODE_STREAM,
    AudioManager.AUDIO_SESSION_ID_GENERATE
) {

    companion object {
        private const val TAG = "RecAudioTrack"
    }

    init {
        try {
            // Mute the dummy wrapper track so it never produces extraneous output or conflicts
            setVolume(0f)
        } catch (_: Exception) {}
    }

    override fun write(audioData: ByteBuffer, sizeInBytes: Int, writeMode: Int): Int {
        try {
            val recorder = CallAudioRecorder.getInstance(context)
            if (recorder.isRecording.value && sizeInBytes > 0) {
                val pcm = ByteArray(sizeInBytes)
                val originalPos = audioData.position()
                audioData.get(pcm)
                audioData.position(originalPos) // Rewind so delegate.write() can read
                recorder.onRemotePcmSamples(delegate.sampleRate, delegate.channelCount, pcm)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error tapping playout ByteBuffer: ${e.message}")
        }
        return delegate.write(audioData, sizeInBytes, writeMode)
    }

    override fun write(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int {
        try {
            val recorder = CallAudioRecorder.getInstance(context)
            if (recorder.isRecording.value && sizeInBytes > 0) {
                val pcm = audioData.copyOfRange(offsetInBytes, offsetInBytes + sizeInBytes)
                recorder.onRemotePcmSamples(delegate.sampleRate, delegate.channelCount, pcm)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error tapping playout ByteArray: ${e.message}")
        }
        return delegate.write(audioData, offsetInBytes, sizeInBytes)
    }

    override fun write(audioData: ShortArray, offsetInShorts: Int, sizeInShorts: Int): Int {
        try {
            val recorder = CallAudioRecorder.getInstance(context)
            if (recorder.isRecording.value && sizeInShorts > 0) {
                val pcm = ByteArray(sizeInShorts * 2)
                for (i in 0 until sizeInShorts) {
                    val s = audioData[offsetInShorts + i].toInt()
                    pcm[i * 2] = (s and 0xFF).toByte()
                    pcm[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                }
                recorder.onRemotePcmSamples(delegate.sampleRate, delegate.channelCount, pcm)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error tapping playout ShortArray: ${e.message}")
        }
        return delegate.write(audioData, offsetInShorts, sizeInShorts)
    }

    override fun play() {
        try { delegate.play() } catch (_: Exception) {}
    }

    override fun stop() {
        try { delegate.stop() } catch (_: Exception) {}
    }

    override fun pause() {
        try { delegate.pause() } catch (_: Exception) {}
    }

    override fun flush() {
        try { delegate.flush() } catch (_: Exception) {}
    }

    override fun release() {
        try { super.release() } catch (_: Exception) {}
        try { delegate.release() } catch (_: Exception) {}
    }

    override fun getPlayState(): Int = delegate.playState
    override fun getState(): Int = delegate.state
    override fun getSampleRate(): Int = delegate.sampleRate
    override fun getChannelCount(): Int = delegate.channelCount
    override fun getAudioFormat(): Int = delegate.audioFormat
    override fun getStreamType(): Int = delegate.streamType

    override fun getBufferCapacityInFrames(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            delegate.bufferCapacityInFrames
        } else {
            0
        }
    }

    override fun getBufferSizeInFrames(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            delegate.bufferSizeInFrames
        } else {
            0
        }
    }

    override fun setBufferSizeInFrames(bufferSizeInFrames: Int): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            delegate.setBufferSizeInFrames(bufferSizeInFrames)
        } else {
            0
        }
    }

    override fun getUnderrunCount(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            delegate.underrunCount
        } else {
            0
        }
    }
}
