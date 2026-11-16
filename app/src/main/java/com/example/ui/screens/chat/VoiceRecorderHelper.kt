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

sealed interface VoiceRecorderPhase {
    data object Idle : VoiceRecorderPhase
    data class Recording(
        val file: File,
        val startedAtMs: Long,
        val durationMs: Long = 0L,
        val amplitudes: List<Float> = emptyList()
    ) : VoiceRecorderPhase
}

sealed interface VoicePlaybackState {
    data object Idle : VoicePlaybackState
    data class Playing(
        val path: String,
        val durationMs: Long,
        val progress: Float = 0f,
        val speed: Float = 1.0f
    ) : VoicePlaybackState
    data class Paused(
        val path: String,
        val durationMs: Long,
        val progress: Float = 0f,
        val speed: Float = 1.0f
    ) : VoicePlaybackState

    val activePath: String?
        get() = when (this) {
            Idle -> null
            is Playing -> path
            is Paused -> path
        }
}

class VoiceRecorderHelper(private val context: Context) {

    companion object {
        private const val TAG = "VoiceRecorderHelper"
        private val PLAYBACK_SPEEDS = floatArrayOf(1.0f, 1.5f, 2.0f)
    }

    private var mediaRecorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null

    private val handler = Handler(Looper.getMainLooper())

    private val _recorderPhase = MutableStateFlow<VoiceRecorderPhase>(VoiceRecorderPhase.Idle)
    val recorderPhase: StateFlow<VoiceRecorderPhase> = _recorderPhase.asStateFlow()

    private val _playbackState = MutableStateFlow<VoicePlaybackState>(VoicePlaybackState.Idle)
    val playbackState: StateFlow<VoicePlaybackState> = _playbackState.asStateFlow()

    // Synchronized compatibility StateFlows derived from canonical states
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDurationMs = MutableStateFlow(0L)
    val recordingDurationMs: StateFlow<Long> = _recordingDurationMs.asStateFlow()

    private val _amplitudeSamples = MutableStateFlow<List<Float>>(emptyList())
    val amplitudeSamples: StateFlow<List<Float>> = _amplitudeSamples.asStateFlow()

    private val _currentPlayingPath = MutableStateFlow<String?>(null)
    val currentPlayingPath: StateFlow<String?> = _currentPlayingPath.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playbackProgress = MutableStateFlow(0f)
    val playbackProgress: StateFlow<Float> = _playbackProgress.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _playbackDurationMs = MutableStateFlow(0L)
    val playbackDurationMs: StateFlow<Long> = _playbackDurationMs.asStateFlow()

    @Synchronized
    private fun commitRecorderPhase(phase: VoiceRecorderPhase) {
        _recorderPhase.value = phase
        when (phase) {
            VoiceRecorderPhase.Idle -> {
                _isRecording.value = false
                _recordingDurationMs.value = 0L
                _amplitudeSamples.value = emptyList()
            }
            is VoiceRecorderPhase.Recording -> {
                _isRecording.value = true
                _recordingDurationMs.value = phase.durationMs
                _amplitudeSamples.value = phase.amplitudes
            }
        }
    }

    @Synchronized
    private fun commitPlaybackState(state: VoicePlaybackState) {
        _playbackState.value = state
        when (state) {
            VoicePlaybackState.Idle -> {
                _isPlaying.value = false
                _currentPlayingPath.value = null
                _playbackProgress.value = 0f
                _playbackDurationMs.value = 0L
            }
            is VoicePlaybackState.Playing -> {
                _isPlaying.value = true
                _currentPlayingPath.value = state.path
                _playbackProgress.value = state.progress
                _playbackDurationMs.value = state.durationMs
                _playbackSpeed.value = state.speed
            }
            is VoicePlaybackState.Paused -> {
                _isPlaying.value = false
                _currentPlayingPath.value = state.path
                _playbackProgress.value = state.progress
                _playbackDurationMs.value = state.durationMs
                _playbackSpeed.value = state.speed
            }
        }
    }

    private val recordingTimerRunnable = object : Runnable {
        override fun run() {
            val phase = _recorderPhase.value
            if (phase is VoiceRecorderPhase.Recording) {
                val raw = try { mediaRecorder?.maxAmplitude ?: 0 } catch (_: Exception) { 0 }
                val normalized = (raw.toFloat() / 32767f).coerceIn(0.05f, 1f)
                val updatedAmps = (phase.amplitudes + normalized).takeLast(40)
                commitRecorderPhase(
                    phase.copy(
                        durationMs = System.currentTimeMillis() - phase.startedAtMs,
                        amplitudes = updatedAmps
                    )
                )
                handler.postDelayed(this, 100)
            }
        }
    }

    private val playbackTimerRunnable = object : Runnable {
        override fun run() {
            val player = mediaPlayer
            val state = _playbackState.value
            if (player != null && state is VoicePlaybackState.Playing && player.isPlaying) {
                val total = player.duration
                val progress = if (total > 0) (player.currentPosition.toFloat() / total.toFloat()).coerceIn(0f, 1f) else state.progress
                commitPlaybackState(state.copy(progress = progress))
                handler.postDelayed(this, 100)
            } else if (state is VoicePlaybackState.Playing) {
                commitPlaybackState(VoicePlaybackState.Idle)
            }
        }
    }

