package com.example.ui.components

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.GreenCall
import com.example.ui.theme.LocalThemeColor
import com.example.util.AudioTrimmerUtil
import com.example.util.CallerTuneInfo
import com.example.util.LksCallerTuneManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

private const val TAG = "CallerTuneTrimmer"
private const val MAX_TUNE_DURATION_MS = 30_000L // 30 seconds standard caller tune limit
private const val MIN_TUNE_DURATION_MS = 5_000L  // 5 seconds minimum

/**
 * CallerTuneTrimmerDialog
 * High-end visual audio portion selector tailored specifically for Caller Tunes (Hello Tunes).
 * Constrains selection to a maximum of 30 seconds, provides instant play preview of the cut,
 * and encodes into Base64 for instant peer-to-peer Firestore calling synchronization.
 */
@Composable
fun CallerTuneTrimmerDialog(
    sourceUri: Uri,
    songTitle: String,
    artist: String,
    source: String = "LOCAL",
    thumbnailUrl: String? = null,
    audioUrl: String = "",
    onTuneSaved: (CallerTuneInfo) -> Unit,
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
    var isPreparingPreview by remember { mutableStateOf(false) }
    var previewPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var previewProgress by remember { mutableFloatStateOf(0f) }

    var isSaving by remember { mutableStateOf(false) }
    var statusError by remember { mutableStateOf("") }

    fun stopPreviewPlayback() {
        try {
            previewPlayer?.stop()
            previewPlayer?.release()
        } catch (_: Exception) {}
        previewPlayer = null
        isPreviewPlaying = false
        isPreparingPreview = false
    }

    DisposableEffect(Unit) {
        onDispose {
            stopPreviewPlayback()
        }
    }

    // Read total duration of audio file
    LaunchedEffect(sourceUri) {
        isLoadingDuration = true
        withContext(Dispatchers.IO) {
            try {
                val mmr = android.media.MediaMetadataRetriever()
                setRetrieverDataSource(context, mmr, sourceUri)
                val durationStr = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                totalDurationMs = durationStr?.toLongOrNull() ?: 0L
                mmr.release()
            } catch (e: Exception) {
                Log.w(TAG, "MediaMetadataRetriever error: ${e.message}")
                try {
                    val player = MediaPlayer()
                    setMediaPlayerDataSource(context, player, sourceUri)
                    player.prepare()
                    totalDurationMs = player.duration.toLong()
                    player.release()
                } catch (e2: Exception) {
                    Log.w(TAG, "MediaPlayer duration fallback error: ${e2.message}")
                    totalDurationMs = 60_000L // Default 60s fallback
                }
            }
        }
        isLoadingDuration = false

        // Default initial window: start at 0s, end at min(30s, totalDuration)
        if (totalDurationMs > 0) {
            val maxInitialEnd = minOf(MAX_TUNE_DURATION_MS, totalDurationMs)
            endFraction = maxInitialEnd.toFloat() / totalDurationMs
            startFraction = 0f
        }
    }

    // Playback loop for preview
    LaunchedEffect(isPreviewPlaying) {
        while (isPreviewPlaying) {
            val p = previewPlayer
            if (p != null) {
                try {
                    if (p.isPlaying) {
                        val currentMs = p.currentPosition.toLong()
                        val targetEndMs = (endFraction * totalDurationMs).toLong()
                        val targetStartMs = (startFraction * totalDurationMs).toLong()
                        if (currentMs >= targetEndMs) {
                            stopPreviewPlayback()
                            previewProgress = endFraction
                            break
                        } else if (currentMs >= targetStartMs) {
                            previewProgress = if (totalDurationMs > 0) currentMs.toFloat() / totalDurationMs else startFraction
                        }
                    }
                } catch (_: Exception) {}
            }
            delay(50)
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
    val selectedDurationMs = endMs - startMs

    Dialog(
        onDismissRequest = {
            stopPreviewPlayback()
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .wrapContentHeight()
                .padding(16.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 10.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFF6750A4).copy(alpha = 0.15f),
                        modifier = Modifier.size(46.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = Color(0xFF6750A4),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Select 30s Caller Tune",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            songTitle.ifBlank { "Selected Song" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = {
                        stopPreviewPlayback()
                        onDismiss()
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(Modifier.height(18.dp))

                when {
                    isLoadingDuration -> {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp), color = themeColor.primary)
                        Spacer(Modifier.height(8.dp))
                        Text("Analyzing audio track...", style = MaterialTheme.typography.bodySmall)
                    }
                    totalDurationMs <= 0L -> {
                        Text("Could not process this audio track", color = MaterialTheme.colorScheme.error)
                    }
                    else -> {
                        // Time Information Cards
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = GreenCall.copy(alpha = 0.12f),
                                modifier = Modifier.padding(2.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    Text("START", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = GreenCall)
                                    Text(formatMs(startMs), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = GreenCall)
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFF6750A4).copy(alpha = 0.12f),
                                modifier = Modifier.padding(2.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text("TUNE LENGTH", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF6750A4))
                                    Text("${(selectedDurationMs / 1000)}s / 30s", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = Color(0xFF6750A4))
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                                modifier = Modifier.padding(2.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    horizontalAlignment = Alignment.End
                                ) {
                                    Text("END", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                                    Text(formatMs(endMs), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        // Draggable Visual Timeline Bar
                        val trackColor = MaterialTheme.colorScheme.surfaceVariant
                        val selectedBrush = Brush.horizontalGradient(
                            listOf(GreenCall.copy(alpha = 0.5f), Color(0xFF6750A4).copy(alpha = 0.5f))
                        )
                        var barWidthPx by remember { mutableFloatStateOf(0f) }
                        val handleWidthPx = with(density) { 24.dp.toPx() }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(78.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            // Track Canvas
                            Canvas(modifier = Modifier.fillMaxWidth().height(54.dp)) {
                                barWidthPx = size.width
                                val r = CornerRadius(14f)
                                drawRoundRect(color = trackColor, size = size, cornerRadius = r)
                                val sx = startFraction * size.width
                                val ex = endFraction * size.width
                                drawRoundRect(brush = selectedBrush, topLeft = Offset(sx, 0f), size = Size(ex - sx, size.height), cornerRadius = r)

                                if (isPreviewPlaying || (previewProgress > startFraction && previewProgress <= endFraction)) {
                                    val px = previewProgress * size.width
                                    drawLine(
                                        color = Color.White,
                                        start = Offset(px, 6f),
                                        end = Offset(px, size.height - 6f),
                                        strokeWidth = 3f
                                    )
                                }
                            }

                            // Start Handle (Green)
                            val startOffsetDp = with(density) { ((startFraction * barWidthPx) - handleWidthPx / 2).toDp() }
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .offset(x = startOffsetDp)
                                    .width(24.dp)
                                    .height(68.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(GreenCall)
                                    .pointerInput(Unit) {
                                        detectHorizontalDragGestures { _, dragAmount ->
                                            if (barWidthPx > 0) {
                                                val delta = dragAmount / barWidthPx
                                                val maxFractionFor30s = (MAX_TUNE_DURATION_MS.toFloat() / totalDurationMs)
                                                val newStart = (startFraction + delta).coerceIn(0f, endFraction - 0.05f)
                                                // Ensure selection doesn't exceed 30s
                                                val currentLengthMs = ((endFraction - newStart) * totalDurationMs).toLong()
                                                if (currentLengthMs <= MAX_TUNE_DURATION_MS) {
                                                    startFraction = newStart
                                                } else {
                                                    startFraction = newStart
                                                    endFraction = (newStart + maxFractionFor30s).coerceAtMost(1f)
                                                }
                                                stopPreviewPlayback()
                                            }
                                        }
                                    }
                            )

                            // End Handle (Red)
                            val endOffsetDp = with(density) { ((endFraction * barWidthPx) - handleWidthPx / 2).toDp() }
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .offset(x = endOffsetDp)
                                    .width(24.dp)
                                    .height(68.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.error)
                                    .pointerInput(Unit) {
                                        detectHorizontalDragGestures { _, dragAmount ->
                                            if (barWidthPx > 0) {
                                                val delta = dragAmount / barWidthPx
                                                val maxFractionFor30s = (MAX_TUNE_DURATION_MS.toFloat() / totalDurationMs)
                                                val newEnd = (endFraction + delta).coerceIn(startFraction + 0.05f, 1f)
                                                val currentLengthMs = ((newEnd - startFraction) * totalDurationMs).toLong()
                                                if (currentLengthMs <= MAX_TUNE_DURATION_MS) {
                                                    endFraction = newEnd
                                                } else {
                                                    endFraction = newEnd
                                                    startFraction = (newEnd - maxFractionFor30s).coerceAtLeast(0f)
                                                }
                                                stopPreviewPlayback()
                                            }
                                        }
                                    }
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("◂ Drag green (Start)", fontSize = 10.sp, color = GreenCall, fontWeight = FontWeight.Bold)
                            Text("Drag red (End) ▸", fontSize = 10.sp, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                        }

                        Spacer(Modifier.height(14.dp))

                        // Quick Chorus Preset Buttons
                        Text(
                            "QUICK PRESETS",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Preset 1: 0:00 - 0:30 Intro
                            SuggestionChip(
                                onClick = {
                                    startFraction = 0f
                                    endFraction = (30_000f / totalDurationMs).coerceAtMost(1f)
                                    stopPreviewPlayback()
                                },
                                label = { Text("Intro 0:00-0:30", fontSize = 11.sp) },
                                modifier = Modifier.weight(1f)
                            )
                            // Preset 2: 0:30 - 1:00 Chorus
                            if (totalDurationMs >= 60_000L) {
                                SuggestionChip(
                                    onClick = {
                                        startFraction = (30_000f / totalDurationMs).coerceIn(0f, 1f)
                                        endFraction = (60_000f / totalDurationMs).coerceIn(0f, 1f)
                                        stopPreviewPlayback()
                                    },
                                    label = { Text("Chorus 0:30-1:00", fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            // Preset 3: 1:00 - 1:30 Hook
                            if (totalDurationMs >= 90_000L) {
                                SuggestionChip(
                                    onClick = {
                                        startFraction = (60_000f / totalDurationMs).coerceIn(0f, 1f)
                                        endFraction = (90_000f / totalDurationMs).coerceIn(0f, 1f)
                                        stopPreviewPlayback()
                                    },
                                    label = { Text("Hook 1:00-1:30", fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        Spacer(Modifier.height(18.dp))

                        // Preview Button
                        OutlinedButton(
                            onClick = {
                                if (isPreviewPlaying || isPreparingPreview) {
                                    stopPreviewPlayback()
                                } else {
                                    isPreparingPreview = true
                                    statusError = ""
                                    coroutineScope.launch {
                                        try {
                                            stopPreviewPlayback()
                                            isPreparingPreview = true

                                            val p = MediaPlayer()
                                            p.setAudioAttributes(
                                                AudioAttributes.Builder()
                                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                                    .build()
                                            )
                                            p.setVolume(1.0f, 1.0f)

                                            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                                            try {
                                                if (audioManager?.mode == AudioManager.MODE_IN_COMMUNICATION) {
                                                    audioManager.mode = AudioManager.MODE_NORMAL
                                                }
                                            } catch (_: Exception) {}

                                            withContext(Dispatchers.IO) {
                                                setMediaPlayerDataSource(context, p, sourceUri)
                                                p.prepare()
                                            }

                                            p.setOnErrorListener { _, what, extra ->
                                                Log.w(TAG, "Preview MediaPlayer error: what=$what extra=$extra")
                                                stopPreviewPlayback()
                                                true
                                            }

                                            p.setOnCompletionListener {
                                                stopPreviewPlayback()
                                                previewProgress = endFraction
                                            }

                                            val targetStartMs = (startFraction * totalDurationMs).toLong().coerceIn(0L, totalDurationMs)

                                            if (targetStartMs > 50L) {
                                                p.setOnSeekCompleteListener {
                                                    try {
                                                        p.start()
                                                        previewPlayer = p
                                                        isPreviewPlaying = true
                                                        isPreparingPreview = false
                                                        previewProgress = startFraction
                                                    } catch (e: Exception) {
                                                        Log.e(TAG, "Failed starting preview after seek: ${e.message}", e)
                                                        stopPreviewPlayback()
                                                    }
                                                }
                                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                                    p.seekTo(targetStartMs, MediaPlayer.SEEK_CLOSEST)
                                                } else {
                                                    p.seekTo(targetStartMs.toInt())
                                                }
                                            } else {
                                                p.seekTo(0)
                                                p.start()
                                                previewPlayer = p
                                                isPreviewPlaying = true
                                                isPreparingPreview = false
                                                previewProgress = startFraction
                                            }
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Preview playback error: ${e.message}", e)
                                            stopPreviewPlayback()
                                            statusError = "Could not preview audio. Please try again."
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            if (isPreparingPreview) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = GreenCall
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Preparing Preview...", fontWeight = FontWeight.Bold)
                            } else {
                                Icon(
                                    if (isPreviewPlaying) Icons.Default.StopCircle else Icons.Default.PlayCircle,
                                    contentDescription = null,
                                    tint = if (isPreviewPlaying) MaterialTheme.colorScheme.error else GreenCall,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(if (isPreviewPlaying) "Stop Preview" else "▶  Preview 30s Caller Tune")
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        if (statusError.isNotBlank()) {
                            Text(statusError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(6.dp))
                        }

                        // Save as Caller Tune Button
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    isSaving = true
                                    statusError = ""
                                    stopPreviewPlayback()

                                    // 1. Trim audio clip
                                    val trimmedUri = withContext(Dispatchers.IO) {
                                        AudioTrimmerUtil.trimAudio(context, sourceUri, startMs, endMs)
                                    }

                                    if (trimmedUri != null) {
                                        // 2. Cache tune and encode Base64
                                        val tuneInfo = withContext(Dispatchers.IO) {
                                            LksCallerTuneManager.processAndCacheTune(
                                                context = context,
                                                uri = trimmedUri,
                                                title = songTitle,
                                                artist = artist,
                                                source = source,
                                                startMs = startMs,
                                                endMs = endMs,
                                                thumbnailUrl = thumbnailUrl,
                                                audioUrl = audioUrl
                                            )
                                        }

                                        // 3. Sync to Firestore profile
                                        com.example.data.repository.FirebaseManager.getInstance(context).updateCallerTune(
                                            title = tuneInfo.title,
                                            artist = tuneInfo.artist,
                                            audioUrl = tuneInfo.audioUrl,
                                            audioBase64 = tuneInfo.audioBase64,
                                            source = tuneInfo.source,
                                            startMs = tuneInfo.startMs,
                                            endMs = tuneInfo.endMs
                                        )

                                        isSaving = false
                                        onTuneSaved(tuneInfo)
                                    } else {
                                        isSaving = false
                                        statusError = "Could not trim audio section. Please try another range."
                                    }
                                }
                            },
                            enabled = !isSaving && selectedDurationMs >= MIN_TUNE_DURATION_MS,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4))
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Setting Caller Tune...", color = Color.White)
                            } else {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("✓ Set as My Caller Tune", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Robust DataSource setter for MediaPlayer supporting internal cache file descriptors,
 * content URIs, and remote streams without SELinux permission errors.
 */
private fun setMediaPlayerDataSource(context: Context, player: MediaPlayer, uri: Uri) {
    when {
        uri.scheme == "file" && uri.path != null -> {
            val file = File(uri.path!!)
            if (file.exists()) {
                val fis = FileInputStream(file)
                try {
                    player.setDataSource(fis.fd, 0, file.length())
                } finally {
                    fis.close()
                }
            } else {
                player.setDataSource(context, uri)
            }
        }
        uri.scheme == "content" -> {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                try {
                    player.setDataSource(pfd.fileDescriptor)
                } finally {
                    pfd.close()
                }
            } else {
                player.setDataSource(context, uri)
            }
        }
        uri.scheme?.startsWith("http") == true -> {
            val headers = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 10)")
            player.setDataSource(context, uri, headers)
        }
        else -> {
            val path = uri.path
            if (path != null && File(path).exists()) {
                val file = File(path)
                val fis = FileInputStream(file)
                try {
                    player.setDataSource(fis.fd, 0, file.length())
                } finally {
                    fis.close()
                }
            } else {
                player.setDataSource(context, uri)
            }
        }
    }
}

/**
 * Robust DataSource setter for MediaMetadataRetriever supporting internal cache file descriptors.
 */
private fun setRetrieverDataSource(context: Context, mmr: android.media.MediaMetadataRetriever, uri: Uri) {
    when {
        uri.scheme == "file" && uri.path != null -> {
            val file = File(uri.path!!)
            if (file.exists()) {
                val fis = FileInputStream(file)
                try {
                    mmr.setDataSource(fis.fd, 0, file.length())
                } finally {
                    fis.close()
                }
            } else {
                mmr.setDataSource(context, uri)
            }
        }
        uri.scheme == "content" -> {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                try {
                    mmr.setDataSource(pfd.fileDescriptor)
                } finally {
                    pfd.close()
                }
            } else {
                mmr.setDataSource(context, uri)
            }
        }
        uri.scheme?.startsWith("http") == true -> {
            mmr.setDataSource(uri.toString(), mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 10)"))
        }
        else -> {
            val path = uri.path
            if (path != null && File(path).exists()) {
                val file = File(path)
                val fis = FileInputStream(file)
                try {
                    mmr.setDataSource(fis.fd, 0, file.length())
                } finally {
                    fis.close()
                }
            } else {
                mmr.setDataSource(context, uri)
            }
        }
    }
}
