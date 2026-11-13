package com.example.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * CallerTuneInfo
 * Represents a user's configured Caller Tune (Hello Tune).
 */
data class CallerTuneInfo(
    val title: String = "",
    val artist: String = "",
    val audioUrl: String = "",
    val audioBase64: String = "",
    val source: String = "DEFAULT", // "YOUTUBE", "LOCAL", "PRESET", "LIBRARY", "DEFAULT", "MY_SAVED_TUNE"
    val startMs: Long = 0L,
    val endMs: Long = 30000L,
    val localUri: String? = null,
    val thumbnailUrl: String? = null
) {
    val isConfigured: Boolean
        get() = title.isNotBlank() && (audioBase64.isNotBlank() || audioUrl.isNotBlank() || !localUri.isNullOrBlank())
}

/**
 * LksCallerTuneManager
 * Handles Caller Tune storage, compression, Base64 conversion for Firestore sync,
 * audio caching, and preview playback.
 */
object LksCallerTuneManager {
    private const val TAG = "LksCallerTuneManager"
    private const val PREFS_NAME = "lks_caller_tune_prefs"

    private const val KEY_TUNE_TITLE = "caller_tune_title"
    private const val KEY_TUNE_ARTIST = "caller_tune_artist"
    private const val KEY_TUNE_URL = "caller_tune_url"
    private const val KEY_TUNE_BASE64 = "caller_tune_base64"
    private const val KEY_TUNE_SOURCE = "caller_tune_source"
    private const val KEY_TUNE_START_MS = "caller_tune_start_ms"
    private const val KEY_TUNE_END_MS = "caller_tune_end_ms"
    private const val KEY_TUNE_LOCAL_URI = "caller_tune_local_uri"
    private const val KEY_TUNE_THUMBNAIL = "caller_tune_thumbnail"

    private var previewPlayer: MediaPlayer? = null

    // ─────────────────────────────────────────────────────────────────────────────
    // 1. LOCAL CALLER TUNE CONFIGURATION
    // ─────────────────────────────────────────────────────────────────────────────

    fun getMyCallerTune(context: Context): CallerTuneInfo {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val title = prefs.getString(KEY_TUNE_TITLE, "") ?: ""
        if (title.isBlank()) return CallerTuneInfo()

        val savedLocalUri = prefs.getString(KEY_TUNE_LOCAL_URI, null)
        val defaultLocalFile = File(context.filesDir, "my_caller_tune.mp3")
        val localUriToUse = if (!savedLocalUri.isNullOrBlank() && try {
            val f = Uri.parse(savedLocalUri).path?.let { File(it) }
            f != null && f.exists()
        } catch (_: Exception) { false }) {
            savedLocalUri
        } else if (defaultLocalFile.exists() && defaultLocalFile.length() > 0) {
            Uri.fromFile(defaultLocalFile).toString()
        } else {
            null
        }

        return CallerTuneInfo(
            title = title,
            artist = prefs.getString(KEY_TUNE_ARTIST, "") ?: "",
            audioUrl = prefs.getString(KEY_TUNE_URL, "") ?: "",
            audioBase64 = prefs.getString(KEY_TUNE_BASE64, "") ?: "",
            source = prefs.getString(KEY_TUNE_SOURCE, "DEFAULT") ?: "DEFAULT",
            startMs = prefs.getLong(KEY_TUNE_START_MS, 0L),
            endMs = prefs.getLong(KEY_TUNE_END_MS, 30000L),
            localUri = localUriToUse,
            thumbnailUrl = prefs.getString(KEY_TUNE_THUMBNAIL, null)
        )
    }