    fun startRecording(): Boolean {
        if (_recorderPhase.value is VoiceRecorderPhase.Recording) return true
        return try {
            stopPlaying()

            val dir = File(context.cacheDir, "voice_recordings").apply { mkdirs() }
            val file = File(dir, "voice_${System.currentTimeMillis()}.m4a")

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
            commitRecorderPhase(
                VoiceRecorderPhase.Recording(
                    file = file,
                    startedAtMs = System.currentTimeMillis()
                )
            )
            handler.post(recordingTimerRunnable)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}", e)
            cancelRecording()
            false
        }
    }

    fun stopRecording(): Pair<File, Long>? {
        val phase = _recorderPhase.value as? VoiceRecorderPhase.Recording ?: return null
        val duration = System.currentTimeMillis() - phase.startedAtMs
        val file = phase.file

        handler.removeCallbacks(recordingTimerRunnable)
        commitRecorderPhase(VoiceRecorderPhase.Idle)

        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping recorder: ${e.message}")
        }
        mediaRecorder = null

        return if (file.exists() && file.length() > 0 && duration >= 800) {
            Pair(file, duration)
        } else {
            file.delete()
            null
        }
    }

    fun cancelRecording() {
        val fileToDelete = (_recorderPhase.value as? VoiceRecorderPhase.Recording)?.file
        handler.removeCallbacks(recordingTimerRunnable)
        commitRecorderPhase(VoiceRecorderPhase.Idle)
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (_: Exception) {}
        mediaRecorder = null
        fileToDelete?.delete()
    }

    fun playAudio(path: String) {
        val file = File(path)
        if (!file.exists()) return

        val activePlayer = mediaPlayer
        when (val current = _playbackState.value) {
            is VoicePlaybackState.Playing if current.path == path && activePlayer != null -> {
                if (activePlayer.isPlaying) activePlayer.pause()
                handler.removeCallbacks(playbackTimerRunnable)
                val progress = if (current.durationMs > 0L) {
                    (activePlayer.currentPosition.toFloat() / current.durationMs.toFloat()).coerceIn(0f, 1f)
                } else current.progress
                commitPlaybackState(
                    VoicePlaybackState.Paused(
                        path = current.path,
                        durationMs = current.durationMs,
                        progress = progress,
                        speed = current.speed
                    )
                )
                return
            }
            is VoicePlaybackState.Paused if current.path == path && activePlayer != null -> {
                activePlayer.start()
                commitPlaybackState(
                    VoicePlaybackState.Playing(
                        path = current.path,
                        durationMs = current.durationMs,
                        progress = current.progress,
                        speed = current.speed
                    )
                )
                handler.post(playbackTimerRunnable)
                return
            }
            else -> Unit
        }

        stopPlaying()

        val speedPref = _playbackSpeed.value
        try {
            val player = MediaPlayer().apply {
                setDataSource(path)
                prepare()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && speedPref != 1.0f) {
                    try {
                        playbackParams = playbackParams.setSpeed(speedPref)
                    } catch (_: Exception) {}
                }
                setOnCompletionListener {
                    handler.removeCallbacks(playbackTimerRunnable)
                    commitPlaybackState(VoicePlaybackState.Idle)
                }
                start()
            }
            mediaPlayer = player
            commitPlaybackState(
                VoicePlaybackState.Playing(
                    path = path,
                    durationMs = player.duration.toLong().coerceAtLeast(0L),
                    progress = 0f,
                    speed = speedPref
                )
            )
            handler.post(playbackTimerRunnable)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play audio: ${e.message}", e)
            stopPlaying()
        }
    }

    fun cyclePlaybackSpeed() {
        val currentIndex = PLAYBACK_SPEEDS.indexOfFirst { it == _playbackSpeed.value }.takeIf { it >= 0 } ?: 0
        val nextSpeed = PLAYBACK_SPEEDS[(currentIndex + 1) % PLAYBACK_SPEEDS.size]
        _playbackSpeed.value = nextSpeed
        when (val state = _playbackState.value) {
            is VoicePlaybackState.Playing -> commitPlaybackState(state.copy(speed = nextSpeed))
            is VoicePlaybackState.Paused -> commitPlaybackState(state.copy(speed = nextSpeed))
            VoicePlaybackState.Idle -> Unit
        }
        try {
            val activePlayer = mediaPlayer
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && activePlayer != null) {
                val params = activePlayer.playbackParams
                activePlayer.playbackParams = params.setSpeed(nextSpeed)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update playback speed: ${e.message}")
        }
    }

    fun seekTo(ratio: Float) {
        val player = mediaPlayer ?: return
        try {
            val dur = player.duration
            if (dur > 0) {
                val clamped = ratio.coerceIn(0f, 1f)
                val targetMs = (clamped * dur).toInt()
                player.seekTo(targetMs)
                when (val state = _playbackState.value) {
                    is VoicePlaybackState.Playing -> commitPlaybackState(state.copy(progress = clamped))
                    is VoicePlaybackState.Paused -> commitPlaybackState(state.copy(progress = clamped))
                    VoicePlaybackState.Idle -> Unit
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to seek audio: ${e.message}")
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
        commitPlaybackState(VoicePlaybackState.Idle)
    }

    fun release() {
        cancelRecording()
        stopPlaying()
    }
}
