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
    fun processAndCacheTune(context: Context, uri: Uri, title: String, artist: String, source: String, startMs: Long, endMs: Long, thumbnailUrl: String? = null): CallerTuneInfo {
        var base64 = ""
        var targetFile = File(context.filesDir, "my_caller_tune.mp3")

        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            val bytes = targetFile.readBytes()
            // If trimmed clip is within Firestore document limit (< 400KB), encode as Base64
            if (bytes.size in 1..400_000) {
                base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                Log.i(TAG, "Encoded Caller Tune into Base64: ${bytes.size} bytes (${base64.length} chars)")
            } else {
                Log.w(TAG, "Caller tune file size is ${bytes.size} bytes (exceeds 400KB limit for direct Base64)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error caching caller tune: ${e.message}", e)
        }

        val info = CallerTuneInfo(
            title = title,
            artist = artist,
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

            val decoded = Base64.decode(base64Str, Base64.DEFAULT)
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

            when {
                !info.localUri.isNullOrBlank() -> {
                    player.setDataSource(context, Uri.parse(info.localUri))
                }
                info.audioBase64.isNotBlank() -> {
                    val file = base64ToTempFile(context, info.audioBase64, "preview") ?: return false
                    player.setDataSource(file.absolutePath)
                }
                info.audioUrl.isNotBlank() -> {
                    player.setDataSource(info.audioUrl)
                }
                else -> return false
            }

            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )

            player.setOnPreparedListener { mp ->
                synchronized(this) {
                    if (previewPlayer == mp) {
                        try {
                            mp.start()
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
            Log.e(TAG, "Error playing preview: ${e.message}")
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
            title = "Acoustic Sunlight Guitar",
            artist = "Acoustic Melody",
            category = "Guitar & Chill",
            durationText = "0:30",
            streamUrl = "https://cdn.pixabay.com/download/audio/2022/05/27/audio_1808fbf07a.mp3",
            emoji = "🎸"
        ),
        CuratedTune(
            id = "curated_2",
            title = "Midnight Lo-Fi Chill Beats",
            artist = "Lo-Fi Beats",
            category = "Lo-Fi",
            durationText = "0:30",
            streamUrl = "https://cdn.pixabay.com/download/audio/2022/01/18/audio_d0a13f69d2.mp3",
            emoji = "🎧"
        ),
        CuratedTune(
            id = "curated_3",
            title = "Uplifting Corporate Breeze",
            artist = "Studio Chimes",
            category = "Professional",
            durationText = "0:30",
            streamUrl = "https://cdn.pixabay.com/download/audio/2021/08/04/audio_12b0c7443c.mp3",
            emoji = "💼"
        ),
        CuratedTune(
            id = "curated_4",
            title = "Peaceful Piano Horizon",
            artist = "Classical Piano",
            category = "Piano",
            durationText = "0:30",
            streamUrl = "https://cdn.pixabay.com/download/audio/2022/05/16/audio_db6591201e.mp3",
            emoji = "🎹"
        ),
        CuratedTune(
            id = "curated_5",
            title = "Cinematic Ambient Strings",
            artist = "Violin Ensemble",
            category = "Romantic",
            durationText = "0:30",
            streamUrl = "https://cdn.pixabay.com/download/audio/2022/08/02/audio_884fe92c21.mp3",
            emoji = "🎻"
        )
    )
}