    fun saveMyCallerTune(context: Context, info: CallerTuneInfo) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_TUNE_TITLE, info.title)
            .putString(KEY_TUNE_ARTIST, info.artist)
            .putString(KEY_TUNE_URL, info.audioUrl)
            .putString(KEY_TUNE_BASE64, info.audioBase64)
            .putString(KEY_TUNE_SOURCE, info.source)
            .putLong(KEY_TUNE_START_MS, info.startMs)
            .putLong(KEY_TUNE_END_MS, info.endMs)
            .putString(KEY_TUNE_LOCAL_URI, info.localUri)
            .putString(KEY_TUNE_THUMBNAIL, info.thumbnailUrl)
            .apply()

        Log.i(TAG, "Saved Caller Tune: ${info.title} (${info.source})")
    }

    fun removeMyCallerTune(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        try {
            val localFile = File(context.filesDir, "my_caller_tune.mp3")
            if (localFile.exists()) localFile.delete()
        } catch (_: Exception) {}
        Log.i(TAG, "Caller Tune removed, reverted to default ringback tone")
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 2. AUDIO FILE CONVERSION & BASE64 SYNC
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Reads a trimmed audio file, copies it to safe internal storage (my_caller_tune.mp3),
     * and encodes it into Base64 (if <= 600KB) so it can sync to Firestore.
     */
    fun processAndCacheTune(
        context: Context,
        uri: Uri,
        title: String,
        artist: String,
        source: String,
        startMs: Long,
        endMs: Long,
        thumbnailUrl: String? = null,
        audioUrl: String = ""
    ): CallerTuneInfo {
        var base64 = ""
        val targetFile = File(context.filesDir, "my_caller_tune.mp3")

        try {
            val srcFile = if (uri.scheme == "file" && uri.path != null) File(uri.path!!) else null
            val isSameFile = srcFile != null && try {
                srcFile.canonicalPath == targetFile.canonicalPath
            } catch (_: Exception) {
                srcFile.absolutePath == targetFile.absolutePath
            }

            if (!isSameFile) {
                val inStream = if (srcFile != null) {
                    FileInputStream(srcFile)
                } else {
                    context.contentResolver.openInputStream(uri)
                }
                inStream?.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            var bytes = if (targetFile.exists()) targetFile.readBytes() else ByteArray(0)

            // If trimmed clip exceeds 450KB, compress with MediaCodec to 64kbps AAC so it safely fits within Firestore limits
            if (bytes.size > 450_000) {
                try {
                    val durationMs = (endMs - startMs).coerceAtLeast(5000L).coerceAtMost(30000L)
                    val compressedUri = AudioTrimmerUtil.tryTrimMediaCodecFast(
                        context = context,
                        sourceFile = targetFile,
                        startMs = 0L,
                        endMs = durationMs,
                        targetBitrate = 64000
                    )
                    val compPath = compressedUri?.path
                    if (compressedUri != null && compPath != null) {
                        val compressedFile = File(compPath)
                        if (compressedFile.exists() && compressedFile.length() > 0) {
                            bytes = compressedFile.readBytes()
                            compressedFile.copyTo(targetFile, overwrite = true)
                            compressedFile.delete()
                            Log.i(TAG, "Compressed caller tune to ${bytes.size} bytes for safe Firestore sync")
                        }
                    }
                } catch (ce: Exception) {
                    Log.w(TAG, "Audio compression failed, falling back to original: ${ce.message}")
                }
            }

            // Up to 600KB encoded as Base64 fits within Firestore's 1MB limit (600KB * 4/3 = 800KB text)
            if (bytes.size in 1..600_000) {
                base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                Log.i(TAG, "Encoded Caller Tune into Base64: ${bytes.size} bytes (${base64.length} chars)")
            } else {
                Log.w(TAG, "Caller tune file size is ${bytes.size} bytes")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error caching caller tune: ${e.message}", e)
        }

        val info = CallerTuneInfo(
            title = title,
            artist = artist,
            audioUrl = audioUrl,
            audioBase64 = base64,
            source = source,
            startMs = startMs,
            endMs = endMs,
            localUri = Uri.fromFile(targetFile).toString(),
            thumbnailUrl = thumbnailUrl
        )
        saveMyCallerTune(context, info)
        return info
    }

    /**
     * Downloads an audio stream from an online URL, trims it to 30s (if needed),
     * caches it locally into my_caller_tune.mp3, encodes Base64 for Firestore,
     * and saves to SharedPreferences.
     */
    suspend fun cacheAndSetTuneFromUrl(
        context: Context,
        title: String,
        artist: String,
        streamUrl: String,
        source: String = "PRESET",
        startMs: Long = 0L,
        endMs: Long = 30000L,
        thumbnailUrl: String? = null
    ): CallerTuneInfo = withContext(Dispatchers.IO) {
        var tempDownloadedFile: File? = null
        try {
            tempDownloadedFile = YouTubeSearchHelper.downloadSongToTempFile(context, streamUrl)
        } catch (e: Exception) {
            Log.w(TAG, "Failed downloading song stream: ${e.message}")
        }

        if (tempDownloadedFile != null && tempDownloadedFile.exists() && tempDownloadedFile.length() > 0) {
            // Trim to max 30s if duration exceeds 30s
            val trimResultUri = try {
                val clipLen = (endMs - startMs).coerceIn(5000L, 30000L)
                AudioTrimmerUtil.trimAudio(context, Uri.fromFile(tempDownloadedFile), startMs, startMs + clipLen)
            } catch (te: Exception) {
                Log.w(TAG, "Trimming downloaded stream failed, using full file: ${te.message}")
                null
            }

            val finalUri = trimResultUri ?: Uri.fromFile(tempDownloadedFile)
            val info = processAndCacheTune(
                context = context,
                uri = finalUri,
                title = title,
                artist = artist,
                source = source,
                startMs = startMs,
                endMs = endMs,
                thumbnailUrl = thumbnailUrl,
                audioUrl = streamUrl
            )
            try { tempDownloadedFile.delete() } catch (_: Exception) {}
            info
        } else {
            // If download failed (e.g. offline), save metadata with streamUrl so it can stream
            val info = CallerTuneInfo(
                title = title,
                artist = artist,
                audioUrl = streamUrl,
                audioBase64 = "",
                source = source,
                startMs = startMs,
                endMs = endMs,
                localUri = null,
                thumbnailUrl = thumbnailUrl
            )
            saveMyCallerTune(context, info)
            info
        }
    }

    /**
     * Converts a Base64 string back into a playable temp file for the caller's device.
     * Uses content hash in filename to prevent serving stale cached audio when user changes tunes.
     */
    fun base64ToTempFile(context: Context, base64Str: String, cacheKey: String): File? {
        if (base64Str.isBlank()) return null
        return try {
            val safeKey = cacheKey.replace(Regex("[^a-zA-Z0-9]"), "_")
            val contentHash = base64Str.hashCode().toString().replace("-", "n")
            val cacheFile = File(context.cacheDir, "caller_tune_${safeKey}_${contentHash}.mp3")
            if (cacheFile.exists() && cacheFile.length() > 0) {
                return cacheFile
            }

            // Cleanup older temporary tune files with same prefix
            try {
                context.cacheDir.listFiles()?.forEach { file ->
                    if (file.name.startsWith("caller_tune_${safeKey}_") && file.name != cacheFile.name) {
                        file.delete()
                    }
                }
            } catch (_: Exception) {}

            val cleanBase64 = if (base64Str.contains(",")) base64Str.substringAfter(",") else base64Str
            val decoded = Base64.decode(cleanBase64, Base64.DEFAULT)
            FileOutputStream(cacheFile).use { it.write(decoded) }
            Log.d(TAG, "Saved Base64 tune to cache file: ${cacheFile.absolutePath} (${decoded.size} bytes)")
            cacheFile
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode Base64 tune: ${e.message}")
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 3. IN-APP PREVIEW PLAYBACK WITH AUTO-RECOVERY
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Smart playback engine with auto-recovery:
     * 1. Checks my_caller_tune.mp3. If valid (> 2KB), plays immediately.
     * 2. If missing or damaged, decodes Base64 into my_caller_tune.mp3.
     * 3. If Base64 is missing, downloads audioUrl into my_caller_tune.mp3.
     * 4. If audioUrl is missing, auto-searches music catalog for song title and downloads it.
     * 5. Plays local audio directly without network stutter or closed descriptor bugs.
     */
    private val audioValidityCache = android.util.LruCache<String, Boolean>(60)
    private val failedBase64Signatures = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private const val PREF_INVALID_B64_SIG = "invalid_b64_tune_sig"

    fun isBase64KnownInvalid(context: Context, base64: String): Boolean {
        if (base64.isBlank()) return false
        val sig = "${base64.length}_${base64.hashCode()}"
        if (failedBase64Signatures.contains(sig)) return true
        val persisted = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_INVALID_B64_SIG, null)
        return if (persisted == sig) {
            failedBase64Signatures.add(sig)
            true
        } else false
    }

    fun markBase64Invalid(context: Context, base64: String) {
        if (base64.isNotBlank()) {
            val sig = "${base64.length}_${base64.hashCode()}"
            failedBase64Signatures.add(sig)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_INVALID_B64_SIG, sig)
                .apply()
        }
    }

    /**
     * Verifies via container magic + MediaExtractor that a local audio file has a parseable
     * container and at least one valid audio track, caching results per (path, size, mtime).
     */
    fun isValidAudioFile(file: File?): Boolean {
        if (file == null || !file.exists() || file.length() <= 1024L) return false
        val cacheKey = "${file.absolutePath}_${file.length()}_${file.lastModified()}"
        synchronized(audioValidityCache) {
            audioValidityCache.get(cacheKey)?.let { return it }
        }
        if (!VideoThumbnailHelper.isPlausibleMediaFile(file)) {
            synchronized(audioValidityCache) { audioValidityCache.put(cacheKey, false) }
            return false
        }
        var extractor: MediaExtractor? = null
        val isValid = try {
            extractor = MediaExtractor()
            try {
                FileInputStream(file).use { fis ->
                    extractor.setDataSource(fis.fd, 0L, file.length())
                }
            } catch (_: java.io.IOException) {
                extractor.setDataSource(file.absolutePath)
            }
            var hasAudioTrack = false
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    extractor.selectTrack(i)
                    val firstSampleSize = extractor.readSampleData(java.nio.ByteBuffer.allocate(64 * 1024), 0)
                    if (firstSampleSize > 0) {
                        hasAudioTrack = true
                        break
                    }
                }
            }
            hasAudioTrack
        } catch (e: Exception) {
            Log.w(TAG, "Audio validation failed for ${file.name}: ${e.message}")
            false
        } finally {
            try { extractor?.release() } catch (_: Exception) {}
        }
        synchronized(audioValidityCache) {
            audioValidityCache.put(cacheKey, isValid)
        }
        return isValid
    }

    private suspend fun awaitPlayPreview(
        context: Context,
        info: CallerTuneInfo,
        onStarted: () -> Unit,
        onCompletion: () -> Unit
    ): Boolean {
        val result = CompletableDeferred<Boolean>()
        val initiated = withContext(Dispatchers.Main) {
            playPreview(
                context = context,
                info = info,
                onStarted = {
                    if (!result.isCompleted) result.complete(true)
                    onStarted()
                },
                onCompletion = onCompletion,
                onError = {
                    if (!result.isCompleted) result.complete(false)
                }
            )
        }
        if (!initiated) return false
        val prepared = withTimeoutOrNull(10_000L) { result.await() } ?: false
        if (!prepared) {
            withContext(Dispatchers.Main) { stopPreview() }
        }
        return prepared
    }

    suspend fun playTuneWithAutoRecovery(
        context: Context,
        info: CallerTuneInfo,
        onStarted: () -> Unit = {},
        onCompletion: () -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        val targetFile = File(context.filesDir, "my_caller_tune.mp3")

        // Step 1: Check if local file exists and actually parses as valid audio
        if (targetFile.exists()) {
            if (isValidAudioFile(targetFile)) {
                val success = awaitPlayPreview(
                    context = context,
                    info = info.copy(source = "MY_SAVED_TUNE", localUri = Uri.fromFile(targetFile).toString()),
                    onStarted = onStarted,
                    onCompletion = onCompletion
                )
                if (success) return@withContext true
            } else {
                Log.w(TAG, "Deleting corrupted cached caller tune file: ${targetFile.absolutePath}")
                try { targetFile.delete() } catch (_: Exception) {}
            }
        }

        // Step 2: Recover from Base64 only if decoded audio validates cleanly
        val savedTune = getMyCallerTune(context)
        val base64 = info.audioBase64.ifBlank { savedTune.audioBase64 }
        if (base64.isNotBlank()) {
            try {
                val cleanBase64 = if (base64.contains(",")) base64.substringAfter(",") else base64
                val decoded = Base64.decode(cleanBase64, Base64.DEFAULT)
                if (decoded.size > 2048) {
                    val verifyFile = File(context.cacheDir, "verify_b64_tune_${System.currentTimeMillis()}.tmp")
                    FileOutputStream(verifyFile).use { it.write(decoded) }
                    if (isValidAudioFile(verifyFile)) {
                        verifyFile.copyTo(targetFile, overwrite = true)
                        try { verifyFile.delete() } catch (_: Exception) {}
                        Log.i(TAG, "Recovered valid caller tune from Base64: ${decoded.size} bytes")
                        val titleToUse = info.title.ifBlank { savedTune.title }
                        val artistToUse = info.artist.ifBlank { savedTune.artist }
                        val updatedInfo = info.copy(
                            title = titleToUse,
                            artist = artistToUse,
                            source = "MY_SAVED_TUNE",
                            localUri = Uri.fromFile(targetFile).toString()
                        )
                        saveMyCallerTune(context, updatedInfo)
                        val success = awaitPlayPreview(
                            context = context,
                            info = updatedInfo,
                            onStarted = onStarted,
                            onCompletion = onCompletion
                        )
                        if (success) return@withContext true
                    } else {
                        Log.w(TAG, "Stored Base64 caller tune failed validation; falling back to URL/catalog re-download")
                        try { verifyFile.delete() } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed recovering from Base64: ${e.message}")
            }
        }

        // Step 3 & 4: Download from saved audioUrl or re-search catalog by song title
        suspend fun tryDownloadAndCache(downloadUrl: String): Boolean {
            if (downloadUrl.isBlank()) return false
            try {
                Log.i(TAG, "Downloading song audio from: $downloadUrl")
                val downloadedFile = YouTubeSearchHelper.downloadSongToTempFile(context, downloadUrl)
                if (downloadedFile != null && isValidAudioFile(downloadedFile)) {
                    val endMsToUse = if (info.endMs > info.startMs) info.endMs else (info.startMs + 30000L)
                    val clipLen = (endMsToUse - info.startMs).coerceIn(5000L, 30000L)
                    val trimmedUri = try {
                        AudioTrimmerUtil.trimAudio(context, Uri.fromFile(downloadedFile), info.startMs, info.startMs + clipLen)
                    } catch (_: Exception) { null }

                    val trimmedFile = trimmedUri?.path?.let { File(it) }
                    val finalFile = if (trimmedFile != null && isValidAudioFile(trimmedFile)) trimmedFile else downloadedFile
                    finalFile.copyTo(targetFile, overwrite = true)
                    try { downloadedFile.delete() } catch (_: Exception) {}
                    if (trimmedFile != null && trimmedFile.exists()) {
                        try { trimmedFile.delete() } catch (_: Exception) {}
                    }

                    // Update prefs and Firestore with valid trimmed audio
                    val titleToUse = info.title.ifBlank { savedTune.title }
                    val artistToUse = info.artist.ifBlank { savedTune.artist }
                    val updatedInfo = processAndCacheTune(
                        context = context,
                        uri = Uri.fromFile(targetFile),
                        title = titleToUse,
                        artist = artistToUse,
                        source = "LIBRARY",
                        startMs = info.startMs,
                        endMs = endMsToUse,
                        thumbnailUrl = info.thumbnailUrl ?: savedTune.thumbnailUrl,
                        audioUrl = downloadUrl
                    )

                    try {
                        com.example.data.repository.FirebaseManager.getInstance(context).updateCallerTune(
                            title = updatedInfo.title,
                            artist = updatedInfo.artist,
                            audioUrl = updatedInfo.audioUrl,
                            audioBase64 = updatedInfo.audioBase64,
                            source = updatedInfo.source,
                            startMs = updatedInfo.startMs,
                            endMs = updatedInfo.endMs
                        )
                    } catch (_: Exception) {}

                    return awaitPlayPreview(
                        context = context,
                        info = updatedInfo.copy(source = "MY_SAVED_TUNE", localUri = Uri.fromFile(targetFile).toString()),
                        onStarted = onStarted,
                        onCompletion = onCompletion
                    )
                } else {
                    try { downloadedFile?.delete() } catch (_: Exception) {}
                }
            } catch (de: Exception) {
                Log.w(TAG, "Download & recovery failed for $downloadUrl: ${de.message}")
            }
            return false
        }

        // Step 3: If base64 is blank, search songs via YouTubeSearchHelper.searchSongs(info.title)
        // to get a fresh working audio URL (since Saavn URLs expire), download to targetFile, and play
        val titleToSearch = info.title.ifBlank { savedTune.title }
        if (titleToSearch.isNotBlank()) {
            try {
                Log.i(TAG, "Auto-searching songs for fresh audio URL: '$titleToSearch'")
                val searchResults = YouTubeSearchHelper.searchSongs(titleToSearch)
                val matched = searchResults.firstOrNull { it.directAudioUrl.isNotBlank() }
                if (matched != null && tryDownloadAndCache(matched.directAudioUrl)) {
                    return@withContext true
                }
                if (matched != null && matched.directAudioUrl.isNotBlank()) {
                    if (awaitPlayPreview(context, info.copy(audioUrl = matched.directAudioUrl), onStarted, onCompletion)) {
                        return@withContext true
                    }
                }
            } catch (se: Exception) {
                Log.w(TAG, "Auto-search failed: ${se.message}")
            }
        }

        // Step 4: Fallback to initial saved audioUrl
        val initialUrl = info.audioUrl.ifBlank { savedTune.audioUrl }
        if (initialUrl.isNotBlank()) {
            if (tryDownloadAndCache(initialUrl)) {
                return@withContext true
            }
            return@withContext awaitPlayPreview(
                context = context,
                info = info.copy(audioUrl = initialUrl),
                onStarted = onStarted,
                onCompletion = onCompletion
            )
        }
        false
    }

    @Synchronized
    fun playPreview(
        context: Context,
        info: CallerTuneInfo,
        onStarted: () -> Unit = {},
        onCompletion: () -> Unit = {},
        onError: () -> Unit = {}
    ): Boolean {
        stopPreview()
        try {
            val player = MediaPlayer()
            previewPlayer = player

            // Ensure normal audio routing so it plays through the speaker
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            try {
                if (audioManager?.mode == AudioManager.MODE_IN_COMMUNICATION) {
                    audioManager.mode = AudioManager.MODE_NORMAL
                }
            } catch (_: Exception) {}

            var dataSourceSet = false
            val savedTune = getMyCallerTune(context)
            val isExplicitlySavedTune = (info.source == "MY_SAVED_TUNE") ||
                    (!info.localUri.isNullOrBlank() && info.localUri.contains("my_caller_tune.mp3")) ||
                    (savedTune.isConfigured && savedTune.title.equals(info.title, ignoreCase = true))

            val localTargetFile = File(context.filesDir, "my_caller_tune.mp3")

            // 1. If this is explicitly the saved caller tune, play local my_caller_tune.mp3 using absolute path
            if (localTargetFile.exists() && isValidAudioFile(localTargetFile) &&
                (isExplicitlySavedTune || info.source == "MY_SAVED_TUNE" || (savedTune.isConfigured && info.title.equals(savedTune.title, ignoreCase = true)))) {
                try {
                    player.reset()
                    player.setDataSource(localTargetFile.absolutePath)
                    dataSourceSet = true
                    Log.d(TAG, "playPreview using local saved file: ${localTargetFile.absolutePath} (${localTargetFile.length()} bytes)")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to setDataSource from localTargetFile: ${e.message}")
                }
            }

            // 2. Try info.localUri using absolute path
            if (!dataSourceSet && !info.localUri.isNullOrBlank()) {
                val isMyTuneFile = info.localUri.contains("my_caller_tune.mp3")
                if (!isMyTuneFile || isExplicitlySavedTune) {
                    try {
                        player.reset()
                        val uri = Uri.parse(info.localUri)
                        val filePath = uri.path
                        if (uri.scheme == "file" && filePath != null) {
                            val f = File(filePath)
                            if (isValidAudioFile(f)) {
                                player.setDataSource(f.absolutePath)
                                dataSourceSet = true
                                Log.d(TAG, "playPreview using info.localUri file: $filePath")
                            }
                        } else if (uri.scheme == "content") {
                            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                            if (pfd != null) {
                                player.setDataSource(pfd.fileDescriptor)
                                pfd.close()
                                dataSourceSet = true
                                Log.d(TAG, "playPreview using info.localUri content descriptor")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to setDataSource from localUri: ${e.message}")
                    }
                }
            }

            // 3. Try Base64 temp file using absolute path
            val base64ToUse = if (info.audioBase64.isNotBlank()) info.audioBase64 else if (isExplicitlySavedTune) savedTune.audioBase64 else ""
            if (!dataSourceSet && base64ToUse.isNotBlank()) {
                try {
                    player.reset()
                    val file = base64ToTempFile(context, base64ToUse, "preview")
                    if (isValidAudioFile(file)) {
                        player.setDataSource(file!!.absolutePath)
                        dataSourceSet = true
                        Log.d(TAG, "playPreview using base64TempFile: ${file.absolutePath} (${file.length()} bytes)")
                    } else {
                        try { file?.delete() } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to setDataSource from base64TempFile: ${e.message}")
                }
            }

            // 4. Try streaming audioUrl with standard browser headers
            val urlToUse = if (info.audioUrl.isNotBlank()) info.audioUrl else if (isExplicitlySavedTune) savedTune.audioUrl else ""
            if (!dataSourceSet && urlToUse.isNotBlank()) {
                try {
                    player.reset()
                    val headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
                        "Accept" to "*/*"
                    )
                    player.setDataSource(context, Uri.parse(urlToUse), headers)
                    dataSourceSet = true
                    Log.d(TAG, "playPreview using stream audioUrl with headers: $urlToUse")
                } catch (e1: Exception) {
                    try {
                        player.reset()
                        player.setDataSource(urlToUse)
                        dataSourceSet = true
                        Log.d(TAG, "playPreview using direct stream audioUrl: $urlToUse")
                    } catch (e2: Exception) {
                        Log.w(TAG, "Failed to setDataSource from audioUrl: ${e2.message}")
                    }
                }
            }

            // 5. Final fallback to localTargetFile
            if (!dataSourceSet && localTargetFile.exists() && isValidAudioFile(localTargetFile) &&
                (isExplicitlySavedTune || info.source == "MY_SAVED_TUNE" || (savedTune.isConfigured && info.title.equals(savedTune.title, ignoreCase = true)))) {
                try {
                    player.reset()
                    player.setDataSource(localTargetFile.absolutePath)
                    dataSourceSet = true
                } catch (e: Exception) {
                    Log.w(TAG, "Final fallback to localTargetFile failed: ${e.message}")
                }
            }

            if (!dataSourceSet) {
                Log.w(TAG, "No valid audio source found for playPreview: ${info.title}")
                stopPreview()
                return false
            }

            // Set AudioAttributes and volume AFTER player.reset() / setDataSource() so reset() never clears them
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            player.setVolume(1.0f, 1.0f)

            player.setOnPreparedListener { mp ->
                synchronized(this) {
                    if (previewPlayer == mp) {
                        try {
                            if (info.startMs > 0 && !isExplicitlySavedTune) {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                    mp.seekTo(info.startMs, MediaPlayer.SEEK_CLOSEST)
                                } else {
                                    mp.seekTo(info.startMs.toInt())
                                }
                            }
                            mp.start()
                            Log.i(TAG, "Preview player started successfully for ${info.title}")
                            onStarted()
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to start preview player: ${e.message}")
                            stopPreview()
                            onError()
                            onCompletion()
                        }
                    } else {
                        try { mp.release() } catch (_: Exception) {}
                    }
                }
            }
            player.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "Preview player error: what=$what extra=$extra")
                stopPreview()
                onError()
                onCompletion()
                true
            }
            player.setOnCompletionListener {
                stopPreview()
                onCompletion()
            }
            player.prepareAsync()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error in playPreview: ${e.message}", e)
            stopPreview()
            return false
        }
    }

    @Synchronized
    fun stopPreview() {
        try {
            previewPlayer?.stop()
            previewPlayer?.release()
        } catch (_: Exception) {}
        previewPlayer = null
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 4. CURATED TRENDING CALLER TUNES (Ready to pick in 1 tap)
    // ─────────────────────────────────────────────────────────────────────────────

    data class CuratedTune(
        val id: String,
        val title: String,
        val artist: String,
        val category: String,
        val durationText: String,
        val streamUrl: String,
        val emoji: String
    )

    val CURATED_TUNES = listOf(
        CuratedTune(
            id = "curated_1",
            title = "Acoustic Guitar",
            artist = "Acoustic Melody",
            category = "Guitar & Chill",
            durationText = "0:30",
            streamUrl = "https://audio-ssl.itunes.apple.com/itunes-assets/AudioPreview125/v4/59/8e/e7/598ee705-61af-531b-0a3a-03de11613905/mzaf_17596824643264671711.plus.aac.p.m4a",
            emoji = "🎸"
        ),
        CuratedTune(
            id = "curated_2",
            title = "Motivational LoFi Chill",
            artist = "Lo-Fi Beats",
            category = "Lo-Fi",
            durationText = "0:30",
            streamUrl = "https://audio-ssl.itunes.apple.com/itunes-assets/AudioPreview221/v4/37/18/65/3718655b-2dd7-1367-1374-0f0b0dff8fce/mzaf_6222293724942473305.plus.aac.p.m4a",
            emoji = "🎧"
        ),
        CuratedTune(
            id = "curated_3",
            title = "Corporate Uplifting",
            artist = "Soundpower",
            category = "Professional",
            durationText = "0:30",
            streamUrl = "https://audio-ssl.itunes.apple.com/itunes-assets/AudioPreview115/v4/5e/f5/81/5ef5810e-332e-2570-f060-d301931adbfb/mzaf_17429935323152206949.plus.aac.p.m4a",
            emoji = "💼"
        ),
        CuratedTune(
            id = "curated_4",
            title = "Soft Piano Horizon",
            artist = "Peaceful Piano",
            category = "Piano",
            durationText = "0:30",
            streamUrl = "https://audio-ssl.itunes.apple.com/itunes-assets/AudioPreview211/v4/0e/06/c3/0e06c37e-1fc6-290d-a3dc-e25b711392ad/mzaf_1930168324025856273.plus.aac.p.m4a",
            emoji = "🎹"
        ),
        CuratedTune(
            id = "curated_5",
            title = "Romantic Violin",
            artist = "Jean-Claude Bensimon",
            category = "Romantic",
            durationText = "0:30",
            streamUrl = "https://audio-ssl.itunes.apple.com/itunes-assets/AudioPreview125/v4/e2/bb/cb/e2bbcb87-f40e-da0f-0766-3b3d67dfdfe0/mzaf_15871035304556593605.plus.aac.p.m4a",
            emoji = "🎻"
        )
    )
}
