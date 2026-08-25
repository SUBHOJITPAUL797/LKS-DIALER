package com.example.ui.components

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.GreenCall
import com.example.ui.theme.LocalThemeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "SongTrimmerDialog"

/**
 * SongTrimmerDialog
 * Full-screen dialog that lets users visually trim an audio file to a specific section.
 *
 * Features:
 * - Visual timeline bar with two draggable start/end handles
 * - Live timestamps updated as handles are dragged
 * - Preview button: plays only the trimmed section
 * - Save: uses MediaExtractor + MediaMuxer to cut audio bytes, writes to internal storage
 */
@Composable
fun SongTrimmerDialog(
    uri: Uri,
    songTitle: String,
    onSave: (trimmedUri: Uri) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val themeColor = LocalThemeColor.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    var totalDurationMs by remember { mutableLongStateOf(0L) }
    var isLoadingDuration by remember { mutableStateOf(true) }

    var startFraction by remember { mutableFloatStateOf(0f) }
    var endFraction by remember { mutableFloatStateOf(1f) }

    var isPreviewPlaying by remember { mutableStateOf(false) }
    var previewPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var previewProgress by remember { mutableFloatStateOf(0f) }

    var isSaving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        onDispose {
            try { previewPlayer?.stop(); previewPlayer?.release() } catch (_: Exception) {}
        }
    }

    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            try {
                val player = MediaPlayer()
                player.setDataSource(context, uri)
                player.prepare()
                totalDurationMs = player.duration.toLong()
                player.release()
            } catch (e: Exception) {
                Log.w(TAG, "Could not read duration: ${e.message}")
                totalDurationMs = 0L
            }
        }
        isLoadingDuration = false
    }

    LaunchedEffect(isPreviewPlaying) {
        while (isPreviewPlaying) {
            val p = previewPlayer
            if (p != null) {
                try {
                    if (p.isPlaying) {
                        val currentMs = p.currentPosition.toLong()
                        val endMs = (endFraction * totalDurationMs).toLong()
                        if (currentMs >= endMs) {
                            p.pause()
                            isPreviewPlaying = false
                            previewProgress = endFraction
                        } else {
                            previewProgress = if (totalDurationMs > 0) currentMs.toFloat() / totalDurationMs else startFraction
                        }
                    }
                } catch (_: Exception) {}
            }
            delay(100)
        }
    }

    fun formatMs(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%d:%02d".format(min, sec)
    }

    val startMs = (startFraction * totalDurationMs).toLong()
    val endMs = (endFraction * totalDurationMs).toLong()
    val trimDurationMs = endMs - startMs

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = themeColor.primary.copy(alpha = 0.12f), modifier = Modifier.size(42.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.ContentCut, contentDescription = null, tint = themeColor.primary, modifier = Modifier.size(22.dp))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Trim Ringtone", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        Text(songTitle.take(40).ifBlank { "Audio File" }, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close") }
                }

                Spacer(Modifier.height(20.dp))

                when {
                    isLoadingDuration -> {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp), color = themeColor.primary)
                        Spacer(Modifier.height(8.dp))
                        Text("Loading audio...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    totalDurationMs <= 0L -> {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("Could not read audio duration", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                    else -> {
                        // Time labels
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(horizontalAlignment = Alignment.Start) {
                                Text("START", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(formatMs(startMs), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = GreenCall)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("DURATION", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(formatMs(trimDurationMs), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = themeColor.primary)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("END", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(formatMs(endMs), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.error)
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        // Timeline track with handles
                        val trackColor = MaterialTheme.colorScheme.surfaceVariant
                        val selectedColor = themeColor.primary.copy(alpha = 0.45f)
                        val startHandleColor = GreenCall
                        val endHandleColor = MaterialTheme.colorScheme.error
                        val progressColor = Color.White.copy(alpha = 0.85f)
                        var barWidthPx by remember { mutableFloatStateOf(0f) }
                        val handleWidthPx = with(density) { 22.dp.toPx() }

                        Box(
                            modifier = Modifier.fillMaxWidth().height(76.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            // Track
                            Canvas(modifier = Modifier.fillMaxWidth().height(52.dp)) {
                                barWidthPx = size.width
                                val r = CornerRadius(12f)
                                drawRoundRect(color = trackColor, size = size, cornerRadius = r)
                                val sx = startFraction * size.width
                                val ex = endFraction * size.width
                                drawRoundRect(color = selectedColor, topLeft = Offset(sx, 0f), size = Size(ex - sx, size.height), cornerRadius = r)
                                if (isPreviewPlaying || previewProgress > startFraction) {
                                    val px = previewProgress * size.width
                                    drawLine(color = progressColor, start = Offset(px, 8f), end = Offset(px, size.height - 8f), strokeWidth = 3f)
                                }
                            }

                            // Start handle
                            val startOffsetDp = with(density) { ((startFraction * barWidthPx) - handleWidthPx / 2).toDp() }
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .offset(x = startOffsetDp)
                                    .width(22.dp)
                                    .height(68.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(startHandleColor)
                                    .pointerInput(Unit) {
                                        detectHorizontalDragGestures { _, dragAmount ->
                                            if (barWidthPx > 0) {
                                                val delta = dragAmount / barWidthPx
                                                startFraction = (startFraction + delta).coerceIn(0f, endFraction - 0.02f)
                                                if (isPreviewPlaying) { try { previewPlayer?.stop() } catch (_: Exception) {}; isPreviewPlaying = false }
                                            }
                                        }
                                    }
                            )

                            // End handle
                            val endOffsetDp = with(density) { ((endFraction * barWidthPx) - handleWidthPx / 2).toDp() }
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .offset(x = endOffsetDp)
                                    .width(22.dp)
                                    .height(68.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(endHandleColor)
                                    .pointerInput(Unit) {
                                        detectHorizontalDragGestures { _, dragAmount ->
                                            if (barWidthPx > 0) {
                                                val delta = dragAmount / barWidthPx
                                                endFraction = (endFraction + delta).coerceIn(startFraction + 0.02f, 1f)
                                                if (isPreviewPlaying) { try { previewPlayer?.stop() } catch (_: Exception) {}; isPreviewPlaying = false }
                                            }
                                        }
                                    }
                            )
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("◂ Drag green to set start", fontSize = 10.sp, color = GreenCall)
                            Text("Drag red to set end ▸", fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                        }

                        Spacer(Modifier.height(8.dp))
                        Text("Full song: ${formatMs(totalDurationMs)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(20.dp))

                        // Preview button
                        OutlinedButton(
                            onClick = {
                                if (isPreviewPlaying) {
                                    try { previewPlayer?.pause() } catch (_: Exception) {}
                                    isPreviewPlaying = false
                                } else {
                                    coroutineScope.launch {
                                        try {
                                            val p = MediaPlayer()
                                            try { previewPlayer?.stop(); previewPlayer?.release() } catch (_: Exception) {}
                                            previewPlayer = p
                                            p.setDataSource(context, uri)
                                            p.prepare()
                                            p.seekTo(startMs.toInt())
                                            p.start()
                                            previewProgress = startFraction
                                            isPreviewPlaying = true
                                        } catch (e: Exception) {
                                            Log.w(TAG, "Preview error: ${e.message}")
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                if (isPreviewPlaying) Icons.Default.StopCircle else Icons.Default.PlayCircle,
                                contentDescription = null,
                                tint = if (isPreviewPlaying) MaterialTheme.colorScheme.error else GreenCall,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(if (isPreviewPlaying) "Stop Preview" else "▶  Preview Trimmed Section")
                        }

                        Spacer(Modifier.height(10.dp))

                        if (saveError.isNotBlank()) {
                            Text(saveError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(bottom = 8.dp))
                        }

                        // Save button
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    isSaving = true; saveError = ""
                                    try { previewPlayer?.stop(); previewPlayer?.release(); previewPlayer = null } catch (_: Exception) {}
                                    isPreviewPlaying = false
                                    val result = withContext(Dispatchers.IO) {
                                        com.example.util.AudioTrimmerUtil.trimAudio(context, uri, startMs, endMs)
                                    }
                                    isSaving = false
                                    if (result != null) {
                                        onSave(result)
                                    } else {
                                        saveError = "Could not crop audio format. Using full audio."
                                        onSave(uri)
                                    }
                                }
                            },
                            enabled = !isSaving && trimDurationMs >= 1000,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = themeColor.primary)
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Trimming...", color = Color.White)
                            } else {
                                Icon(Icons.Default.ContentCut, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Save Trim as Ringtone", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                        if (trimDurationMs < 1000) {
                            Text("Select at least 1 second", fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}
