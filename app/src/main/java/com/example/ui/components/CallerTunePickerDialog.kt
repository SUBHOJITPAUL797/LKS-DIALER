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

private data class CallerTuneTrimmerTarget(
    val uri: Uri,
    val title: String,
    val artist: String,
    val source: String,
    val thumbnailUrl: String? = null,
    val audioUrl: String = ""
)

private sealed interface TunePreviewTarget {
    data object None : TunePreviewTarget
    data class SearchSong(val videoId: String) : TunePreviewTarget
    data class Curated(val id: String) : TunePreviewTarget
}

private sealed interface TuneApplyState {
    data object Idle : TuneApplyState
    data class DownloadingForTrim(val videoId: String) : TuneApplyState
    data class SettingSearchSong(val videoId: String) : TuneApplyState
    data class SettingCurated(val id: String) : TuneApplyState
}

private sealed interface SearchCatalogState {
    data object Loading : SearchCatalogState
    data object Empty : SearchCatalogState
    data class Loaded(val songs: List<YouTubeSongItem>) : SearchCatalogState
}

/**
 * CallerTunePickerDialog
 * Full-featured, 100% in-app music discoverer and Caller Tune selector.
 * - Searches millions of songs across Bollywood, Hollywood, Punjabi, and Global hits.
 * - Direct in-app audio preview (no external YouTube or browser redirects!).
 * - 1-tap download and instant visual 30-second chorus trimmer.
 * - Encodes trimmed audio into Firestore profile for instant ringback playback.
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
    val tabTitles = listOf("🔍 Search Music", "📁 My Device", "🎵 Curated")

    // Search Music State
    var searchQuery by remember { mutableStateOf("") }
    var searchCatalogState by remember { mutableStateOf<SearchCatalogState>(SearchCatalogState.Loading) }

    // Explicit Preview, Apply, and Trimmer State Machines
    var previewTarget by remember { mutableStateOf<TunePreviewTarget>(TunePreviewTarget.None) }
    var tuneApplyState by remember { mutableStateOf<TuneApplyState>(TuneApplyState.Idle) }
    var activeTrimmer by remember { mutableStateOf<CallerTuneTrimmerTarget?>(null) }

    // Launcher for device audio picker
    val audioFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { pickedUri ->
        if (pickedUri != null) {
            val title = com.example.util.LksRingtoneManager.getRingtoneTitle(context, pickedUri)
            activeTrimmer = CallerTuneTrimmerTarget(
                uri = pickedUri,
                title = title,
                artist = "My Audio",
                source = "LOCAL",
                thumbnailUrl = null,
                audioUrl = ""
            )
        }
    }

    // Load initial trending songs
    LaunchedEffect(Unit) {
        searchCatalogState = SearchCatalogState.Loading
        val results = YouTubeSearchHelper.searchSongs("Trending Songs")
        searchCatalogState = if (results.isEmpty()) SearchCatalogState.Empty else SearchCatalogState.Loaded(results)
    }

    // If trimmer is active, show the CallerTuneTrimmerDialog
    val trimmerTarget = activeTrimmer
    if (trimmerTarget != null) {
        CallerTuneTrimmerDialog(
            sourceUri = trimmerTarget.uri,
            songTitle = trimmerTarget.title,
            artist = trimmerTarget.artist,
            source = trimmerTarget.source,
            thumbnailUrl = trimmerTarget.thumbnailUrl,
            audioUrl = trimmerTarget.audioUrl,
            onTuneSaved = { savedTune ->
                activeTrimmer = null
                onTuneSelected(savedTune)
            },
            onDismiss = { activeTrimmer = null }
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
                .fillMaxHeight(0.92f)
                .padding(vertical = 12.dp),
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
                            "Search songs, preview in app & trim 30s chorus",
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

                Spacer(Modifier.height(14.dp))

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
                                previewTarget = TunePreviewTarget.None
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
                        // ── TAB 1: MUSIC CATALOG SEARCH ─────────────────────────────────
                        Column(modifier = Modifier.fillMaxSize()) {
                            // Search Bar
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text("Search any song, artist, Bollywood, Pop...", fontSize = 13.sp) },
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

                            // Genre Suggestion Chips
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                val genres = listOf(
                                    "🔥 Bollywood Hits",
                                    "🌍 Global Top 50",
                                    "⚡ Punjabi Hits",
                                    "🎧 Lo-Fi Chill",
                                    "🎸 Acoustic Guitar",
                                    "🎻 Romantic Hits"
                                )
                                items(genres) { genre ->
                                    val cleanText = genre.substringAfter(" ")
                                    SuggestionChip(
                                        onClick = {
                                            searchQuery = cleanText
                                            coroutineScope.launch {
                                                LksCallerTuneManager.stopPreview()
                                                previewTarget = TunePreviewTarget.None
                                                searchCatalogState = SearchCatalogState.Loading
                                                val results = YouTubeSearchHelper.searchSongs(cleanText)
                                                searchCatalogState = if (results.isEmpty()) SearchCatalogState.Empty else SearchCatalogState.Loaded(results)
                                            }
                                        },
                                        label = { Text(genre, fontSize = 11.sp) }
                                    )
                                }
                            }

                            // Search Trigger Button
                            if (searchQuery.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Button(
                                    onClick = {
                                        coroutineScope.launch {
                                            LksCallerTuneManager.stopPreview()
                                            previewTarget = TunePreviewTarget.None
                                            searchCatalogState = SearchCatalogState.Loading
                                            val results = YouTubeSearchHelper.searchSongs(searchQuery)
                                            searchCatalogState = if (results.isEmpty()) SearchCatalogState.Empty else SearchCatalogState.Loaded(results)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4))
                                ) {
                                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Search Songs")
                                }
                            }

                            Spacer(Modifier.height(10.dp))

                            when (val catalogState = searchCatalogState) {
                                SearchCatalogState.Loading -> {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            CircularProgressIndicator(color = Color(0xFF6750A4), modifier = Modifier.size(36.dp))
                                            Spacer(Modifier.height(12.dp))
                                            Text("Searching music library...", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                                SearchCatalogState.Empty -> {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("No songs found. Try searching for song or artist name.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                is SearchCatalogState.Loaded -> {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        items(catalogState.songs) { song ->
                                            val isPreviewPlaying = previewTarget == TunePreviewTarget.SearchSong(song.videoId)
                                            val isDownloading = tuneApplyState == TuneApplyState.DownloadingForTrim(song.videoId)
                                            val isSetting = tuneApplyState == TuneApplyState.SettingSearchSong(song.videoId)

                                            InAppMusicSongCard(
                                                song = song,
                                                isPreviewPlaying = isPreviewPlaying,
                                                isDownloading = isDownloading,
                                                isSetting = isSetting,
                                                onTogglePreview = {
                                                    if (isPreviewPlaying) {
                                                        LksCallerTuneManager.stopPreview()
                                                        previewTarget = TunePreviewTarget.None
                                                    } else {
                                                        LksCallerTuneManager.stopPreview()
                                                        previewTarget = TunePreviewTarget.SearchSong(song.videoId)
                                                        val tuneInfo = CallerTuneInfo(
                                                            title = song.title,
                                                            artist = song.channelName,
                                                            audioUrl = song.directAudioUrl,
                                                            source = "LIBRARY"
                                                        )
                                                        val started = LksCallerTuneManager.playPreview(context, tuneInfo) {
                                                            previewTarget = TunePreviewTarget.None
                                                        }
                                                        if (!started) {
                                                            previewTarget = TunePreviewTarget.None
                                                            Toast.makeText(context, "Audio stream preview not available for this track", Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                },
                                                onDirectSet = {
                                                    coroutineScope.launch {
                                                        LksCallerTuneManager.stopPreview()
                                                        previewTarget = TunePreviewTarget.None
                                                        tuneApplyState = TuneApplyState.SettingSearchSong(song.videoId)
                                                        Toast.makeText(context, "Setting \"${song.title}\" as your Caller Tune...", Toast.LENGTH_SHORT).show()

                                                        val tuneInfo = if (song.directAudioUrl.isNotBlank()) {
                                                            LksCallerTuneManager.cacheAndSetTuneFromUrl(
                                                                context = context,
                                                                title = song.title,
                                                                artist = song.channelName,
                                                                streamUrl = song.directAudioUrl,
                                                                source = "LIBRARY",
                                                                startMs = 0L,
                                                                endMs = 30000L,
                                                                thumbnailUrl = song.thumbnailUrl
                                                            )
                                                        } else {
                                                            CallerTuneInfo(
                                                                title = song.title,
                                                                artist = song.channelName,
                                                                source = "LIBRARY",
                                                                thumbnailUrl = song.thumbnailUrl
                                                            )
                                                        }

                                                        com.example.data.repository.FirebaseManager.getInstance(context).updateCallerTune(
                                                            title = tuneInfo.title,
                                                            artist = tuneInfo.artist,
                                                            audioUrl = tuneInfo.audioUrl,
                                                            audioBase64 = tuneInfo.audioBase64,
                                                            source = tuneInfo.source,
                                                            startMs = tuneInfo.startMs,
                                                            endMs = tuneInfo.endMs
                                                        )
                                                        tuneApplyState = TuneApplyState.Idle
                                                        Toast.makeText(context, "Caller Tune active: ${song.title}", Toast.LENGTH_LONG).show()
                                                        onTuneSelected(tuneInfo)
                                                    }
                                                },
                                                onSelectAndTrim = {
                                                    coroutineScope.launch {
                                                        LksCallerTuneManager.stopPreview()
                                                        previewTarget = TunePreviewTarget.None
                                                        tuneApplyState = TuneApplyState.DownloadingForTrim(song.videoId)

                                                        val audioFile = if (song.directAudioUrl.isNotBlank()) {
                                                            YouTubeSearchHelper.downloadSongToTempFile(context, song.directAudioUrl)
                                                        } else null

                                                        tuneApplyState = TuneApplyState.Idle
                                                        if (audioFile != null && audioFile.exists()) {
                                                            activeTrimmer = CallerTuneTrimmerTarget(
                                                                uri = Uri.fromFile(audioFile),
                                                                title = song.title,
                                                                artist = song.channelName,
                                                                source = "LIBRARY",
                                                                thumbnailUrl = song.thumbnailUrl,
                                                                audioUrl = song.directAudioUrl
                                                            )
                                                        } else {
                                                            Toast.makeText(context, "Could not load track audio for trimming. Try another track or 'My Device'.", Toast.LENGTH_LONG).show()
                                                        }
                                                    }
                                                }
                                            )
                                        }
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
                                val isCuratedPreviewing = previewTarget == TunePreviewTarget.Curated(curated.id)
                                val isCuratedSetting = tuneApplyState == TuneApplyState.SettingCurated(curated.id)
                                CuratedTuneCard(
                                    tune = curated,
                                    isPlaying = isCuratedPreviewing,
                                    isSetting = isCuratedSetting,
                                    onTogglePreview = {
                                        if (isCuratedPreviewing) {
                                            LksCallerTuneManager.stopPreview()
                                            previewTarget = TunePreviewTarget.None
                                        } else {
                                            LksCallerTuneManager.stopPreview()
                                            previewTarget = TunePreviewTarget.Curated(curated.id)
                                            val info = CallerTuneInfo(
                                                title = curated.title,
                                                artist = curated.artist,
                                                audioUrl = curated.streamUrl,
                                                source = "PRESET"
                                            )
                                            val started = LksCallerTuneManager.playPreview(context, info) {
                                                previewTarget = TunePreviewTarget.None
                                            }
                                            if (!started) {
                                                previewTarget = TunePreviewTarget.None
                                            }
                                        }
                                    },
                                    onSelect = {
                                        LksCallerTuneManager.stopPreview()
                                        previewTarget = TunePreviewTarget.None
                                        tuneApplyState = TuneApplyState.SettingCurated(curated.id)
                                        coroutineScope.launch {
                                            Toast.makeText(context, "Setting \"${curated.title}\" as your Caller Tune...", Toast.LENGTH_SHORT).show()
                                            val tuneInfo = LksCallerTuneManager.cacheAndSetTuneFromUrl(
                                                context = context,
                                                title = curated.title,
                                                artist = curated.artist,
                                                streamUrl = curated.streamUrl,
                                                source = "PRESET",
                                                startMs = 0L,
                                                endMs = 30000L
                                            )
                                            com.example.data.repository.FirebaseManager.getInstance(context).updateCallerTune(
                                                title = tuneInfo.title,
                                                artist = tuneInfo.artist,
                                                audioUrl = tuneInfo.audioUrl,
                                                audioBase64 = tuneInfo.audioBase64,
                                                source = tuneInfo.source,
                                                startMs = tuneInfo.startMs,
                                                endMs = tuneInfo.endMs
                                            )
                                            tuneApplyState = TuneApplyState.Idle
                                            Toast.makeText(context, "Caller Tune active: ${curated.title}", Toast.LENGTH_LONG).show()
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
 * InAppMusicSongCard
 * 100% in-app song representation:
 * - Tap artwork or volume icon to preview audio immediately inside the app.
 * - Tap "Trim & Set" to download in 1s and open visual 30-second chorus trimmer.
 */
