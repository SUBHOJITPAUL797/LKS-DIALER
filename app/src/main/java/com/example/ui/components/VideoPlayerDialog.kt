package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    val handler = remember { Handler(Looper.getMainLooper()) }

    // Position updater runnable
    DisposableEffect(Unit) {
        val runnable = object : Runnable {
            override fun run() {
                videoViewRef?.let { vv ->
                    if (vv.isPlaying) {
                        currentPositionMs = vv.currentPosition.toLong().coerceAtLeast(0L)
                        isPlaying = true
                    } else if (isPlaying && currentPositionMs >= durationMs && durationMs > 0) {
                        isPlaying = false
                    }
                }
                handler.postDelayed(this, 250)
            }
        }
        handler.post(runnable)
        onDispose {
            handler.removeCallbacks(runnable)
            videoViewRef?.stopPlayback()
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
                            mp.isLooping = false
                            durationMs = mp.duration.toLong().coerceAtLeast(0L)
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

            // Top bar controls: Back button, Title, External Open / Share
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismissRequest) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
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
                        imageVector = Icons.Default.OpenInNew,
                        contentDescription = "Open with external app",
                        tint = Color.White
                    )
                }
            }

            // Center Play / Pause tap overlay button
            IconButton(
                onClick = {
                    videoViewRef?.let { vv ->
                        if (vv.isPlaying) {
                            vv.pause()
                            isPlaying = false
                        } else {
                            if (currentPositionMs >= durationMs && durationMs > 0) {
                                vv.seekTo(0)
                                currentPositionMs = 0
                            }
                            vv.start()
                            isPlaying = true
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(64.dp)
                    .background(Color.Black.copy(alpha = 0.45f), shape = CircleShape)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }

            // Bottom controls: Scrubber + Time Display
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.7f))
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // Scrubber Slider
                val sliderValue = if (durationMs > 0) {
                    (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                } else 0f

                Slider(
                    value = sliderValue,
                    onValueChange = { ratio ->
                        if (durationMs > 0) {
                            val newPos = (ratio * durationMs).toLong()
                            currentPositionMs = newPos
                            videoViewRef?.seekTo(newPos.toInt())
                        }
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
                        text = formatTime(currentPositionMs),
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 12.sp
                    )
                    Text(
                        text = formatTime(durationMs),
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 12.sp
                    )
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
