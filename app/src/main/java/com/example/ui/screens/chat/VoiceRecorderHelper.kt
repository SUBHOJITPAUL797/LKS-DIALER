package com.example.ui.screens.chat

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class VoiceRecorderHelper(private val context: Context) {

    companion object {
        private const val TAG = "VoiceRecorderHelper"
    }

    private var mediaRecorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentRecordingFile: File? = null
    private var recordingStartTime = 0L

    private val handler = Handler(Looper.getMainLooper())

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDurationMs = MutableStateFlow(0L)
    val recordingDurationMs: StateFlow<Long> = _recordingDurationMs.asStateFlow()

    private val _currentPlayingPath = MutableStateFlow<String?>(null)
    val currentPlayingPath: StateFlow<String?> = _currentPlayingPath.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playbackProgress = MutableStateFlow(0f)
    val playbackProgress: StateFlow<Float> = _playbackProgress.asStateFlow()

    private val recordingTimerRunnable = object : Runnable {
        override fun run() {
            if (_isRecording.value) {
                _recordingDurationMs.value = System.currentTimeMillis() - recordingStartTime
                handler.postDelayed(this, 100)
            }
        }
    }

    private val playbackTimerRunnable = object : Runnable {
        override fun run() {
            val player = mediaPlayer
            if (player != null && player.isPlaying) {
                val current = player.currentPosition
                val total = player.duration
                if (total > 0) {
                    _playbackProgress.value = current.toFloat() / total.toFloat()
                }
                handler.postDelayed(this, 100)
            } else {
                _isPlaying.value = false
                _playbackProgress.value = 0f
            }
        }
    }

    fun startRecording(): Boolean {
        try {
            stopPlaying()

            val dir = File(context.cacheDir, "voice_recordings").apply { mkdirs() }
            val file = File(dir, "voice_${System.currentTimeMillis()}.m4a")
            currentRecordingFile = file

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64000)
                setAudioSamplingRate(44100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            mediaRecorder = recorder
            recordingStartTime = System.currentTimeMillis()
            _isRecording.value = true
            _recordingDurationMs.value = 0L
            handler.post(recordingTimerRunnable)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}", e)
            cancelRecording()
            return false
        }
    }

    fun stopRecording(): Pair<File, Long>? {
        if (!_isRecording.value) return null
        val duration = System.currentTimeMillis() - recordingStartTime
        val file = currentRecordingFile

        handler.removeCallbacks(recordingTimerRunnable)
        _isRecording.value = false
        _recordingDurationMs.value = 0L

        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping recorder: ${e.message}")
        }
        mediaRecorder = null

        return if (file != null && file.exists() && file.length() > 0 && duration >= 800) {
            Pair(file, duration)
        } else {
            file?.delete()
            null
        }
    }

    fun cancelRecording() {
        handler.removeCallbacks(recordingTimerRunnable)
        _isRecording.value = false
        _recordingDurationMs.value = 0L
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (_: Exception) {}
        mediaRecorder = null
        currentRecordingFile?.delete()
        currentRecordingFile = null
    }

    fun playAudio(path: String) {
        val file = File(path)
        if (!file.exists()) return

        if (_currentPlayingPath.value == path && mediaPlayer != null) {
            if (mediaPlayer!!.isPlaying) {
                mediaPlayer!!.pause()
                _isPlaying.value = false
                handler.removeCallbacks(playbackTimerRunnable)
            } else {
                mediaPlayer!!.start()
                _isPlaying.value = true
                handler.post(playbackTimerRunnable)
            }
            return
        }

        stopPlaying()

        try {
            val player = MediaPlayer().apply {
                setDataSource(path)
                prepare()
                setOnCompletionListener {
                    _isPlaying.value = false
                    _playbackProgress.value = 0f
                    _currentPlayingPath.value = null
                    handler.removeCallbacks(playbackTimerRunnable)
                }
                start()
            }
            mediaPlayer = player
            _currentPlayingPath.value = path
            _isPlaying.value = true
            handler.post(playbackTimerRunnable)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play audio: ${e.message}", e)
            stopPlaying()
        }
    }

    fun stopPlaying() {
        handler.removeCallbacks(playbackTimerRunnable)
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
        } catch (_: Exception) {}
        mediaPlayer = null
        _isPlaying.value = false
        _playbackProgress.value = 0f
        _currentPlayingPath.value = null
    }

    fun release() {
        cancelRecording()
        stopPlaying()
    }
}