@Composable
private fun InAppMusicSongCard(
    song: YouTubeSongItem,
    isPreviewPlaying: Boolean,
    isDownloading: Boolean,
    isSetting: Boolean,
    onTogglePreview: () -> Unit,
    onDirectSet: () -> Unit,
    onSelectAndTrim: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(
            1.dp,
            if (isPreviewPlaying) GreenCall.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val cardContext = LocalContext.current

            // 1. Thumbnail with in-app play overlay & duration badge
            Box(
                modifier = Modifier
                    .size(width = 64.dp, height = 64.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black)
                    .clickable(onClick = onTogglePreview)
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(cardContext)
                        .data(song.thumbnailUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                // Center in-app play/stop badge
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(if (isPreviewPlaying) GreenCall.copy(alpha = 0.45f) else Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isPreviewPlaying) Icons.Default.StopCircle else Icons.Default.PlayArrow,
                        contentDescription = if (isPreviewPlaying) "Stop" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
                // Duration Tag
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color.Black.copy(alpha = 0.75f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(2.dp)
                ) {
                    Text(
                        song.durationText,
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                    )
                }
            }

            Spacer(Modifier.width(10.dp))

            // 2. Song Details
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
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
                if (isPreviewPlaying) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "▶ Playing preview in app...",
                        style = MaterialTheme.typography.labelSmall,
                        color = GreenCall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.width(6.dp))

            // 3. Audio Preview Toggle Button
            IconButton(onClick = onTogglePreview) {
                Icon(
                    if (isPreviewPlaying) Icons.Default.StopCircle else Icons.Default.VolumeUp,
                    contentDescription = "Preview",
                    tint = if (isPreviewPlaying) MaterialTheme.colorScheme.error else Color(0xFF6750A4),
                    modifier = Modifier.size(24.dp)
                )
            }

            // 4. Action Buttons (Direct Set + Trim)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    onClick = onDirectSet,
                    enabled = !isDownloading && !isSetting,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    if (isSetting) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text("Set", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }

                OutlinedButton(
                    onClick = onSelectAndTrim,
                    enabled = !isDownloading && !isSetting,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    if (isDownloading) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color(0xFF6750A4), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.ContentCut, contentDescription = null, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("Trim", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
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
    isSetting: Boolean,
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
                enabled = !isSetting,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                if (isSetting) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text("Set", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}
