package com.example.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.data.model.UserDto
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
    val source: String = "DEFAULT", // "YOUTUBE", "LOCAL", "PRESET", "DEFAULT"
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

        return CallerTuneInfo(
            title = title,
            artist = prefs.getString(KEY_TUNE_ARTIST, "") ?: "",
            audioUrl = prefs.getString(KEY_TUNE_URL, "") ?: "",
            audioBase64 = prefs.getString(KEY_TUNE_BASE64, "") ?: "",
            source = prefs.getString(KEY_TUNE_SOURCE, "DEFAULT") ?: "DEFAULT",
            startMs = prefs.getLong(KEY_TUNE_START_MS, 0L),
            endMs = prefs.getLong(KEY_TUNE_END_MS, 30000L),
            localUri = prefs.getString(KEY_TUNE_LOCAL_URI, null),
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
     * Reads a trimmed audio file, copies it to safe internal storage,
     * and encodes it into Base64 (if <= 400KB) so it can sync to Firestore.
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
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            var bytes = targetFile.readBytes()

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
                    if (compressedUri != null && compressedUri.path != null) {
                        val compressedFile = File(compressedUri.path!!)
                        if (compressedFile.exists() && compressedFile.length() > 0) {
                            bytes = compressedFile.readBytes()
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
                Log.w(TAG, "Caller tune file size is ${bytes.size} bytes (still exceeds 600KB)")
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
    // 3. IN-APP PREVIEW PLAYBACK
    // ─────────────────────────────────────────────────────────────────────────────

    @Synchronized
    fun playPreview(context: Context, info: CallerTuneInfo, onCompletion: () -> Unit = {}): Boolean {
        stopPreview()
        try {
            val player = MediaPlayer()
            previewPlayer = player

            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            player.setVolume(1.0f, 1.0f)

            // Ensure normal audio routing so it plays through the speaker
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            try {
                if (audioManager?.mode == AudioManager.MODE_IN_COMMUNICATION) {
                    audioManager.mode = AudioManager.MODE_NORMAL
                }
            } catch (_: Exception) {}

            var dataSourceSet = false

            // 1. Try local saved file in filesDir (my_caller_tune.mp3)
            val localTargetFile = File(context.filesDir, "my_caller_tune.mp3")
            if (localTargetFile.exists() && localTargetFile.length() > 0) {
                try {
                    val fis = FileInputStream(localTargetFile)
                    player.setDataSource(fis.fd, 0, localTargetFile.length())
                    fis.close()
                    dataSourceSet = true
                    Log.d(TAG, "playPreview using local saved file: ${localTargetFile.absolutePath} (${localTargetFile.length()} bytes)")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to setDataSource from localTargetFile: ${e.message}")
                }
            }

            // 2. Try info.localUri if not set yet
            if (!dataSourceSet && !info.localUri.isNullOrBlank()) {
                try {
                    val uri = Uri.parse(info.localUri)
                    if (uri.scheme == "file" && uri.path != null) {
                        val f = File(uri.path!!)
                        if (f.exists() && f.length() > 0) {
                            val fis = FileInputStream(f)
                            player.setDataSource(fis.fd, 0, f.length())
                            fis.close()
                            dataSourceSet = true
                        }
                    } else if (uri.scheme == "content") {
                        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                        if (pfd != null) {
                            player.setDataSource(pfd.fileDescriptor)
                            pfd.close()
                            dataSourceSet = true
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to setDataSource from localUri: ${e.message}")
                }
            }

            // 3. Try Base64 temp file
            if (!dataSourceSet && info.audioBase64.isNotBlank()) {
                try {
                    val file = base64ToTempFile(context, info.audioBase64, "preview")
                    if (file != null && file.exists() && file.length() > 0) {
                        val fis = FileInputStream(file)
                        player.setDataSource(fis.fd, 0, file.length())
                        fis.close()
                        dataSourceSet = true
                        Log.d(TAG, "playPreview using base64TempFile: ${file.absolutePath} (${file.length()} bytes)")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to setDataSource from base64TempFile: ${e.message}")
                }
            }

            // 4. Fallback to audioUrl with User-Agent header
            if (!dataSourceSet && info.audioUrl.isNotBlank()) {
                try {
                    val headers = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36")
                    player.setDataSource(context, Uri.parse(info.audioUrl), headers)
                    dataSourceSet = true
                    Log.d(TAG, "playPreview using stream audioUrl: ${info.audioUrl}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to setDataSource from audioUrl: ${e.message}")
                }
            }

            if (!dataSourceSet) {
                Log.w(TAG, "No valid audio source found for playPreview")
                stopPreview()
                return false
            }

            player.setOnPreparedListener { mp ->
                synchronized(this) {
                    if (previewPlayer == mp) {
                        try {
                            // If streaming full song via audioUrl, seek to startMs
                            if (info.startMs > 0 && !localTargetFile.exists() && info.audioBase64.isBlank()) {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                    mp.seekTo(info.startMs, MediaPlayer.SEEK_CLOSEST)
                                } else {
                                    mp.seekTo(info.startMs.toInt())
                                }
                            }
                            mp.start()
                            Log.i(TAG, "Preview player started successfully!")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to start preview player: ${e.message}")
                            stopPreview()
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
            Log.e(TAG, "Error playing preview: ${e.message}", e)
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
