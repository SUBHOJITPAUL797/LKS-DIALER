package com.example.ui.components

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.ui.theme.GreenCall
import com.example.ui.theme.LocalThemeColor
import com.example.util.CallerTuneInfo
import com.example.util.LksCallerTuneManager
import com.example.util.YouTubeSearchHelper
import com.example.util.YouTubeSongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * CallerTunePickerDialog
 * Full-featured, user-friendly dialog for discovering, searching, previewing,
 * and setting Caller Tunes (Hello Tunes) from YouTube, local storage, or curated presets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallerTunePickerDialog(
    onTuneSelected: (CallerTuneInfo) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val themeColor = LocalThemeColor.current
    val coroutineScope = rememberCoroutineScope()

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabTitles = listOf("🔍 YouTube", "📁 My Device", "🎵 Curated")

    // YouTube Search State
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<YouTubeSongItem>>(emptyList()) }
    var previewingVideoId by remember { mutableStateOf<String?>(null) }
    var selectedYouTubeSongForPortion by remember { mutableStateOf<YouTubeSongItem?>(null) }

    // Trimmer State
    var activeTrimmerUri by remember { mutableStateOf<Uri?>(null) }
    var activeTrimmerTitle by remember { mutableStateOf("") }
    var activeTrimmerArtist by remember { mutableStateOf("") }
    var activeTrimmerSource by remember { mutableStateOf("LOCAL") }
    var activeTrimmerThumb by remember { mutableStateOf<String?>(null) }

    // Curated Tunes Preview State
    var previewingCuratedId by remember { mutableStateOf<String?>(null) }

    // Launcher for device audio picker
    val audioFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { pickedUri ->
        if (pickedUri != null) {
            val title = com.example.util.LksRingtoneManager.getRingtoneTitle(context, pickedUri)
            activeTrimmerUri = pickedUri
            activeTrimmerTitle = title
            activeTrimmerArtist = "My Audio"
            activeTrimmerSource = "LOCAL"
            activeTrimmerThumb = null
        }
    }

    // Load initial trending YouTube songs
    LaunchedEffect(Unit) {
        isSearching = true
        searchResults = YouTubeSearchHelper.searchSongs("Trending Songs")
        isSearching = false
    }

    // If YouTube portion dialog is active
    if (selectedYouTubeSongForPortion != null) {
        YouTubePortionPickerDialog(
            song = selectedYouTubeSongForPortion!!,
            onPortionConfirmed = { startMs, endMs ->
                val song = selectedYouTubeSongForPortion!!
                selectedYouTubeSongForPortion = null
                coroutineScope.launch {
                    val tuneInfo = CallerTuneInfo(
                        title = song.title,
                        artist = song.channelName,
                        audioUrl = "https://www.youtube.com/watch?v=${song.videoId}",
                        source = "YOUTUBE",
                        startMs = startMs,
                        endMs = endMs,
                        thumbnailUrl = song.thumbnailUrl
                    )
                    LksCallerTuneManager.saveMyCallerTune(context, tuneInfo)
                    com.example.data.repository.FirebaseManager.getInstance(context).updateCallerTune(
                        title = tuneInfo.title,
                        artist = tuneInfo.artist,
                        audioUrl = tuneInfo.audioUrl,
                        audioBase64 = tuneInfo.audioBase64,
                        source = tuneInfo.source,
                        startMs = tuneInfo.startMs,
                        endMs = tuneInfo.endMs
                    )
                    Toast.makeText(context, "Caller Tune set: ${song.title}", Toast.LENGTH_LONG).show()
                    onTuneSelected(tuneInfo)
                }
            },
            onDismiss = { selectedYouTubeSongForPortion = null }
        )
        return
    }

    // If trimmer is active, show the CallerTuneTrimmerDialog
    if (activeTrimmerUri != null) {
        CallerTuneTrimmerDialog(
            sourceUri = activeTrimmerUri!!,
            songTitle = activeTrimmerTitle,
            artist = activeTrimmerArtist,
            source = activeTrimmerSource,
            thumbnailUrl = activeTrimmerThumb,
            onTuneSaved = { savedTune ->
                activeTrimmerUri = null
                onTuneSelected(savedTune)
            },
            onDismiss = { activeTrimmerUri = null }
        )
        return
    }

    Dialog(
        onDismissRequest = {
            LksCallerTuneManager.stopPreview()
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.90f)
                .padding(vertical = 16.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
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
                            "Caller Tune Library",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            "Choose a song for callers to hear while ringing",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = {
                        LksCallerTuneManager.stopPreview()
                        onDismiss()
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Navigation Tabs
                PrimaryTabRow(
                    selectedTabIndex = selectedTabIndex,
                    containerColor = Color.Transparent,
                    divider = {}
                ) {
                    tabTitles.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = {
                                LksCallerTuneManager.stopPreview()
                                previewingCuratedId = null
                                selectedTabIndex = index
                            },
                            text = {
                                Text(
                                    title,
                                    fontWeight = if (selectedTabIndex == index) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 13.sp
                                )
                            }
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Tab Content
                when (selectedTabIndex) {
                    0 -> {
                        // ── TAB 1: YOUTUBE MUSIC SEARCH ─────────────────────────────────
                        Column(modifier = Modifier.fillMaxSize()) {
                            // Search Bar
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text("Search songs, artists, or paste YouTube link...", fontSize = 13.sp) },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF6750A4)) },
                                trailingIcon = {
                                    if (searchQuery.isNotBlank()) {
                                        IconButton(onClick = { searchQuery = "" }) {
                                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                                        }
                                    }
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(Modifier.height(10.dp))

                            // Quick Genre Suggestion Chips
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                val genres = listOf(
                                    "🔥 Bollywood Hits",
                                    "🌍 Global Top 50",
                                    "🎧 Lo-Fi Chill",
                                    "🎸 Acoustic Guitar",
                                    "🎻 Piano Melodies",
                                    "⚡ Punjabi Hits"
                                )
                                items(genres) { genre ->
                                    val cleanText = genre.substringAfter(" ")
                                    SuggestionChip(
                                        onClick = {
                                            searchQuery = cleanText
                                            coroutineScope.launch {
                                                isSearching = true
                                                searchResults = YouTubeSearchHelper.searchSongs(cleanText)
                                                isSearching = false
                                            }
                                        },
                                        label = { Text(genre, fontSize = 11.sp) }
                                    )
                                }
                            }

                            // Search Button Trigger
                            if (searchQuery.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Button(
                                    onClick = {
                                        coroutineScope.launch {
                                            isSearching = true
                                            searchResults = YouTubeSearchHelper.searchSongs(searchQuery)
                                            isSearching = false
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4))
                                ) {
                                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Search YouTube Songs")
                                }
                            }

                            Spacer(Modifier.height(10.dp))

                            if (isSearching) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        CircularProgressIndicator(color = Color(0xFF6750A4), modifier = Modifier.size(36.dp))
                                        Spacer(Modifier.height(12.dp))
                                        Text("Searching YouTube catalog...", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            } else if (searchResults.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("No songs found. Try a different search query.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    items(searchResults) { song ->
                                        YouTubeSongCard(
                                            song = song,
                                            onSelectAndTrim = {
                                                selectedYouTubeSongForPortion = song
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    1 -> {
                        // ── TAB 2: MY DEVICE AUDIO ──────────────────────────────────────
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = themeColor.primary.copy(alpha = 0.12f),
                                modifier = Modifier.size(80.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.AudioFile,
                                        contentDescription = null,
                                        tint = themeColor.primary,
                                        modifier = Modifier.size(42.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(20.dp))
                            Text(
                                "Pick Audio from Your Phone",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Select any MP3, song, or recording from your device storage. You can visually trim any 30-second portion.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                            Spacer(Modifier.height(28.dp))
                            Button(
                                onClick = {
                                    audioFileLauncher.launch(arrayOf("audio/*"))
                                },
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = themeColor.primary),
                                modifier = Modifier.fillMaxWidth(0.8f).height(50.dp)
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Choose Audio File & Trim", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    2 -> {
                        // ── TAB 3: CURATED TRENDING TUNES ───────────────────────────────
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(LksCallerTuneManager.CURATED_TUNES) { curated ->
                                CuratedTuneCard(
                                    tune = curated,
                                    isPlaying = previewingCuratedId == curated.id,
                                    onTogglePreview = {
                                        if (previewingCuratedId == curated.id) {
                                            LksCallerTuneManager.stopPreview()
                                            previewingCuratedId = null
                                        } else {
                                            previewingCuratedId = curated.id
                                            val info = CallerTuneInfo(
                                                title = curated.title,
                                                artist = curated.artist,
                                                audioUrl = curated.streamUrl,
                                                source = "PRESET"
                                            )
                                            LksCallerTuneManager.playPreview(context, info) {
                                                previewingCuratedId = null
                                            }
                                        }
                                    },
                                    onSelect = {
                                        LksCallerTuneManager.stopPreview()
                                        previewingCuratedId = null
                                        coroutineScope.launch {
                                            val tuneInfo = CallerTuneInfo(
                                                title = curated.title,
                                                artist = curated.artist,
                                                audioUrl = curated.streamUrl,
                                                source = "PRESET",
                                                startMs = 0L,
                                                endMs = 30000L
                                            )
                                            LksCallerTuneManager.saveMyCallerTune(context, tuneInfo)
                                            com.example.data.repository.FirebaseManager.getInstance(context).updateCallerTune(
                                                title = tuneInfo.title,
                                                artist = tuneInfo.artist,
                                                audioUrl = tuneInfo.audioUrl,
                                                audioBase64 = tuneInfo.audioBase64,
                                                source = tuneInfo.source,
                                                startMs = tuneInfo.startMs,
                                                endMs = tuneInfo.endMs
                                            )
                                            Toast.makeText(context, "Caller Tune set to: ${curated.title}", Toast.LENGTH_LONG).show()
                                            onTuneSelected(tuneInfo)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * YouTubeSongCard
 * Visual representation of a song found on YouTube.
 */
@Composable
private fun YouTubeSongCard(
    song: YouTubeSongItem,
    onSelectAndTrim: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail with duration badge
            Box(
                modifier = Modifier
                    .size(width = 86.dp, height = 58.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black)
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(song.thumbnailUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                // Duration Tag
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color.Black.copy(alpha = 0.75f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(3.dp)
                ) {
                    Text(
                        song.durationText,
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            // Details
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = song.channelName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(8.dp))

            // Select & Set Tune Button
            Button(
                onClick = onSelectAndTrim,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("Set Tune", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}

/**
 * CuratedTuneCard
 * Pre-packaged popular royalty-free tunes with instant preview and 1-tap activation.
 */
@Composable
private fun CuratedTuneCard(
    tune: LksCallerTuneManager.CuratedTune,
    isPlaying: Boolean,
    onTogglePreview: () -> Unit,
    onSelect: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF6750A4).copy(alpha = 0.15f),
                modifier = Modifier.size(46.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(tune.emoji, fontSize = 22.sp)
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    tune.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${tune.artist} • ${tune.category}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Play/Pause Preview
            IconButton(onClick = onTogglePreview) {
                Icon(
                    if (isPlaying) Icons.Default.StopCircle else Icons.Default.PlayCircle,
                    contentDescription = if (isPlaying) "Stop Preview" else "Play Preview",
                    tint = if (isPlaying) MaterialTheme.colorScheme.error else GreenCall,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(Modifier.width(4.dp))

            Button(
                onClick = onSelect,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("Set", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}

/**
 * YouTubePortionPickerDialog
 * Clean portion selection modal letting users choose the exact 30-second portion
 * (Chorus, Intro, Hook, or Custom Slider) for their YouTube Caller Tune.
 */
@Composable
fun YouTubePortionPickerDialog(
    song: YouTubeSongItem,
    onPortionConfirmed: (startMs: Long, endMs: Long) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedPreset by remember { mutableStateOf("CHORUS") } // "CHORUS", "INTRO", "HOOK", "CUSTOM"
    var customStartSec by remember { mutableFloatStateOf(30f) }

    fun parseDurationSec(durText: String): Int {
        val parts = durText.split(":")
        return when (parts.size) {
            2 -> (parts[0].toIntOrNull() ?: 3) * 60 + (parts[1].toIntOrNull() ?: 30)
            3 -> (parts[0].toIntOrNull() ?: 0) * 3600 + (parts[1].toIntOrNull() ?: 3) * 60 + (parts[2].toIntOrNull() ?: 30)
            else -> 210 // 3.5 min default
        }
    }

    val totalSec = remember(song.durationText) { parseDurationSec(song.durationText) }
    val maxStartSec = maxOf(0, totalSec - 30).toFloat()

    val currentStartSec = when (selectedPreset) {
        "INTRO" -> 0
        "CHORUS" -> minOf(30, maxStartSec.toInt())
        "HOOK" -> minOf(60, maxStartSec.toInt())
        else -> customStartSec.toInt()
    }
    val currentEndSec = minOf(totalSec, currentStartSec + 30)

    fun formatSec(sec: Int): String {
        val m = sec / 60
        val s = sec % 60
        return "%d:%02d".format(m, s)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
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
                        modifier = Modifier.size(42.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.ContentCut,
                                contentDescription = null,
                                tint = Color(0xFF6750A4),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Select 30s Portion",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            song.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Time window display card
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF6750A4).copy(alpha = 0.12f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("START TIME", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF6750A4))
                            Text(formatSec(currentStartSec), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = Color(0xFF6750A4))
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF6750A4).copy(alpha = 0.2f)
                        ) {
                            Text(
                                "30 SECONDS",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF6750A4),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("END TIME", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF6750A4))
                            Text(formatSec(currentEndSec), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = Color(0xFF6750A4))
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Presets
                Text(
                    "CHOOSE SONG SECTION",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Start)
                )
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedPreset == "CHORUS",
                        onClick = { selectedPreset = "CHORUS" },
                        label = { Text("🌟 Chorus (0:30-1:00)", fontSize = 11.sp) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = selectedPreset == "INTRO",
                        onClick = { selectedPreset = "INTRO" },
                        label = { Text("🎶 Intro (0:00-0:30)", fontSize = 11.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedPreset == "HOOK",
                        onClick = { selectedPreset = "HOOK" },
                        label = { Text("⚡ Hook (1:00-1:30)", fontSize = 11.sp) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = selectedPreset == "CUSTOM",
                        onClick = { selectedPreset = "CUSTOM" },
                        label = { Text("🎛️ Custom Slider", fontSize = 11.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }

                if (selectedPreset == "CUSTOM" && maxStartSec > 0) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Drag slider to set custom start time:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Start)
                    )
                    Slider(
                        value = customStartSec,
                        onValueChange = { customStartSec = it },
                        valueRange = 0f..maxStartSec,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(20.dp))

                // Confirm button
                Button(
                    onClick = {
                        onPortionConfirmed(currentStartSec * 1000L, currentEndSec * 1000L)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4))
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("✓ Set as My Caller Tune", fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}
