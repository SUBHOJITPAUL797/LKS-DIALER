package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.VideoView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.example.ui.theme.TealPrimary
import java.io.File
import java.util.Locale

@Composable
fun VideoPlayerDialog(
    videoFile: File,
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(true) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }
    var mediaPlayerRef by remember { mutableStateOf<MediaPlayer?>(null) }
    val handler = remember { Handler(Looper.getMainLooper()) }

    var isDragging by remember { mutableStateOf(false) }
    var dragPositionMs by remember { mutableLongStateOf(0L) }
    var wasPlayingBeforeDrag by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }

    fun performExactSeek(targetMs: Long) {
        val clamped = targetMs.coerceIn(0L, durationMs.coerceAtLeast(0L))
        val mp = mediaPlayerRef
        try {
            if (mp != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // MediaPlayer.SEEK_CLOSEST forces decoder to decode up to the exact requested frame,
                // preventing keyframe snapping (e.g., jumping from 46s back to 40s).
                mp.seekTo(clamped, MediaPlayer.SEEK_CLOSEST)
            } else {
                videoViewRef?.seekTo(clamped.toInt())
            }
        } catch (_: Exception) {
            try {
                videoViewRef?.seekTo(clamped.toInt())
            } catch (_: Exception) {}
        }
    }

    // Position updater runnable
    DisposableEffect(Unit) {
        val runnable = object : Runnable {
            override fun run() {
                if (!isDragging) {
                    videoViewRef?.let { vv ->
                        try {
                            if (vv.isPlaying) {
                                currentPositionMs = vv.currentPosition.toLong().coerceAtLeast(0L)
                                isPlaying = true
                            } else if (isPlaying && currentPositionMs >= durationMs && durationMs > 0) {
                                isPlaying = false
                            }
                        } catch (_: Exception) {}
                    }
                }
                handler.postDelayed(this, 200)
            }
        }
        handler.post(runnable)
        onDispose {
            handler.removeCallbacks(runnable)
            try {
                videoViewRef?.stopPlayback()
            } catch (_: Exception) {}
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // Video Surface
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .align(Alignment.Center),
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setVideoURI(Uri.fromFile(videoFile))
                        setOnPreparedListener { mp ->
                            mediaPlayerRef = mp
                            mp.isLooping = false
                            durationMs = mp.duration.toLong().coerceAtLeast(0L)
                            mp.setOnSeekCompleteListener { completedMp ->
                                if (!isDragging) {
                                    try {
                                        currentPositionMs = completedMp.currentPosition.toLong().coerceAtLeast(0L)
                                    } catch (_: Exception) {}
                                }
                            }
                            start()
                            isPlaying = true
                        }
                        setOnCompletionListener {
                            isPlaying = false
                            currentPositionMs = durationMs
                        }
                        setOnErrorListener { _, _, _ ->
                            isPlaying = false
                            true
                        }
                        videoViewRef = this
                    }
                },
                update = { vv ->
                    videoViewRef = vv
                }
            )

            // Transparent tap area to toggle controls on/off
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        showControls = !showControls
                    }
            )

            // Top bar controls: Back button, Title, External Open / Share
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.65f))
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismissRequest) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Close",
                            tint = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = videoFile.name,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    // Open in external app button
                    IconButton(
                        onClick = {
                            try {
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    videoFile
                                )
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, "video/*")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                val chooser = Intent.createChooser(intent, "Open video with...")
                                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(chooser)
                            } catch (_: Exception) {}
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = "Open with external app",
                            tint = Color.White
                        )
                    }
                }
            }

            // Center controls: [Replay 10s] [Play/Pause] [Forward 10s]
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Replay 10s button
                    IconButton(
                        onClick = {
                            val newPos = (currentPositionMs - 10_000L).coerceAtLeast(0L)
                            currentPositionMs = newPos
                            performExactSeek(newPos)
                        },
                        modifier = Modifier
                            .size(50.dp)
                            .background(Color.Black.copy(alpha = 0.5f), shape = CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Replay10,
                            contentDescription = "Replay 10 seconds",
                            tint = Color.White,
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    // Play / Pause center button
                    IconButton(
                        onClick = {
                            videoViewRef?.let { vv ->
                                if (vv.isPlaying) {
                                    vv.pause()
                                    isPlaying = false
                                } else {
                                    if (currentPositionMs >= durationMs && durationMs > 0) {
                                        performExactSeek(0L)
                                        currentPositionMs = 0L
                                    }
                                    vv.start()
                                    isPlaying = true
                                }
                            }
                        },
                        modifier = Modifier
                            .size(68.dp)
                            .background(Color.Black.copy(alpha = 0.6f), shape = CircleShape)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(40.dp)
                        )
                    }

                    // Forward 10s button
                    IconButton(
                        onClick = {
                            val newPos = (currentPositionMs + 10_000L).coerceAtMost(durationMs)
                            currentPositionMs = newPos
                            performExactSeek(newPos)
                        },
                        modifier = Modifier
                            .size(50.dp)
                            .background(Color.Black.copy(alpha = 0.5f), shape = CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Forward10,
                            contentDescription = "Forward 10 seconds",
                            tint = Color.White,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }
            }

            // Bottom controls: Scrubber + Time Display
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.75f))
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    val displayMs = if (isDragging) dragPositionMs else currentPositionMs
                    val sliderValue = if (durationMs > 0) {
                        (displayMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                    } else 0f

                    Slider(
                        value = sliderValue,
                        onValueChange = { ratio ->
                            if (!isDragging) {
                                isDragging = true
                                wasPlayingBeforeDrag = videoViewRef?.isPlaying == true
                                if (wasPlayingBeforeDrag) {
                                    videoViewRef?.pause()
                                }
                            }
                            if (durationMs > 0) {
                                dragPositionMs = (ratio * durationMs).toLong().coerceIn(0L, durationMs)
                            }
                        },
                        onValueChangeFinished = {
                            val targetMs = dragPositionMs
                            currentPositionMs = targetMs
                            performExactSeek(targetMs)
                            if (wasPlayingBeforeDrag) {
                                videoViewRef?.start()
                                isPlaying = true
                            }
                            isDragging = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor = TealPrimary,
                            activeTrackColor = TealPrimary,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatTime(displayMs),
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = formatTime(durationMs),
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
}
